package com.johnnytv.player

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.load
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The TV guide, as a grid: channels down the side, hours across the top, each
 * programme a block scaled to how long it runs.
 *
 * Guide data is fetched per channel as rows come into view and cached, so the app
 * never has to pull the portal's entire XMLTV file.
 */
@OptIn(UnstableApi::class)
class EpgActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var timeline: Timeline

    private lateinit var categoryRow: LinearLayout
    private lateinit var timeHeader: FrameLayout
    private lateinit var timeHeaderScroll: HorizontalScrollView
    private lateinit var gridScroll: HorizontalScrollView
    private lateinit var gridHost: FrameLayout
    private lateinit var channelColumn: RecyclerView
    private lateinit var gridRows: RecyclerView
    private lateinit var nowLine: View
    private lateinit var progress: View
    private lateinit var status: TextView

    private lateinit var selectedTitle: TextView
    private lateinit var selectedTime: TextView
    private lateinit var selectedLength: TextView
    private lateinit var selectedChannelTag: TextView
    private lateinit var selectedDescription: TextView
    private lateinit var selectedProgress: ProgressBar
    private lateinit var selectedStar: ImageView

    private lateinit var previewFrame: FrameLayout
    private lateinit var previewVideo: PlayerView
    private lateinit var previewLogo: ImageView
    private lateinit var previewNote: TextView

    /** The channel the preview is showing, or trying to. */
    private var previewing: StreamItem? = null
    private var previewJob: Job? = null
    private var previewPlayer: ExoPlayer? = null
    /** Which of the channel's addresses the reused player is currently on. */
    private var previewUrlIndex = 0

    private lateinit var channelAdapter: ChannelColumnAdapter
    private lateinit var rowAdapter: EpgRowAdapter
    private lateinit var watchHint: TextView
    private lateinit var guideDate: TextView

    private var channels: List<StreamItem> = emptyList()
    private var currentCategoryId: String = CATEGORY_ALL
    private val inFlight = HashSet<String>()
    private var syncingScroll = false

    /** Whether the opening selection has been put on the programme that is on now. */
    private var landedOnNow = false

    /** What is waiting to be recorded, re-read whenever this screen comes back. */
    private var scheduledNow: List<Scheduled> = emptyList()

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!Catalog.isLoaded) Catalog.load(this)
        EpgCache.load(this)

        setContentView(R.layout.activity_epg)
        timeline = Timeline(this)

        categoryRow = findViewById(R.id.categoryRow)
        timeHeader = findViewById(R.id.timeHeader)
        timeHeaderScroll = findViewById(R.id.timeHeaderScroll)
        gridScroll = findViewById(R.id.gridScroll)
        gridHost = findViewById(R.id.gridHost)
        channelColumn = findViewById(R.id.channelColumn)
        gridRows = findViewById(R.id.gridRows)
        nowLine = findViewById(R.id.nowLine)
        progress = findViewById(R.id.guideProgress)
        status = findViewById(R.id.guideStatus)
        selectedTitle = findViewById(R.id.selectedTitle)
        selectedTime = findViewById(R.id.selectedTime)
        selectedLength = findViewById(R.id.selectedLength)
        selectedChannelTag = findViewById(R.id.selectedChannelTag)
        selectedDescription = findViewById(R.id.selectedDescription)
        selectedProgress = findViewById(R.id.selectedProgress)
        selectedStar = findViewById(R.id.selectedStar)
        previewFrame = findViewById(R.id.guidePreviewFrame)
        previewVideo = findViewById(R.id.guidePreviewVideo)
        previewLogo = findViewById(R.id.guidePreviewLogo)
        previewNote = findViewById(R.id.guidePreviewNote)
        previewFrame.setOnClickListener { previewing?.let { play(it) } }
        watchHint = findViewById(R.id.watchHint)
        guideDate = findViewById(R.id.guideDate)

        buildTimeHeader()
        pinGridWidth()
        positionNowLine()

        channelAdapter = ChannelColumnAdapter(
            timeline,
            onPlay = { channel -> play(channel) },
            onRecordByTime = { channel -> RecordByTime.forChannel(this, channel) { refreshRecordMarks() } }
        )
        rowAdapter = EpgRowAdapter(
            timeline = timeline,
            programmesFor = { channel -> EpgCache.cached(channel.streamId) },
            onNeedData = { channel, position -> loadRow(channel, position) },
            onFocused = { channel, programme -> showSelected(channel, programme, fromFocus = true) },
            onPressed = { channel, programme -> blockClicked(channel, programme) },
            onPlay = { channel -> play(channel) },
            onRecord = { channel, programme ->
                RecordDialog.show(this, channel, programme) { refreshRecordMarks() }
            },
            onRecordByTime = { channel -> RecordByTime.forChannel(this, channel) { refreshRecordMarks() } },
            recordState = { channel, programme -> recordStateFor(channel, programme) },
            onLeftEdge = { focusCategoryRow() },
            onTopEdge = { focusCategoryRow() }
        )

        channelColumn.layoutManager = LinearLayoutManager(this)
        channelColumn.adapter = channelAdapter
        // No change animations: the two lists are scrolled together by pixel
        // deltas, and a cross-fade on one of them shows up as a wobble.
        channelColumn.itemAnimator = null

        gridRows.layoutManager = LinearLayoutManager(this)
        gridRows.adapter = rowAdapter

        // Both lists used to declare setHasFixedSize(true). It was wrong on the
        // grid - its width is wrap_content, so it genuinely does resize when a
        // category with longer programmes loads - and the release build refuses
        // to ship while it is there. The saving was a layout pass on a screen
        // that is rebuilt once per category change, so nothing is lost.

        syncVerticalScrolling()
        syncHorizontalScrolling()

        buildCategories()
        updateClock()
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    /**
     * The red dots in the guide.
     *
     * Read from what is actually running and what is actually filed, rather
     * than from anything this screen remembers - so a recording set on the
     * player, or one that started while the guide was open, is marked too.
     */
    private fun recordStateFor(channel: StreamItem, programme: Programme): Int {
        if (channel.streamId.isBlank()) return 0
        val now = System.currentTimeMillis()
        if (RecorderService.isRecording &&
            RecorderService.activeStreamId == channel.streamId &&
            programme.start <= now && programme.end > now
        ) {
            return 2
        }
        val waiting = scheduledNow.any {
            it.streamId == channel.streamId &&
                (
                    (it.startAt < programme.end && programme.start < it.endAt) ||
                        (it.series && it.title.equals(programme.title, ignoreCase = true))
                    )
        }
        return if (waiting) 1 else 0
    }

    private fun refreshRecordMarks() {
        scheduledNow = runCatching { Schedules.upcoming(this) }.getOrDefault(emptyList())
        rowAdapter.notifyDataSetChanged()
    }

    override fun onResume() {
        super.onResume()
        refreshRecordMarks()
        // Coming back from a channel the viewer surfed away from: put the guide on
        // the channel they ended on, and clear the note either way so it cannot
        // move the remote on some later screen.
        val landedOn = PlayerActivity.consumeChannelLandedOn()
        if (landedOn.isNotBlank() && channels.any { it.streamId == landedOn }) {
            channelAdapter.select(landedOn)
        }
        // onStop released the preview; start it again for whatever is highlighted.
        previewing?.let { channel -> previewing = null; queuePreview(channel) }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        // Going to the player, or away from the app entirely - either way the
        // connection this was holding has to go back.
        previewJob?.cancel()
        stopPreview()
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { EpgCache.save(context) }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePreviewPlayer()
    }

    // ---------- header ----------

    private fun buildTimeHeader() {
        timeHeader.removeAllViews()
        // Every half hour, the way a set-top box guide reads - the hour alone is
        // too coarse to judge how far through a programme you are.
        val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
        val halfHour = 30L * 60L * 1000L
        var slot = ((timeline.start + halfHour - 1) / halfHour) * halfHour
        while (slot < timeline.end) {
            val label = TextView(this)
            label.text = clock.format(Date(slot)).lowercase(Locale.getDefault())
            label.setTextColor(getColor(R.color.text_secondary))
            label.textSize = 12f
            label.letterSpacing = 0.04f
            label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.leftMargin = timeline.xFor(slot) + dp(14)
            label.layoutParams = params
            timeHeader.addView(label)
            slot += halfHour
        }
        timeHeader.layoutParams = timeHeader.layoutParams.apply { width = timeline.widthPx }
    }

    /**
     * The grid is always exactly as wide as the timeline, so say so.
     *
     * It was left to size itself around its rows, which meant it measured zero
     * with no rows and the timeline's width with them - so every category change
     * resized it, and a resize is what asks the scroll view to go looking for the
     * child it remembered. Pinning the width removes the question entirely:
     * nothing about switching category changes how wide this is.
     */
    private fun pinGridWidth() {
        gridHost.layoutParams = gridHost.layoutParams.apply { width = timeline.widthPx }
        gridRows.layoutParams = gridRows.layoutParams.apply { width = timeline.widthPx }
    }

    private fun positionNowLine() {
        val params = nowLine.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = timeline.xFor(System.currentTimeMillis())
        nowLine.layoutParams = params
    }

    /** One line, the way a set-top box writes it: "Tue., Sep. 15, 6:31 p.m." */
    private fun updateClock() {
        guideDate.text = SimpleDateFormat("EEE, MMM d, h:mm a", Locale.getDefault()).format(Date())
        positionNowLine()
    }

    // ---------- categories ----------

    private fun buildCategories() {
        val entries = ArrayList<Category>()
        entries.add(Category(CATEGORY_FAVOURITES, getString(R.string.favourites)))
        // No All here either: a guide of three thousand rows is not something
        // anyone scrolls, and every category is one press away.
        entries.addAll(Catalog.liveCategories.inPreferredOrder())

        categoryRow.removeAllViews()
        for (entry in entries) {
            val chip = TextView(this)
            chip.text = entry.name
            chip.setTextColor(getColor(R.color.text_primary))
            chip.textSize = 14f
            chip.gravity = Gravity.CENTER
            chip.isAllCaps = true
            chip.letterSpacing = 0.04f
            chip.isFocusable = true
            chip.isClickable = true
            chip.setBackgroundResource(R.drawable.bg_category_chip)
            chip.setPadding(dp(14), dp(7), dp(14), dp(7))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.rightMargin = dp(6)
            chip.layoutParams = params
            chip.tag = entry.id
            chip.setOnClickListener { selectCategory(entry.id) }
            chip.setOnKeyListener { _, keyCode, event ->
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    focusWhatsOnNow()
            }
            categoryRow.addView(chip)
        }
        // Open on the first real category - Favourites is usually empty. A portal
        // that returned channels but no categories has no chip to offer, so fall
        // back to the whole list rather than an empty screen.
        selectCategory(
            entries.getOrNull(1)?.id
                ?: if (Catalog.live.isEmpty()) CATEGORY_FAVOURITES else CATEGORY_ALL
        )
    }

    private fun selectCategory(categoryId: String) {
        currentCategoryId = categoryId
        // A different category is a different set of rows to settle onto.
        landedOnNow = false
        for (i in 0 until categoryRow.childCount) {
            val child = categoryRow.getChildAt(i)
            child.isActivated = child.tag == categoryId
        }

        channels = when (categoryId) {
            CATEGORY_ALL -> Catalog.live.inPreferredChannelOrder(Catalog.liveCategories)
            CATEGORY_FAVOURITES -> {
                val ids = prefs.favouriteIds(Kind.LIVE)
                Catalog.live.filter { ids.contains(it.streamId) }
            }
            else -> Catalog.liveChannels(categoryId, emptySet())
        }

        inFlight.clear()
        clearSelection()
        val anyCached = channels.any { EpgCache.cached(it.streamId) != null }
        progress.visibility = if (channels.isNotEmpty() && !anyCached) View.VISIBLE else View.GONE
        channelAdapter.submit(channels)
        rowAdapter.submit(channels)
        channelColumn.scrollToPosition(0)
        gridRows.scrollToPosition(0)

        val empty = channels.isEmpty()
        status.visibility = if (empty) View.VISIBLE else View.GONE
        status.text = getString(
            if (categoryId == CATEGORY_FAVOURITES) R.string.no_favourite_channels else R.string.no_channels
        )
    }

    // ---------- data ----------

    private fun loadRow(channel: StreamItem, position: Int) {
        if (!inFlight.add(channel.streamId)) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { EpgCache.fetch(prefs.client(), channel.streamId) }
            inFlight.remove(channel.streamId)
            // The list may have changed category while this was in flight, so find
            // where this channel sits now rather than trusting the old index.
            progress.visibility = View.GONE
            val index = channels.indexOfFirst { it.streamId == channel.streamId }
            if (index >= 0) rowAdapter.notifyItemChanged(index)
            // The first time any row knows what is on, put the selection on it, so
            // the guide opens describing the programme that is actually playing
            // rather than whatever happens to be first in the row.
            if (!landedOnNow) {
                gridRows.post {
                    // Never yank the remote out of the headings someone is using -
                    // this is only for the opening selection.
                    if (landedOnNow || categoryRow.hasFocus()) return@post
                    if (focusWhatsOnNow()) landedOnNow = true
                }
            }
        }
    }

    /** Which programme the panel is describing, so a second press can watch it. */
    private var shownKey: String = ""

    /** Empties the panel - what it was describing has gone off screen. */
    private fun clearSelection() {
        shownKey = ""
        channelAdapter.select("")
        selectedTitle.text = ""
        selectedTime.text = ""
        selectedLength.text = ""
        selectedChannelTag.text = ""
        selectedProgress.visibility = View.INVISIBLE
        selectedDescription.visibility = View.INVISIBLE
        selectedStar.setImageResource(R.drawable.ic_star_outline)
        watchHint.visibility = View.GONE
        previewing = null
        previewJob?.cancel()
        stopPreview()
        previewLogo.visibility = View.VISIBLE
        previewLogo.setImageResource(R.drawable.tile_placeholder)
    }

    /**
     * A press on a programme.
     *
     * The first one describes it, the second watches the channel. On a remote the
     * description already appears as focus lands, so OK watches straight away;
     * on a touchscreen it takes the two taps, which is what stops a stray tap
     * throwing someone into a channel they did not want.
     */
    private fun blockClicked(channel: StreamItem, programme: Programme) {
        val key = "${channel.streamId}:${programme.start}"
        if (key == shownKey) play(channel) else showSelected(channel, programme)
    }

    /**
     * [fromFocus] means the remote merely landed here. That describes the
     * programme but must not arm the second press, or a later tap on the same
     * block on a device with both a remote and a touchscreen would start playing
     * with no warning.
     */
    private fun showSelected(channel: StreamItem, programme: Programme, fromFocus: Boolean = false) {
        shownKey = if (fromFocus) "" else "${channel.streamId}:${programme.start}"
        channelAdapter.select(channel.streamId)

        selectedTitle.text = programme.title
        selectedTime.text = EpgRowAdapter.slot(programme)

        val runs = ((programme.end - programme.start) / 60000L).toInt().coerceAtLeast(0)
        selectedLength.text = if (runs > 0) getString(R.string.minutes_long, runs) else ""

        // The bar is only meaningful for something actually running: a programme
        // three hours away is not "0% through", it has not started.
        val now = System.currentTimeMillis()
        val running = now in programme.start until programme.end && runs > 0
        selectedProgress.visibility = if (running) View.VISIBLE else View.INVISIBLE
        if (running) {
            selectedProgress.progress = (((now - programme.start) * 100L) /
                (programme.end - programme.start)).toInt().coerceIn(0, 100)
        }

        val category = Catalog.liveCategories.firstOrNull { it.id == channel.categoryId }?.name
        selectedChannelTag.text =
            if (category.isNullOrBlank()) channel.name else "${channel.name}   ·   $category"

        selectedDescription.text = programme.description
        selectedDescription.visibility =
            if (programme.description.isBlank()) View.INVISIBLE else View.VISIBLE

        selectedStar.setImageResource(
            if (prefs.isFavourite(Kind.LIVE, channel.streamId)) R.drawable.ic_star_filled
            else R.drawable.ic_star_outline
        )

        watchHint.setText(if (fromFocus) R.string.watch_hint_focus else R.string.watch_hint)
        watchHint.visibility = View.VISIBLE

        queuePreview(channel)
    }

    /* =====================================================================
       THE PREVIEW

       Lifted wholesale from the Live TV list, rules and all, because those rules
       were learned the hard way: a preview holds one of the line's connections,
       and most lines here allow exactly one. So it waits until the remote has
       actually settled, never runs two at once, and does nothing at all when the
       viewer has switched previews off in Settings.
       ===================================================================== */

    private fun queuePreview(channel: StreamItem) {
        if (!prefs.previewEnabled) return
        // A preview is a whole stream. While something is recording, that is
        // the line's one connection already spoken for.
        if (RecorderService.isRecording) return
        if (previewing?.streamId == channel.streamId) return
        previewing = channel
        previewLogo.visibility = View.VISIBLE
        previewLogo.load(channel.icon) {
            placeholder(R.drawable.tile_placeholder)
            error(R.drawable.tile_placeholder)
        }
        previewNote.visibility = View.GONE
        stopPreview()
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            // Long enough that holding a direction down does not open a stream per
            // row, short enough that pausing on a channel feels like it answered.
            delay(PREVIEW_SETTLE_MS)
            if (previewing?.streamId == channel.streamId) startPreview(channel)
        }
    }

    /**
     * The player is built once and kept.
     *
     * Creating an ExoPlayer means starting up a decoder, and doing that again for
     * every channel the remote pauses on was costing the best part of a second
     * before a single frame could arrive. Handing the same player a new address
     * skips all of it.
     */
    private fun ensurePreviewPlayer(): ExoPlayer {
        previewPlayer?.let { return it }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Config.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)

        // Show a picture as soon as there is a quarter of a second of it. A
        // preview that stutters is fine; a preview that makes you wait is not -
        // the whole point is answering "what is this channel" quickly.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 250, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
        // Never takes audio focus: a muted picture that silenced whatever else the
        // box was playing every time the remote moved would be intolerable.
        exo.setAudioAttributes(PREVIEW_AUDIO, false)
        exo.volume = 0f
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Never touch the player from inside its own callback. Step out
                // first, try the other container once - some portals serve only
                // .ts and some only .m3u8 - then give up rather than sitting on
                // the connection retrying.
                val channel = previewing ?: return
                val next = previewUrlIndex + 1
                previewVideo.post {
                    if (isFinishing || isDestroyed) return@post
                    if (previewing?.streamId != channel.streamId) return@post
                    startPreview(channel, next)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    previewLogo.visibility = View.GONE
                    previewNote.visibility = View.GONE
                }
            }
        })
        previewVideo.player = exo
        previewPlayer = exo
        return exo
    }

    private fun startPreview(channel: StreamItem, urlIndex: Int = 0) {
        // The preview is a whole connection. queuePreview already refuses to
        // start one while a recording is running, but a recording can begin
        // while a preview is playing - somebody holding OK on the programme
        // they are watching - so the last word on it belongs here, where every
        // route to a preview ends up.
        if (RecorderService.isRecording) {
            stopPreview()
            previewNote.setText(R.string.preview_recording)
            previewNote.visibility = View.VISIBLE
            return
        }
        val urls = prefs.client().liveUrls(channel.streamId)
        if (urlIndex >= urls.size) {
            previewNote.setText(R.string.preview_unavailable)
            previewNote.visibility = View.VISIBLE
            return
        }
        previewUrlIndex = urlIndex
        watchForRecording()
        val exo = ensurePreviewPlayer()
        exo.stop()
        exo.setMediaItem(MediaItem.fromUri(urls[urlIndex]))
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * Give the connection back without throwing the player away - stopping is
     * what frees the line, and keeping the player is what makes the next channel
     * appear quickly.
     */
    private fun stopPreview() {
        previewPlayer?.stop()
        previewPlayer?.clearMediaItems()
        previewGuard.removeCallbacksAndMessages(null)
    }

    /**
     * Watches for a recording starting underneath a preview that is already
     * playing, and gets out of its way. Without this the preview keeps the line
     * for a few seconds and then freezes - which looks like the app is broken
     * when it is really two things wanting the same single connection.
     */
    private val previewGuard = android.os.Handler(android.os.Looper.getMainLooper())

    private fun watchForRecording() {
        previewGuard.removeCallbacksAndMessages(null)
        previewGuard.postDelayed(object : Runnable {
            override fun run() {
                if (isFinishing) return
                if (RecorderService.isRecording) {
                    previewJob?.cancel()
                    stopPreview()
                    previewNote.setText(R.string.preview_recording)
                    previewNote.visibility = View.VISIBLE
                    return
                }
                previewGuard.postDelayed(this, 2_000L)
            }
        }, 2_000L)
    }

    private fun releasePreviewPlayer() {
        previewVideo.player = null
        previewPlayer?.release()
        previewPlayer = null
    }

    /**
     * Coming down from the headings, the remote lands on what is on NOW - not on
     * whatever block it happened to be sitting on before, which after a scroll is
     * usually something two hours away.
     *
     * Returns false when the row has no listings yet, so the press falls through
     * and behaves the way it always did.
     */
    /**
     * UP AND DOWN KEEP THE TIME.
     *
     * A guide is a picture of time: the same moment is the same place on every
     * row. Left to itself the remote does not know that - it moves to whichever
     * block happens to overlap the one you are on, and on a channel showing a
     * three hour film that is a block starting an hour later. Two rows down and
     * you are somewhere else entirely without having asked to be.
     *
     * So the time is carried instead of the position: the moment at the middle
     * of the block you are on, matched against the blocks in the row you are
     * moving to. What is showing at nine o'clock stays what is showing at nine
     * o'clock, all the way down the list.
     */
    /**
     * Up and down in the grid are taken here first, so they can keep the time
     * rather than letting Android pick by position.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val onABlock = focused?.getTag(R.id.epg_block_start) != null
            if (onABlock) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (moveRowKeepingTime(true)) return true
                    KeyEvent.KEYCODE_DPAD_UP -> if (moveRowKeepingTime(false)) return true
                    // Moving along a row is the only thing that changes which
                    // moment you are looking at, so it is the only thing that
                    // moves the cursor. Taken after the move, from wherever the
                    // remote actually landed.
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val handled = super.dispatchKeyEvent(event)
                        gridRows.post {
                            val now = currentFocus?.getTag(R.id.epg_block_start) as? Long
                            if (now != null) cursorTime = now
                        }
                        return handled
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * The moment the remote is looking at.
     *
     * Carried rather than worked out, because working it out was wrong: taking
     * the middle of the block you are on means that on a three hour film you
     * are "looking at" half past the hour two hours from now, and the row below
     * lands on whatever is showing then - two programmes to the right of where
     * anybody thought they were.
     *
     * Left and right move it. Up and down read it and leave it alone, so a run
     * down forty channels stays on the same minute from first to last.
     */
    private var cursorTime: Long = 0L

    /** Temporary: prove whether the up/down handler is running at all. */
    private val SHOW_GUIDE_WORKINGS = true

    private fun moveRowKeepingTime(down: Boolean): Boolean {
        val focused = currentFocus ?: return false
        val start = focused.getTag(R.id.epg_block_start) as? Long ?: return false
        val end = focused.getTag(R.id.epg_block_end) as? Long ?: return false

        // Whatever the cursor says, as long as the block under it still covers
        // that moment. If it does not - the guide moved on, or the highlight
        // was put somewhere by something other than the remote - it is taken
        // from the start of the block, which is where the eye is anyway.
        val moment = if (cursorTime in start until end) cursorTime else start
        cursorTime = moment

        /*
         * Saying so out loud, for now.
         *
         * Twice I have changed how this lands and twice it has behaved exactly
         * as it did before, which usually means the change is not running at
         * all rather than running and being wrong. A line in the corner settles
         * that in one press: if it appears, this code has the key and the
         * fault is in where it puts the highlight; if it never appears, the key
         * is going somewhere else entirely and everything I have written here
         * is beside the point.
         *
         * Out again once it is proved.
         */
        if (SHOW_GUIDE_WORKINGS) {
            val clock = java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
            android.widget.Toast.makeText(
                this,
                "keeping " + clock.format(java.util.Date(moment)),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        val manager = gridRows.layoutManager as? LinearLayoutManager ?: return false
        // Straight from the focused block: the list resolves which row owns it
        // however deep it sits, which guessing at the parent did not.
        val holder = gridRows.findContainingViewHolder(focused) ?: return false
        val next = holder.bindingAdapterPosition + if (down) 1 else -1
        if (next < 0 || next >= (gridRows.adapter?.itemCount ?: 0)) return false

        // The row may not be built yet if it is just off screen; bring it in
        // and try again on the next pass rather than refusing to move.
        val target = gridRows.findViewHolderForAdapterPosition(next)?.itemView
        if (target == null) {
            manager.scrollToPosition(next)
            gridRows.post { moveRowKeepingTime(down) }
            return true
        }

        val blocks = ArrayList<View>()
        collectBlocks(target, blocks)
        if (blocks.isEmpty()) return false

        val covering = blocks.firstOrNull {
            val from = it.getTag(R.id.epg_block_start) as? Long ?: return@firstOrNull false
            val to = it.getTag(R.id.epg_block_end) as? Long ?: return@firstOrNull false
            moment in from until to
        }
        // Nothing covers that moment - a row whose listings stop early, or a
        // gap the portal never filled. Take the nearest thing that has not
        // already finished, rather than the nearest of any kind, which is how
        // the highlight used to end up an hour in the past.
        val landing = covering ?: blocks
            .filter { (it.getTag(R.id.epg_block_end) as? Long ?: 0L) > moment }
            .minByOrNull { (it.getTag(R.id.epg_block_start) as? Long ?: Long.MAX_VALUE) }
            ?: blocks.lastOrNull()
        return landing?.requestFocus() ?: false
    }

    private fun collectBlocks(view: View, into: MutableList<View>) {
        if (view.getTag(R.id.epg_block_start) != null && view.isFocusable) {
            into.add(view)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectBlocks(view.getChildAt(i), into)
        }
    }

    private fun focusWhatsOnNow(): Boolean {
        val manager = gridRows.layoutManager as? LinearLayoutManager ?: return false
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return false
        // The top row may still be waiting on its listings; take the first one on
        // screen that actually knows what is on.
        for (position in first..last) {
            val row = gridRows.findViewHolderForAdapterPosition(position)?.itemView as? ViewGroup
                ?: continue
            val onAir = row.findViewWithTag<View>(EpgRowAdapter.TAG_ON_AIR) ?: continue
            if (onAir.requestFocus()) return true
        }
        // Nothing is marked as on air - a category where every row is still
        // loading, or a run of channels the portal keeps no listings for. Take
        // the first thing that will accept the remote rather than leaving it
        // stranded in the headings, which looks like the guide ignoring you.
        for (position in first..last) {
            val row = gridRows.findViewHolderForAdapterPosition(position)?.itemView as? ViewGroup
                ?: continue
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                if (child.isFocusable && child.requestFocus()) return true
            }
        }
        return false
    }

    /**
     * There is nothing to the left of the earliest programme still on, so left
     * goes up to the category headings rather than pushing against a wall - and
     * the highlight never ends up on something that finished an hour ago.
     */
    private fun focusCategoryRow() {
        for (i in 0 until categoryRow.childCount) {
            val chip = categoryRow.getChildAt(i)
            if (chip.isActivated) { chip.requestFocus(); return }
        }
        categoryRow.getChildAt(0)?.requestFocus()
    }

    private fun play(channel: StreamItem) {
        // The guide's own column order, so up and down in the player follow the
        // same channels the guide was showing.
        Catalog.playbackQueue = channels
        PlayerActivity.start(
            this,
            urls = prefs.client().liveUrls(channel.streamId),
            title = channel.name,
            kind = Kind.LIVE,
            contentId = channel.streamId,
            category = currentCategoryId
        )
    }

    // ---------- keeping the panes lined up ----------

    private fun syncVerticalScrolling() {
        channelColumn.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (syncingScroll) return
                syncingScroll = true
                gridRows.scrollBy(0, dy)
                syncingScroll = false
            }
        })
        gridRows.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (syncingScroll) return
                syncingScroll = true
                channelColumn.scrollBy(0, dy)
                syncingScroll = false
            }
        })
    }

    private fun syncHorizontalScrolling() {
        gridScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (timeHeaderScroll.scrollX != scrollX) timeHeaderScroll.scrollX = scrollX
        }
        // Open on roughly now rather than at the far left.
        gridScroll.post { gridScroll.scrollTo((timeline.xFor(System.currentTimeMillis()) - dp(40)).coerceAtLeast(0), 0) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CATEGORY_ALL = "__all"
        private const val CATEGORY_FAVOURITES = "__favourites"

        /** How long the remote must sit still before a preview is worth opening. */
        private const val PREVIEW_SETTLE_MS = 600L

        private val PREVIEW_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
}
