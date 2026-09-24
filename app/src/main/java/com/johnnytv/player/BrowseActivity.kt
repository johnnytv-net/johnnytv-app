package com.johnnytv.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.max

/** Live TV, Movies and Series all browse through this one screen. */
@OptIn(UnstableApi::class)
class BrowseActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var kind: Kind

    /**
     * One client for the life of the screen. Every call to prefs.client() builds a
     * fresh OkHttp stack with its own pool, so asking per row would leave hundreds
     * of idle connections open against the portal.
     */
    private val client: XtreamClient by lazy { prefs.client() }

    private lateinit var categoryList: RecyclerView
    private lateinit var tileGrid: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var sortButton: TextView
    private lateinit var screenTitle: TextView
    private lateinit var emptyLabel: TextView

    // The strip along the bottom: what is on the channel the remote is sitting on.
    private lateinit var nowBar: View
    private lateinit var nowChannel: TextView
    private lateinit var nowClock: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowNext: TextView
    private lateinit var nowProgress: ProgressBar
    private var nowFor: String = ""
    private var nowJob: Job? = null

    // List view: channels down the middle, the highlighted one previewing on the right.
    private lateinit var listMode: View
    private lateinit var channelList: RecyclerView
    private lateinit var viewButton: TextView
    private lateinit var previewVideo: PlayerView
    private lateinit var previewLogo: ImageView
    private lateinit var previewNote: TextView
    private lateinit var previewChannel: TextView
    private lateinit var previewTitle: TextView
    private lateinit var previewTime: TextView
    private lateinit var previewDescription: TextView
    private lateinit var previewProgress: ProgressBar
    private lateinit var previewUpNext: RecyclerView
    private lateinit var previewFrame: View
    private lateinit var previewEmpty: View
    private lateinit var rowAdapter: ChannelRowAdapter
    private lateinit var upNextAdapter: UpNextAdapter

    private var listView = false
    /** The focused row was recycled away, so focus should be handed back on settle. */
    private var listLostFocusWhileScrolling = false
    private var highlighted: StreamItem? = null
    /** What was opened, so pressing back returns the remote to it. */
    private var lastOpenedId: String = ""
    private var lastOpenedKind: Kind = Kind.LIVE
    /** A focus hand-back waiting on the next layout pass, so it can be called off. */
    private var pendingFocus: OneShotPreDrawListener? = null
    private var previewJob: Job? = null
    private var previewPlayer: ExoPlayer? = null
    private val ticker = Handler(Looper.getMainLooper())
    private val guideJobs = HashMap<String, Job>()
    /** At most a couple of guide requests in flight, however fast the list scrolls. */
    private val guideSlots = Semaphore(2)

    /** A TV has no touchscreen, so the on-screen keyboard is a full overlay. */
    private val onTv: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var tileAdapter: TileAdapter

    private var sortAlphabetical = false
    private var currentCategoryId: String = CATEGORY_ALL

    /** Search-everything mode: one box across Live TV, Movies and Series. */
    private var searchEverything = false

    /** Something has been typed, so the screen is showing the whole library. */
    private var searchingLibrary = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        kind = runCatching { Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: Kind.LIVE.name) }
            .getOrDefault(Kind.LIVE)

        searchEverything = intent.getBooleanExtra(EXTRA_SEARCH_ALL, false)

        if (!Catalog.isLoaded) Catalog.load(this)

        setContentView(R.layout.activity_browse)
        categoryList = findViewById(R.id.categoryList)
        tileGrid = findViewById(R.id.tileGrid)
        searchInput = findViewById(R.id.searchInput)
        sortButton = findViewById(R.id.sortButton)
        screenTitle = findViewById(R.id.screenTitle)
        emptyLabel = findViewById(R.id.emptyLabel)
        nowBar = findViewById(R.id.nowBar)
        nowChannel = findViewById(R.id.nowChannel)
        nowClock = findViewById(R.id.nowClock)
        nowTitle = findViewById(R.id.nowTitle)
        nowNext = findViewById(R.id.nowNext)
        nowProgress = findViewById(R.id.nowProgress)
        listMode = findViewById(R.id.listMode)
        channelList = findViewById(R.id.channelList)
        viewButton = findViewById(R.id.viewButton)
        previewVideo = findViewById(R.id.previewVideo)
        previewLogo = findViewById(R.id.previewLogo)
        previewNote = findViewById(R.id.previewNote)
        previewChannel = findViewById(R.id.previewChannel)
        previewTitle = findViewById(R.id.previewTitle)
        previewTime = findViewById(R.id.previewTime)
        previewDescription = findViewById(R.id.previewDescription)
        previewProgress = findViewById(R.id.previewProgress)
        previewUpNext = findViewById(R.id.previewUpNext)
        previewFrame = findViewById(R.id.previewFrame)
        previewEmpty = findViewById(R.id.previewEmpty)

        screenTitle.text = if (searchEverything) {
            getString(R.string.search_everything)
        } else when (kind) {
            Kind.LIVE -> getString(R.string.live_tv)
            Kind.VOD -> getString(R.string.movies)
            Kind.SERIES -> getString(R.string.series)
        }

        categoryAdapter = CategoryAdapter { category -> showCategory(category.id) }
        tileAdapter = TileAdapter(
            isFavourite = { tile -> prefs.isFavourite(tile.kind, tile.id) },
            onOpen = { tile -> open(tile) },
            onLongPress = { tile -> toggleFavourite(tile) },
            onFocused = { tile -> showWhatsOn(tile) },
            searchOnly = searchEverything
        )

        rowAdapter = ChannelRowAdapter(
            isFavourite = { item -> prefs.isFavourite(Kind.LIVE, item.streamId) },
            onOpen = { item -> playChannel(item) },
            onLongPress = { item -> toggleFavourite(tileFor(item)) },
            onFocused = { item -> highlight(item) },
            programmesFor = { id -> EpgCache.cached(id) },
            onNeedGuide = { item -> fetchGuideFor(item) },
            onGuideNoLongerNeeded = { item -> cancelGuideFor(item.streamId) },
            onFocusedRowRecycled = { listLostFocusWhileScrolling = true }
        )
        channelList.layoutManager = LinearLayoutManager(this)
        channelList.adapter = rowAdapter
        channelList.itemAnimator = null
        channelList.setItemViewCacheSize(20)
        // Rows are recycled out from under the remote on a fast scroll, and focus
        // has to land somewhere - by default it escapes sideways into the category
        // column. Put it back on the channel it was on once the list settles.
        channelList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                if (!listLostFocusWhileScrolling) return
                listLostFocusWhileScrolling = false
                if (!listView || view.hasFocus()) return
                // Posted, so focus is not moved from inside a scroll callback -
                // requesting it scrolls the list again, and re-entering a scroll
                // that way throws.
                view.post { restoreListFocus() }
            }
        })

        upNextAdapter = UpNextAdapter()
        previewUpNext.layoutManager = LinearLayoutManager(this)
        previewUpNext.adapter = upNextAdapter
        previewUpNext.isFocusable = false

        // The preview is the button: press it and the channel goes full screen.
        previewFrame.setOnClickListener { highlighted?.let { playChannel(it) } }
        // Selecting the preview turns its sound on; stepping away silences it
        // again, so browsing the list stays quiet.
        previewFrame.setOnFocusChangeListener { _, hasFocus ->
            previewPlayer?.let { preview ->
                preview.setAudioAttributes(PREVIEW_AUDIO, hasFocus)
                preview.volume = if (hasFocus) 1f else 0f
                // Something else may have taken the sound while this was muted.
                if (hasFocus && !preview.playWhenReady) preview.play()
            }
        }

        // The toggle only makes sense for Live TV - films and series have no guide.
        listView = kind == Kind.LIVE && !searchEverything && prefs.liveListView
        viewButton.visibility =
            if (kind == Kind.LIVE && !searchEverything) View.VISIBLE else View.GONE
        viewButton.setOnClickListener {
            listView = !listView
            prefs.liveListView = listView
            applyViewMode()
            showCategory(currentCategoryId)
        }

        categoryList.layoutManager = LinearLayoutManager(this)
        categoryList.adapter = categoryAdapter

        /*
         * SCROLLING A CATEGORY WITH THREE THOUSAND THINGS IN IT.
         *
         * The list itself is cheap; the artwork is not. Every row that scrolls
         * into view asks for a picture, decodes it, and throws away the one
         * that just left - and on a long run down Sports that is hundreds of
         * decodes a second, which is what the stutter is.
         *
         * Three changes, none of them clever. Keep more rows in hand so a
         * flick back up does not re-fetch everything; ask the layout to build
         * the next rows before they are needed rather than at the moment they
         * are; and stop animating rows in and out, which is invisible at this
         * speed and costs a frame each time.
         */
        val grid = GridLayoutManager(this, spanCount())
        grid.isItemPrefetchEnabled = true
        grid.initialPrefetchItemCount = spanCount() * 2
        tileGrid.layoutManager = grid
        tileGrid.itemAnimator = null
        tileGrid.setItemViewCacheSize(spanCount() * 4)
        tileGrid.recycledViewPool.setMaxRecycledViews(0, spanCount() * 6)
        tileGrid.adapter = tileAdapter
        tileGrid.setHasFixedSize(true)

        sortButton.setOnClickListener {
            sortAlphabetical = !sortAlphabetical
            sortButton.text = getString(
                if (sortAlphabetical) R.string.sort_az else R.string.sort_default
            )
            showCategory(currentCategoryId)
        }
        sortButton.text = getString(R.string.sort_default)

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                // Typing widens the screen to the whole library and clearing the box
                // puts the chosen category back, so a channel filed under a heading
                // you would never open is still findable now that All has gone.
                val wanted = text.isNotBlank()
                if (!searchEverything && wanted != searchingLibrary) {
                    searchingLibrary = wanted
                    showCategory(currentCategoryId)
                }
                tileAdapter.filter(text)
                if (::rowAdapter.isInitialized) rowAdapter.filter(text)
                updateEmpty()
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                prefs.addRecentSearch(searchInput.text.toString())
                dismissKeyboard()
                (if (listView) channelList else tileGrid).requestFocus()
            }
            false
        }
        // Moving off the search box puts the keyboard away rather than leaving it
        // sitting over what was just found. Watched at the window, because a list
        // hands focus to one of its rows and never to itself.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, _ ->
            if (old === searchInput) dismissKeyboard()
        }

        if (searchEverything) {
            categoryList.visibility = View.GONE
            findViewById<View>(R.id.categoryDivider).visibility = View.GONE
            sortButton.visibility = View.GONE
            searchInput.hint = getString(R.string.search_everything_hint)
            updateEmpty()
            // Thousands of items across three catalogues: built off the main
            // thread so the keyboard is up straight away. Nothing is shown until
            // something is typed, so there is nothing to wait for on screen.
            lifecycleScope.launch {
                val tiles = withContext(Dispatchers.Default) { everythingTiles() }
                tileAdapter.submit(tiles)
                tileAdapter.filter(searchInput.text?.toString().orEmpty())
                updateEmpty()
            }
        } else {
            buildSidebar()
        }

        applyViewMode()

        // On a TV, focusing the search box throws a full-screen keyboard over the
        // content before anyone has asked for it - and the box is first in the
        // layout, so it takes focus by default unless something else claims it.
        if (onTv) {
            val content = if (listView) channelList else tileGrid
            content.post { if (!content.hasFocus()) content.requestFocus() }
        } else if (searchEverything || intent.getBooleanExtra(EXTRA_FOCUS_SEARCH, false)) {
            searchInput.requestFocus()
        }
    }

    override fun onPause() {
        super.onPause()
        pendingFocus?.removeListener()
        pendingFocus = null
        // Never leave a preview holding the line while the screen is in the background.
        stopPreview()
        stopProgressTicks()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreview()
        stopProgressTicks()
        guideJobs.values.forEach { it.cancel() }
        guideJobs.clear()
    }

    override fun onResume() {
        super.onResume()
        // A film or series screen can add or drop a favourite while we are away,
        // so the counts beside Favourites and Continue watching are re-read here
        // as well. The highlight stays where it was.
        refreshSidebarCounts()
        // Favourites and resume points can change while we were away. In list view
        // the rows are repainted in place - rebuilding would throw the viewer back
        // to the top of the channel list every time they came out of a channel.
        when {
            searchEverything -> {
                tileAdapter.notifyDataSetChanged()
                tileGrid.post { restoreFocusToLastOpened() }
            }
            listView && kind == Kind.LIVE -> {
                rowAdapter.notifyDataSetChanged()
                startProgressTicks()
                updateEmpty()
                channelList.post { restoreFocusToLastOpened() }
                // onPause stopped the preview. Re-arm it for whatever is still
                // highlighted, or the panel sits on a logo for good.
                highlighted?.let { channel ->
                    highlighted = null
                    highlight(channel)
                }
            }
            else -> {
                showCategory(currentCategoryId)
                startProgressTicks()      // the grid strip has a bar to keep moving too
                tileGrid.post { restoreFocusToLastOpened() }
            }
        }
    }

    // ---------- sidebar ----------

    /**
     * The sidebar's entries, counts and all.
     *
     * Separated out because there are two reasons to want them: building the
     * screen, which also decides where to start, and refreshing the numbers
     * after something changes, which must not move anybody.
     */
    private fun sidebarCategories(): List<Category> {
        val specials = ArrayList<Category>()
        /*
         * COUNT WHAT CAN ACTUALLY BE SEEN.
         *
         * This used to count stored favourites, while the list itself only
         * shows the ones that still match a channel. When a portal changes a
         * channel's id - which they do - the favourite left behind points at
         * nothing: counted in the sidebar, absent from the list, and
         * impossible to remove because it cannot be selected. "Favourites 2"
         * over an empty screen, and no way out of it.
         *
         * Counting the same list that is drawn means the number can never
         * disagree with the screen again.
         */
        specials.add(Category(CATEGORY_FAVOURITES, getString(R.string.favourites), favouritesOnScreen()))
        // The guide belongs beside the channels, not behind a trip back to the
        // home screen.
        if (kind == Kind.LIVE) specials.add(Category(CATEGORY_GUIDE, getString(R.string.tv_guide)))
        if (kind != Kind.LIVE) {
            specials.add(Category(CATEGORY_RECENT, getString(R.string.recently_added)))
            specials.add(Category(CATEGORY_CONTINUE, getString(R.string.continue_watching), prefs.continueWatching(kind).size))
        }
        // All earns its place on a film or series library, where an A-Z list is a
        // normal way to browse. On Live TV it is three thousand channels nobody
        // scrolls, and the search box now reaches every one of them anyway.
        if (kind != Kind.LIVE) {
            specials.add(Category(CATEGORY_ALL, getString(R.string.all_items), sourceSize()))
        }

        val categories = when (kind) {
            // Live TV is the only one with a house order worth imposing.
            Kind.LIVE -> Catalog.liveCategories.inPreferredOrder()
            Kind.VOD -> Catalog.vodCategories
            Kind.SERIES -> Catalog.seriesCategories
        }

        return specials + categories
    }

    /**
     * The counts, brought up to date, with the highlight left where it is.
     */
    private fun refreshSidebarCounts() {
        val all = sidebarCategories()
        categoryAdapter.submit(all)
        val index = all.indexOfFirst { it.id == currentCategoryId }
        if (index >= 0) categoryAdapter.selectAt(index)
    }

    private fun buildSidebar() {
        val all = sidebarCategories()
        categoryAdapter.submit(all)

        // Favourites, the guide, Recently added and the rest always come first,
        // so counting them off the front gives back the two halves the starting
        // position is worked out from.
        val ours = setOf(
            CATEGORY_FAVOURITES, CATEGORY_GUIDE, CATEGORY_RECENT,
            CATEGORY_CONTINUE, CATEGORY_ALL
        )
        val specialsCount = all.takeWhile { it.id in ours }.size
        val categories = all.drop(specialsCount)

        val requested = intent.getStringExtra(EXTRA_START_CATEGORY)
        val asked = if (requested == null) -1 else all.indexOfFirst { it.id == requested }
        // A category id can go stale between syncs; fall back rather than dropping
        // the viewer on the empty Favourites screen.
        var startIndex = if (asked >= 0) asked else defaultStartIndex(specialsCount, categories)
        // The guide is an action, so opening on it would fire the guide the moment
        // Live TV was opened and leave the browse screen behind it empty. Whatever
        // the arithmetic above decided, step off it.
        if (all.getOrNull(startIndex)?.id == CATEGORY_GUIDE) {
            startIndex = all.indexOfFirst { it.id != CATEGORY_GUIDE && it.id != CATEGORY_FAVOURITES }
            if (startIndex < 0) startIndex = 0
        }
        categoryAdapter.selectAt(startIndex)
        categoryList.scrollToPosition(startIndex)
        showCategory(all[startIndex].id)
    }

    /** How many favourites actually resolve to something in the catalogue. */
    private fun favouritesOnScreen(): Int =
        if (kind == Kind.LIVE) channelsFor(CATEGORY_FAVOURITES).size
        else tilesFor(CATEGORY_FAVOURITES).size

    /**
     * Which sidebar entry to open on.
     *
     * Favourites stays first in the list, but opening on it shows "Nothing here"
     * for most people, so never start there. For live TV, prefer a real viewing
     * category over the full channel list, which on most portals begins with a
     * screen of PPV placeholders.
     */
    private fun defaultStartIndex(specialsCount: Int, categories: List<Category>): Int {
        if (kind == Kind.LIVE) {
            val wanted = Config.PREFERRED_LIVE_CATEGORIES
            // Only the portal's own categories are considered - never Favourites,
            // All and friends, whose names are translated and could collide.
            // Exact names are tried across the whole preference list before any
            // partial match, so "SPORTS" never loses to "PPV SPORTS EVENTS".
            for (name in wanted) {
                val exact = categories.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                if (exact >= 0) return specialsCount + exact
            }
            for (name in wanted) {
                val partial = categories.indexOfFirst { it.name.contains(name, ignoreCase = true) }
                if (partial >= 0) return specialsCount + partial
            }
        }
        // Favourites stays first in the sidebar but opening on it shows "Nothing
        // here" for most people, so never start there.
        //
        // Live TV falls to its first real category. Films and series must not:
        // their categories come back in the portal's own order, unsorted and
        // unfiltered, so landing on the first of them can mean opening Movies
        // straight onto the adult shelf. They keep "Recently added".
        if (kind == Kind.LIVE && categories.isNotEmpty()) return specialsCount
        return if (specialsCount > 1) 1 else 0
    }

    private fun sourceSize(): Int = when (kind) {
        Kind.LIVE -> Catalog.live.size
        Kind.VOD -> Catalog.vod.size
        Kind.SERIES -> Catalog.series.size
    }

    // ---------- content ----------

    private fun showCategory(categoryId: String) = showCategory(categoryId, null)

    /**
     * @param keepPlaceAt when set, the list is rebuilt without jumping to the
     *   top and the remote is put on the item now at that position. Used when
     *   something has been removed from the list somebody is standing in.
     */
    private fun showCategory(categoryId: String, keepPlaceAt: Int?) {
        // The guide is an action, not a category: it opens and leaves the
        // sidebar selection exactly where it was.
        if (categoryId == CATEGORY_GUIDE) {
            startActivity(Intent(this, EpgActivity::class.java))
            return
        }
        // Search mode has no categories; the list is everything, always.
        if (searchEverything) return
        currentCategoryId = categoryId

        if (listView && kind == Kind.LIVE) {
            var channels = if (searchingLibrary) everyChannel() else channelsFor(categoryId)
            if (sortAlphabetical) channels = channels.sortedBy { it.name.lowercase() }
            rowAdapter.submit(channels)
            rowAdapter.filter(searchInput.text.toString())
            if (keepPlaceAt == null) channelList.scrollToPosition(0) else stayAt(channelList, keepPlaceAt)
            listLostFocusWhileScrolling = false
            highlighted = null
            stopPreview()
            showPanel(false)
            startProgressTicks()
            updateEmpty()
            return
        }
        var tiles = if (searchingLibrary) everyTile() else tilesFor(categoryId)
        if (sortAlphabetical) {
            tiles = tiles.sortedBy { it.title.lowercase() }
        } else if (kind != Kind.LIVE && categoryId != CATEGORY_CONTINUE) {
            // Films and series lead with what arrived most recently. A portal's own
            // order is however its database happens to be sorted, which puts last
            // year's catalogue in front of this week's additions. Continue Watching
            // is the exception - it is already in the order things were left.
            tiles = tiles.sortedByDescending { it.added }
        }
        tileAdapter.submit(tiles)
        tileAdapter.filter(searchInput.text.toString())
        if (keepPlaceAt == null) tileGrid.scrollToPosition(0) else stayAt(tileGrid, keepPlaceAt)
        updateEmpty()
    }

    /**
     * KEEPING SOMEBODY WHERE THEY WERE.
     *
     * Taking a channel out of Favourites rebuilt the list and sent it back to
     * the top with nothing focused, which on a remote means there is nowhere to
     * press next - so removing four channels meant leaving the screen and
     * coming back four times. Reported by somebody's parents, which is the only
     * kind of report that finds this sort of thing.
     *
     * The list now stays where it was and the highlight moves onto whatever has
     * taken the removed item's place, or the last item when the end of the list
     * has just gone.
     */
    private fun stayAt(list: RecyclerView, index: Int) {
        val count = list.adapter?.itemCount ?: 0
        if (count == 0) {
            // The last one has gone: the sidebar is the only place left to be.
            categoryList.post {
                categoryList.findFocus() ?: categoryList.requestFocus()
            }
            return
        }
        val landing = index.coerceIn(0, count - 1)
        list.post {
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(landing, 0)
                ?: list.scrollToPosition(landing)
            list.post {
                /*
                 * The search box must not win this.
                 *
                 * With nothing focused after a rebuild, Android gives focus to
                 * the first view that will take it - which here is the search
                 * field at the top, so removing a favourite opened the
                 * keyboard. The field is told to let go, the row is asked
                 * first, and the list itself catches anything left over.
                 */
                val row = list.findViewHolderForAdapterPosition(landing)?.itemView
                val took = row?.requestFocus() == true
                if (!took) list.requestFocus()
            }
        }
    }

    /** Everything the app knows about, for the one search box on the home screen. */
    private fun everythingTiles(): List<Tile> {
        val liveLabel = getString(R.string.live_tv)
        val filmLabel = getString(R.string.movies)
        val seriesLabel = getString(R.string.series)
        val out = ArrayList<Tile>(Catalog.live.size + Catalog.vod.size + Catalog.series.size)
        // Channels first, then films, then series - one shape at a time, so the
        // grid does not alternate tall and short cards down the page.
        for (item in Catalog.live) {
            out.add(
                Tile(
                    id = item.streamId,
                    title = item.name,
                    subtitle = liveLabel,
                    image = IconMemory.artFor(item.streamId, item.name, item.icon),
                    kind = Kind.LIVE,
                    added = item.added
                )
            )
        }
        for (item in Catalog.vod) {
            out.add(Tile(item.streamId, item.name, filmLabel, item.icon, Kind.VOD, item.added))
        }
        for (item in Catalog.series) {
            out.add(Tile(item.seriesId, item.name, seriesLabel, item.cover, Kind.SERIES, item.added))
        }
        return out
    }

    /**
     * The whole library of this kind, which is what a screen shows while there is
     * something in the search box. "All" already means exactly this, so it is
     * built the same way even where the sidebar no longer offers it.
     */
    private fun everyChannel(): List<StreamItem> = channelsFor(CATEGORY_ALL)

    private fun everyTile(): List<Tile> = tilesFor(CATEGORY_ALL)

    /** The same filtering the grid does, but keeping the channels themselves. */
    private fun channelsFor(categoryId: String): List<StreamItem> = when (categoryId) {
        CATEGORY_ALL -> Catalog.live.inPreferredChannelOrder(Catalog.liveCategories)
        CATEGORY_FAVOURITES -> {
            val ids = prefs.favouriteIds(Kind.LIVE)
            Catalog.live.filter { ids.contains(it.streamId) }
        }
        else -> Catalog.liveChannels(categoryId, emptySet())
    }

    private fun tilesFor(categoryId: String): List<Tile> = when (kind) {
        Kind.SERIES -> seriesTiles(categoryId)
        else -> streamTiles(categoryId)
    }

    private fun streamTiles(categoryId: String): List<Tile> {
        val source = when {
            // "All" on Live TV follows the house order rather than the portal's,
            // which otherwise opens on a screen of pay-per-view placeholders.
            kind == Kind.LIVE && categoryId == CATEGORY_ALL ->
                Catalog.live.inPreferredChannelOrder(Catalog.liveCategories)
            kind == Kind.LIVE -> Catalog.live
            else -> Catalog.vod
        }
        val filtered = when (categoryId) {
            CATEGORY_ALL -> source
            CATEGORY_FAVOURITES -> {
                val ids = prefs.favouriteIds(kind)
                source.filter { ids.contains(it.streamId) }
            }
            CATEGORY_CONTINUE -> {
                val order = prefs.continueWatching(kind)
                source.filter { order.contains(it.streamId) }
                    .sortedBy { order.indexOf(it.streamId) }
            }
            CATEGORY_RECENT -> source.sortedByDescending { it.added }.take(RECENT_LIMIT)
            else -> source.filter { it.categoryId == categoryId }
        }
        return filtered.map {
            Tile(
                id = it.streamId,
                title = it.name,
                subtitle = if (kind == Kind.LIVE) it.num else "",
                image = if (kind == Kind.LIVE) {
                    IconMemory.artFor(it.streamId, it.name, it.icon)
                } else {
                    it.icon
                },
                kind = kind,
                added = it.added
            )
        }
    }

    private fun seriesTiles(categoryId: String): List<Tile> {
        val source = Catalog.series
        val filtered = when (categoryId) {
            CATEGORY_ALL -> source
            CATEGORY_FAVOURITES -> {
                val ids = prefs.favouriteIds(Kind.SERIES)
                source.filter { ids.contains(it.seriesId) }
            }
            CATEGORY_CONTINUE -> {
                val order = prefs.continueWatching(Kind.SERIES)
                source.filter { order.contains(it.seriesId) }
                    .sortedBy { order.indexOf(it.seriesId) }
            }
            CATEGORY_RECENT -> source.sortedByDescending { it.added }.take(RECENT_LIMIT)
            else -> source.filter { it.categoryId == categoryId }
        }
        return filtered.map {
            Tile(
                id = it.seriesId,
                title = it.name,
                subtitle = "",
                image = it.cover,
                kind = Kind.SERIES,
                added = it.added
            )
        }
    }

    /**
     * Fills the strip at the bottom for the channel the remote just landed on.
     *
     * Nothing is fetched until the remote settles, or holding a direction down
     * through fifty channels would fire fifty requests at the portal. A
     * touchscreen never focuses anything, so the strip simply never appears
     * there - tapping a channel plays it, which is what a finger expects.
     */
    private fun showWhatsOn(tile: Tile) {
        if (tile.kind != Kind.LIVE) { nowBar.visibility = View.GONE; return }
        if (tile.id == nowFor) return
        nowFor = tile.id

        nowChannel.text = tile.title
        nowTitle.text = getString(R.string.loading)
        nowNext.text = ""
        nowClock.text = ""
        nowProgress.progress = 0
        nowBar.visibility = View.VISIBLE

        nowJob?.cancel()
        nowJob = lifecycleScope.launch {
            val cached = EpgCache.cached(tile.id)
            val listings = cached ?: run {
                delay(WHATS_ON_SETTLE_MS)          // the remote may still be moving
                if (nowFor != tile.id) return@launch
                withContext(Dispatchers.IO) { EpgCache.fetch(client, tile.id) }
            }
            if (nowFor != tile.id) return@launch
            paintWhatsOn(listings)
        }
    }

    private fun paintWhatsOn(listings: List<Programme>) {
        val now = System.currentTimeMillis()
        val current = listings.firstOrNull { it.start <= now && it.end > now }
        val next = listings.firstOrNull { it.start > now }

        if (current == null) {
            nowTitle.text = getString(R.string.no_guide_short)
            nowClock.text = ""
            nowProgress.progress = 0
        } else {
            nowTitle.text = current.title
            nowClock.text = EpgRowAdapter.slot(current)
            val span = (current.end - current.start).coerceAtLeast(1L)
            nowProgress.progress = (((now - current.start) * 100L) / span).toInt().coerceIn(0, 100)
        }
        nowNext.text = if (next == null) {
            ""
        } else {
            getString(R.string.up_next, EpgRowAdapter.clock().format(Date(next.start)), next.title)
        }
    }

    // ---------- list view ----------

    /**
     * Hands focus back to the channel the remote was on before its row was
     * recycled. Falls back to the first row on screen if that channel has since
     * scrolled well out of view.
     */
    /**
     * Back out of a channel and the remote should be on the channel you were
     * watching, not thrown up to the search box - which is where focus lands by
     * default, because it is the first focusable thing on the screen.
     */
    private fun restoreFocusToLastOpened() {
        // Nothing was opened from this screen, so anything left over belongs to
        // another one - the guide, say - and must not move the remote here.
        if (lastOpenedId.isBlank()) return

        // Surfing up and down inside the player ends on a different channel than
        // the one that was opened; land on that one instead.
        val landedOn = PlayerActivity.consumeChannelLandedOn()
        val list = listView && kind == Kind.LIVE && !searchEverything
        val view = if (list) channelList else tileGrid

        fun positionOf(id: String): Int = when {
            id.isBlank() -> -1
            list -> rowAdapter.positionOf(id)
            else -> tileAdapter.positionOf(id, lastOpenedKind)
        }

        // The surfed-to channel first, then the one actually opened - which is
        // still the right answer if the list has been rebuilt since.
        var at = if (lastOpenedKind == Kind.LIVE) positionOf(landedOn) else -1
        if (at < 0) at = positionOf(lastOpenedId)
        // Keep it for the next attempt rather than giving up on this screen for good.
        if (at < 0) return
        lastOpenedId = ""

        val focusIt = Runnable {
            view.findViewHolderForAdapterPosition(at)?.itemView?.requestFocus()
        }
        if (view.findViewHolderForAdapterPosition(at) == null) {
            view.scrollToPosition(at)
            // The row does not exist until the scroll has actually been laid out;
            // a plain post can beat that layout pass and find nothing to focus.
            pendingFocus?.removeListener()
            pendingFocus = view.doOnPreDraw {
                pendingFocus = null
                focusIt.run()
            }
        } else {
            focusIt.run()
        }
    }

    private fun restoreListFocus() {
        if (!listView || channelList.hasFocus()) return
        // Only step in if focus actually escaped this screen's content.
        if (!categoryList.hasFocus() && !searchInput.hasFocus() && !viewButton.hasFocus()) return

        val wanted = highlighted?.streamId?.let { rowAdapter.positionOf(it) } ?: RecyclerView.NO_POSITION
        val target = channelList.findViewHolderForAdapterPosition(wanted)?.itemView
            ?: (channelList.layoutManager as? LinearLayoutManager)
                ?.findFirstCompletelyVisibleItemPosition()
                ?.takeIf { it != RecyclerView.NO_POSITION }
                ?.let { channelList.findViewHolderForAdapterPosition(it)?.itemView }
        target?.requestFocus()
    }

    private fun applyViewMode() {
        val list = listView && kind == Kind.LIVE && !searchEverything
        listMode.visibility = if (list) View.VISIBLE else View.GONE
        tileGrid.visibility = if (list) View.GONE else View.VISIBLE
        viewButton.text = getString(if (list) R.string.view_grid else R.string.view_list)
        nowBar.visibility = View.GONE      // the list carries its own "on now"
        nowFor = ""                        // or the strip never returns for this channel
        listLostFocusWhileScrolling = false
        if (!list) stopPreview()
        startProgressTicks()               // both views have a bar that must move
    }

    /** A list row and a grid tile describe the same channel; favourites need a Tile. */
    private fun tileFor(item: StreamItem): Tile = Tile(
        id = item.streamId,
        title = item.name,
        subtitle = item.num,
        image = item.icon,
        kind = Kind.LIVE,
        added = item.added
    )

    /**
     * The channels on screen, in screen order, so the player can carry on down
     * the same list when the viewer presses down on the remote.
     */
    private fun liveQueue(): List<StreamItem> {
        if (listView && kind == Kind.LIVE && !searchEverything) return rowAdapter.visible()
        val byId = Catalog.live.associateBy { it.streamId }
        return tileAdapter.visible().filter { it.kind == Kind.LIVE }.mapNotNull { byId[it.id] }
    }

    private fun playChannel(item: StreamItem) {
        stopPreview()
        lastOpenedId = item.streamId
        lastOpenedKind = Kind.LIVE
        Catalog.playbackQueue = liveQueue()
        PlayerActivity.start(
            this,
            urls = client.liveUrls(item.streamId),
            title = item.name,
            kind = Kind.LIVE,
            contentId = item.streamId,
            category = currentCategoryId
        )
    }

    /**
     * Pulls a channel's listings so its row can fill in.
     *
     * Held behind a short wait and a two-at-a-time limit: scrolling a category of
     * three hundred channels must not put three hundred requests on the portal,
     * which on a line that allows one connection is how you lock yourself out.
     * A row that scrolls away before its turn is dropped.
     */
    private fun fetchGuideFor(item: StreamItem) {
        val id = item.streamId
        if (guideJobs.containsKey(id) || EpgCache.cached(id) != null) return
        guideJobs[id] = lifecycleScope.launch {
            try {
                delay(GUIDE_SETTLE_MS)
                guideSlots.withPermit {
                    withContext(Dispatchers.IO) { EpgCache.fetch(client, id) }
                }
                if (listView) rowAdapter.guideArrived(id)
                if (highlighted?.streamId == id) paintPreview()
            } finally {
                // Only if this is still the job on file - a cancelled fetch can
                // outlive its cancel and would otherwise evict its replacement.
                if (guideJobs[id] === coroutineContext[Job]) guideJobs.remove(id)
            }
        }
    }

    /** The row left the screen before its listings were wanted. */
    private fun cancelGuideFor(streamId: String) {
        guideJobs.remove(streamId)?.cancel()
    }

    /**
     * The remote landed on a channel in list view: fill the panel, then - once it
     * has sat still - start a muted preview.
     */
    private fun highlight(item: StreamItem) {
        if (highlighted?.streamId == item.streamId) return
        highlighted = item
        stopPreview()
        showPanel(true)

        previewChannel.text = if (item.num.isBlank()) item.name else "${item.num}  ·  ${item.name}"
        previewLogo.visibility = View.VISIBLE
        previewLogo.load(IconMemory.artFor(item.streamId, item.name, item.icon).ifBlank { null }) {
            crossfade(false)
            placeholder(R.drawable.tile_placeholder)
            error(R.drawable.tile_placeholder)
            fallback(R.drawable.tile_placeholder)
        }
        paintPreview()
        if (EpgCache.cached(item.streamId) == null) fetchGuideFor(item)

        previewJob?.cancel()
        if (!prefs.previewEnabled) return
        // Same reason as the guide: a preview is a second connection, and
        // while a recording is running there isn't one to spare.
        if (RecorderService.isRecording) return
        previewJob = lifecycleScope.launch {
            // Only once the remote stops moving. Holding a direction through fifty
            // channels must not open fifty streams - on a line with one connection
            // that is the quickest way to lock yourself out.
            delay(PREVIEW_SETTLE_MS)
            if (highlighted?.streamId == item.streamId && listView) startPreview(item)
        }
    }

    /** Hides the panel's detail until a channel is actually highlighted. */
    private fun showPanel(hasChannel: Boolean) {
        previewEmpty.visibility = if (hasChannel) View.GONE else View.VISIBLE
        previewLogo.visibility = if (hasChannel) View.VISIBLE else View.GONE
        previewNote.visibility = View.GONE
        for (v in listOf(previewChannel, previewTitle, previewTime,
                         previewDescription, previewProgress, previewUpNext)) {
            v.visibility = if (hasChannel) View.VISIBLE else View.GONE
        }
        previewFrame.isClickable = hasChannel
        previewFrame.isFocusable = hasChannel
    }

    private fun paintPreview() {
        val item = highlighted ?: return
        val listings = EpgCache.cached(item.streamId)
        val now = System.currentTimeMillis()
        val current = listings?.firstOrNull { it.start <= now && it.end > now }

        if (current == null) {
            previewTitle.setText(if (listings == null) R.string.loading else R.string.no_guide_short)
            previewTime.text = ""
            previewDescription.text = ""
            previewProgress.progress = 0
        } else {
            previewTitle.text = current.title
            previewTime.text = EpgRowAdapter.slot(current)
            previewDescription.text = current.description
            val span = (current.end - current.start).coerceAtLeast(1L)
            previewProgress.progress = (((now - current.start) * 100L) / span).toInt().coerceIn(0, 100)
        }
        upNextAdapter.submit(listings.orEmpty().filter { it.start > now }.take(6))
    }

    private fun startPreview(item: StreamItem, urlIndex: Int = 0) {
        stopPreview()
        val urls = client.liveUrls(item.streamId)
        if (urlIndex >= urls.size) {
            previewNote.setText(R.string.preview_unavailable)
            previewNote.visibility = View.VISIBLE
            return
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Config.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        // A preview only has to look alive, so it starts on the shallowest buffer
        // that will hold a picture rather than the deep one a full watch uses.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 10_000, 500, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .setLoadControl(loadControl)
            .build()
        // Audio focus is taken only while the preview can actually be heard. A
        // muted preview that grabbed focus would stop whatever else the box was
        // playing every time the remote moved down a row.
        val audible = previewFrame.hasFocus()
        exo.setAudioAttributes(PREVIEW_AUDIO, audible)
        // Silent while browsing; audible once the preview itself is selected.
        exo.volume = if (audible) 1f else 0f
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Never release a player from inside its own callback. Step out
                // first, then try the other container (.ts vs .m3u8) once - some
                // portals serve only one of them - and give up after that rather
                // than retrying and holding the connection open.
                previewVideo.post {
                    if (isFinishing || isDestroyed) return@post
                    if (highlighted?.streamId != item.streamId || !listView) return@post
                    startPreview(item, urlIndex + 1)
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
        exo.setMediaItem(MediaItem.fromUri(urls[urlIndex]))
        exo.prepare()
        exo.playWhenReady = true
        previewPlayer = exo
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewVideo.player = null
        previewPlayer?.release()
        previewPlayer = null
        previewLogo.visibility = View.VISIBLE
        previewNote.visibility = View.GONE
    }

    /**
     * A programme's progress is worked out when it is painted, so without a nudge
     * the bar sits frozen on a channel someone has left highlighted.
     */
    private val progressTick = object : Runnable {
        override fun run() {
            // Never repaint while the list is moving - a rebind mid-scroll is
            // how the remote loses its place.
            val settled = channelList.scrollState == RecyclerView.SCROLL_STATE_IDLE
            if (listView) {
                paintPreview()
                // Rows are left alone while the list is moving: a rebind mid-scroll
                // is how the remote loses its place.
                if (settled) rowAdapter.tickProgress()
            } else if (nowFor.isNotBlank()) {
                EpgCache.cached(nowFor)?.let { paintWhatsOn(it) }
            }
            ticker.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private fun startProgressTicks() {
        ticker.removeCallbacks(progressTick)
        ticker.postDelayed(progressTick, PROGRESS_TICK_MS)
    }

    private fun stopProgressTicks() = ticker.removeCallbacks(progressTick)

    private fun dismissKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun updateEmpty() {
        val empty = if (listView && kind == Kind.LIVE) rowAdapter.isEmpty() else tileAdapter.isEmpty()
        emptyLabel.visibility = if (empty) View.VISIBLE else View.GONE
        if (searchEverything) {
            // Before anything is typed the grid is empty on purpose, so say so
            // rather than leaving "Nothing here" under an untouched search box.
            emptyLabel.setText(
                if (searchInput.text.isNullOrBlank()) R.string.search_prompt else R.string.nothing_here
            )
        }
    }

    // ---------- actions ----------

    private fun open(tile: Tile) {
        lastOpenedId = tile.id
        lastOpenedKind = tile.kind
        when (tile.kind) {
            Kind.SERIES -> startActivity(
                Intent(this, SeriesActivity::class.java)
                    .putExtra(SeriesActivity.EXTRA_SERIES_ID, tile.id)
                    .putExtra(SeriesActivity.EXTRA_TITLE, tile.title)
                    .putExtra(SeriesActivity.EXTRA_COVER, tile.image)
            )
            Kind.LIVE -> {
                Catalog.playbackQueue = liveQueue()
                PlayerActivity.start(
                    this,
                    urls = client.liveUrls(tile.id),
                    title = tile.title,
                    kind = Kind.LIVE,
                    contentId = tile.id,
                    category = if (searchEverything) CATEGORY_ALL else currentCategoryId
                )
            }
            Kind.VOD -> {
                val movie = Catalog.vod.firstOrNull { it.streamId == tile.id } ?: return
                // A poster and a title is not enough to choose by, so the film
                // opens its own page first. Watch is already focused there, so
                // anyone who knew what they wanted is still one press away.
                MovieActivity.start(
                    this,
                    movieId = movie.streamId,
                    title = tile.title,
                    cover = movie.icon,
                    containerExtension = movie.containerExtension
                )
            }
        }
    }

    private fun toggleFavourite(tile: Tile) {
        val nowFavourite = prefs.toggleFavourite(tile.kind, tile.id)
        Toast.makeText(
            this,
            getString(if (nowFavourite) R.string.added_favourite else R.string.removed_favourite, tile.title),
            Toast.LENGTH_SHORT
        ).show()

        /*
         * The number beside Favourites is part of the answer, not decoration.
         *
         * The sidebar is built once when the screen opens and the count is
         * taken then; adding or removing a favourite redrew the grid but left
         * that number where it was. Four favourites, remove one, and the list
         * says four with three things in it - which makes somebody doubt
         * whether the removal happened at all.
         *
         * So the sidebar is rebuilt here too, and the place in it is kept, so
         * the count changes under the highlight without the highlight moving.
         */
        refreshSidebarCounts()

        /*
         * REMOVING ONE ROW, NOT REBUILDING THE LIST.
         *
         * Standing in Favourites and taking a star off used to rebuild the
         * whole category. For the frame between the old rows going and the new
         * ones arriving there is nothing on screen to hold focus, and the
         * screen jumped to the search box at the top - every single time.
         * Making the box unfocusable did not help, because the jump was the
         * list emptying rather than the box grabbing anything.
         *
         * So the row is simply taken out. Everything else stays exactly where
         * it is, the highlight moves down to whatever has closed the gap, and
         * nothing else on the screen moves at all.
         */
        if (currentCategoryId == CATEGORY_FAVOURITES && !nowFavourite) {
            val removedAt = if (listView && kind == Kind.LIVE) {
                rowAdapter.removeById(tile.id)
            } else {
                tileAdapter.removeById(tile.id)
            }
            if (removedAt >= 0) {
                val list = if (listView && kind == Kind.LIVE) channelList else tileGrid
                val remaining = list.adapter?.itemCount ?: 0
                if (remaining == 0) {
                    updateEmpty()
                    categoryList.requestFocus()
                } else {
                    val landing = removedAt.coerceIn(0, remaining - 1)
                    list.post {
                        list.findViewHolderForAdapterPosition(landing)?.itemView?.requestFocus()
                            ?: list.requestFocus()
                    }
                    updateEmpty()
                }
            }
        }
    }

    private fun spanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        // Channel logos need width to stay legible; posters are narrow, so more fit.
        val target = if (kind == Kind.LIVE || searchEverything) 112f else 92f
        // The grid is 74% of the row once the sidebar, divider and padding are
        // out - except in search mode, where there is no sidebar at all.
        val gridDp = (widthDp - SIDE_CHROME_DP) * (if (searchEverything) 1f else 0.74f)
        return max(2, (gridDp / target).toInt())
    }

    companion object {
        /** Outer padding (8dp each side) plus the divider and its margins. */
        private const val SIDE_CHROME_DP = 27f

        const val EXTRA_KIND = "kind"
        const val EXTRA_START_CATEGORY = "start_category"
        const val EXTRA_FOCUS_SEARCH = "focus_search"
        const val EXTRA_SEARCH_ALL = "search_all"
        /** How long the remote must sit still before the portal is asked. */
        private const val WHATS_ON_SETTLE_MS = 350L

        /** Ordinary film sound, for the preview panel once it can be heard. */
        private val PREVIEW_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        /** How long the remote must sit still before a preview opens a stream. */
        private const val PREVIEW_SETTLE_MS = 1_500L
        /** How long a row must stay on screen before its listings are worth fetching. */
        private const val GUIDE_SETTLE_MS = 250L
        /** How often the "how far through" bars are repainted. */
        private const val PROGRESS_TICK_MS = 30_000L
        const val CATEGORY_ALL = "__all"
        const val CATEGORY_FAVOURITES = "__favourites"
        /** Not a category at all - the sidebar's way into the guide. */
        const val CATEGORY_GUIDE = "__guide"
        const val CATEGORY_RECENT = "__recent"
        const val CATEGORY_CONTINUE = "__continue"
        private const val RECENT_LIMIT = 120
    }
}
