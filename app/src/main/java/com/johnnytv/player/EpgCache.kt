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
 */
object EpgCache {

    // Written from IO threads, read on the main thread during bind - must be concurrent.
    private val memory = ConcurrentHashMap<String, List<Programme>>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()

    private const val TTL_MS = 3L * 60L * 60L * 1000L
    private const val FILE = "epg_cache.json"

    fun cached(streamId: String): List<Programme>? {
        val age = System.currentTimeMillis() - (fetchedAt[streamId] ?: 0L)
        if (age > TTL_MS) return null
        return memory[streamId]
    }

    /** Blocking. Call off the main thread. */
    fun fetch(client: XtreamClient, streamId: String): List<Programme> {
        cached(streamId)?.let { return it }
        val listings = runCatching { client.epg(streamId) }.getOrDefault(emptyList())
        memory[streamId] = listings
        fetchedAt[streamId] = System.currentTimeMillis()
        return listings
    }

    fun clear() {
        memory.clear()
        fetchedAt.clear()
    }

    // ---------- disk ----------

    fun load(context: Context) {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val savedAt = root.optLong("saved", 0L)
            if (System.currentTimeMillis() - savedAt > TTL_MS) return
            val channels = root.optJSONObject("channels") ?: return
            val keys = channels.keys()
            while (keys.hasNext()) {
                val key = keys.next()
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
                memory[key] = list
                fetchedAt[key] = savedAt
            }
        }
    }

    /** Call off the main thread - this writes a file. */
    fun save(context: Context) {
        runCatching {
            val channels = JSONObject()
            for ((id, list) in memory) {
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
                .put("saved", System.currentTimeMillis())
                .put("channels", channels)
            File(context.filesDir, FILE).writeText(root.toString())
        }
    }
}
