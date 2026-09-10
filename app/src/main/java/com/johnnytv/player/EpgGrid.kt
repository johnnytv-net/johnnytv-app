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
    val rowHeightPx: Int = context.resources.getDimensionPixelSize(R.dimen.epg_row_height)
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
}

/** The channel names down the left. Same row height as the grid so the two scroll together. */
class ChannelColumnAdapter(
    private val onPlay: (StreamItem) -> Unit = {}
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
    /** Left was pressed on the earliest programme still on: there is no going back. */
    private val onLeftEdge: () -> Unit = {}
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
            row.addView(placeholderBlock(row.context, R.string.loading))
            onNeedData(channel, position)
            return
        }

        val visible = listings.filter { it.end > timeline.start && it.start < timeline.end }
        if (visible.isEmpty()) {
            row.addView(placeholderBlock(row.context, R.string.no_guide_short))
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
            block.text = programme.title
            block.maxLines = 2
            block.ellipsize = android.text.TextUtils.TruncateAt.END
            block.gravity = Gravity.CENTER_VERTICAL
            // A state list, because the focused block turns near-white and its
            // title has to turn dark with it.
            block.setTextColor(row.context.getColorStateList(R.color.epg_block_text))
            block.textSize = 14f
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
                block.setOnLongClickListener { onPlay(channel); true }
                block.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) onFocused(channel, programme)
                }
                if (firstLive == null) {
                    firstLive = block
                    // Nothing to the left but the past. Left goes up to the
                    // category headings instead of nudging against a wall.
                    block.setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                            event.action == KeyEvent.ACTION_DOWN
                        ) {
                            onLeftEdge()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            row.addView(block)
        }
    }

    private fun placeholderBlock(context: Context, textRes: Int): View {
        val label = TextView(context)
        label.setText(textRes)
        label.gravity = Gravity.CENTER_VERTICAL
        label.setTextColor(context.getColor(R.color.text_secondary))
        label.textSize = 14f
        label.setPadding(dp(context, 14), 0, dp(context, 12), 0)
        label.setBackgroundResource(R.drawable.bg_epg_block)
        val gap = dp(context, 3)
        val params = FrameLayout.LayoutParams(timeline.widthPx - gap, timeline.rowHeightPx - gap * 2)
        params.topMargin = gap
        label.layoutParams = params
        return label
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    override fun getItemCount(): Int = items.size

    companion object {
        /** Marks the block that is on air right now. */
        const val TAG_ON_AIR = "on_air"

        /** How far back a finished programme is faded, so it reads as history. */
        private const val PAST_ALPHA = 0.4f

        fun clock(): SimpleDateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        fun headerClock(): SimpleDateFormat = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        fun slot(programme: Programme): String {
            val c = clock()
            return "${c.format(Date(programme.start))} - ${c.format(Date(programme.end))}"
        }
    }
}
