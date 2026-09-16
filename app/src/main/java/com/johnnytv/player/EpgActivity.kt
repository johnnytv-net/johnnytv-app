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
import android.widget.LinearLayout
import android.widget.TextView
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

    private lateinit var guideClock: TextView
    private lateinit var selectedTitle: TextView
    private lateinit var selectedTime: TextView
    private lateinit var selectedDescription: TextView

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
        guideClock = findViewById(R.id.guideClock)
        selectedTitle = findViewById(R.id.selectedTitle)
        selectedTime = findViewById(R.id.selectedTime)
        selectedDescription = findViewById(R.id.selectedDescription)
        watchHint = findViewById(R.id.watchHint)
        guideDate = findViewById(R.id.guideDate)

        buildTimeHeader()
        pinGridWidth()
        positionNowLine()

        channelAdapter = ChannelColumnAdapter(onPlay = { channel -> play(channel) })
        rowAdapter = EpgRowAdapter(
            timeline = timeline,
            programmesFor = { channel -> EpgCache.cached(channel.streamId) },
            onNeedData = { channel, position -> loadRow(channel, position) },
            onFocused = { channel, programme -> showSelected(channel, programme, fromFocus = true) },
            onPressed = { channel, programme -> blockClicked(channel, programme) },
            onPlay = { channel -> play(channel) },
            onLeftEdge = { focusCategoryRow() }
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

    override fun onResume() {
        super.onResume()
        // Coming back from a channel the viewer surfed away from: put the guide on
        // the channel they ended on, and clear the note either way so it cannot
        // move the remote on some later screen.
        val landedOn = PlayerActivity.consumeChannelLandedOn()
        if (landedOn.isNotBlank() && channels.any { it.streamId == landedOn }) {
            channelAdapter.select(landedOn)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        val context = applicationContext
        lifecycleScope.launch(Dispatchers.IO) { EpgCache.save(context) }
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

    private fun updateClock() {
        val now = Date()
        guideClock.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(now)
        guideDate.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
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
            chip.setPadding(dp(16), dp(9), dp(16), dp(9))
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
            else -> Catalog.live.filter { it.categoryId == categoryId }
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
        selectedDescription.visibility = View.GONE
        watchHint.visibility = View.GONE
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
        selectedTime.text = "${channel.name}   ·   ${EpgRowAdapter.slot(programme)}"
        selectedDescription.text = programme.description
        selectedDescription.visibility =
            if (programme.description.isBlank()) View.GONE else View.VISIBLE
        watchHint.setText(if (fromFocus) R.string.watch_hint_focus else R.string.watch_hint)
        watchHint.visibility = View.VISIBLE
    }

    /**
     * Coming down from the headings, the remote lands on what is on NOW - not on
     * whatever block it happened to be sitting on before, which after a scroll is
     * usually something two hours away.
     *
     * Returns false when the row has no listings yet, so the press falls through
     * and behaves the way it always did.
     */
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
    }
}
