package com.johnnytv.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/**
 * Loads artwork, and gives it one more try if the first attempt fails.
 *
 * Artwork servers drop the odd request when a list asks for twenty at once, and
 * without a second try that channel keeps the grey placeholder for as long as
 * the row lives - which is why a logo could be missing from a list row while the
 * same logo drew perfectly well somewhere else on the screen.
 *
 * [stillWanted] is checked before the retry: by then the view may have been
 * recycled onto something else entirely.
 */
internal fun ImageView.loadArtwork(
    url: String,
    placeholderRes: Int,
    retry: Boolean = true,
    stillWanted: () -> Boolean = { true }
) {
    load(url.ifBlank { null }) {
        crossfade(false)
        // Half the memory per picture, and on a logo or a poster at tile size
        // nobody has ever seen the difference. Memory is what decides how much
        // artwork survives a scroll before it has to be fetched again.
        bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
        placeholder(placeholderRes)
        error(placeholderRes)
        fallback(placeholderRes)
        if (retry && url.isNotBlank()) {
            listener(onError = { _, _ ->
                postDelayed({
                    if (stillWanted()) loadArtwork(url, placeholderRes, retry = false, stillWanted = stillWanted)
                }, RETRY_ARTWORK_MS)
            })
        }
    }
}

/** Long enough for a busy artwork server to catch its breath. */
private const val RETRY_ARTWORK_MS = 900L

/** Sidebar list: Favourites, Recently Added, Continue Watching, then real categories. */
class CategoryAdapter(
    private val onSelected: (Category) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    private val items = ArrayList<Category>()
    private var selectedPosition = RecyclerView.NO_POSITION

    fun submit(list: List<Category>) {
        items.clear()
        items.addAll(list)
        selectedPosition = RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun selectAt(position: Int) {
        if (position < 0 || position >= items.size) return
        val previous = selectedPosition
        selectedPosition = position
        if (previous != RecyclerView.NO_POSITION) notifyItemChanged(previous)
        notifyItemChanged(position)
    }

    fun itemAt(position: Int): Category? = items.getOrNull(position)

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.categoryName)
        val count: TextView = view.findViewById(R.id.categoryCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.name

        // The guide is an action sitting among the categories, so it is drawn as
        // one - outlined and tinted - rather than looking like a filter that
        // selects nothing.
        val isAction = item.id == BrowseActivity.CATEGORY_GUIDE
        holder.itemView.setBackgroundResource(
            if (isAction) R.drawable.bg_sidebar_action else R.drawable.bg_list_item
        )
        if (isAction) {
            holder.count.setText(R.string.whats_on_now)
            holder.count.visibility = View.VISIBLE
            holder.itemView.isActivated = false
            holder.itemView.setOnClickListener {
                val current = holder.bindingAdapterPosition
                if (current != RecyclerView.NO_POSITION) onSelected(items[current])
            }
            return
        }

        if (item.count > 0) {
            // Favourites reads as a tally - FAVOURITES 5 - because that number is
            // the whole point of the row. Everything else says what it is counting.
            holder.count.text =
                if (item.id == BrowseActivity.CATEGORY_FAVOURITES) item.count.toString()
                else "${item.count} items"
            holder.count.visibility = View.VISIBLE
        } else {
            holder.count.visibility = View.GONE
        }
        holder.itemView.isActivated = position == selectedPosition
        holder.itemView.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current == RecyclerView.NO_POSITION) return@setOnClickListener
            selectAt(current)
            onSelected(items[current])
        }
    }

    override fun getItemCount(): Int = items.size
}

/** The artwork grid - channels, movies and series all render through this. */
class TileAdapter(
    private val isFavourite: (Tile) -> Boolean,
    private val onOpen: (Tile) -> Unit,
    private val onLongPress: (Tile) -> Unit,
    /** The remote landed on a tile. Not called on a touchscreen, which has no focus. */
    private val onFocused: (Tile) -> Unit = {},
    /** Search results only: show nothing until something is typed. */
    private val searchOnly: Boolean = false
) : RecyclerView.Adapter<TileAdapter.VH>() {

    private val all = ArrayList<Tile>()
    private val shown = ArrayList<Tile>()
    private var query = ""

    fun submit(list: List<Tile>) {
        all.clear()
        all.addAll(list)
        applyFilter()
    }

    fun filter(text: String) {
        query = text
        applyFilter()
    }

    fun isEmpty(): Boolean = shown.isEmpty()

    /**
     * Where an item sits on screen right now, or -1. The kind matters in search
     * mode, where a channel and a film can carry the same id.
     */
    fun positionOf(id: String, kind: Kind? = null): Int =
        shown.indexOfFirst { it.id == id && (kind == null || it.kind == kind) }

    /** What is on screen, in screen order, after sorting and any search. */
    fun visible(): List<Tile> = ArrayList(shown)

    private fun applyFilter() {
        shown.clear()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            if (!searchOnly) shown.addAll(all)
        } else {
            // Two passes over the same text: as typed, and with the spaces and
            // punctuation taken out of both sides. The second is what lets
            // "bluejays" find "MLB 09 | Blue Jays x Royals".
            val squashed = needle.filter { it.isLetterOrDigit() }
            for (item in all) {
                if (item.searchable.contains(needle) ||
                    (squashed.isNotEmpty() && item.condensed.contains(squashed))
                ) {
                    shown.add(item)
                }
            }
        }
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.tileImage)
        val star: ImageView = view.findViewById(R.id.tileStar)
        val title: TextView = view.findViewById(R.id.tileTitle)
        val subtitle: TextView = view.findViewById(R.id.tileSubtitle)
    }

    /**
     * A channel and a film need different shaped cards, and a search that spans
     * both shows them side by side - so the shape follows the item, not the screen.
     */
    override fun getItemViewType(position: Int): Int =
        if (shown[position].kind == Kind.LIVE) VIEW_CHANNEL else VIEW_POSTER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == VIEW_POSTER) R.layout.item_tile_poster
                     else R.layout.item_tile_channel
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = shown[position]
        val placeholderRes = if (item.kind == Kind.LIVE) R.drawable.tile_placeholder
                             else R.drawable.tile_placeholder_poster
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.subtitle.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
        holder.star.visibility = if (isFavourite(item)) View.VISIBLE else View.GONE

        // Always go through load(), even with no URL: it cancels whatever request
        // this recycled view had in flight, which is what used to leave the previous
        // channel's logo sitting under a new channel's name.
        //
        // One failure used to be final: the JohnnyTV mark drew in place of the
        // poster and stayed there for as long as the tile lived. Artwork servers
        // drop the odd request when a grid asks for twenty at once, so a single
        // miss gets one more try before the mark is accepted as the answer.
        holder.image.loadArtwork(item.image, placeholderRes) {
            val at = holder.bindingAdapterPosition
            at != RecyclerView.NO_POSITION && shown.getOrNull(at)?.id == item.id
        }

        holder.itemView.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current == RecyclerView.NO_POSITION) return@setOnClickListener
            onOpen(shown[current])
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            val current = holder.bindingAdapterPosition
            if (current != RecyclerView.NO_POSITION) onFocused(shown[current])
        }
        holder.itemView.setOnLongClickListener {
            val current = holder.bindingAdapterPosition
            if (current == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            onLongPress(shown[current])
            // repaint just this tile so the star appears or disappears straight away
            notifyItemChanged(current)
            true
        }
    }


    override fun getItemCount(): Int = shown.size

    private companion object {
        const val VIEW_CHANNEL = 0
        const val VIEW_POSTER = 1
    }
}

/** Episode list inside a series. */
class EpisodeAdapter(
    private val onPlay: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    private val items = ArrayList<Episode>()
    private var resumable: Set<String> = emptySet()

    fun submit(list: List<Episode>, partWatched: Set<String>) {
        items.clear()
        items.addAll(list)
        resumable = partWatched
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.episodeNumber)
        val title: TextView = view.findViewById(R.id.episodeTitle)
        val note: TextView = view.findViewById(R.id.episodeNote)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.number.text = item.episodeNum.toString()
        holder.title.text = item.title
        val partWatched = resumable.contains(item.episodeId)
        holder.note.text = if (partWatched) "Resume" else ""
        holder.note.visibility = if (partWatched) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener {
            val current = holder.bindingAdapterPosition
            if (current == RecyclerView.NO_POSITION) return@setOnClickListener
            onPlay(items[current])
        }
    }

    override fun getItemCount(): Int = items.size
}

/**
 * Live TV as a list rather than a grid: every channel carries what is on it now,
 * so a category can be read down the page without opening anything.
 */
class ChannelRowAdapter(
    private val isFavourite: (StreamItem) -> Boolean,
    private val onOpen: (StreamItem) -> Unit,
    private val onLongPress: (StreamItem) -> Unit,
    private val onFocused: (StreamItem) -> Unit,
    /** Listings already known for this channel, or null if none have been fetched. */
    private val programmesFor: (String) -> List<Programme>?,
    /** Asked to fetch a channel's listings once its row is on screen. */
    private val onNeedGuide: (StreamItem) -> Unit,
    /** The row left the screen before its listings arrived. */
    private val onGuideNoLongerNeeded: (StreamItem) -> Unit,
    /** The row the remote was on has just been recycled away underneath it. */
    private val onFocusedRowRecycled: () -> Unit = {}
) : RecyclerView.Adapter<ChannelRowAdapter.VH>() {

    private val all = ArrayList<StreamItem>()
    private val shown = ArrayList<StreamItem>()
    private var query = ""

    init {
        // Stable ids let the list keep the remote on the same channel when rows
        // are rebuilt. Without them a refresh mid-scroll drops focus, and it
        // escapes sideways into the category column.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val id = shown.getOrNull(position)?.streamId ?: return RecyclerView.NO_ID
        // Portals number their streams, so the id itself is unique. Only fall back
        // to a hash - which can collide across thousands of channels - if it isn't.
        return id.toLongOrNull() ?: id.hashCode().toLong()
    }

    fun submit(list: List<StreamItem>) {
        all.clear()
        all.addAll(list)
        applyFilter()
    }

    fun filter(text: String) {
        query = text
        applyFilter()
    }

    fun isEmpty(): Boolean = shown.isEmpty()

    fun itemAt(position: Int): StreamItem? = shown.getOrNull(position)

    /**
     * Repaints one channel's "on now" line once its listings arrive - only that
     * line, so the logo does not flash while the list is being scrolled.
     */
    fun guideArrived(streamId: String) {
        val at = shown.indexOfFirst { it.streamId == streamId }
        if (at >= 0) notifyItemChanged(at, PROGRESS)
    }

    /** Where a channel sits in the list right now, or -1. */
    fun positionOf(streamId: String): Int = shown.indexOfFirst { it.streamId == streamId }

    /** The channels on screen, in screen order, after sorting and any search. */
    fun visible(): List<StreamItem> = ArrayList(shown)

    private fun applyFilter() {
        shown.clear()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            shown.addAll(all)
        } else {
            val squashed = needle.filter { it.isLetterOrDigit() }
            for (item in all) {
                val plain = item.name.lowercase()
                if (plain.contains(needle) ||
                    (squashed.isNotEmpty() && plain.filter { it.isLetterOrDigit() }.contains(squashed))
                ) {
                    shown.add(item)
                }
            }
        }
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.rowNumber)
        val logo: ImageView = view.findViewById(R.id.rowLogo)
        val name: TextView = view.findViewById(R.id.rowName)
        val now: TextView = view.findViewById(R.id.rowNow)
        val progress: ProgressBar = view.findViewById(R.id.rowProgress)
        val star: ImageView = view.findViewById(R.id.rowStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_row, parent, false))

    /**
     * A progress-only repaint. Rebinding the whole row every half minute would
     * reload the logo and shake the remote's focus loose, so the ticking bar is
     * updated on its own.
     */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PROGRESS)) {
            paintNowLine(holder, shown[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = shown[position]
        holder.number.text = item.num
        holder.name.text = item.name
        holder.star.visibility = if (isFavourite(item)) View.VISIBLE else View.GONE
        holder.logo.loadArtwork(
            IconMemory.artFor(item.streamId, item.name, item.icon),
            R.drawable.tile_placeholder
        ) {
            val at = holder.bindingAdapterPosition
            at != RecyclerView.NO_POSITION && shown.getOrNull(at)?.streamId == item.streamId
        }
    }

    /*
     * Listings are asked for when a row appears and dropped when it leaves, rather
     * than on bind - a fast scroll through a big category would otherwise queue a
     * request for every channel it flew past.
     */
    override fun onViewAttachedToWindow(holder: VH) {
        val at = holder.bindingAdapterPosition
        val item = shown.getOrNull(at) ?: return
        if (programmesFor(item.streamId) == null) onNeedGuide(item)
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        // Being recycled while holding focus is the only unambiguous sign that the
        // remote lost its place - a deliberate press left or up never lands here.
        if (holder.itemView.hasFocus()) onFocusedRowRecycled()
        val at = holder.bindingAdapterPosition
        val item = shown.getOrNull(at) ?: return
        onGuideNoLongerNeeded(item)
    }

    private fun paintNowLine(holder: VH, item: StreamItem) {
        val listings = programmesFor(item.streamId)
        if (listings == null) {
            holder.now.setText(R.string.loading)
            holder.progress.visibility = View.INVISIBLE
            return
        }
        val now = System.currentTimeMillis()
        val current = listings.firstOrNull { it.start <= now && it.end > now }
        if (current == null) {
            holder.now.setText(R.string.no_guide_short)
            holder.progress.visibility = View.INVISIBLE
        } else {
            holder.now.text = current.title
            val span = (current.end - current.start).coerceAtLeast(1L)
            holder.progress.progress =
                (((now - current.start) * 100L) / span).toInt().coerceIn(0, 100)
            holder.progress.visibility = View.VISIBLE
        }
    }

    /** Repaints every visible bar without disturbing anything else. */
    fun tickProgress() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PROGRESS)
    }

    override fun getItemCount(): Int = shown.size

    private companion object {
        /** Marks a repaint that only touches the "on now" line. */
        val PROGRESS = Any()
    }
}

/** The schedule under the preview: start time and programme, a few hours ahead. */
class UpNextAdapter : RecyclerView.Adapter<UpNextAdapter.VH>() {

    private val items = ArrayList<Programme>()

    fun submit(list: List<Programme>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.upTime)
        val title: TextView = view.findViewById(R.id.upTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_upnext, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.time.text = EpgRowAdapter.clock().format(Date(item.start))
        holder.title.text = item.title
    }

    override fun getItemCount(): Int = items.size
}
