package com.johnnytv.player

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared geometry for the guide: how wide a minute is, and what window we're showing. */
class Timeline(context: Context, hoursShown: Int = 12) {

    val pxPerMinute: Float = 5f * context.resources.displayMetrics.density

    /**
     * Eight channels on screen, whatever the television.
     *
     * A fixed row height is a guess about a screen you cannot see: the same 64dp
     * that fills a 720p panel leaves half a row hanging off a 1080p one. So the
     * height is worked out instead - take what the screen has, subtract the parts
     * above the grid, divide by eight. The bounds are there so an unusual display
     * cannot produce a row too thin to read or so tall it defeats the point.
     */
    val rowHeightPx: Int = run {
        val metrics = context.resources.displayMetrics
        val chrome = ROWS_CHROME_DP * metrics.density
        val free = metrics.heightPixels - chrome
        (free / ROWS_ON_SCREEN).toInt()
            .coerceIn((34f * metrics.density).toInt(), (64f * metrics.density).toInt())
    }
    val start: Long
    val end: Long

    init {
        // Start on the half hour before now, so there's a little history on screen.
        // Counted on the viewer's own clock rather than in whole milliseconds since
        // 1970: the two only agree in timezones a whole half-hour from UTC, and
        // anywhere else the ruler would be labelled at times like 8:45.
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.MILLISECOND, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MINUTE, if (calendar.get(Calendar.MINUTE) >= 30) 30 else 0)
        start = calendar.timeInMillis - 30L * 60L * 1000L
        end = start + hoursShown * 60L * 60L * 1000L
    }

    val widthPx: Int
        get() = ((end - start) / 60000L * pxPerMinute).toInt()

    fun xFor(time: Long): Int =
        (((time - start).coerceAtLeast(0L)) / 60000f * pxPerMinute).toInt()

    fun widthFor(from: Long, to: Long): Int {
        val clampedFrom = from.coerceAtLeast(start)
        val clampedTo = to.coerceAtMost(end)
        return ((clampedTo - clampedFrom) / 60000f * pxPerMinute).toInt()
    }

    companion object {
        /** How many channels the guide should show at once. */
        private const val ROWS_ON_SCREEN = 8

        /**
         * Everything above the rows, in dp: the preview panel and its padding, the
         * category chips, and the time headings. Kept in step with activity_epg.xml
         * by hand - if that layout grows, this number grows with it.
         */
        private const val ROWS_CHROME_DP = 214f
    }
}

/** The channel names down the left. Same row height as the grid so the two scroll together. */
class ChannelColumnAdapter(
    private val timeline: Timeline,
    private val onPlay: (StreamItem) -> Unit = {},
    /** OK held on the channel's name: set a recording by time. */
    private val onRecordByTime: (StreamItem) -> Unit = {}
) : RecyclerView.Adapter<ChannelColumnAdapter.VH>() {

    private val items = ArrayList<StreamItem>()

    fun submit(list: List<StreamItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.channelLogo)
        val name: TextView = view.findViewById(R.id.channelName)
        val number: TextView = view.findViewById(R.id.channelNumber)
    }

    /** The channel the panel above is describing, drawn in brand blue. */
    private var selectedId: String = ""
    private var host: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        host = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        host = null
    }

    /**
     * Moves the highlight.
     *
     * This runs on every step of the remote, so it repaints the rows already on
     * screen directly rather than telling the list they changed - a rebind would
     * reload the logo and flash the placeholder each time, and can throw outright
     * if it lands mid-scroll.
     */
    fun select(streamId: String) {
        selectedId = streamId
        val list = host ?: return
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val at = list.getChildAdapterPosition(child)
            if (at in items.indices) child.isActivated = items[at].streamId == selectedId
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val holder = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel, parent, false)
        )
        // The grid works its row height out from the screen so eight channels fit;
        // this column has to agree with it exactly or the two halves drift apart
        // as you scroll.
        holder.itemView.layoutParams = holder.itemView.layoutParams.apply {
            height = timeline.rowHeightPx
        }
        // Tapping the channel itself watches it - the obvious thing to try.
        // Deliberately not focusable: a remote that could step left into this
        // column would lose its place in the guide on the way back.
        holder.itemView.isClickable = true
        holder.itemView.isFocusable = false
        holder.itemView.setOnClickListener {
            val at = holder.bindingAdapterPosition
            if (at != RecyclerView.NO_POSITION && at < items.size) onPlay(items[at])
        }
        return holder
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.number.text = item.num
        holder.itemView.isActivated = item.streamId == selectedId
        holder.itemView.setOnLongClickListener { onRecordByTime(item); true }
        val logoUrl = IconMemory.artFor(item.streamId, item.name, item.icon)
        holder.logo.load(logoUrl.ifBlank { null }) {
            crossfade(false)
            placeholder(R.drawable.tile_placeholder)
            error(R.drawable.tile_placeholder)
            fallback(R.drawable.tile_placeholder)
        }
    }

    override fun getItemCount(): Int = items.size
}

/**
 * One row of programme blocks per channel, laid out by time rather than by count -
 * a two hour programme is twice as wide as a one hour one.
 */
class EpgRowAdapter(
    private val timeline: Timeline,
    private val programmesFor: (StreamItem) -> List<Programme>?,
    private val onNeedData: (StreamItem, Int) -> Unit,
    /** The remote landed on a block: describe it. */
    private val onFocused: (StreamItem, Programme) -> Unit,
    /** A press: describe it, or watch it if it is already the one described. */
    private val onPressed: (StreamItem, Programme) -> Unit,
    private val onPlay: (StreamItem) -> Unit,
    /** OK held down on a programme: record it. */
    private val onRecord: (StreamItem, Programme) -> Unit = { _, _ -> },
    /** OK held on a channel with no listings: record it by time instead. */
    private val onRecordByTime: (StreamItem) -> Unit = { },
    /**
     * Whether this programme is being recorded right now (2), set to be (1), or
     * neither (0) - drawn as a red dot in front of the title, the way every
     * set-top box marks its own recordings.
     */
    private val recordState: (StreamItem, Programme) -> Int = { _, _ -> 0 },
    /** Left was pressed on the earliest programme still on: there is no going back. */
    private val onLeftEdge: () -> Unit = {},
    /** Up was pressed on the top row: the headings are what is above it. */
    private val onTopEdge: () -> Unit = {}
) : RecyclerView.Adapter<EpgRowAdapter.VH>() {

    private val items = ArrayList<StreamItem>()

    fun submit(list: List<StreamItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val row: FrameLayout) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val row = FrameLayout(parent.context)
        row.layoutParams = ViewGroup.LayoutParams(timeline.widthPx, timeline.rowHeightPx)
        return VH(row)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = items[position]
        val row = holder.row
        row.removeAllViews()

        val listings = programmesFor(channel)
        if (listings == null) {
            row.addView(placeholderBlock(row.context, R.string.loading, channel, position))
            onNeedData(channel, position)
            return
        }

        val visible = listings.filter { it.end > timeline.start && it.start < timeline.end }
        if (visible.isEmpty()) {
            row.addView(placeholderBlock(row.context, R.string.no_guide_short, channel, position))
            return
        }

        val now = System.currentTimeMillis()
        val gap = dp(row.context, 3)
        // The first block the remote is allowed to land on: a programme that has
        // already finished is drawn for context but cannot be selected, so the
        // highlight never sits on something nobody can watch.
        var firstLive: TextView? = null
        for (programme in visible) {
            val width = timeline.widthFor(programme.start, programme.end)
            // Narrower than the gap between blocks: there is nothing to draw, and
            // the subtraction below would go negative and lay out over its neighbour.
            if (width <= gap) continue
            val block = TextView(row.context)
            block.text = withRecordDot(programme.title, recordState(channel, programme))
            block.maxLines = 2
            block.ellipsize = android.text.TextUtils.TruncateAt.END
            block.gravity = Gravity.CENTER_VERTICAL
            // A state list, because the focused block turns near-white and its
            // title has to turn dark with it.
            block.setTextColor(row.context.getColorStateList(R.color.epg_block_text))
            block.textSize = 15f
            block.letterSpacing = 0.01f
            block.setPadding(dp(row.context, 14), 0, dp(row.context, 12), 0)
            block.setBackgroundResource(R.drawable.bg_epg_block)

            val finished = programme.end in 1 until now
            block.isFocusable = !finished
            block.isClickable = !finished
            if (finished) block.alpha = PAST_ALPHA

            // What is on now is shown by lifting the block, not by shouting in
            // bold - the whole column then reads as "now" at a glance.
            val onAir = programme.start <= now && programme.end > now
            block.isActivated = onAir
            // Tagged so the screen can drop the remote straight onto what is on
            // now when focus arrives from the headings above.
            if (onAir) block.tag = TAG_ON_AIR
            // What time this block covers. Up and down use it to land on the
            // programme showing at the same moment rather than on whatever
            // block happens to overlap on screen.
            block.setTag(R.id.epg_block_start, programme.start)
            block.setTag(R.id.epg_block_end, programme.end)
            block.typeface = Typeface.create(
                if (onAir) "sans-serif-medium" else "sans-serif", Typeface.NORMAL
            )

            val params = FrameLayout.LayoutParams(width - gap, timeline.rowHeightPx - gap * 2)
            params.leftMargin = timeline.xFor(programme.start)
            params.topMargin = gap
            block.layoutParams = params

            if (!finished) {
                block.setOnClickListener {
                    if (block.isFocused) onPlay(channel) else onPressed(channel, programme)
                }
                // Holding OK used to be a second way to watch the channel,
                // which the plain press already does. It is worth more as the
                // way in to recording: one press to read what a programme is,
                // one hold to keep it.
                block.setOnLongClickListener { onRecord(channel, programme); true }
                block.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) onFocused(channel, programme)
                }
                val isFirstBlock = firstLive == null
                if (isFirstBlock) firstLive = block
                block.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when {
                        // Nothing to the left but the past. Left goes up to the
                        // category headings instead of nudging against a wall.
                        isFirstBlock && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onLeftEdge(); true
                        }
                        // Up out of the top row lands on the category you are
                        // actually in. Left to itself, Android picks whichever
                        // heading happens to sit nearest on screen, which after
                        // scrolling along the categories is almost never the one
                        // you came down from.
                        position == 0 && keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                            onTopEdge(); true
                        }
                        else -> false
                    }
                }
            }
            row.addView(block)
        }
    }

    /**
     * A filled red dot for a recording running now, a hollow one for a recording
     * that is still waiting for its time.
     *
     * Drawn into the title rather than placed beside it, because these blocks
     * are as wide as the programme is long: anything sitting alongside would be
     * the first thing squeezed off a half-hour show.
     */
    private fun withRecordDot(title: String, state: Int): CharSequence {
        if (state <= 0) return title
        val mark = if (state >= 2) "\u25CF " else "\u25CB "
        val text = android.text.SpannableString(mark + title)
        text.setSpan(
            android.text.style.ForegroundColorSpan(RECORD_RED),
            0,
            mark.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    /**
     * The row for a channel with nothing to say - still loading, or a channel the
     * portal keeps no listings for.
     *
     * It used to be a caption: not focusable, not clickable, just grey words. That
     * was wrong twice over. You could not watch the channel from the guide, which
     * is the one thing the guide is for; and because the remote cannot land on it,
     * a screenful of these was a screenful of nothing - pressing down from the
     * category headings found no block to move to and the highlight stayed put,
     * which reads as the guide being broken.
     *
     * So it behaves like any other block now. It says what it knows, it takes
     * focus, and pressing it watches the channel.
     */
    private fun placeholderBlock(
        context: Context,
        textRes: Int,
        channel: StreamItem,
        position: Int
    ): View {
        val label = TextView(context)
        label.setText(textRes)
        label.gravity = Gravity.CENTER_VERTICAL
        label.setTextColorStateList(context)
        label.textSize = 14f
        label.setPadding(dp(context, 14), 0, dp(context, 12), 0)
        label.setBackgroundResource(R.drawable.bg_epg_block)
        label.isFocusable = true
        label.isClickable = true
        label.tag = TAG_ON_AIR
        // Nothing is known about what is on, so the panel above is told exactly
        // that rather than being left describing the last channel.
        val blank = Programme(
            title = context.getString(textRes),
            description = "",
            start = timeline.start,
            end = timeline.end,
            nowPlaying = false
        )
        label.setOnClickListener { onPlay(channel) }
        // Holding OK on a channel with no listings is exactly where somebody
        // wants to set a recording - PPV and event channels have empty rows,
        // and they are the ones worth recording to the minute. Nothing to press
        // meant nothing to record, which is no use on fight night.
        label.setOnLongClickListener { onRecordByTime(channel); true }
        label.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(channel, blank) }
        label.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { onLeftEdge(); true }
                KeyEvent.KEYCODE_DPAD_UP -> if (position == 0) { onTopEdge(); true } else false
                else -> false
            }
        }
        val gap = dp(context, 3)
        val params = FrameLayout.LayoutParams(timeline.widthPx - gap, timeline.rowHeightPx - gap * 2)
        params.topMargin = gap
        label.layoutParams = params
        return label
    }

    private fun TextView.setTextColorStateList(context: Context) {
        setTextColor(context.getColorStateList(R.color.epg_block_text))
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun getItemCount(): Int = items.size

    companion object {
        /** Marks the block that is on air right now. */
        const val TAG_ON_AIR = "on_air"

        /** How far back a finished programme is faded, so it reads as history. */
        private const val PAST_ALPHA = 0.4f

        /** The red every recorder has used since the video recorder. */
        private const val RECORD_RED = 0xFFE0393E.toInt()

        fun clock(): SimpleDateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        fun headerClock(): SimpleDateFormat = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        fun slot(programme: Programme): String {
            val c = clock()
            return "${c.format(Date(programme.start))} - ${c.format(Date(programme.end))}"
        }
    }
}
