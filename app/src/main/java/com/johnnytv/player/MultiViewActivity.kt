package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.io.InterruptedIOException

/**
 * TWO CHANNELS SIDE BY SIDE, ON A LINE THAT ALLOWS ONE CONNECTION.
 *
 * The work is all in MultiFeed.kt: the two screens never hold the line at the
 * same moment, each plays out of its own reserve while the other is topping
 * up, and the joins are exact, so neither picture nor sound gives the trick
 * away. This file is only the screen - two players, a highlight, and a menu.
 *
 * The remote: left and right choose a screen, and the sound follows the
 * highlight. OK opens the menu for that screen. Up and down change its
 * channel. Menu shows or hides the numbers in the corner. Back leaves.
 */
@OptIn(UnstableApi::class)
class MultiViewActivity : AppCompatActivity() {

    private class Pane(
        val frame: View,
        val video: PlayerView,
        val border: View,
        val label: TextView,
        val hint: TextView,
        val stats: TextView
    ) {
        var channel: StreamItem? = null
        var feed: LineFeed? = null
        var player: ExoPlayer? = null
        var pendingIndex = -1
    }

    private lateinit var prefs: Prefs
    private val client: XtreamClient by lazy { prefs.client() }
    private val line = LineShare()
    private val handler = Handler(Looper.getMainLooper())
    private var panes: List<Pane> = emptyList()
    private var active = 0
    private var showNumbers = false

    /** The blue outline: up while the remote is in use, gone once it rests. */
    private var highlightUp = true
    private var swallowOk = false
    private val hideHighlight = Runnable {
        highlightUp = false
        panes.forEach { it.border.visibility = View.GONE }
    }
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_multiview)
        if (!Catalog.isLoaded) Catalog.load(this)

        panes = listOf(R.id.paneLeft, R.id.paneRight).map { id ->
            val frame = findViewById<View>(id)
            Pane(
                frame = frame,
                video = frame.findViewById(R.id.paneVideo),
                border = frame.findViewById(R.id.paneBorder),
                label = frame.findViewById(R.id.paneLabel),
                hint = frame.findViewById(R.id.paneHint),
                stats = frame.findViewById(R.id.paneStats)
            )
        }
        panes.forEachIndexed { index, pane ->
            pane.frame.setOnClickListener {
                val wasUp = highlightUp
                wake()
                if (!wasUp) setActive(active)
                else if (active == index) openMenu(index) else setActive(index)
            }
        }

        // The channels that were on last time, if this is still the same
        // service - stream numbers repeat from one portal to the next.
        val saved = getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (saved.getString(KEY_SERVER, "") == client.server) {
            panes.forEachIndexed { index, pane ->
                val id = saved.getString(KEY_PANE + index, "").orEmpty()
                if (id.isNotBlank()) pane.channel = Catalog.live.firstOrNull { it.streamId == id }
            }
        }
        // Opened from a channel that was already on: that one goes on the left.
        val first = intent.getStringExtra(EXTRA_FIRST).orEmpty()
        if (first.isNotBlank()) {
            Catalog.live.firstOrNull { it.streamId == first }?.let { channel ->
                if (panes[1].channel?.streamId == channel.streamId) panes[1].channel = null
                panes[0].channel = channel
            }
        }
        setActive(0)
    }

    override fun onStart() {
        super.onStart()
        started = true
        wake()
        setActive(active)
        panes.forEachIndexed { index, pane ->
            val channel = pane.channel
            if (channel != null) tune(index, channel) else showEmpty(pane)
        }
        handler.post(watch)
        // Nothing chosen yet: go straight to choosing rather than showing two
        // black rectangles and waiting to be asked.
        if (panes.all { it.channel == null }) choose(0)
    }

    override fun onStop() {
        super.onStop()
        started = false
        handler.removeCallbacksAndMessages(null)
        panes.forEach { stopPane(it) }
    }

    // ---------- the remote ----------

    /**
     * Shows the outline and starts the clock on putting it away again.
     *
     * Two pictures side by side are the thing being watched; a blue box round
     * one of them is only useful while a choice is being made.
     */
    private fun wake() {
        highlightUp = true
        handler.removeCallbacks(hideHighlight)
        handler.postDelayed(hideHighlight, HIGHLIGHT_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val wasUp = highlightUp
            wake()
            if (!wasUp) {
                setActive(active)
                // With the outline away, the first press of OK only brings it
                // back - opening a menu for a screen nobody can see is
                // highlighted would be a guess.
                val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (isOk && event.repeatCount == 0) swallowOk = true
            }
        }
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> -1
            else -> 0
        }
        if (step != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) queueStep(active, step)
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) setActive(0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) setActive(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Acted on as the button comes up, so the press that opens the
                // menu is not also the press that picks its first row.
                if (event.action == KeyEvent.ACTION_UP) {
                    if (swallowOk) swallowOk = false else openMenu(active)
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    showNumbers = !showNumbers
                    panes.forEach { it.stats.visibility = if (showNumbers && it.feed != null) View.VISIBLE else View.GONE }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setActive(index: Int) {
        active = index
        panes.forEachIndexed { at, pane ->
            pane.border.visibility = if (at == index && highlightUp) View.VISIBLE else View.GONE
            pane.player?.volume = if (at == index) 1f else 0f
        }
    }

    private fun openMenu(index: Int) {
        val pane = panes[index]
        val channel = pane.channel
        if (channel == null) {
            choose(index)
            return
        }
        val options = listOf(
            getString(R.string.multiview_change),
            getString(R.string.multiview_full_screen),
            getString(R.string.multiview_close_pane)
        )
        showOptions(channel.name, options) { which ->
            when (which) {
                0 -> choose(index)
                1 -> goFullScreen(channel)
                2 -> {
                    stopPane(pane)
                    pane.channel = null
                    showEmpty(pane)
                    remember()
                }
            }
        }
    }

    /** A folder, then a channel in it. */
    private fun choose(index: Int) {
        val favourites = prefs.favouriteIds(Kind.LIVE)
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        if (favourites.isNotEmpty()) {
            ids.add(BrowseActivity.CATEGORY_FAVOURITES)
            names.add(getString(R.string.favourites))
        }
        for (category in Catalog.liveCategories.inPreferredOrder()) {
            ids.add(category.id)
            names.add(category.name)
        }
        if (ids.isEmpty()) return
        showOptions(getString(R.string.multiview_pick_folder), names) { which ->
            val channels = Catalog.liveChannels(ids[which], favourites)
            if (channels.isNotEmpty()) {
                showOptions(names[which], channels.map { it.name }) { pick ->
                    // The same channel twice would only be the two screens
                    // fighting over one picture.
                    val other = panes[1 - index].channel
                    if (other?.streamId == channels[pick].streamId) {
                        Toast.makeText(this, R.string.multiview_already_on, Toast.LENGTH_LONG).show()
                    } else {
                        tune(index, channels[pick])
                    }
                }
            }
        }
    }

    private fun goFullScreen(channel: StreamItem) {
        // Let go of the line before the ordinary player asks for it.
        panes.forEach { stopPane(it) }
        started = false
        PlayerActivity.start(
            this,
            urls = client.liveUrls(channel.streamId),
            title = channel.name,
            kind = Kind.LIVE,
            contentId = channel.streamId,
            category = channel.categoryId
        )
        finish()
    }

    // ---------- up and down ----------

    private fun queueStep(index: Int, by: Int) {
        val pane = panes[index]
        val channel = pane.channel ?: return
        val list = Catalog.liveChannels(channel.categoryId, prefs.favouriteIds(Kind.LIVE))
        if (list.size < 2) return
        val here = if (pane.pendingIndex >= 0) pane.pendingIndex
            else list.indexOfFirst { it.streamId == channel.streamId }.coerceAtLeast(0)
        val next = ((here + by) % list.size + list.size) % list.size
        pane.pendingIndex = next
        pane.label.text = list[next].name
        handler.removeCallbacksAndMessages(pane)
        handler.postAtTime({
            val settled = pane.pendingIndex
            pane.pendingIndex = -1
            val target = list.getOrNull(settled)
            val other = panes[1 - index].channel
            if (target != null && started && target.streamId != pane.channel?.streamId &&
                target.streamId != other?.streamId
            ) {
                tune(index, target)
            } else {
                pane.label.text = pane.channel?.name.orEmpty()
            }
        }, pane, android.os.SystemClock.uptimeMillis() + SETTLE_MS)
    }

    // ---------- playing ----------

    private fun showEmpty(pane: Pane) {
        pane.label.visibility = View.GONE
        pane.stats.visibility = View.GONE
        pane.hint.visibility = View.VISIBLE
    }

    private fun tune(index: Int, channel: StreamItem) {
        val pane = panes[index]
        stopPane(pane)
        pane.channel = channel
        pane.label.text = channel.name
        pane.label.visibility = View.VISIBLE
        pane.hint.visibility = View.GONE
        remember()

        // The raw feed, not the playlist wrapper: turn-taking works on the
        // one long stream.
        val url = client.liveUrls(channel.streamId).firstOrNull { !it.contains(".m3u8") } ?: return
        val uri = Uri.parse(url)
        val feed = LineFeed(url, line, Config.USER_AGENT)

        val source = ProgressiveMediaSource.Factory(DataSource.Factory { FeedSource(feed, uri) })
            .createMediaSource(MediaItem.fromUri(uri))

        // Half a minute in hand, and never less: the reserve is what the
        // screen plays from while the other one is having its turn.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(HOLD_MS, HOLD_MS, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (pane.player !== exo) return
                // Start this screen again from scratch rather than leaving a
                // dead rectangle beside a live one.
                handler.postDelayed({
                    val again = pane.channel
                    if (started && pane.player === exo && again != null) tune(index, again)
                }, RESTART_MS)
            }
        })
        exo.volume = if (index == active) 1f else 0f
        exo.setMediaSource(source)
        exo.playWhenReady = true
        exo.prepare()

        pane.feed = feed
        pane.player = exo
        pane.video.player = exo
        pane.stats.visibility = if (showNumbers) View.VISIBLE else View.GONE
        feed.start()
    }

    private fun stopPane(pane: Pane) {
        handler.removeCallbacksAndMessages(pane)
        pane.pendingIndex = -1
        val feed = pane.feed
        val player = pane.player
        pane.feed = null
        pane.player = null          // cleared first, so its parting errors are ignored
        pane.video.player = null
        feed?.close()
        player?.stop()
        player?.release()
    }

    /**
     * Tells each feed how much its player is holding, which is what decides
     * when it next asks for the line - and keeps the corner numbers current.
     */
    private val watch = object : Runnable {
        override fun run() {
            if (!started) return
            // A recording has started underneath us and needs the line whole.
            if (RecorderService.isRecording) {
                Toast.makeText(this@MultiViewActivity, R.string.multiview_recording, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            for (pane in panes) {
                val feed = pane.feed ?: continue
                val player = pane.player ?: continue
                feed.playerBufferedMs = player.totalBufferedDuration
                if (showNumbers) pane.stats.text = feed.summary()
            }
            handler.postDelayed(this, WATCH_MS)
        }
    }

    private fun remember() {
        val edit = getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
        edit.putString(KEY_SERVER, client.server)
        panes.forEachIndexed { index, pane ->
            edit.putString(KEY_PANE + index, pane.channel?.streamId.orEmpty())
        }
        edit.apply()
    }

    /** Hands the player whatever the feed has stitched together. */
    private class FeedSource(private val feed: LineFeed, private val address: Uri) : BaseDataSource(true) {

        private var opened = false

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            opened = true
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = try {
                feed.pipe.read(buffer, offset, length)
            } catch (interrupted: InterruptedException) {
                throw InterruptedIOException()
            }
            if (count < 0) return C.RESULT_END_OF_INPUT
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = address

        override fun close() {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        private const val STORE = "jtv_multiview"
        private const val KEY_SERVER = "server"
        private const val KEY_PANE = "pane"
        private const val EXTRA_FIRST = "first_channel"

        private const val HOLD_MS = 30_000
        private const val WATCH_MS = 400L
        private const val SETTLE_MS = 700L
        private const val RESTART_MS = 1_500L
        private const val HIGHLIGHT_MS = 5_000L

        fun open(context: Context, firstChannelId: String = "") {
            // A recording owns the line until it finishes.
            if (RecorderService.isRecording) {
                Toast.makeText(context, R.string.multiview_recording, Toast.LENGTH_LONG).show()
                return
            }
            context.startActivity(
                Intent(context, MultiViewActivity::class.java).putExtra(EXTRA_FIRST, firstChannelId)
            )
        }
    }
}
