package com.johnnytv.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The whole catalogue, fetched once and cached on the device.
 *
 * Browsing then costs nothing and, just as importantly, stops the app hammering
 * the portal - Xtream servers usually cap how many connections one account may
 * open at a time.
 */
object Catalog {

    var liveCategories: List<Category> = emptyList()
    var vodCategories: List<Category> = emptyList()
    var seriesCategories: List<Category> = emptyList()

    var live: List<StreamItem> = emptyList()
    var vod: List<StreamItem> = emptyList()
    var series: List<SeriesItem> = emptyList()

    val isLoaded: Boolean
        get() = live.isNotEmpty() || vod.isNotEmpty() || series.isNotEmpty()

    /**
     * True when this service's Live TV folders are already in the order they
     * are to be shown in, and the app's own built-in order must keep off.
     */
    var liveOrderFixed: Boolean = false
        private set

    /**
     * The channel list exactly as it was on screen when a channel was opened -
     * same order, same search filter. The player walks this so that pressing down
     * goes to the channel that was next in the list the viewer was looking at.
     */
    var playbackQueue: List<StreamItem> = emptyList()

    // ---------- syncing ----------

    /**
     * Pulls everything from the portal and writes it to disk.
     * [progress] is called with (section, status) so the sync screen can follow along.
     * Sections that fail are left empty rather than failing the whole sync.
     */
    /*
     * The count goes with the news.
     *
     * Each section used to report itself finished while the lists it had just
     * fetched were still waiting in local variables - everything is assigned at
     * the end, so that a half-built catalogue is never visible to the rest of
     * the app. Perfectly sound, except that the screen asking "how many
     * channels?" was told none, because none had been assigned yet. It read
     * "0 channels" beside a finished tick.
     *
     * So the number travels with the message rather than being looked up after
     * it: the section that has just finished says how much it found.
     */
    fun sync(context: Context, client: XtreamClient, progress: (String, String) -> Unit) {
        reasons = emptyMap()
        progress(SECTION_LIVE, STATUS_WORKING)
        val liveCats = fetched(SECTION_LIVE) { client.liveCategories() }
        val liveStreams = fetched(SECTION_LIVE) { client.allLiveStreams() }
        counted = counted + (SECTION_LIVE to liveStreams.size)
        progress(SECTION_LIVE, if (liveStreams.isEmpty()) STATUS_EMPTY else STATUS_DONE)

        progress(SECTION_VOD, STATUS_WORKING)
        val vodCats = fetched(SECTION_VOD) { client.vodCategories() }
        val vodStreams = fetched(SECTION_VOD) { client.allVodStreams() }
        counted = counted + (SECTION_VOD to vodStreams.size)
        progress(SECTION_VOD, if (vodStreams.isEmpty()) STATUS_EMPTY else STATUS_DONE)

        progress(SECTION_SERIES, STATUS_WORKING)
        val seriesCats = fetched(SECTION_SERIES) { client.seriesCategories() }
        val seriesList = fetched(SECTION_SERIES) { client.allSeries() }
        counted = counted + (SECTION_SERIES to seriesList.size)
        progress(SECTION_SERIES, if (seriesList.isEmpty()) STATUS_EMPTY else STATUS_DONE)

        /*
         * The lineup, if this service has one.
         *
         * Done here, once, rather than every time a screen asks: what is stored
         * and cached from this point on is already the arranged list, so the
         * browse screen, the guide, the search box and the player all agree
         * without any of them knowing the rules exist.
         */
        // The service's rules, plus anything set for this one account.
        val boxPrefs = Prefs(context)
        val lineup = Lineups.forAccount(
            client.server,
            listOf(client.username, boxPrefs.username, boxPrefs.friendlyName),
            hideAdult = boxPrefs.parentalLock
        )
        // Named channels go first, so none of the arranging below ever sees
        // them - a signpost row at the top of a folder is not a channel.
        val keptLive = Lineups.dropChannels(lineup?.hideChannels ?: emptyList(), liveStreams)
        // Folders the customer locked on this box, by the names they chose from.
        val locked = if (boxPrefs.parentalLock) boxPrefs.lockedFolders else emptySet()
        val offered = boxPrefs.offeredFolders
        /*
         * The chosen folders, plus any adult folder nobody has been shown yet.
         * The customer ticks the adult ones themselves - they arrive already
         * ticked - but a panel can add a new one at any time, and a folder that
         * was never on the list could not have been ticked.
         */
        fun lockedIn(kind: String, all: List<Category>): List<String> {
            val chosen = locked.filter { it.startsWith("$kind|") }.map { it.substringAfter('|') }
            if (!boxPrefs.parentalLock) return chosen
            val unseen = all.filter { Lineups.looksAdult(it.name) && !offered.contains("$kind|" + it.name) }
                .map { it.name }
            return chosen + unseen
        }
        val arranged = Lineups.dropFolders(Lineups.apply(lineup, liveCats, keptLive), lockedIn("live", liveCats))

        /*
         * Films and series get the blunt half of the rules: folders named there
         * go, and everything filed in them goes with them. A folder taken away
         * while its titles stay behind leaves films that can still be searched
         * for and played but that belong nowhere, which is worse than leaving
         * the folder alone.
         */
        val prunedVod = Lineups.prune((lineup?.hideMovies ?: emptyList()) + lockedIn("vod", vodCats), vodCats)
        val prunedSeries = Lineups.prune((lineup?.hideSeries ?: emptyList()) + lockedIn("series", seriesCats), seriesCats)

        liveOrderFixed = lineup?.strict == true
        liveCategories = arranged.first
        vodCategories = prunedVod.first
        seriesCategories = prunedSeries.first
        live = arranged.second
        vod = if (prunedVod.second.isEmpty()) vodStreams
              else vodStreams.filter { !prunedVod.second.contains(it.categoryId) }
        series = if (prunedSeries.second.isEmpty()) seriesList
                 else seriesList.filter { !prunedSeries.second.contains(it.categoryId) }
        playbackQueue = emptyList()      // the old list is about to be rebuilt

        applyCounts()
        // Note the artwork this service supplied, so the other one can borrow it.
        runCatching { IconMemory.remember(context, arranged.second) }
        save(context)

        /*
         * Clear out favourites whose channel has gone.
         *
         * A portal that renumbers its streams leaves favourites pointing at
         * ids that no longer exist. They cannot be seen and so cannot be
         * removed by hand - the only moment we can safely tidy them is here,
         * holding a catalogue we have just fetched and know to be real.
         */
        val prefs = Prefs(context)
        runCatching {
            if (arranged.second.isNotEmpty()) {
                prefs.pruneFavourites(Kind.LIVE, arranged.second.map { it.streamId }.toSet())
            }
            if (vodStreams.isNotEmpty()) {
                prefs.pruneFavourites(Kind.VOD, vodStreams.map { it.streamId }.toSet())
            }
            if (seriesList.isNotEmpty()) {
                prefs.pruneFavourites(Kind.SERIES, seriesList.map { it.seriesId }.toSet())
            }
        }
        prefs.lastSync = System.currentTimeMillis()
    }

    /**
     * The channels in a Live TV category, in the order the app shows them.
     *
     * Shared so the browse screen, the guide and the player all agree on what
     * "the next channel" means.
     */
    fun liveChannels(categoryId: String, favouriteIds: Set<String>): List<StreamItem> = when (categoryId) {
        BrowseActivity.CATEGORY_ALL -> live.inPreferredChannelOrder(liveCategories)
        BrowseActivity.CATEGORY_FAVOURITES -> live.filter { favouriteIds.contains(it.streamId) }
        else -> {
            val list = live.filter { it.categoryId == categoryId }
            // An event block is a schedule, so show it as one.
            val name = liveCategories.firstOrNull { it.id == categoryId }?.name ?: ""
            if (EventOrder.isEventCategory(name)) EventOrder.sort(list) else list
        }
    }

    private fun applyCounts() {
        countInto(liveCategories) { id -> live.count { it.categoryId == id } }
        countInto(vodCategories) { id -> vod.count { it.categoryId == id } }
        countInto(seriesCategories) { id -> series.count { it.categoryId == id } }
    }

    private fun countInto(categories: List<Category>, counter: (String) -> Int) {
        for (category in categories) category.count = counter(category.id)
    }

    // ---------- disk ----------

    fun load(context: Context): Boolean {
        runCatching { IconMemory.load(context) }
        playbackQueue = emptyList()      // belongs to whatever was on screen before
        return try {
            liveOrderFixed = File(context.filesDir, FILE_ORDER_FIXED).exists()
            liveCategories = readList(context, FILE_LIVE_CATS) { Category(it.optString("i", ""), it.optString("t", "")) }
            vodCategories = readList(context, FILE_VOD_CATS) { Category(it.optString("i", ""), it.optString("t", "")) }
            seriesCategories = readList(context, FILE_SERIES_CATS) { Category(it.optString("i", ""), it.optString("t", "")) }
            live = readList(context, FILE_LIVE) { StreamItem.fromJson(it) }
            vod = readList(context, FILE_VOD) { StreamItem.fromJson(it) }
            series = readList(context, FILE_SERIES) { SeriesItem.fromJson(it) }
            applyCounts()
            isLoaded
        } catch (e: Exception) {
            false
        }
    }

    private fun save(context: Context) {
        runCatching {
            val flag = File(context.filesDir, FILE_ORDER_FIXED)
            if (liveOrderFixed) flag.writeText("1") else flag.delete()
        }
        val category: (Category) -> JSONObject = { JSONObject().put("i", it.id).put("t", it.name) }
        writeList(context, FILE_LIVE_CATS, liveCategories, category)
        writeList(context, FILE_VOD_CATS, vodCategories, category)
        writeList(context, FILE_SERIES_CATS, seriesCategories, category)
        writeList(context, FILE_LIVE, live) { it.toJson() }
        writeList(context, FILE_VOD, vod) { it.toJson() }
        writeList(context, FILE_SERIES, series) { it.toJson() }
    }

    fun clear(context: Context) {
        for (name in listOf(
            FILE_LIVE_CATS, FILE_VOD_CATS, FILE_SERIES_CATS,
            FILE_LIVE, FILE_VOD, FILE_SERIES
        )) {
            runCatching { File(context.filesDir, name).delete() }
        }
        runCatching { File(context.filesDir, FILE_ORDER_FIXED).delete() }
        liveOrderFixed = false
        liveCategories = emptyList()
        vodCategories = emptyList()
        seriesCategories = emptyList()
        live = emptyList()
        vod = emptyList()
        series = emptyList()
        // Stream ids are small numbers and repeat across portals, so a queue left
        // over from the previous account would tune the wrong channels.
        playbackQueue = emptyList()
    }

    /*
     * THE SAVED COPY, ONE ITEM AT A TIME.
     *
     * The catalogue kept on the box is read on every start, and it used to be
     * read whole - the file as text, then the text as a tree - which on a film
     * list is the largest thing the app ever asks memory for, asked for before
     * the home screen has even appeared. Written and read an item at a time it
     * costs one item. See JsonStream.
     *
     * It is also written to one side and swapped in when complete, so a box
     * switched off part-way through a Refresh keeps the list it had rather
     * than half of the new one.
     */
    private fun <T> writeList(context: Context, name: String, items: List<T>, toJson: (T) -> JSONObject) {
        runCatching {
            val file = File(context.filesDir, name)
            val beside = File(context.filesDir, "$name.tmp")
            beside.bufferedWriter().use { out ->
                out.write("[")
                var first = true
                for (item in items) {
                    if (!first) out.write(",")
                    first = false
                    out.write(toJson(item).toString())
                }
                out.write("]")
            }
            if (!beside.renameTo(file)) {
                file.delete()
                beside.renameTo(file)
            }
        }
    }

    /** Empty when the file is missing or cannot be read through to its end. */
    private fun <T> readList(context: Context, name: String, make: (JSONObject) -> T): List<T> {
        val file = File(context.filesDir, name)
        if (!file.exists()) return emptyList()
        val out = ArrayList<T>()
        return try {
            file.bufferedReader().use { text -> JsonStream.eachObject(text) { out.add(make(it)) } }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * How much each section found, the moment it finished - before the lists
     * themselves are handed over. The sync screen reads this rather than the
     * catalogue, which is still empty while the work is going on.
     */
    var counted: Map<String, Int> = emptyMap()
        private set

    /**
     * Why a section came back with nothing, when it did. A failed fetch used
     * to be quietly treated as an empty one, which left the sync screen saying
     * "check your account" over what was really a timeout or a server that had
     * moved. The first thing each section ran into is kept here instead.
     */
    var reasons: Map<String, String> = emptyMap()
        private set

    private fun <T> fetched(section: String, call: () -> List<T>): List<T> = try {
        call()
    } catch (e: Throwable) {
        val why = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        if (!reasons.containsKey(section)) reasons = reasons + (section to why)
        emptyList()
    }

    const val SECTION_LIVE = "LIVE TV"
    const val SECTION_VOD = "MOVIES"
    const val SECTION_SERIES = "SERIES"

    const val STATUS_WAITING = "Waiting…"
    const val STATUS_WORKING = "Loading…"
    const val STATUS_DONE = "Done"
    const val STATUS_EMPTY = "None"

    private const val FILE_LIVE_CATS = "live_categories.json"
    private const val FILE_VOD_CATS = "vod_categories.json"
    private const val FILE_SERIES_CATS = "series_categories.json"
    private const val FILE_LIVE = "live.json"
    private const val FILE_VOD = "vod.json"
    private const val FILE_SERIES = "series.json"
    private const val FILE_ORDER_FIXED = "live_order_fixed"
}
