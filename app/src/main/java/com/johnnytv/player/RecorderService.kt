package com.johnnytv.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * THE RECORDER
 *
 * Every IPTV player can start a recording. The reason so many of them stop
 * halfway through a match is that they record *through the video player*: the
 * thing on screen is also the thing writing the file, so a stall, a codec
 * hiccup, a channel change or the app being pushed out of memory takes the
 * recording with it.
 *
 * This does not touch the player at all. It opens its own connection to the
 * portal's raw .ts feed and copies bytes to disk in its own service, in its own
 * thread, with nothing to decode and nothing to draw. Whatever the screen is
 * doing - buffering, showing a spinner, or switched off entirely - is none of
 * its business.
 *
 * What can still go wrong, and what is done about it:
 *
 *  - The feed stops sending. Nothing is reported; the socket simply goes quiet.
 *    So there is a read timeout: five seconds of silence is treated as a drop,
 *    the connection is thrown away and a new one opened, and the seconds lost
 *    are written into the recording's report. It keeps doing that until the
 *    programme's end time, however many times it takes.
 *
 *  - Android kills the app to free memory. A foreground service with a
 *    notification is the one thing the system leaves alone, which is what makes
 *    this survivable on a Firestick as well as a Shield.
 *
 *  - The box goes to sleep, or the television is switched off. A wake lock and
 *    a wifi lock hold the processor and the network up for the length of the
 *    recording and are let go the moment it ends.
 *
 *  - The drive is pulled out. Writing fails, so it moves to internal storage
 *    mid-recording and notes it, rather than ending there.
 */
class RecorderService : Service() {

    private var worker: Thread? = null
    @Volatile private var stopping = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_STICKY

        val recordingId = intent?.getStringExtra(EXTRA_ID) ?: return stopHere()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
        val endAt = intent.getLongExtra(EXTRA_END_AT, 0L)
        if (urls.isEmpty() || endAt <= System.currentTimeMillis()) return stopHere()

        startForeground(notification(title, channel))
        activeId = recordingId
        activeStreamId = streamId

        worker = thread(name = "johnnytv-recorder") {
            runCatching { record(recordingId, title, channel, streamId, urls, endAt) }
            activeId = ""
            activeStreamId = ""
            stopSelf()
        }
        return START_STICKY
    }

    private fun stopHere(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        releaseLocks()
        // A recording cut short by the system rather than by its end time is
        // still a recording - close the row off so it plays instead of sitting
        // there claiming to be in progress for ever.
        val id = activeId
        if (id.isNotBlank()) {
            RecordingStore.update(this, id) {
                if (it.isRecording) {
                    it.state = STATE_DONE
                    it.endedAt = System.currentTimeMillis()
                }
            }
            activeId = ""
            activeStreamId = ""
        }
        super.onDestroy()
    }

    // ---------- the loop ----------

    private fun record(
        id: String,
        title: String,
        channel: String,
        streamId: String,
        urls: List<String>,
        endAt: Long
    ) {
        holdLocks()

        val target = Storage.chosen(this)
        var folder = File(target?.dir ?: File(filesDir, Storage.FOLDER), id)
        folder.mkdirs()

        RecordingStore.update(this, id) {
            it.dirPath = folder.absolutePath
            it.startedAt = System.currentTimeMillis()
        }

        // Clear the way first: watched recordings nobody has marked Keep are
        // dropped, oldest first, until there is room for this one. A drive that
        // fills up halfway through is the other classic way a recording dies.
        val needed = ((endAt - System.currentTimeMillis()).toDouble() / (60L * 60L * 1000L) *
            StorageTarget.BYTES_PER_HOUR).toLong()
        runCatching { RecordingStore.freeUpSpace(this, needed, folder) }

        // Try the playlist first. A portal that closes the raw stream every few
        // seconds - which is what the reports have been full of - hands out a
        // perfectly well behaved HLS playlist instead, where every segment has a
        // number and is fetched exactly once. See recordFromPlaylist.
        val playlistUrl = urls.firstOrNull { it.endsWith(".m3u8") }
        if (playlistUrl != null && recordFromPlaylist(id, playlistUrl, endAt, folder)) {
            return
        }

        val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            // The watchdog. A portal that stalls says nothing at all, so silence
            // for this long is what counts as the feed having gone away.
            .readTimeout(SILENCE_IS_A_DROP_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        var urlIndex = 0
        var partIndex = 0
        var partStarted = 0L
        var out: FileOutputStream? = null
        /** Bytes of a half-finished packet, waiting for the rest of it. */
        var carry = EMPTY
        /** True until the run of 0x47s has been found after a (re)connection. */
        var needSync = true
        /** The chunk is due to roll over, as soon as a PAT comes past. */
        var rotateWhenReady = false
        /** This connection is new, so its first bytes belong in a new chunk. */
        var startFreshChunk = false
        /** Nothing is worth writing until the stream says where it begins. */
        var needPat = false
        /** The newest timestamp written to disk, in 90 kHz ticks. */
        var lastPts = -1L
        /** After a reconnect: drop what the portal resends until it moves past lastPts. */
        var trimming = false
        var bytesTotal = 0L
        var lastSave = 0L
        var awaySince = 0L
        var everWrote = false

        fun openNewPart() {
            runCatching { out?.flush(); out?.close() }
            val name = String.format("part%03d.ts", partIndex)
            partIndex++
            partStarted = System.currentTimeMillis()
            out = FileOutputStream(File(folder, name))
            RecordingStore.update(this, id) { if (!it.parts.contains(name)) it.parts.add(name) }
        }

        openNewPart()

        while (!stopping && System.currentTimeMillis() < endAt) {
            val url = urls[urlIndex % urls.size]
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Config.USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("empty")
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)

                    // Back on air. If we were away, that is a gap worth reporting -
                    // it is the difference between "it recorded" and "it recorded,
                    // and here is exactly what you missed".
                    if (awaySince > 0L) {
                        val lost = ((System.currentTimeMillis() - awaySince) / 1000L).toInt()
                        if (lost > 0) {
                            RecordingStore.update(this, id) {
                                it.gaps.add(Gap(awaySince, lost))
                                it.reconnects++
                            }
                        }
                        awaySince = 0L
                    }

                    /*
                     * EVERY CONNECTION GETS ITS OWN CHUNK.
                     *
                     * A reconnection is not a continuation. The portal picks up
                     * wherever it is now, usually replaying a few seconds it has
                     * already sent, and with its own timing rather than a
                     * continuation of the old one. Written into the middle of the
                     * same file, that is what makes a recording repeat itself,
                     * stick on one frame, and then run with the sound ahead of the
                     * picture: the player is trying to read two streams as one.
                     *
                     * Started as a new chunk instead, the player treats it as a new
                     * piece and resets its clock at the join - the seam becomes a
                     * momentary pause instead of a minute of drift.
                     */
                    needSync = true
                    if (everWrote) {
                        startFreshChunk = true
                        needPat = true
                        trimming = true
                    }

                    while (!stopping && System.currentTimeMillis() < endAt) {
                        val read = input.read(buffer)
                        if (read < 0) break          // the portal closed the connection
                        if (read == 0) continue

                        /*
                         * WHOLE PACKETS ONLY.
                         *
                         * A transport stream is a procession of 188-byte packets,
                         * each starting with 0x47. Writing whatever happened to
                         * arrive in the last read - which is what this used to do -
                         * chops the first and last packet of every write in half.
                         * The player then has to guess where the next frame starts:
                         * the sound skips, and the picture sits on one frame until
                         * the next keyframe turns up. That is the frozen photo with
                         * the audio carrying on.
                         *
                         * So: anything left over at the end of a read is held back
                         * and glued to the front of the next one, and only complete
                         * packets ever reach the drive.
                         */
                        val data = if (carry.isEmpty()) {
                            buffer.copyOf(read)
                        } else {
                            val joined = ByteArray(carry.size + read)
                            System.arraycopy(carry, 0, joined, 0, carry.size)
                            System.arraycopy(buffer, 0, joined, carry.size, read)
                            joined
                        }
                        carry = EMPTY

                        var start = 0
                        if (needSync) {
                            val at = findSync(data)
                            if (at < 0) {
                                // Not enough to be sure yet - keep the tail and
                                // wait for more rather than writing rubbish.
                                carry = data.copyOfRange(
                                    (data.size - SYNC_LOOKBACK).coerceAtLeast(0),
                                    data.size
                                )
                                continue
                            }
                            start = at
                            needSync = false
                        }

                        var whole = ((data.size - start) / TS_PACKET) * TS_PACKET
                        if (whole <= 0) {
                            carry = data.copyOfRange(start, data.size)
                            continue
                        }

                        // A new connection: throw away everything up to the first
                        // PAT, so the chunk starts where a player can start.
                        if (needPat) {
                            val pat = findPat(data, start, start + whole)
                            if (pat < 0) {
                                carry = data.copyOfRange(start + whole, data.size)
                                continue
                            }
                            start = pat
                            needPat = false
                            whole = ((data.size - start) / TS_PACKET) * TS_PACKET
                            if (whole <= 0) {
                                carry = data.copyOfRange(start, data.size)
                                continue
                            }
                        }

                        /*
                         * THE REWIND, CUT OUT.
                         *
                         * A portal does not resume where it left off. It reconnects
                         * you to its own buffer, which usually means several seconds
                         * you already have. Written down, those seconds play twice:
                         * the recording jumps backwards, the sound repeats, and from
                         * then on the picture and the audio are arguing about what
                         * time it is.
                         *
                         * Every packet carries a timestamp, so the recorder keeps the
                         * last one it wrote and throws away everything the portal
                         * resends until the clock passes that point. What reaches the
                         * drive is one continuous programme with the repeats removed.
                         */
                        if (trimming && lastPts >= 0L) {
                            val fresh = firstPacketAfter(data, start, start + whole, lastPts)
                            if (fresh < 0) {
                                // All of this is material we already hold.
                                carry = data.copyOfRange(start + whole, data.size)
                                continue
                            }
                            start = fresh
                            trimming = false
                            whole = ((data.size - start) / TS_PACKET) * TS_PACKET
                            if (whole <= 0) {
                                carry = data.copyOfRange(start, data.size)
                                continue
                            }
                        } else if (trimming) {
                            trimming = false
                        }

                        val newest = newestPts(data, start, start + whole)
                        if (newest >= 0L) lastPts = newest

                        if (startFreshChunk) {
                            startFreshChunk = false
                            rotateWhenReady = false
                            openNewPart()
                        }

                        // A new chunk has to begin at a point the player can start
                        // reading cold, which means a PAT packet - the stream's own
                        // table of contents. Splitting anywhere else leaves a file
                        // whose first second cannot be decoded.
                        var writeLen = whole
                        var splitAt = -1
                        if (rotateWhenReady) {
                            val pat = findPat(data, start, start + whole)
                            if (pat > start) {
                                splitAt = pat
                                writeLen = pat - start
                            }
                        }

                        try {
                            out?.write(data, start, writeLen)
                            if (splitAt >= 0) {
                                openNewPart()
                                rotateWhenReady = false
                                out?.write(data, splitAt, start + whole - splitAt)
                            }
                        } catch (writeFailure: Exception) {
                            // The drive went away mid-recording. Carry on into
                            // internal storage rather than ending here.
                            val fallback = Storage.internal(this)?.dir ?: File(filesDir, Storage.FOLDER)
                            folder = File(fallback, id).apply { mkdirs() }
                            RecordingStore.update(this, id) {
                                it.dirPath = folder.absolutePath
                                it.note = "The drive was disconnected, so the rest was saved on the box."
                            }
                            openNewPart()
                            rotateWhenReady = false
                            out?.write(data, start, whole)
                        }

                        carry = data.copyOfRange(start + whole, data.size)
                        bytesTotal += whole
                        everWrote = true

                        val now = System.currentTimeMillis()
                        if (now - partStarted >= PART_LENGTH_MS) rotateWhenReady = true
                        if (now - lastSave >= SAVE_EVERY_MS) {
                            lastSave = now
                            val soFar = bytesTotal
                            RecordingStore.update(this, id) { it.bytes = soFar }
                        }
                    }
                }
                // A clean end of stream before the finish time means the portal
                // closed on us. Straight back in - the wait is what turns a
                // handover into a hole.
                if (!stopping && System.currentTimeMillis() < endAt) {
                    needSync = true
                    continue
                }
            } catch (e: Exception) {
                if (awaySince == 0L) awaySince = System.currentTimeMillis()
                // A stream that has never produced a byte is probably the wrong
                // container for this portal - try the other one. One that worked
                // and then stopped gets reconnected as it is.
                if (!everWrote) urlIndex++
                runCatching { Thread.sleep(RECONNECT_WAIT_MS) }
            }
        }

        runCatching { out?.flush(); out?.close() }
        releaseLocks()

        val finishedAt = System.currentTimeMillis()
        RecordingStore.update(this, id) {
            it.bytes = bytesTotal
            it.endedAt = finishedAt
            it.state = if (everWrote) STATE_DONE else STATE_FAILED
            if (!everWrote && it.note.isBlank()) {
                it.note = "Nothing arrived from the server for this channel."
            }
        }
    }

    // ---------- recording from a playlist ----------

    /**
     * HLS RECORDING.
     *
     * The raw .ts approach assumes the portal will keep one connection open and
     * keep pushing. Some do. This one closes it every fourteen seconds, and on
     * every reconnect resends a few seconds it has already sent - which is why a
     * recording made that way jumps backwards and the sound ends up somewhere
     * else entirely. Two hundred and seventy five of those in an hour cannot be
     * papered over.
     *
     * A playlist has none of that ambiguity. Every segment carries a number, so
     * each one is fetched exactly once and written in order; a connection that
     * dies takes one segment with it, and that segment is simply fetched again.
     * Segments also begin at keyframes by definition, which makes them safe
     * places to start a new chunk.
     *
     * Returns false if this portal does not actually serve a playlist, so the
     * raw stream is still there to fall back on.
     */
    private fun recordFromPlaylist(
        id: String,
        playlistUrl: String,
        endAt: Long,
        startFolder: File
    ): Boolean {
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        var mediaUrl = playlistUrl
        val first = fetchText(http, mediaUrl) ?: return false
        if (!first.contains("#EXTM3U")) return false

        // A master playlist just points at the real ones - follow the first.
        if (first.contains("#EXT-X-STREAM-INF")) {
            val variant = first.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            mediaUrl = absolute(mediaUrl, variant)
        }

        var folder = startFolder
        var out: FileOutputStream? = null
        var partIndex = 0
        var partStarted = 0L
        var bytesTotal = 0L
        var lastSave = 0L
        var lastSequence = -1L
        var awaySince = 0L
        var everWrote = false
        var rotateWhenReady = false

        fun openNewPart() {
            runCatching { out?.flush(); out?.close() }
            val name = String.format("part%03d.ts", partIndex)
            partIndex++
            partStarted = System.currentTimeMillis()
            out = FileOutputStream(File(folder, name))
            RecordingStore.update(this, id) { if (!it.parts.contains(name)) it.parts.add(name) }
        }

        openNewPart()

        while (!stopping && System.currentTimeMillis() < endAt) {
            val text = fetchText(http, mediaUrl)
            if (text == null) {
                if (awaySince == 0L) awaySince = System.currentTimeMillis()
                runCatching { Thread.sleep(1_000L) }
                continue
            }

            var sequence = text.lineSequence()
                .firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") }
                ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L

            val targetSeconds = text.lineSequence()
                .firstOrNull { it.startsWith("#EXT-X-TARGETDURATION") }
                ?.substringAfter(':')?.trim()?.toDoubleOrNull() ?: 6.0

            val segments = ArrayList<Pair<Long, String>>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                segments.add(sequence to absolute(mediaUrl, line))
                sequence++
            }

            var wroteThisRound = false
            for ((number, url) in segments) {
                if (stopping || System.currentTimeMillis() >= endAt) break
                if (lastSequence >= 0 && number <= lastSequence) continue   // already have it

                val bytes = fetchBytes(http, url)
                if (bytes == null) {
                    if (awaySince == 0L) awaySince = System.currentTimeMillis()
                    continue
                }

                // A segment always starts at a keyframe, so this is a safe place
                // to begin a new chunk.
                if (rotateWhenReady) {
                    rotateWhenReady = false
                    openNewPart()
                }

                try {
                    out?.write(bytes)
                } catch (writeFailure: Exception) {
                    val fallback = Storage.internal(this)?.dir ?: File(filesDir, Storage.FOLDER)
                    folder = File(fallback, id).apply { mkdirs() }
                    RecordingStore.update(this, id) {
                        it.dirPath = folder.absolutePath
                        it.note = "The drive was disconnected, so the rest was saved on the box."
                    }
                    openNewPart()
                    out?.write(bytes)
                }

                lastSequence = number
                bytesTotal += bytes.size
                everWrote = true
                wroteThisRound = true

                if (awaySince > 0L) {
                    val lost = ((System.currentTimeMillis() - awaySince) / 1000L).toInt()
                    if (lost >= 2) {
                        RecordingStore.update(this, id) {
                            it.gaps.add(Gap(awaySince, lost))
                            it.reconnects++
                        }
                    }
                    awaySince = 0L
                }

                val now = System.currentTimeMillis()
                if (now - partStarted >= PART_LENGTH_MS) rotateWhenReady = true
                if (now - lastSave >= SAVE_EVERY_MS) {
                    lastSave = now
                    val soFar = bytesTotal
                    RecordingStore.update(this, id) { it.bytes = soFar }
                }
            }

            // Wait about half a segment before asking again - long enough not to
            // hammer the portal, short enough never to fall behind its window.
            val wait = if (wroteThisRound) (targetSeconds * 500).toLong() else 1_000L
            runCatching { Thread.sleep(wait.coerceIn(500L, 6_000L)) }
        }

        runCatching { out?.flush(); out?.close() }

        val finishedAt = System.currentTimeMillis()
        RecordingStore.update(this, id) {
            it.bytes = bytesTotal
            it.endedAt = finishedAt
            it.state = if (everWrote) STATE_DONE else STATE_FAILED
        }
        // If nothing at all arrived, let the raw stream have a go instead.
        return everWrote
    }

    private fun fetchText(http: OkHttpClient, url: String): String? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", Config.USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private fun fetchBytes(http: OkHttpClient, url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", Config.USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }.getOrNull()

    /** Playlists mix absolute and relative segment addresses. */
    private fun absolute(playlistUrl: String, line: String): String = when {
        line.startsWith("http://") || line.startsWith("https://") -> line
        line.startsWith("/") -> {
            val scheme = playlistUrl.substringBefore("://")
            val host = playlistUrl.substringAfter("://").substringBefore('/')
            "$scheme://$host$line"
        }
        else -> playlistUrl.substringBeforeLast('/') + "/" + line
    }

    // ---------- keeping the box awake ----------

    private fun holdLocks() {
        runCatching {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "johnnytv:recording")
                .also { it.setReferenceCounted(false); it.acquire(MAX_RECORDING_MS) }
        }
        runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "johnnytv:recording")
                .also { it.setReferenceCounted(false); it.acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    // ---------- the notification ----------

    private fun startForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
    }

    private fun notification(title: String, channel: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val notificationChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Recording",
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationChannel.setShowBadge(false)
                manager.createNotificationChannel(notificationChannel)
            }
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, RecordingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(title.ifBlank { "Recording" })
            .setContentText(channel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(R.drawable.ic_record, "Stop", stop)
            .build()
    }

    companion object {

        private val EMPTY = ByteArray(0)

        /** The size of one transport-stream packet. Not negotiable; it is the format. */
        private const val TS_PACKET = 188

        /** How much of an unsynced read to keep while waiting for more. */
        private const val SYNC_LOOKBACK = TS_PACKET * 4

        /**
         * Where the packets start.
         *
         * One 0x47 proves nothing - it is a perfectly ordinary byte inside video
         * data. Three of them exactly 188 apart is the stream.
         */
        private fun findSync(data: ByteArray): Int {
            val last = data.size - TS_PACKET * 2 - 1
            var i = 0
            while (i <= last) {
                if (data[i] == SYNC_BYTE &&
                    data[i + TS_PACKET] == SYNC_BYTE &&
                    data[i + TS_PACKET * 2] == SYNC_BYTE
                ) {
                    return i
                }
                i++
            }
            return -1
        }

        /**
         * The next programme association table, which is where a player can pick
         * the stream up from nothing. PID 0, carried in the two bytes after the
         * sync byte.
         */
        private fun findPat(data: ByteArray, from: Int, until: Int): Int {
            var i = from
            while (i + TS_PACKET <= until) {
                if (data[i] == SYNC_BYTE) {
                    val pid = ((data[i + 1].toInt() and 0x1F) shl 8) or (data[i + 2].toInt() and 0xFF)
                    val unitStart = (data[i + 1].toInt() and 0x40) != 0
                    if (pid == 0 && unitStart) return i
                }
                i += TS_PACKET
            }
            return -1
        }

        private const val SYNC_BYTE: Byte = 0x47

        /** A stream's clock wraps after about 26 hours. */
        private const val PTS_WRAP = 1L shl 33

        /**
         * The timestamp on a packet, or -1 if it has none.
         *
         * Only the first packet of each audio or video unit carries one, sitting
         * in the PES header behind five bytes with a particular shape. Anything
         * that doesn't match that shape exactly is left alone rather than guessed
         * at - a misread timestamp would be worse than no timestamp.
         */
        private fun ptsAt(data: ByteArray, packet: Int): Long {
            if (data[packet] != SYNC_BYTE) return -1L
            val unitStart = (data[packet + 1].toInt() and 0x40) != 0
            if (!unitStart) return -1L

            val adaptation = (data[packet + 3].toInt() shr 4) and 0x03
            var payload = packet + 4
            if (adaptation == 2) return -1L                       // no payload at all
            if (adaptation == 3) payload += (data[packet + 4].toInt() and 0xFF) + 1
            if (payload + 14 > packet + TS_PACKET) return -1L

            // PES start code, then a stream id in the audio or video range.
            if (data[payload].toInt() != 0x00 ||
                data[payload + 1].toInt() != 0x00 ||
                data[payload + 2].toInt() != 0x01
            ) return -1L
            val streamId = data[payload + 3].toInt() and 0xFF
            val isAudioOrVideo = streamId in 0xC0..0xEF
            if (!isAudioOrVideo) return -1L

            val flags = data[payload + 7].toInt() and 0xC0
            if (flags == 0) return -1L                            // carries no timestamp
            val p = payload + 9
            if ((data[p].toInt() and 0xF0) == 0) return -1L

            return (((data[p].toLong() and 0x0E) shl 29) or
                ((data[p + 1].toLong() and 0xFF) shl 22) or
                ((data[p + 2].toLong() and 0xFE) shl 14) or
                ((data[p + 3].toLong() and 0xFF) shl 7) or
                ((data[p + 4].toLong() and 0xFE) shr 1))
        }

        /** The newest timestamp in a run of packets, for remembering where we are. */
        private fun newestPts(data: ByteArray, from: Int, until: Int): Long {
            var i = from
            var best = -1L
            while (i + TS_PACKET <= until) {
                val pts = ptsAt(data, i)
                if (pts >= 0L && (best < 0L || pts > best)) best = pts
                i += TS_PACKET
            }
            return best
        }

        /**
         * The first packet carrying material we do not already have.
         *
         * A timestamp that has gone backwards by hours is the clock wrapping
         * rather than a rewind, so that counts as new. Anything within a few
         * seconds behind is the portal replaying its buffer, and is skipped.
         */
        private fun firstPacketAfter(data: ByteArray, from: Int, until: Int, after: Long): Int {
            var i = from
            while (i + TS_PACKET <= until) {
                val pts = ptsAt(data, i)
                if (pts >= 0L) {
                    val ahead = pts > after
                    val wrapped = after - pts > PTS_WRAP / 2
                    if (ahead || wrapped) return i
                }
                i += TS_PACKET
            }
            return -1
        }

        /** Ten minutes per chunk: small enough to lose nothing, few enough to play. */
        private const val PART_LENGTH_MS = 10L * 60L * 1000L

        /** Silence from the portal for this long counts as the feed having dropped. */
        private const val SILENCE_IS_A_DROP_MS = 5_000L

        /** How long to wait before opening a new connection after a drop. */
        private const val RECONNECT_WAIT_MS = 1_500L

        /** How often the row on the Recordings screen is brought up to date. */
        private const val SAVE_EVERY_MS = 5_000L

        /** An upper bound on the wake lock, so a stuck recording cannot hold the box awake for ever. */
        private const val MAX_RECORDING_MS = 12L * 60L * 60L * 1000L

        private const val NOTIFICATION_ID = 4711
        private const val CHANNEL_ID = "johnnytv-recording"

        const val ACTION_STOP = "com.johnnytv.player.STOP_RECORDING"

        private const val EXTRA_ID = "rec_id"
        private const val EXTRA_TITLE = "rec_title"
        private const val EXTRA_CHANNEL = "rec_channel"
        private const val EXTRA_STREAM_ID = "rec_stream"
        private const val EXTRA_URLS = "rec_urls"
        private const val EXTRA_END_AT = "rec_end"

        /**
         * Which recording is running, for the whole app to read.
         *
         * Most lines here allow one connection, so the player has to know not to
         * open a second stream while this one is being written.
         */
        @Volatile
        var activeId: String = ""
            private set

        @Volatile
        var activeStreamId: String = ""
            private set

        val isRecording: Boolean get() = activeId.isNotBlank()

        /**
         * Starts recording a channel now, until [endAt]. Returns the new
         * recording's id, or null when something is already recording.
         */
        fun start(
            context: Context,
            title: String,
            channel: StreamItem,
            endAt: Long
        ): String? {
            if (isRecording) return null
            val app = context.applicationContext
            val prefs = Prefs(app)
            val id = "r" + System.currentTimeMillis()
            val urls = prefs.client().liveUrls(channel.streamId)

            RecordingStore.put(
                app,
                Recording(
                    id = id,
                    title = title.ifBlank { channel.name },
                    channel = channel.name,
                    streamId = channel.streamId,
                    startedAt = System.currentTimeMillis(),
                    plannedEnd = endAt
                )
            )

            val intent = Intent(app, RecorderService::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_TITLE, title.ifBlank { channel.name })
                .putExtra(EXTRA_CHANNEL, channel.name)
                .putExtra(EXTRA_STREAM_ID, channel.streamId)
                .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                .putExtra(EXTRA_END_AT, endAt)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            return id
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.startService(Intent(app, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}
