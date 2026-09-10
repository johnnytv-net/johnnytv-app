package com.johnnytv.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * BORROWS A LOGO FROM THE OTHER SERVICE.
 *
 * Two services rarely have artwork for the same channels: one has a logo for
 * TSN 1 and nothing for Sportsnet, the other the reverse. Every time the app
 * syncs, it quietly notes the logo each channel name came with. When a channel
 * turns up later with none - on the other service, or after the portal drops
 * one - the remembered logo is used instead.
 *
 * So signing in to both accounts once fills the gaps in each of them, with no
 * list to maintain. Names are matched the same loose way the logo pack matches
 * them, so "SP - SPORTSNET PACIFIC HD" and "CA: Sportsnet Pacific" count as the
 * same channel.
 */
object IconMemory {

    private const val FILE = "icon_memory.json"
    // Room for two full portals - a cap that filled up on the first service
    // would leave nothing for the second, which is the whole point.
    private const val LIMIT = 25_000

    private var known: MutableMap<String, String> =
        java.util.concurrent.ConcurrentHashMap()
    @Volatile private var loaded = false

    /** Name to match-key, so the same channel is not re-parsed on every redraw. */
    private val keyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Reads the file. Blocking - call from a background thread. */
    fun load(context: Context) {
        if (loaded) return
        known = read(context)
        loaded = true
    }

    /**
     * Notes the artwork this sync brought in. Blocking - call from the sync
     * thread, never the main one.
     */
    fun remember(context: Context, items: List<StreamItem>) {
        load(context)
        val map = known
        var changed = false
        for (item in items) {
            val icon = item.icon.trim()
            if (icon.isBlank()) continue
            val key = key(item.name)
            if (key.isBlank()) continue
            // First one wins, so a service that syncs later cannot overwrite
            // artwork that is already working.
            if (map.containsKey(key)) continue
            if (map.size >= LIMIT) continue
            map[key] = icon
            changed = true
        }
        if (changed) write(context, map)
    }

    /**
     * The artwork to draw for a channel, all sources considered: your logo pack
     * first, then whatever this service supplied, then a logo remembered from
     * the other one. A channel the pack deliberately blanks (an "@logo" entry)
     * is left blank, so it keeps the JohnnyTV mark rather than borrowing the
     * poor artwork that was the reason for blanking it.
     */
    fun artFor(streamId: String, name: String, portalIcon: String): String {
        val override = LogoPack.override(streamId, name)
        if (override == LogoPack.OWN_LOGO) return ""   // blanked on purpose
        if (override != null) return override
        if (portalIcon.isNotBlank()) return portalIcon
        return iconFor(name)
    }

    /** A remembered logo for this channel name, or blank. */
    fun iconFor(name: String): String {
        if (known.isEmpty()) return ""
        val key = key(name)
        if (key.isBlank()) return ""
        return known[key].orEmpty()
    }

    private fun key(name: String): String =
        keyCache[name] ?: LogoPack.matchKey(name).also {
            if (keyCache.size < LIMIT) keyCache[name] = it
        }

    /**
     * Wipes it. Deliberately NOT called on sign out - the whole point is that
     * artwork learned from one account is still there for the next one.
     */
    fun clear(context: Context) {
        known = java.util.concurrent.ConcurrentHashMap()
        keyCache.clear()
        loaded = true
        runCatching { File(context.filesDir, FILE).delete() }
    }

    // ---------- disk ----------

    private fun read(context: Context): MutableMap<String, String> {
        val out = java.util.concurrent.ConcurrentHashMap<String, String>()
        try {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return out
            val json = JSONObject(file.readText())
            for (key in json.keys()) {
                val value = json.optString(key, "").trim()
                if (value.isNotBlank()) out[key] = value
            }
        } catch (e: Exception) {
            return java.util.concurrent.ConcurrentHashMap()
        }
        return out
    }

    private fun write(context: Context, map: Map<String, String>) {
        try {
            val json = JSONObject()
            for ((key, value) in map) json.put(key, value)
            File(context.filesDir, FILE).writeText(json.toString())
        } catch (e: Exception) {
            // Not being able to remember artwork is never worth failing a sync over.
        }
    }
}
