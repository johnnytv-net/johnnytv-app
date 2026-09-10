package com.johnnytv.player

import android.content.Context
import org.json.JSONArray
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
    fun sync(context: Context, client: XtreamClient, progress: (String, String) -> Unit) {
        progress(SECTION_LIVE, STATUS_WORKING)
        val liveCats = runCatching { client.liveCategories() }.getOrDefault(emptyList())
        val liveStreams = runCatching { client.allLiveStreams() }.getOrDefault(emptyList())
        progress(SECTION_LIVE, if (liveStreams.isEmpty()) STATUS_EMPTY else STATUS_DONE)

        progress(SECTION_VOD, STATUS_WORKING)
        val vodCats = runCatching { client.vodCategories() }.getOrDefault(emptyList())
        val vodStreams = runCatching { client.allVodStreams() }.getOrDefault(emptyList())
        progress(SECTION_VOD, if (vodStreams.isEmpty()) STATUS_EMPTY else STATUS_DONE)

        progress(SECTION_SERIES, STATUS_WORKING)
        val seriesCats = runCatching { client.seriesCategories() }.getOrDefault(emptyList())
        val seriesList = runCatching { client.allSeries() }.getOrDefault(emptyList())
        progress(SECTION_SERIES, if (seriesList.isEmpty()) STATUS_EMPTY else STATUS_DONE)

        liveCategories = liveCats
        vodCategories = vodCats
        seriesCategories = seriesCats
        live = liveStreams
        vod = vodStreams
        series = seriesList
        playbackQueue = emptyList()      // the old list is about to be rebuilt

        applyCounts()
        // Note the artwork this service supplied, so the other one can borrow it.
        runCatching { IconMemory.remember(context, liveStreams) }
        save(context)
        Prefs(context).lastSync = System.currentTimeMillis()
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
        else -> live.filter { it.categoryId == categoryId }
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
            liveCategories = readCategories(context, FILE_LIVE_CATS)
            vodCategories = readCategories(context, FILE_VOD_CATS)
            seriesCategories = readCategories(context, FILE_SERIES_CATS)
            live = readStreams(context, FILE_LIVE)
            vod = readStreams(context, FILE_VOD)
            series = readSeries(context, FILE_SERIES)
            applyCounts()
            isLoaded
        } catch (e: Exception) {
            false
        }
    }

    private fun save(context: Context) {
        writeCategories(context, FILE_LIVE_CATS, liveCategories)
        writeCategories(context, FILE_VOD_CATS, vodCategories)
        writeCategories(context, FILE_SERIES_CATS, seriesCategories)
        write(context, FILE_LIVE, Json.arrayOf(live.map { it.toJson() }))
        write(context, FILE_VOD, Json.arrayOf(vod.map { it.toJson() }))
        write(context, FILE_SERIES, Json.arrayOf(series.map { it.toJson() }))
    }

    fun clear(context: Context) {
        for (name in listOf(
            FILE_LIVE_CATS, FILE_VOD_CATS, FILE_SERIES_CATS,
            FILE_LIVE, FILE_VOD, FILE_SERIES
        )) {
            runCatching { File(context.filesDir, name).delete() }
        }
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

    private fun write(context: Context, name: String, array: JSONArray) {
        runCatching { File(context.filesDir, name).writeText(array.toString()) }
    }

    private fun read(context: Context, name: String): JSONArray {
        val file = File(context.filesDir, name)
        if (!file.exists()) return JSONArray()
        return runCatching { Json.parse(file.readText()) }.getOrDefault(JSONArray())
    }

    private fun writeCategories(context: Context, name: String, categories: List<Category>) {
        write(context, name, Json.arrayOf(categories.map {
            JSONObject().put("i", it.id).put("t", it.name)
        }))
    }

    private fun readCategories(context: Context, name: String): List<Category> {
        val array = read(context, name)
        val out = ArrayList<Category>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(Category(o.optString("i", ""), o.optString("t", "")))
        }
        return out
    }

    private fun readStreams(context: Context, name: String): List<StreamItem> {
        val array = read(context, name)
        val out = ArrayList<StreamItem>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(StreamItem.fromJson(o))
        }
        return out
    }

    private fun readSeries(context: Context, name: String): List<SeriesItem> {
        val array = read(context, name)
        val out = ArrayList<SeriesItem>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(SeriesItem.fromJson(o))
        }
        return out
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
}
