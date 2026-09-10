package com.johnnytv.player

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * YOUR OWN CHANNEL LOGOS.
 *
 * Portals supply whatever logo they happen to have, and some of them are small,
 * squashed or plain wrong. This lets you replace any of them from a file you
 * host - no rebuild, no reinstall. Everyone's app picks the change up the next
 * time it opens.
 *
 * The file is logos.json, sitting next to config.json:
 *
 *   {
 *     "TSN 1": "https://example.com/logos/tsn1.png",
 *     "Sportsnet Pacific": "https://example.com/logos/sn-pacific.png",
 *     "#41827": "https://example.com/logos/one-off.png"
 *   }
 *
 * Names are matched loosely, so ONE entry covers every copy of that channel:
 * "TSN 1" matches "TSN 1", "CA: TSN 1 HD", "tsn 1 fhd" and so on. Case,
 * spacing, punctuation, a short country prefix and the quality word are all
 * ignored. A key starting with # is a stream id instead, for the rare channel
 * whose name is too odd to match.
 *
 * You can also put the same block inside config.json as "logos": { ... }, or
 * point somewhere else entirely with "logos_url".
 */
object LogoPack {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        // Hard ceiling: logos are decoration and must never hold up the home screen.
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Names written exactly as the pack spells them. Always win. */
    @Volatile private var byExactName: Map<String, String> = emptyMap()
    /** The same names with a country prefix taken off. Only used if nothing exact matched. */
    @Volatile private var byLooseName: Map<String, String> = emptyMap()
    @Volatile private var byId: Map<String, String> = emptyMap()
    /** Patterns ending in *, longest first, tried only when nothing else matched. */
    @Volatile private var wildcards: List<Pair<String, String>> = emptyList()

    /**
     * A category or country tag on the front of a channel name.
     *
     * Portals label their lists in their own way - "CA: TSN 1", "USA| TSN 1",
     * "[CA] TSN 1", "SP - SPORTSNET PACIFIC HD" - and some stack two of them.
     * A tag is a short run of letters or digits followed by a bracket, colon,
     * bar, or a spaced hyphen, so a real name like "TSN 1: The Ocho" is left
     * alone.
     */
    private val PREFIX = Regex(
        "^\\s*(?:[\\[(|]\\s*[A-Z0-9]{1,4}\\s*[\\])|]|[A-Z0-9]{2,4}\\s*[:|]|[A-Z0-9]{2,4}\\s+-)\\s*"
    )
    private val SEPARATORS = Regex("[^A-Z0-9]+")

    /**
     * A broadcaster's call letters in brackets, as American portals write them:
     * "USA - ABC 8 DALLAS TX (WFAA)". One entry named WFAA then covers that
     * station whatever the rest of the name says.
     */
    private val CALLSIGN = Regex("\\(([KW][A-Z]{2,3})(?:-TV)?\\)")

    /**
     * Write "@logo" instead of a web address and the channel gets the JohnnyTV
     * mark the app already carries - drawn at full quality, never stretched.
     * For the channels no logo library in the world has artwork for.
     */
    const val OWN_LOGO = "@logo"

    /** Answers already worked out, so a 10,000 channel grid costs one lookup each. */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val MEMO_LIMIT = 20_000
    private const val NO_OVERRIDE = "\u0000"

    /** Some portals stack a country tag and a category tag on the same name. */
    private const val MAX_PREFIXES = 2

    /** Quality and format words that are never part of the channel's real name. */
    private val NOISE = setOf(
        "HD", "FHD", "UHD", "SD", "4K", "8K", "HEVC", "H265", "H264",
        "1080P", "720P", "480P", "RAW", "BACKUP"
    )

    /** Reads the copy already on the device. Cheap - safe to call at startup. */
    fun load(context: Context) {
        apply(Prefs(context).logoPack)
    }

    /**
     * Blocking - call from a background thread.
     *
     * Anything already on the device is kept if the download fails, so a customer
     * with no signal still sees the logos they had yesterday.
     */
    fun refresh(context: Context, config: RemoteConfig?) {
        val url = when {
            config != null && config.logosUrl.isNotBlank() -> config.logosUrl
            else -> siblingOfConfig()
        }

        val merged = LinkedHashMap<String, String>()
        var downloaded = true
        if (url.isNotBlank()) {
            val fetched = download(url)
            if (fetched == null) downloaded = false else merged.putAll(fetched)
        }
        // Entries written straight into config.json win over the separate file.
        if (config != null) merged.putAll(config.logos)

        // Nothing arrived and nothing was configured - leave what we already have.
        if (merged.isEmpty() && !downloaded) return

        val out = JSONObject()
        for ((key, value) in merged) out.put(key, value)
        val text = out.toString()
        Prefs(context).logoPack = text
        apply(text)
    }

    /**
     * The logo to draw for a channel: yours if you named it, otherwise the
     * portal's, otherwise blank so the placeholder shows.
     */
    fun iconFor(streamId: String, name: String, fallback: String): String =
        when (val found = override(streamId, name)) {
            null -> fallback
            OWN_LOGO -> ""          // blank tells the tile to draw our own logo
            else -> found
        }

    /**
     * What the pack says about this channel: a web address, [OWN_LOGO] to draw
     * our own mark on purpose, or null for "no opinion". The difference matters
     * to [IconMemory], which must not fill in a blank that was deliberate.
     */
    fun override(streamId: String, name: String): String? {
        if (byId.isEmpty() && byExactName.isEmpty() &&
            byLooseName.isEmpty() && wildcards.isEmpty()) return null

        byId[streamId.trim()]?.let { return it }

        val found = memo[name] ?: resolve(name).also {
            if (memo.size < MEMO_LIMIT) memo[name] = it
        }
        return if (found == NO_OVERRIDE) null else found
    }

    /** The whole search for one channel name. Called once per name, then remembered. */
    private fun resolve(name: String): String {
        val upper = name.trim().uppercase(Locale.ROOT)
        val keys = keysFor(upper)

        for (key in keys) byExactName[key]?.let { return it }
        for (key in keys) byLooseName[key]?.let { return it }

        // Call letters beat a pattern: they name one station exactly.
        CALLSIGN.find(upper)?.groupValues?.getOrNull(1)?.let { sign ->
            byExactName[sign]?.let { return it }
            byLooseName[sign]?.let { return it }
        }

        // Last resort: "NFL |*" style patterns, so a hundred event channels
        // sharing a name can share one logo. Every form of the name is tried,
        // or a pattern would never match a channel carrying a country tag.
        for ((pattern, url) in wildcards) {
            for (key in keys) if (key.startsWith(pattern)) return url
        }
        return NO_OVERRIDE
    }

    // ---------- matching ----------

    /**
     * The single form a channel name is filed under elsewhere in the app - the
     * whole name with tags, case, spacing, punctuation and the quality word
     * taken out. Shared with [IconMemory] so both agree on what "the same
     * channel" means.
     */
    fun matchKey(name: String): String =
        keysFor(name.trim().uppercase(Locale.ROOT)).lastOrNull().orEmpty()

    /**
     * The forms a name may be looked up under, most exact first: the whole name,
     * then the same name with one tag taken off the front, then with two.
     */
    private fun keysFor(upper: String): List<String> {
        val forms = ArrayList<String>(3)
        var text = upper
        var rounds = 0
        while (true) {
            val key = condense(text)
            if (key.isNotBlank() && !forms.contains(key)) forms.add(key)
            if (rounds >= MAX_PREFIXES) break
            val stripped = text.replaceFirst(PREFIX, "")
            if (stripped == text || stripped.isBlank()) break
            text = stripped
            rounds++
        }
        return forms
    }

    /** Case, spacing, punctuation and the quality word all thrown away. */
    private fun condense(text: String): String = text.split(SEPARATORS)
        .filter { it.isNotBlank() && !NOISE.contains(it) }
        .joinToString("")

    // ---------- loading ----------

    private fun apply(raw: String) {
        if (raw.isBlank()) {
            byExactName = emptyMap()
            byLooseName = emptyMap()
            byId = emptyMap()
            wildcards = emptyList()
            memo.clear()
            return
        }
        val exact = HashMap<String, String>()
        val loose = HashMap<String, String>()
        val ids = HashMap<String, String>()
        val patterns = ArrayList<Pair<String, String>>()
        try {
            val json = JSONObject(raw)
            for (key in json.keys()) {
                val url = json.optString(key, "").trim()
                val label = key.trim()
                if (url.isBlank() || label.isBlank()) continue
                if (label.startsWith("#")) {
                    ids[label.removePrefix("#").trim()] = url
                } else if (label.endsWith("*")) {
                    val pattern = keysFor(label.dropLast(1).uppercase(Locale.ROOT)).firstOrNull().orEmpty()
                    if (pattern.isNotBlank()) patterns.add(pattern to url)
                } else {
                    // The name as written always wins; the prefix-stripped form is only
                    // a fallback, and never replaces an entry someone spelled out.
                    val forms = keysFor(label.uppercase(Locale.ROOT))
                    val full = forms.firstOrNull().orEmpty()
                    if (full.isNotBlank()) exact[full] = url
                    for (form in forms.drop(1)) {
                        if (form.isNotBlank() && !loose.containsKey(form)) loose[form] = url
                    }
                }
            }
        } catch (e: Exception) {
            return
        }
        byExactName = exact
        byLooseName = loose
        byId = ids
        // Longest pattern first, so "NFLNETWORK" beats a bare "NFL".
        wildcards = patterns.sortedByDescending { it.first.length }
        memo.clear()
    }

    /** logos.json in the same folder as config.json, so no extra setup is needed. */
    private fun siblingOfConfig(): String {
        val base = Config.CONFIG_URL.trim()
        if (!base.contains('/')) return ""
        return base.substringBeforeLast('/') + "/logos.json"
    }

    /** Null means the download itself failed, as opposed to an empty list. */
    private fun download(url: String): Map<String, String>? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Config.USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                val body = response.body?.string()?.trim()
                if (body.isNullOrEmpty()) null else parse(body)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Accepts either a bare { name: url } file or one wrapped in "logos". */
    private fun parse(body: String): Map<String, String>? = try {
        val root = JSONObject(body)
        val block = root.optJSONObject("logos") ?: root
        val out = LinkedHashMap<String, String>()
        for (key in block.keys()) {
            val url = block.optString(key, "").trim()
            if (url.isNotBlank()) out[key] = url
        }
        out
    } catch (e: Exception) {
        null
    }
}
