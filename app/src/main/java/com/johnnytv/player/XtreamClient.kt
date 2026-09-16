package com.johnnytv.player

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

open class XtreamException(message: String) : Exception(message)

/**
 * The portal answered and did not recognise this login. With more than one
 * service configured that is not a failure - it just means try the next one.
 */
class WrongCredentials(message: String) : XtreamException(message)

/**
 * The portal recognised the login but the line is not active. That identifies
 * the right service, so there is no point trying any others.
 */
class AccountInactive(message: String) : XtreamException(message)

/**
 * Client for the Xtream Codes player API (player_api.php).
 * Every call blocks - run them off the main thread.
 */
class XtreamClient(
    rawServer: String,
    private val username: String,
    private val password: String
) {

    val server: String = normalizeServer(rawServer)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ---------- account ----------

    /** Validates the credentials. Returns a short status line, or throws. */
    fun login(): String {
        val root = try {
            JSONObject(get("$server/player_api.php?${creds()}").trim())
        } catch (e: XtreamException) {
            throw e
        } catch (e: Exception) {
            throw XtreamException("That address did not answer like a portal.")
        }
        val info = root.optJSONObject("user_info")
            ?: throw XtreamException("The server did not return account info.")

        if ((info.opt("auth")?.toString() ?: "0") != "1") {
            throw WrongCredentials("Wrong username or password.")
        }
        val status = info.optString("status", "")
        if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) {
            throw AccountInactive("Account is not active (status: $status).")
        }

        val expiry = info.optString("exp_date", "")
        return if (expiry.isNotBlank() && expiry != "null") {
            "Active until ${formatDate(expiry)}"
        } else {
            "Active"
        }
    }

    /**
     * When this line runs out, in milliseconds, or 0 where the portal reports no
     * expiry at all. Never throws - a reminder that cannot be worked out is simply
     * not shown.
     */
    fun accountExpiry(): Long = try {
        val info = JSONObject(get("$server/player_api.php?${creds()}").trim())
            .optJSONObject("user_info") ?: JSONObject()
        val raw = info.optString("exp_date", "").trim()
        if (raw.isBlank() || raw == "null") 0L else (raw.toLongOrNull() ?: 0L) * 1000L
    } catch (e: Exception) {
        0L
    }

    /** Extra account detail for the settings screen. Never throws. */
    fun accountSummary(): Map<String, String> = try {
        val info = JSONObject(get("$server/player_api.php?${creds()}").trim())
            .optJSONObject("user_info") ?: JSONObject()
        val out = LinkedHashMap<String, String>()
        out["Status"] = info.optString("status", "-")
        val exp = info.optString("exp_date", "")
        out["Expires"] = if (exp.isBlank() || exp == "null") "Never" else formatDate(exp)
        out["Connections"] = info.optString("max_connections", "-")
        out["Active now"] = info.optString("active_cons", "-")
        out
    } catch (e: Exception) {
        emptyMap()
    }

    // ---------- catalogue ----------

    fun liveCategories(): List<Category> = categories("get_live_categories")

    fun vodCategories(): List<Category> = categories("get_vod_categories")

    fun seriesCategories(): List<Category> = categories("get_series_categories")

    fun allLiveStreams(): List<StreamItem> = streams("get_live_streams", Kind.LIVE)

    fun allVodStreams(): List<StreamItem> = streams("get_vod_streams", Kind.VOD)

    fun allSeries(): List<SeriesItem> {
        val array = asArray(get("$server/player_api.php?${creds()}&action=get_series"))
        val out = ArrayList<SeriesItem>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.opt("series_id")?.toString() ?: continue
            out.add(
                SeriesItem(
                    seriesId = id,
                    name = o.optString("name", "").ifBlank { "Untitled" },
                    cover = o.optString("cover", ""),
                    plot = o.optString("plot", ""),
                    categoryId = o.opt("category_id")?.toString() ?: "",
                    added = o.optString("last_modified", "").toLongOrNull()
                        ?: o.optString("added", "").toLongOrNull() ?: 0L,
                    genre = o.optString("genre", "").take(40),
                    year = o.optString("releaseDate", "").take(4),
                    rating = o.opt("rating")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" } ?: "",
                    backdrop = firstBackdrop(o)
                )
            )
        }
        return out
    }

    /** Portals return backdrop_path as an array of URLs; take the first usable one. */
    private fun firstBackdrop(o: JSONObject): String {
        val array = o.optJSONArray("backdrop_path") ?: return ""
        for (i in 0 until array.length()) {
            val value = array.optString(i, "")
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /**
     * Everything the portal knows about one film.
     *
     * Xtream returns this as a loose bag of fields whose names and types vary by
     * panel - rating arrives as a string on one and a number on another, cast is
     * sometimes "cast" and sometimes "actors" - so every value is read
     * defensively and an absent one comes back blank rather than throwing.
     */
    fun movieInfo(vodId: String): MovieInfo {
        val body = get("$server/player_api.php?${creds()}&action=get_vod_info&vod_id=${enc(vodId)}")
        val root = try {
            JSONObject(body.trim())
        } catch (e: Exception) {
            throw XtreamException("Could not read this film.")
        }
        val info = root.optJSONObject("info") ?: JSONObject()
        val movie = root.optJSONObject("movie_data") ?: JSONObject()

        fun text(vararg keys: String): String {
            for (key in keys) {
                val value = info.opt(key) ?: movie.opt(key) ?: continue
                val asText = when (value) {
                    is org.json.JSONArray ->
                        (0 until value.length()).joinToString(", ") { value.optString(it, "") }
                    else -> value.toString()
                }
                val cleaned = asText.trim()
                if (cleaned.isNotBlank() && cleaned != "null" && cleaned != "0") return cleaned
            }
            return ""
        }

        val cover = text("movie_image", "cover_big", "cover")
        return MovieInfo(
            plot = text("plot", "description"),
            cast = text("cast", "actors"),
            director = text("director"),
            genre = text("genre"),
            releaseDate = text("releasedate", "release_date"),
            rating = text("rating"),
            duration = text("duration", "episode_run_time"),
            cover = cover,
            backdrop = run {
                val arr = info.optJSONArray("backdrop_path")
                if (arr != null && arr.length() > 0) arr.optString(0, "") else ""
            },
            containerExtension = text("container_extension").ifBlank { "mp4" }
        )
    }

    /** Seasons mapped to their episodes, in season order. */
    fun seriesEpisodes(seriesId: String): Map<Int, List<Episode>> {
        val body = get("$server/player_api.php?${creds()}&action=get_series_info&series_id=${enc(seriesId)}")
        val root = try {
            JSONObject(body.trim())
        } catch (e: Exception) {
            throw XtreamException("Could not read this series.")
        }
        val episodesObj = root.optJSONObject("episodes") ?: return emptyMap()
        val out = sortedMapOf<Int, List<Episode>>()
        val keys = episodesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val seasonNumber = key.toIntOrNull() ?: continue
            val arr = episodesObj.optJSONArray(key) ?: continue
            val list = ArrayList<Episode>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.opt("id")?.toString() ?: continue
                val info = e.optJSONObject("info")
                list.add(
                    Episode(
                        episodeId = id,
                        title = e.optString("title", "").ifBlank { "Episode ${i + 1}" },
                        season = seasonNumber,
                        episodeNum = e.opt("episode_num")?.toString()?.toIntOrNull() ?: (i + 1),
                        containerExtension = e.optString("container_extension", "").ifBlank { "mp4" },
                        plot = info?.optString("plot", "") ?: ""
                    )
                )
            }
            list.sortBy { it.episodeNum }
            out[seasonNumber] = list
        }
        return out
    }

    // ---------- guide ----------

    /** Today's schedule for one channel. Empty if the portal carries no guide for it. */
    fun epg(streamId: String): List<Programme> = parseEpg(
        get("$server/player_api.php?${creds()}&action=get_simple_data_table&stream_id=${enc(streamId)}")
    )

    /** Just what's on now and next - cheap enough to call when playback starts. */
    fun nowNext(streamId: String): List<Programme> = parseEpg(
        get("$server/player_api.php?${creds()}&action=get_short_epg&stream_id=${enc(streamId)}&limit=2")
    )

    private fun parseEpg(body: String): List<Programme> {
        val root = try {
            JSONObject(body.trim())
        } catch (e: Exception) {
            return emptyList()
        }
        val array = root.optJSONArray("epg_listings") ?: return emptyList()
        learnPortalOffset(array)
        val out = ArrayList<Programme>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp", "").trim().toLongOrNull()?.times(1000L)
                ?: portalTime(o.optString("start", ""))
            val end = o.optString("stop_timestamp", "").trim().toLongOrNull()?.times(1000L)
                ?: portalTime(o.optString("end", ""))
            if (start <= 0L) continue
            out.add(
                Programme(
                    title = maybeBase64(o.optString("title", "")).ifBlank { "Untitled" },
                    description = maybeBase64(o.optString("description", "")),
                    start = start,
                    end = end,
                    nowPlaying = o.opt("now_playing")?.toString() == "1"
                )
            )
        }
        out.sortBy { it.start }
        return out
    }

    /**
     * Portals base64-encode guide text, but not all of them do. Only decode when the
     * value actually looks like base64, otherwise a plain title turns into noise.
     */
    private fun maybeBase64(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val looksEncoded = trimmed.length % 4 == 0 &&
            trimmed.length >= 4 &&
            trimmed.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        if (!looksEncoded) return trimmed
        return try {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
            // if decoding produced control characters it wasn't really base64 text
            if (decoded.isEmpty() || decoded.any { it.code in 0..8 || it.code in 14..31 }) trimmed else decoded
        } catch (e: Exception) {
            trimmed
        }
    }

    /**
     * Reads one of the portal's written-out times, in the portal's own timezone.
     *
     * Portals report a programme twice: "2026-09-07 13:00:00" in whatever zone the
     * server keeps, and a unix timestamp in real UTC. The two disagree by the
     * server's offset - three hours, on this one. Channels that come back with a
     * timestamp are exact; the rest have only the text, and reading that as if it
     * were written in the viewer's own timezone is what slides those rows away
     * from the now-line while their neighbours sit right.
     *
     * So wherever a listing carries both, the gap between them is noted, and the
     * text-only ones are read through it. Until a portal has shown us one of each,
     * the old assumption - the viewer's timezone - is as good a guess as any.
     */
    private fun portalTime(raw: String): Long {
        val asUtc = parseEpgUtc(raw)
        if (asUtc <= 0L) return 0L
        val offset = portalOffsetMs ?: return parseEpgLocal(raw)
        return asUtc + offset
    }

    /** Notes the portal's offset from the first listing that reports both forms. */
    private fun learnPortalOffset(array: JSONArray) {
        if (portalOffsetMs != null) return
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val stamp = o.optString("start_timestamp", "").trim().toLongOrNull()?.times(1000L) ?: continue
            if (stamp <= 0L) continue
            val written = parseEpgUtc(o.optString("start", ""))
            if (written <= 0L) continue
            portalOffsetMs = stamp - written
            return
        }
    }

    private fun parseEpgUtc(raw: String): Long = try {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        format.parse(raw.trim())?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun parseEpgLocal(raw: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(raw.trim())?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ---------- playback ----------

    fun liveUrls(streamId: String): List<String> =
        Config.LIVE_CONTAINERS.map { "$server/live/$username/$password/$streamId.$it" }

    fun movieUrls(streamId: String, ext: String): List<String> =
        listOf("$server/movie/$username/$password/$streamId.$ext")

    fun episodeUrls(episodeId: String, ext: String): List<String> =
        listOf("$server/series/$username/$password/$episodeId.$ext")

    // ---------- internals ----------

    private fun creds(): String = "username=${enc(username)}&password=${enc(password)}"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Config.USER_AGENT)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw XtreamException("Server returned HTTP ${response.code}.")
                }
                return response.body?.string()
                    ?: throw XtreamException("The server sent an empty response.")
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: Exception) {
            throw XtreamException("Could not reach the server. ${e.message ?: ""}".trim())
        }
    }

    private fun categories(action: String): List<Category> {
        val array = asArray(get("$server/player_api.php?${creds()}&action=$action"))
        val out = ArrayList<Category>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.opt("category_id")?.toString() ?: continue
            out.add(Category(id, o.optString("category_name", "").ifBlank { "Category $id" }))
        }
        return out
    }

    private fun streams(action: String, kind: Kind): List<StreamItem> {
        val array = asArray(get("$server/player_api.php?${creds()}&action=$action"))
        val out = ArrayList<StreamItem>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.opt("stream_id")?.toString() ?: continue
            out.add(
                StreamItem(
                    streamId = id,
                    num = o.opt("num")?.toString() ?: (i + 1).toString(),
                    name = o.optString("name", "").ifBlank { "Untitled" },
                    icon = o.optString("stream_icon", ""),
                    containerExtension = o.optString("container_extension", "").ifBlank { "mp4" },
                    categoryId = o.opt("category_id")?.toString() ?: "",
                    added = o.optString("added", "").toLongOrNull() ?: 0L,
                    kind = kind
                )
            )
        }
        return out
    }

    private fun asArray(body: String): JSONArray {
        val trimmed = body.trim()
        return when {
            trimmed.startsWith("[") -> try {
                JSONArray(trimmed)
            } catch (e: Exception) {
                throw XtreamException("The server sent a list this app could not read.")
            }
            trimmed.startsWith("{") -> JSONArray()
            else -> throw XtreamException("Unexpected response from the server.")
        }
    }

    private fun formatDate(raw: String): String = try {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(raw.trim().toLong() * 1000L))
    } catch (e: Exception) {
        raw
    }

    companion object {

        /**
         * How far the portal's written-out guide times sit from real UTC, learned
         * from the first listing that reports both a text time and a timestamp.
         * One portal is signed in at a time, and it is cleared on sign out.
         */
        @Volatile
        private var portalOffsetMs: Long? = null

        fun forgetPortalOffset() {
            portalOffsetMs = null
        }

        fun normalizeServer(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return s
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
                s = "http://$s"
            }
            val query = s.indexOf('?')
            if (query > 0) s = s.substring(0, query)
            s = s.trimEnd('/')
            val lower = s.lowercase(Locale.US)
            for (tail in listOf("/player_api.php", "/panel_api.php", "/get.php", "/index.php", "/c")) {
                if (lower.endsWith(tail)) {
                    s = s.substring(0, s.length - tail.length)
                    break
                }
            }
            return s.trimEnd('/')
        }
    }
}
