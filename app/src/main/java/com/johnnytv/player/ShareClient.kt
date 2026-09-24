package com.johnnytv.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * FINDING THE SHIELD FROM ANOTHER ROOM.
 *
 * Every television running the app with sharing switched on announces itself on
 * the home network. This listens for those announcements, asks each one what it
 * has recorded, and hands back something the Recordings screen can draw.
 *
 * Blocking, so it is always called off the main thread, and bounded: a house
 * with no Shield on, or a network that does not pass the announcements, costs
 * a few seconds and returns nothing, rather than a spinner that never ends.
 */
object ShareClient {

    data class Box(val name: String, val host: String, val port: Int) {
        fun base(): String = "http://$host:$port"
    }

    data class Remote(
        val box: Box,
        val id: String,
        val title: String,
        val channel: String,
        val at: Long,
        val minutes: Int
    ) {
        fun playlistUrl(): String =
            box.base() + "/r/" + java.net.URLEncoder.encode(id, "UTF-8") + "/" + RecorderService.PLAYLIST_NAME
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** How long to listen for other televisions before giving up. */
    private const val LISTEN_MS = 3_500L

    /** Every sharing television on the network, other than this one. */
    fun findBoxes(context: Context): List<Box> {
        val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = java.util.Collections.synchronizedList(ArrayList<Box>())
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        val ownName = "JohnnyTV " + (Build.MODEL ?: "box")

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                // Our own announcement, if this box is sharing too, is of no
                // interest - its recordings are already on its own list.
                val sharing = Prefs(context).shareRecordings
                if (sharing && info.serviceName == ownName) return
                if (!seen.add(info.serviceName)) return
                @Suppress("DEPRECATION")
                runCatching {
                    manager.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            found.add(Box(resolved.serviceName.removePrefix("JohnnyTV ").trim(), host, resolved.port))
                        }
                    })
                }
            }
        }

        runCatching { manager.discoverServices(ShareService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        runCatching { CountDownLatch(1).await(LISTEN_MS, TimeUnit.MILLISECONDS) }
        runCatching { manager.stopServiceDiscovery(listener) }
        return ArrayList(found)
    }

    /** What one box has recorded. Empty if it cannot be reached. */
    fun recordingsOn(box: Box): List<Remote> {
        return try {
            val request = Request.Builder().url(box.base() + "/list").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = JSONObject(response.body?.string().orEmpty())
                val array = json.optJSONArray("recordings") ?: return emptyList()
                val named = box.copy(name = json.optString("device", box.name))
                val out = ArrayList<Remote>(array.length())
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    out.add(
                        Remote(
                            box = named,
                            id = o.optString("id", ""),
                            title = o.optString("title", "Recording"),
                            channel = o.optString("channel", ""),
                            at = o.optLong("at", 0L),
                            minutes = o.optInt("minutes", 0)
                        )
                    )
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Everything recorded on every other television in the house. */
    /**
     * The box whose address somebody typed in, if there is one. Tried first:
     * away from home it is the only one that can answer, and at home it
     * answers sooner than waiting to hear an announcement.
     */
    fun writtenDownBox(context: Context): Box? {
        val typed = Prefs(context).awayBox.trim()
        if (typed.isBlank()) return null
        val cleaned = typed.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val host = cleaned.substringBefore(':')
        val port = cleaned.substringAfter(':', "8765").toIntOrNull() ?: 8765
        if (host.isBlank()) return null
        return Box(name = "Home", host = host, port = port)
    }

    fun everything(context: Context): List<Remote> {
        val boxes = ArrayList<Box>()
        writtenDownBox(context)?.let { boxes.add(it) }
        for (box in findBoxes(context)) {
            if (boxes.none { it.host == box.host }) boxes.add(box)
        }
        return boxes.flatMap { recordingsOn(it) }.sortedByDescending { it.at }
    }
}
