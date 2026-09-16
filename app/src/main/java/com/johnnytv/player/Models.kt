package com.johnnytv.player

import org.json.JSONArray
import org.json.JSONObject

enum class Kind { LIVE, VOD, SERIES }

data class Category(
    val id: String,
    val name: String,
    var count: Int = 0
)

/** A live channel or a movie. */
data class StreamItem(
    val streamId: String,
    val num: String,
    val name: String,
    val icon: String,
    val containerExtension: String,
    val categoryId: String,
    val added: Long,
    val kind: Kind
) {
    fun toJson(): JSONObject = JSONObject()
        .put("i", streamId)
        .put("n", num)
        .put("t", name)
        .put("g", icon)
        .put("e", containerExtension)
        .put("c", categoryId)
        .put("a", added)
        .put("k", kind.name)

    companion object {
        fun fromJson(o: JSONObject): StreamItem = StreamItem(
            streamId = o.optString("i", ""),
            num = o.optString("n", ""),
            name = o.optString("t", ""),
            icon = o.optString("g", ""),
            containerExtension = o.optString("e", "mp4"),
            categoryId = o.optString("c", ""),
            added = o.optLong("a", 0L),
            kind = runCatching { Kind.valueOf(o.optString("k", "LIVE")) }.getOrDefault(Kind.LIVE)
        )
    }
}

data class SeriesItem(
    val seriesId: String,
    val name: String,
    val cover: String,
    val plot: String,
    val categoryId: String,
    val added: Long,
    val genre: String = "",
    val year: String = "",
    val rating: String = "",
    val backdrop: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("i", seriesId)
        .put("t", name)
        .put("g", cover)
        .put("p", plot)
        .put("c", categoryId)
        .put("a", added)
        .put("gn", genre)
        .put("y", year)
        .put("r", rating)
        .put("b", backdrop)

    /** "TV Show · 2022 · 8.1 · Action & Adventure" - blank pieces are dropped. */
    fun metaLine(): String = listOf("TV Show", year, rating, genre)
        .filter { it.isNotBlank() }
        .joinToString("  ·  ")

    companion object {
        fun fromJson(o: JSONObject): SeriesItem = SeriesItem(
            seriesId = o.optString("i", ""),
            name = o.optString("t", ""),
            cover = o.optString("g", ""),
            plot = o.optString("p", ""),
            categoryId = o.optString("c", ""),
            added = o.optLong("a", 0L),
            genre = o.optString("gn", ""),
            year = o.optString("y", ""),
            rating = o.optString("r", ""),
            backdrop = o.optString("b", "")
        )
    }
}

data class Episode(
    val episodeId: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val containerExtension: String,
    val plot: String
)

/**
 * One row in a grid. Live channels, movies and series all render the same way,
 * so the browse screen only ever deals with these.
 */
data class Tile(
    val id: String,
    val title: String,
    val subtitle: String,
    val image: String,
    val kind: Kind,
    val added: Long
) {
    /** Worked out once, so searching a big list costs no allocation per keystroke. */
    val searchable: String = title.lowercase()

    /**
     * The same title with spaces and punctuation stripped out, so "bluejays"
     * finds "Blue Jays" and "tsn1" finds "TSN 1". Typing a channel name the way
     * you say it should not depend on where the portal put its spaces.
     */
    val condensed: String = searchable.filter { it.isLetterOrDigit() }
}


/**
 * What a portal can tell you about a film before you commit to watching it.
 * Every field is optional, because every portal fills in a different subset.
 */
data class MovieInfo(
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val genre: String = "",
    val releaseDate: String = "",
    val rating: String = "",
    val duration: String = "",
    val cover: String = "",
    val backdrop: String = "",
    val containerExtension: String = "mp4"
)

/**
 * Puts a portal's categories into the order set in [Config], leaving everything
 * it says nothing about exactly where the portal had it.
 */
fun List<Category>.inPreferredOrder(): List<Category> {
    fun rank(name: String): Int {
        val upper = name.uppercase().trim()
        // Adult is settled first, so a category called "ADULT PPV" goes to the
        // bottom rather than being lifted up with the events.
        if (Config.CATEGORY_LAST.any { upper.contains(it.uppercase()) }) return LAST_RANK
        // An exact name beats a partial one, so plain "ENGLISH" sits above
        // "ENGLISH LOCALS (USA)" rather than wherever the portal had it.
        Config.CATEGORY_FIRST.forEachIndexed { at, wanted ->
            if (upper == wanted.uppercase()) return at * 2
        }
        Config.CATEGORY_FIRST.forEachIndexed { at, wanted ->
            if (upper.contains(wanted.uppercase())) return at * 2 + 1
        }
        if (Config.CATEGORY_EVENTS.any { upper.contains(it.uppercase()) }) return EVENTS_RANK
        return MIDDLE_RANK
    }
    // sortedBy is stable, so equal ranks keep the order they arrived in.
    return sortedBy { rank(it.name) }
}

/**
 * The same order applied to channels rather than categories, for the "All" list.
 *
 * A portal's own numbering usually opens on its pay-per-view and event blocks;
 * this puts the categories people actually watch first and leaves the order
 * inside each category exactly as the portal had it.
 */
fun List<StreamItem>.inPreferredChannelOrder(categories: List<Category>): List<StreamItem> {
    if (isEmpty() || categories.isEmpty()) return this
    val rankOf = HashMap<String, Int>(categories.size)
    categories.inPreferredOrder().forEachIndexed { at, category -> rankOf[category.id] = at }
    val unknown = categories.size + 1
    return sortedBy { rankOf[it.categoryId] ?: unknown }
}

/** Just under the pinned names, and clear of the portal's own ordering. */
private const val EVENTS_RANK = 100
private const val MIDDLE_RANK = 1_000
private const val LAST_RANK = 9_000

object Json {
    fun arrayOf(items: List<JSONObject>): JSONArray {
        val a = JSONArray()
        for (o in items) a.put(o)
        return a
    }

    fun parse(text: String): JSONArray = try {
        JSONArray(text)
    } catch (e: Exception) {
        JSONArray()
    }
}

/** One programme in the guide. Times are epoch milliseconds. */
data class Programme(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
    val nowPlaying: Boolean
)
