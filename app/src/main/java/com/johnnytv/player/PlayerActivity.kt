package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var playerView: PlayerView
    private lateinit var statusLabel: TextView
    private lateinit var nowPlayingLabel: TextView
    private lateinit var loadingView: View

    /**
     * One client for the life of the screen. Building a fresh one per channel
     * change would throw away the connection pool every time the viewer pressed
     * down, which is the opposite of what channel surfing needs.
     */
    private val client: XtreamClient by lazy { prefs.client() }

    private var player: ExoPlayer? = null
    private var urls: List<String> = emptyList()
    private var urlIndex = 0
    private var title: String = ""
    private var kind: Kind = Kind.LIVE
    private var contentId: String = ""

    /** The channels either side of this one, so up and down change channel. */
    private var siblings: List<StreamItem> = emptyList()

    /** The "put this on?" question, while it is up. Only ever one at a time. */
    private var castAsk: AlertDialog? = null
    private lateinit var channelLabel: TextView

    /** Where a held-down button has walked to, before it settles and tunes. */
    private var pendingIndex = -1

    /** This channel has stalled on this line before, so hold more in hand. */
    private var deeperBuffer = false

    /** A recording being played back: a list of chunk files, not a live stream. */
    private var playlist = false

    /** When the picture last stopped moving, so a stall can be caught early. */
    private var stalledSince = 0L

    private var resumeFrom: Long = 0L
    private var hasSeeked = false
    private var retriesOnCurrentUrl = 0
    private var playedCurrentUrl = false
    private var pendingRetry: Runnable? = null
    private var guideJob: kotlinx.coroutines.Job? = null

    /**
     * Whether the player's own controls are on screen. Read from the view instead
     * and the answer is "no" while they are still sliding into place, which is
     * exactly when someone pressing OK then down would lose the press.
     */
    private var controlsUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        statusLabel = findViewById(R.id.playerStatus)
        nowPlayingLabel = findViewById(R.id.playerNowPlaying)
        channelLabel = findViewById(R.id.playerChannel)
        loadingView = findViewById(R.id.playerLoading)

        urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: emptyList()
        playlist = intent.getBooleanExtra(EXTRA_PLAYLIST, false)
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        contentId = intent.getStringExtra(EXTRA_CONTENT_ID) ?: ""
        kind = runCatching { Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.LIVE.name) }
            .getOrDefault(Kind.LIVE)
        landedOn = ""

        if (urls.isEmpty()) {
            showStatus(getString(R.string.nothing_to_play))
            return
        }

        resumeFrom = if (kind == Kind.LIVE) 0L else prefs.position(kind, contentId)

        if (kind == Kind.LIVE) {
            // Prefer the list exactly as it was on screen - same order, same
            // search filter - so "next channel" means the next one the viewer
            // could see. Only fall back to the whole category if that list has
            // been rebuilt since.
            val queue = Catalog.playbackQueue
            siblings = if (queue.any { it.streamId == contentId }) {
                queue
            } else {
                val category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
                if (category.isNotBlank() && Catalog.isLoaded) {
                    Catalog.liveChannels(category, prefs.favouriteIds(Kind.LIVE))
                } else {
                    emptyList()
                }
            }
        }
        if (kind == Kind.LIVE && contentId.isNotBlank()) showNowPlaying()

        playerView.keepScreenOn = true
        playerView.setShowNextButton(false)
        playerView.setShowPreviousButton(false)
        playerView.setControllerShowTimeoutMs(4000)
        playerView.setControllerAutoShow(false)
        if (kind == Kind.LIVE) {
            // Nothing to skip through on a live feed - the seek arrows just
            // sit either side of the spinner looking like leftovers.
            playerView.setShowRewindButton(false)
            playerView.setShowFastForwardButton(false)
        }
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                controlsUp = visibility == View.VISIBLE
            }
        )

        // With the controls open, back should put them away rather than walking
        // out of the channel.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controlsUp) {
                    playerView.hideController()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        if (urls.isNotEmpty()) startPlayback()
        if (kind == Kind.LIVE) playerView.post(castWatch)
        if (kind == Kind.LIVE && !playlist) playerView.postDelayed(stallWatch, STALL_CHECK_MS)
    }

    override fun onStop() {
        super.onStop()
        rememberPosition()
        cancelPendingRetry()
        playerView.removeCallbacks(applyChannelStep)
        pendingIndex = -1
        // A banner naming a channel we never tuned to would still be sitting
        // there when the screen came back.
        channelLabel.removeCallbacks(hideChannelLabel)
        channelLabel.visibility = View.GONE
        playerView.removeCallbacks(castWatch)
        playerView.removeCallbacks(stallWatch)
        stalledSince = 0L
        castAsk?.dismiss()
        castAsk = null
        releasePlayer()
    }

    private fun rememberPosition() {
        val active = player ?: return
        if (kind == Kind.LIVE || contentId.isBlank()) return
        val position = active.currentPosition
        val duration = if (active.duration > 0) active.duration else 0L
        prefs.savePosition(kind, contentId, position, duration)
    }

    // ---------- changing channel from the phone ----------

    /**
     * Two jobs on one timer.
     *
     * It tells the website what is on this screen, which is what lets the site
     * say "your TV is on TSN" rather than handing somebody a dead picture on a
     * one-connection line. And it looks for a channel the phone has asked for,
     * because wanting to change what the television is showing is the whole
     * point - waiting for somebody to back out to the home screen first would
     * defeat it.
     */
    private val castWatch = object : Runnable {
        override fun run() {
            lifecycleScope.launch {
                val command = withContext(Dispatchers.IO) {
                    if (contentId.isNotBlank()) CastLink.report(prefs, contentId, title)
                    CastLink.peek(prefs)
                }
                if (command != null && !isFinishing) obeyCast(command)
            }
            playerView.postDelayed(this, CAST_POLL_MS)
        }
    }

    /**
     * Asks before taking the screen off whoever is sitting in front of it.
     *
     * A phone can be anywhere. This television is in somebody's living room,
     * and there may well be a person watching it who did not send anything. So
     * a request that arrives while something is playing is an offer, not an
     * order: whoever is holding the remote decides, and ignoring it is the
     * answer if nobody is there. The phone gets its way instantly only when the
     * app is idle on the home screen, where there is nothing to interrupt.
     */
    private fun obeyCast(command: CastLink.Command) {
        // Cleared whatever happens - including when the answer is no. A request
        // that survived being declined would simply ask again five seconds later.
        lifecycleScope.launch { withContext(Dispatchers.IO) { CastLink.ack(prefs) } }
        if (command.id == contentId) return
        val channel = Catalog.live.firstOrNull { it.streamId == command.id } ?: return
        if (castAsk != null) return

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.cast_ask_title))
            .setMessage(getString(R.string.cast_ask_message, channel.name))
            .setPositiveButton(R.string.cast_ask_yes) { _, _ ->
                playerView.removeCallbacks(applyChannelStep)
                pendingIndex = -1
                tune(channel)
            }
            .setNegativeButton(R.string.cast_ask_no, null)
            .create()
        dialog.setOnDismissListener { castAsk = null }
        castAsk = dialog
        dialog.show()
        // An empty room should not be left holding a question for ever.
        playerView.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, CAST_ASK_MS)
    }

    // ---------- changing channel from the remote ----------

    /**
     * Up and down change channel while a live stream is playing, in the order the
     * list you came from was showing. Handled here rather than by the player's own
     * controls, which would otherwise take the press for seeking.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The record button on a remote that has one. Nothing else on this
        // screen wants it, so it can be taken straight.
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_MEDIA_RECORD &&
            kind == Kind.LIVE
        ) {
            val channel = Catalog.live.firstOrNull { it.streamId == contentId }
            if (channel != null) RecordDialog.showForLive(this, channel)
            return true
        }
        val step = stepFor(event.keyCode)
        // Only while the player's own controls are hidden. With them showing, the
        // remote belongs to them - that is how you reach the subtitles button.
        if (step != 0 && kind == Kind.LIVE && siblings.size > 1 &&
            !controlsUp && !playerView.isControllerFullyVisible
        ) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    queueChannelStep(step)
                    return true
                }
                // The release has to be swallowed as well. Left to reach the
                // player, it opens the controls over every channel change.
                KeyEvent.ACTION_UP -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun stepFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> 1

        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1

        else -> 0
    }

    /**
     * Walks the list and shows where the presses have got to, but waits for the
     * viewer to stop pressing before tuning. Holding the button down repeats at
     * about twenty presses a second; opening twenty streams would knock a
     * single-connection line over before the first one had a picture.
     */
    private fun queueChannelStep(by: Int) {
        if (siblings.isEmpty()) return
        val here = if (pendingIndex >= 0) {
            pendingIndex
        } else {
            siblings.indexOfFirst { it.streamId == contentId }.coerceAtLeast(0)
        }
        pendingIndex = ((here + by) % siblings.size + siblings.size) % siblings.size
        showChannelLabel(siblings[pendingIndex])
        playerView.removeCallbacks(applyChannelStep)
        playerView.postDelayed(applyChannelStep, CHANNEL_SETTLE_MS)
    }

    private val applyChannelStep = Runnable {
        val index = pendingIndex
        pendingIndex = -1
        val next = siblings.getOrNull(index)
        if (next != null && next.streamId != contentId && !isFinishing) tune(next)
    }

    /** Switches to [next] and starts it playing. */
    private fun tune(next: StreamItem) {
        // A recording in progress owns the line's one connection. Changing
        // channel would knock it off air, and somebody would find a broken
        // recording tomorrow with no idea why - so it is refused out loud
        // instead.
        if (RecorderService.isRecording && RecorderService.activeStreamId != next.streamId) {
            android.widget.Toast.makeText(this, R.string.record_busy_watching, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // Anything still pending belongs to the channel we are leaving.
        cancelPendingRetry()
        guideJob?.cancel()
        nowPlayingLabel.removeCallbacks(hideNowPlaying)
        nowPlayingLabel.visibility = View.GONE

        contentId = next.streamId
        title = next.name
        landedOn = next.streamId
        urls = client.liveUrls(next.streamId)
        urlIndex = 0
        retriesOnCurrentUrl = 0
        playedCurrentUrl = false

        showChannelLabel(next)
        playerView.hideController()
        startPlayback()
        showNowPlaying()
    }

    private fun showChannelLabel(channel: StreamItem) {
        channelLabel.text =
            if (channel.num.isBlank()) channel.name else "${channel.num}  ·  ${channel.name}"
        channelLabel.visibility = View.VISIBLE
        channelLabel.removeCallbacks(hideChannelLabel)
        channelLabel.postDelayed(hideChannelLabel, 4_000L)
    }

    private val hideChannelLabel = Runnable { channelLabel.visibility = View.GONE }
    private val hideNowPlaying = Runnable { nowPlayingLabel.visibility = View.GONE }

    // ---------- fixing itself ----------

    /**
     * THE STALL WATCHDOG.
     *
     * ExoPlayer reports an error when a stream breaks, and reconnecting from
     * that is old behaviour. What it does not report is the far commoner
     * failure: the portal keeps the socket open and simply stops sending. The
     * player sits in BUFFERING for ever, perfectly happy, showing a spinner,
     * and nothing in the code below ever fires.
     *
     * So the picture is watched rather than the player. Three seconds of
     * nothing moving is not a wobble, it is a feed that has gone away, and the
     * cure is the one that already exists - throw the connection away and open
     * it again. A viewer who would have sat looking at a spinner gets a couple
     * of seconds of frozen picture instead.
     *
     * Twice on the same channel and it is not luck: that channel is noted, and
     * from then on it starts with a much deeper buffer on this line.
     */
    private val stallWatch = object : Runnable {
        override fun run() {
            val active = player
            if (active != null && !isFinishing) {
                val stuck = active.playbackState == Player.STATE_BUFFERING && active.playWhenReady
                if (stuck) {
                    val now = System.currentTimeMillis()
                    if (stalledSince == 0L) {
                        stalledSince = now
                    } else if (now - stalledSince >= STALL_MS) {
                        stalledSince = 0L
                        if (prefs.noteStall(contentId)) deeperBuffer = true
                        recover()
                    }
                } else {
                    stalledSince = 0L
                }
            }
            playerView.postDelayed(this, STALL_CHECK_MS)
        }
    }

    // ---------- playback ----------

    private fun startPlayback() {
        releasePlayer()
        if (urlIndex >= urls.size) {
            showStatus(getString(R.string.could_not_play, title))
            return
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Config.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        // IPTV portals are less steady than a commercial video service, so hold a
        // deeper buffer than the ExoPlayer defaults: start playing quickly, then keep
        // up to a minute in hand to ride out a wobbly feed.
        // A channel that has stalled here before starts with a cushion instead
        // of the usual quick start: a second and a half of extra wait once beats
        // a freeze every few minutes.
        val jumpy = deeperBuffer || prefs.isJumpy(contentId)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (jumpy) Config.MIN_BUFFER_MS * 2 else Config.MIN_BUFFER_MS,
                Config.MAX_BUFFER_MS,
                if (jumpy) Config.BUFFER_FOR_PLAYBACK_DEEP_MS else Config.BUFFER_FOR_PLAYBACK_MS,
                if (jumpy) Config.BUFFER_AFTER_REBUFFER_MS * 2 else Config.BUFFER_AFTER_REBUFFER_MS
            )
            .setBackBuffer(30_000, true)
            // Start on a duration of video rather than a number of bytes, so a
            // high-bitrate channel doesn't sit there filling a byte quota first.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Late word from a player we have already moved on from - a
                // channel change tearing one down often ends in an error.
                if (player !== exo) return
                if (playlist) {
                    showStatus(getString(R.string.could_not_play, title))
                    return
                }
                // A wobbly feed is not a dead feed. Try the same stream again a couple
                // of times, then fall through to the other container (.m3u8 vs .ts),
                // and only give up once nothing is left.
                recover()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== exo) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> showLoading()
                    Player.STATE_READY -> {
                        retriesOnCurrentUrl = 0
                        playedCurrentUrl = true
                        hideStatus()
                        if (!hasSeeked && resumeFrom > 0L) {
                            hasSeeked = true
                            exo.seekTo(resumeFrom)
                        }
                    }
                    Player.STATE_ENDED -> {
                        if (kind == Kind.LIVE) {
                            // A live channel never really "ends" - the feed dropped.
                            // Reconnect instead of closing the player.
                            recover()
                        } else {
                            if (contentId.isNotBlank()) prefs.removePosition(kind, contentId)
                            finish()
                        }
                    }
                    else -> Unit
                }
            }
        })

        playerView.player = exo
        player = exo
        if (playlist) {
            // A recording: every chunk handed over at once, so it plays through
            // as one programme rather than stopping at the end of each ten
            // minutes.
            exo.setMediaItems(urls.map { MediaItem.fromUri(it) })
        } else {
            exo.setMediaItem(MediaItem.fromUri(urls[urlIndex]))
        }
        exo.prepare()
        exo.playWhenReady = true

        showLoading()
    }

    /**
     * Reconnect after a drop-out. Same stream a few times, then the next container,
     * and only then admit defeat. Live channels keep retrying rather than closing.
     */
    private fun recover() {
        // If this URL never produced a picture in the first place, re-trying it is
        // just dead time on the loading spinner - go straight to the next container.
        val worthRetrying = playedCurrentUrl && retriesOnCurrentUrl < RETRIES_PER_URL
        if (worthRetrying) {
            retriesOnCurrentUrl++
            showLoading()
            restartAfter(RETRY_DELAY_MS)
            return
        }

        retriesOnCurrentUrl = 0
        playedCurrentUrl = false
        urlIndex++
        if (urlIndex < urls.size) {
            showLoading()
            restartAfter(FAILOVER_DELAY_MS)
            return
        }

        if (kind == Kind.LIVE) {
            // Start the whole cycle again rather than dumping the viewer out of a
            // channel that may well come back.
            urlIndex = 0
            showStatus(getString(R.string.reconnecting, title))
            restartAfter(LIVE_RECONNECT_DELAY_MS)
        } else {
            showStatus(getString(R.string.could_not_play, title))
        }
    }

    /** A retry, held so a channel change can call it off before it fires. */
    private fun restartAfter(delayMs: Long) {
        cancelPendingRetry()
        // Let go of the failed stream now rather than in four seconds' time - on a
        // one-connection line that socket is the thing being waited for. Posted,
        // because this is reached from inside the player's own callback.
        playerView.post { if (!isFinishing) releasePlayer() }
        val retry = Runnable {
            pendingRetry = null
            if (!isFinishing) startPlayback()
        }
        pendingRetry = retry
        playerView.postDelayed(retry, delayMs)
    }

    private fun cancelPendingRetry() {
        pendingRetry?.let { playerView.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun releasePlayer() {
        player?.let {
            player = null          // cleared first, so its parting errors are ignored
            // Films and episodes must come back where they were: a reconnect, or
            // a trip to the home screen, rebuilds the player from scratch.
            if (kind != Kind.LIVE) {
                val at = it.currentPosition
                if (at > 0L) {
                    resumeFrom = at
                    hasSeeked = false
                }
            }
            it.stop()
            it.release()
        }
        playerView.player = null
    }

    /** One cheap call to the guide so the viewer knows what they just tuned into. */
    private fun showNowPlaying() {
        val forChannel = contentId
        guideJob?.cancel()
        guideJob = lifecycleScope.launch {
            val listings = withContext(Dispatchers.IO) {
                runCatching { client.nowNext(forChannel) }.getOrDefault(emptyList())
            }
            // The viewer has moved on while the guide was being fetched.
            if (forChannel != contentId) return@launch
            val now = System.currentTimeMillis()
            val current = listings.firstOrNull { it.nowPlaying }
                ?: listings.firstOrNull { it.start <= now && (it.end <= 0L || it.end > now) }
                ?: listings.firstOrNull()
                ?: return@launch

            nowPlayingLabel.text = getString(R.string.now_playing_label, current.title)
            nowPlayingLabel.visibility = View.VISIBLE
            nowPlayingLabel.removeCallbacks(hideNowPlaying)
            nowPlayingLabel.postDelayed(hideNowPlaying, 8_000L)
        }
    }

    private fun showLoading() {
        // Deliberately does not touch the controls: a rebuffer must not snatch
        // them away from under someone reaching for the subtitles button.
        loadingView.visibility = View.VISIBLE
        statusLabel.visibility = View.GONE
    }

    private fun showStatus(message: String) {
        loadingView.visibility = View.GONE
        statusLabel.text = message
        statusLabel.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        loadingView.visibility = View.GONE
        statusLabel.visibility = View.GONE
    }

    companion object {
        private const val RETRIES_PER_URL = 2
        private const val RETRY_DELAY_MS = 1_200L
        private const val FAILOVER_DELAY_MS = 250L
        private const val LIVE_RECONNECT_DELAY_MS = 4_000L

        /** How long the presses must stop for before the channel actually changes. */
        private const val CHANNEL_SETTLE_MS = 600L

        /**
         * How often to tell the website what is on and look for a request.
         *
         * Six seconds is the slowest that still feels like pressing a button
         * rather than sending a letter, and it costs a few hundred bytes -
         * less than a tenth of a second of the video already streaming.
         */
        private const val CAST_POLL_MS = 6_000L

        /** How long the question waits for somebody who may not be there. */
        private const val CAST_ASK_MS = 20_000L

        /** How long the picture may sit still before it counts as a stall. */
        private const val STALL_MS = 3_000L
        private const val STALL_CHECK_MS = 1_000L

        private const val EXTRA_URLS = "extra_urls"
        private const val EXTRA_PLAYLIST = "extra_playlist"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_KIND = "extra_kind"
        private const val EXTRA_CONTENT_ID = "extra_content_id"
        private const val EXTRA_CATEGORY = "extra_category"

        /**
         * The channel surfing ended on, so backing out lands the remote there
         * rather than on the channel that was first opened.
         */
        private var landedOn: String = ""

        fun consumeChannelLandedOn(): String {
            val id = landedOn
            landedOn = ""
            return id
        }

        /** Plays a recording: its chunks, in order, as one programme. */
        fun startPlaylist(
            context: Context,
            urls: List<String>,
            title: String,
            contentId: String
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_KIND, Kind.VOD.name)
                    .putExtra(EXTRA_CONTENT_ID, contentId)
                    .putExtra(EXTRA_PLAYLIST, true)
            )
        }

        fun start(
            context: Context,
            urls: List<String>,
            title: String,
            kind: Kind,
            contentId: String,
            /** Which Live TV category this came from, so up and down can change channel. */
            category: String = ""
        ) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_KIND, kind.name)
                    .putExtra(EXTRA_CONTENT_ID, contentId)
                    .putExtra(EXTRA_CATEGORY, category)
            )
        }
    }
}
