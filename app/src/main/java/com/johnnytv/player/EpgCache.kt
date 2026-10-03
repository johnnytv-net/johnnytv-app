package com.johnnytv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Guide data, fetched one channel at a time and kept around.
 *
 * The grid only ever needs the rows you can see, so this fills in as you scroll
 * rather than pulling the portal's whole XMLTV file up front.
 *
 * Three things this is careful about, each of which used to put "No guide" on
 * a channel that had one:
 *
 *   - A request that FAILED is not the same as a channel with no guide. It
 *     used to be filed as an empty schedule and believed for three hours, and
 *     written to disk so that closing the app did not clear it either. Now a
 *     failure is tried once more, and if that fails too it is only remembered
 *     for under a minute.
 *   - A channel that honestly has nothing listed is asked again after twenty
 *     minutes, not three hours - portals fill their guide in as the day goes.
 *   - Listings belong to the service they came from. Channel numbers repeat
 *     from one portal to the next, so a box that changed line used to show the
 *     last line's programmes against this one's channels.
 */
object EpgCache {

    // Written from IO threads, read on the main thread during bind - must be concurrent.
    private val memory = ConcurrentHashMap<String, List<Programme>>()
    private val goodUntil = ConcurrentHashMap<String, Long>()

    /** The service the listings in memory came from. */
    @Volatile private var owner = ""

    private const val TTL_MS = 3L * 60L * 60L * 1000L
    private const val EMPTY_TTL_MS = 20L * 60L * 1000L
    private const val FAILED_TTL_MS = 45L * 1000L
    private const val RETRY_PAUSE_MS = 400L

    /**
     * How much of a channel's schedule is worth keeping. The portal sends
     * every day it has; the grid shows half a day. Keeping the lot made the
     * saved file many times the size it needed to be, and that file is read
     * every time the guide opens.
     */
    private const val KEEP_BEFORE_MS = 3L * 60L * 60L * 1000L
    private const val KEEP_AFTER_MS = 30L * 60L * 60L * 1000L

    private const val FILE = "epg_cache.json"

    fun cached(streamId: String): List<Programme>? {
        val until = goodUntil[streamId] ?: return null
        if (System.currentTimeMillis() > until) return null
        return memory[streamId]
    }

    /** Blocking. Call off the main thread. */
    fun fetch(client: XtreamClient, streamId: String): List<Programme> {
        claim(client.server)
        cached(streamId)?.let { return it }

        var failed = false
        var listings: List<Programme> = try {
            client.epg(streamId)
        } catch (e: Exception) {
            failed = true
            emptyList()
        }
        if (failed) {
            // One more go before giving up: a single dropped request is common
            // when a screenful of rows asks at once.
            try {
                Thread.sleep(RETRY_PAUSE_MS)
                listings = client.epg(streamId)
                failed = false
            } catch (e: Exception) {
                failed = true
            }
        }

        val now = System.currentTimeMillis()
        val kept = listings.filter { it.end > now - KEEP_BEFORE_MS && it.start < now + KEEP_AFTER_MS }
        memory[streamId] = kept
        goodUntil[streamId] = now + when {
            failed -> FAILED_TTL_MS
            kept.isEmpty() -> EMPTY_TTL_MS
            else -> TTL_MS
        }
        return kept
    }

    fun clear() {
        memory.clear()
        goodUntil.clear()
    }

    /** Everything held belongs to one service; a different one starts clean. */
    private fun claim(server: String) {
        val tag = server.trim().trimEnd('/').lowercase()
        if (tag == owner) return
        synchronized(this) {
            if (tag == owner) return
            memory.clear()
            goodUntil.clear()
            owner = tag
        }
    }

    // ---------- disk ----------

    fun load(context: Context, server: String) {
        claim(server)
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            // Saved by another service, or before services were told apart.
            if (root.optString("service", "") != owner) return
            val savedAt = root.optLong("saved", 0L)
            val now = System.currentTimeMillis()
            if (now - savedAt > TTL_MS) return
            val channels = root.optJSONObject("channels") ?: return
            val keys = channels.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                // What is already in memory is at least as fresh as the file.
                if (cached(key) != null) continue
                val array = channels.optJSONArray(key) ?: continue
                val list = ArrayList<Programme>(array.length())
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    list.add(
                        Programme(
                            title = o.optString("t", ""),
                            description = o.optString("d", ""),
                            start = o.optLong("s", 0L),
                            end = o.optLong("e", 0L),
                            nowPlaying = false
                        )
                    )
                }
                if (list.isEmpty()) continue
                memory[key] = list
                goodUntil[key] = savedAt + TTL_MS
            }
        }
    }

    /** Call off the main thread - this writes a file. */
    fun save(context: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            val channels = JSONObject()
            for ((id, list) in memory) {
                // Only real schedules are worth keeping. An empty one is either
                // a failure or a channel worth asking about again.
                if (list.isEmpty()) continue
                if ((goodUntil[id] ?: 0L) < now) continue
                val array = JSONArray()
                for (p in list) {
                    array.put(
                        JSONObject()
                            .put("t", p.title)
                            .put("d", p.description)
                            .put("s", p.start)
                            .put("e", p.end)
                    )
                }
                channels.put(id, array)
            }
            val root = JSONObject()
                .put("saved", now)
                .put("service", owner)
                .put("channels", channels)
            File(context.filesDir, FILE).writeText(root.toString())
        }
    }
}
