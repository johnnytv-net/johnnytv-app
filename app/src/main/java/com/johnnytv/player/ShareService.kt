package com.johnnytv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * SHARING RECORDINGS WITH THE OTHER TELEVISIONS IN THE HOUSE.
 *
 * The Shield has the drive and the recordings. The Google TV upstairs has
 * neither. Rather than copy anything anywhere, the Shield simply offers what it
 * has to the rest of the home network, and the app on any other television
 * plays it straight off the Shield's drive.
 *
 * It is the smallest server that does the job: a list of recordings, and the
 * files that make each one up. Nothing leaves the house, nothing is exposed to
 * the internet - the router keeps outsiders out exactly as it always has - and
 * there is no account, no password and nothing to set up beyond switching it
 * on. The other television finds the Shield by itself: the Shield announces
 * itself on the network the way printers and speakers do, so nobody ever types
 * an address.
 *
 * It runs as a service so it is still there while the Shield sits on the home
 * screen of something else entirely. It is switched on and off in Settings, and
 * off by default - a box with nothing to share has no business listening.
 */
class ShareService : Service() {

    private var socket: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        startForeground(notification())
        holdWifi()
        thread(name = "johnnytv-share") { serve() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { socket?.close() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        super.onDestroy()
    }

    private fun serve() {
        val server = runCatching { ServerSocket(PORT) }.getOrElse {
            // The port is taken - most likely by this same service in a
            // previous life. Any free port will do; the other television finds
            // it through the announcement rather than by number.
            runCatching { ServerSocket(0) }.getOrNull()
        } ?: return
        socket = server
        announce(server.localPort)

        while (running) {
            val client = runCatching { server.accept() }.getOrNull() ?: continue
            thread(name = "johnnytv-share-client") {
                runCatching { answer(client) }
                runCatching { client.close() }
            }
        }
    }

    // ---------- being found ----------

    /**
     * Telling the house the Shield is here.
     *
     * The same mechanism a network printer uses: a named service on the local
     * network that anything else can look for. The name carries the device's
     * own name, so a house with two boxes sharing can tell which is which.
     */
    private fun announce(port: Int) {
        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val info = NsdServiceInfo().apply {
            serviceName = "JohnnyTV " + (Build.MODEL ?: "box")
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    // ---------- answering ----------

    private fun answer(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val head = readHead(input) ?: return
        val requestLine = head.firstOrNull() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
            reply(client.getOutputStream(), 405, "text/plain", "no".toByteArray())
            return
        }
        val headOnly = parts[0] == "HEAD"
        val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
        val range = head.firstOrNull { it.lowercase().startsWith("range:") }
            ?.substringAfter(':')?.trim()

        val out = client.getOutputStream()
        when {
            path == "/list" -> reply(out, 200, "application/json", listing().toByteArray(), headOnly)
            path.startsWith("/r/") -> sendFile(out, path.removePrefix("/r/"), range, headOnly)
            else -> reply(out, 404, "text/plain", "not here".toByteArray(), headOnly)
        }
    }

    /**
     * The recordings worth offering: finished ones, newest first. A recording
     * still being written is left out - the other television would reach the
     * end of what exists so far and stop, which looks broken rather than live.
     */
    private fun listing(): String {
        val array = JSONArray()
        for (recording in RecordingStore.all(this)) {
            if (recording.isRecording) continue
            if (recording.playlistFile(this) == null && recording.files(this).isEmpty()) continue
            array.put(
                JSONObject()
                    .put("id", recording.id)
                    .put("title", recording.title)
                    .put("channel", recording.channel)
                    .put("at", recording.startedAt)
                    .put("minutes", (recording.lengthMs() / 60_000L).toInt())
            )
        }
        return JSONObject().put("device", Build.MODEL ?: "box").put("recordings", array).toString()
    }

    /**
     * One file from one recording's folder, and nothing else.
     *
     * The request names a recording and a file within it; anything that is not
     * a recording's own playlist or one of its parts is refused, so no amount of
     * cleverness in the address can walk out of the recordings folder.
     */
    private fun sendFile(out: OutputStream, rest: String, range: String?, headOnly: Boolean) {
        val id = rest.substringBefore('/')
        val name = rest.substringAfter('/', "")
        val allowed = name == RecorderService.PLAYLIST_NAME || name.matches(Regex("part\\d{3,4}\\.ts"))
        if (id.isBlank() || !allowed || id.contains("..")) {
            reply(out, 404, "text/plain", "not here".toByteArray(), headOnly)
            return
        }
        val recording = RecordingStore.find(this, id)
        if (recording == null || recording.isRecording) {
            reply(out, 404, "text/plain", "not here".toByteArray(), headOnly)
            return
        }

        // Asking for the playlist also puts it right first, exactly as playing
        // it on the Shield itself would.
        val folder = if (name == RecorderService.PLAYLIST_NAME) {
            recording.playlistFile(this)?.parentFile ?: recording.folderOnDisk(this)
        } else {
            recording.folderOnDisk(this)
        }
        val file = File(folder, name)
        if (!file.isFile) {
            reply(out, 404, "text/plain", "not here".toByteArray(), headOnly)
            return
        }

        val type = if (name.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "video/mp2t"
        val size = file.length()
        var from = 0L
        var to = size - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-').toLongOrNull()
            val b = spec.substringAfter('-', "").toLongOrNull()
            if (a != null) { from = a; if (b != null) to = minOf(b, size - 1) }
            else if (b != null) { from = maxOf(0L, size - b) }
            partial = from in 0..to
        }
        val length = to - from + 1

        val headers = StringBuilder()
        headers.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        headers.append("Content-Type: ").append(type).append("\r\n")
        headers.append("Content-Length: ").append(length).append("\r\n")
        headers.append("Accept-Ranges: bytes\r\n")
        if (partial) headers.append("Content-Range: bytes ").append(from).append('-').append(to)
            .append('/').append(size).append("\r\n")
        headers.append("Cache-Control: no-cache\r\n")
        headers.append("Connection: close\r\n\r\n")
        out.write(headers.toString().toByteArray())
        if (headOnly) { out.flush(); return }

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(from)
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read <= 0) break
                out.write(buffer, 0, read)
                left -= read
            }
        }
        out.flush()
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray, headOnly: Boolean = false) {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray()
        )
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun readHead(input: BufferedInputStream): List<String>? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (total < 16_384) {
            val c = input.read()
            if (c < 0) return if (lines.isEmpty()) null else lines
            total++
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) return lines
                lines.add(text)
                line.setLength(0)
            } else {
                line.append(c.toChar())
            }
        }
        return lines
    }

    // ---------- staying reachable ----------

    /** Keeps Wi-Fi from dozing while the Shield is offering its recordings. */
    private fun holdWifi() {
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "johnnytv:share")
                .also { it.setReferenceCounted(false); it.acquire() }
        }
    }

    private fun startForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sharing recordings", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("Sharing recordings")
            .setContentText("Other TVs in the house can watch them")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        /** The name other televisions look for. */
        const val SERVICE_TYPE = "_johnnytv._tcp."
        private const val PORT = 8765
        private const val NOTIFICATION_ID = 4712
        private const val CHANNEL_ID = "johnnytv-share"

        /** Starts or stops sharing to match the setting. */
        fun apply(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, ShareService::class.java)
            if (Prefs(app).shareRecordings) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent)
                    else app.startService(intent)
                }
            } else {
                runCatching { app.stopService(intent) }
            }
        }
    }
}
