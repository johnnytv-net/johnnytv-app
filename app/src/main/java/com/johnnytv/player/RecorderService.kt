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
            // Torn down before the recorder could tidy up: close the file and
            // finish the playlist here, so what was captured still plays as a
            // complete recording rather than an unfinished live stream.
            runCatching { out?.flush(); out?.close() }
            runCatching {
                if (currentPart.isNotEmpty()) {
                    partsWritten.add(Triple(currentPart, finishedLength(), currentPartIsBreak))
                    currentPart = ""
                }
                writePlaylist(true)
            }
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

    // ---------- the two ways a portal hands out a channel ----------

    /**
     * ONE STREAM, OR MANY PIECES.
     *
     * Portals serve live television in one of two shapes, and the difference
     * decides how a recorder has to behave.
     *
     * Some open a socket and pour video down it for hours. That is the easy one:
     * read until the programme ends, and treat silence as a fault.
     *
     * Others hand out twelve seconds at a time and close politely, because that
     * is how HLS works. Read that the first way and every twelve seconds looks
     * like a failure - the recorder reconnects, notes a dropout, and stitches a
     * seam into the file. One evening of that is hundreds of seams, which is
     * exactly what A&E produced: 122 "drops" in 25 minutes, none of them real.
     *
     * So the recorder learns which it is dealing with. Two clean endings in
     * quick succession is not a bad line, it is a segmented channel saying so,
     * and from that point it follows the playlist instead: ask what the pieces
     * are, fetch each one, write them end to end. No reconnects, no seams.
     */
    private enum class Mode { STREAM, SEGMENTS }

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
        folder = File(target?.dir ?: File(filesDir, Storage.FOLDER), id)
        folder.mkdirs()
        recordingId = id

        startedAtWall = System.currentTimeMillis()
        RecordingStore.update(this, id) {
            it.dirPath = folder.absolutePath
            it.startedAt = startedAtWall
        }

        // Clear the way first: watched recordings nobody has marked Keep are
        // dropped, oldest first, until there is room for this one. A drive that
        // fills up halfway through is the other classic way a recording dies.
        val needed = ((endAt - System.currentTimeMillis()).toDouble() / (60L * 60L * 1000L) *
            StorageTarget.BYTES_PER_HOUR).toLong()
        runCatching { RecordingStore.freeUpSpace(this, needed, folder) }

        val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            // The watchdog. A portal that stalls says nothing at all, so silence
            // for this long is what counts as the feed having gone away.
            .readTimeout(SILENCE_IS_A_DROP_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

        openNewPart()

        val streamUrl = urls.firstOrNull { !it.contains(".m3u8") } ?: urls.first()
        val playlistUrl = urls.firstOrNull { it.contains(".m3u8") }

        var mode = Mode.STREAM
        var shortEndings = 0
        /** Set once the playlist route has been tried and found wanting. */
        var segmentsFailed = false

        while (!stopping && System.currentTimeMillis() < endAt) {
            if (mode == Mode.SEGMENTS && playlistUrl != null) {
                // If it turns out not to be a playlist after all, fall back to
                // reading it as one long stream rather than spinning here.
                if (!followPlaylist(http, playlistUrl, id, endAt)) {
                    // It had its chance and produced nothing. Reading the stream
                    // directly is what works on this channel, so stay there.
                    mode = Mode.STREAM
                    segmentsFailed = true
                    shortEndings = 0
                }
                continue
            }

            val startedAt = System.currentTimeMillis()
            val before = bytesTotal
            readOneStream(http, streamUrl, id, endAt)
            val lasted = System.currentTimeMillis() - startedAt
            val deliveredVideo = bytesTotal > before

            // What matters is the shape of the ending, not its manners. Some
            // portals close a segment tidily and some just cut the socket, and
            // the first version of this only counted the polite ones - so a
            // channel that hung up rudely every twelve seconds was never
            // recognised as segmented at all.
            if (deliveredVideo && lasted < SEGMENT_LOOKS_LIKE_MS) {
                shortEndings++
                // Twice is a pattern rather than bad luck. Switch to asking for
                // the pieces properly, and stop calling these dropouts.
                if (shortEndings >= 2 && playlistUrl != null && !segmentsFailed) {
                    mode = Mode.SEGMENTS
                    awaySince = 0L
                    RecordingStore.update(this, id) {
                        it.gaps.clear()
                        it.reconnects = 0
                    }
                    continue
                }
            } else {
                shortEndings = 0
            }

            // The plain stream address is not answering at all. The playlist is
            // worth a try before giving the evening up as lost.
            if (!everWrote && tried >= 2 && playlistUrl != null) {
                mode = Mode.SEGMENTS
                continue
            }

            if (!stopping && System.currentTimeMillis() < endAt) {
                if (awaySince == 0L) awaySince = System.currentTimeMillis()
                runCatching { Thread.sleep(RECONNECT_WAIT_MS) }
            }
        }

        closeUp(id)
    }

    /**
     * One connection to a continuous stream, read until it stops.
     *
     * How it ended is judged by the caller, from how long it lasted and whether
     * any video came out of it.
     */
    private fun readOneStream(
        http: OkHttpClient,
        url: String,
        id: String,
        endAt: Long
    ) {
        val openedAt = System.currentTimeMillis()
        connections++
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

                backOnAir(id)
                needSync = true
                if (everWrote) {
                    startFreshChunk = true
                    needPat = true
                    trimming = true
                    trimStartedAt = System.currentTimeMillis()
                }

                while (!stopping && System.currentTimeMillis() < endAt) {
                    val read = input.read(buffer)
                    if (read < 0) return               // the portal closed it
                    if (read == 0) continue
                    feed(buffer, read, id)
                    housekeeping(id)
                }
            }
        } catch (e: Exception) {
            if (!everWrote) tried++
        } finally {
            readingMs += System.currentTimeMillis() - openedAt
        }
    }

    /**
     * A segmented channel, followed the way it expects.
     *
     * Read the playlist, fetch anything on it we do not already have, write the
     * pieces end to end, then ask again. Segments arrive whole and in order, so
     * there is nothing to resynchronise and no seam between them - the file is
     * as continuous as the broadcast.
     */
    private fun followPlaylist(
        http: OkHttpClient,
        playlistUrl: String,
        id: String,
        endAt: Long
    ): Boolean {
        val seen = LinkedHashSet<String>()
        var emptyRounds = 0
        /*
         * SEGMENT MODE HAS TO EARN ITS PLACE.
         *
         * Following a playlist is right for a channel that is served in pieces.
         * It is a disaster for one that is not, because nothing announces the
         * mistake: the playlist is fetched, nothing on it is new, and the
         * recorder waits politely while the programme goes past. Two minutes
         * recorded, thirty-one seconds of it with a connection actually open.
         *
         * So it is given a short while to produce something. If no new piece
         * arrives in that time, it gives up and goes back to reading the stream
         * directly, which is the mode that was working.
         */
        var lastPieceAt = System.currentTimeMillis()

        while (!stopping && System.currentTimeMillis() < endAt) {
            // Checked here rather than after a successful fetch, because the way
            // this went wrong was a playlist that never fetched at all: the
            // error path looped quietly for a hundred seconds while the
            // programme went past and the recorder read nothing.
            if (System.currentTimeMillis() - lastPieceAt > SEGMENTS_MUST_DELIVER_MS) return false

            val playlist = try {
                val request = Request.Builder()
                    .url(playlistUrl)
                    .header("User-Agent", Config.USER_AGENT)
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                if (awaySince == 0L) awaySince = System.currentTimeMillis()
                runCatching { Thread.sleep(RECONNECT_WAIT_MS) }
                continue
            }

            val segments = segmentsIn(playlist, playlistUrl)
            if (segments.isEmpty()) {
                // Not a playlist after all - go back to reading it as a stream.
                if (++emptyRounds >= 3) return false
                runCatching { Thread.sleep(POLL_WAIT_MS) }
                continue
            }
            emptyRounds = 0

            var got = 0
            for (piece in segments) {
                if (stopping || System.currentTimeMillis() >= endAt) break
                if (!seen.add(piece.url)) continue

                /*
                 * WHERE THE CLOCK JUMPS, THE FILE BREAKS.
                 *
                 * Every piece carries its own timestamps, and they do not always
                 * continue from the last one. Written straight through, a player
                 * reading that file reaches the jump and decides the programme is
                 * over - which is a recording that stops dead after thirty
                 * seconds even though all of it is there on the drive.
                 *
                 * Starting a new file at each jump gives the player a clean break
                 * it understands: it finishes one piece, moves to the next, and
                 * the viewer sees a continuous programme.
                 */
                /*
                 * ONE PIECE, ONE FILE.
                 *
                 * Splitting only where the clock jumped was not enough, because a
                 * clock is not the only thing that changes between HLS pieces:
                 * the internal stream numbers can change too, and a player that
                 * has bound itself to the old ones reads that as the end of the
                 * programme. Hence a recording that stops dead eighteen seconds
                 * in with all five minutes present on the drive.
                 *
                 * So each piece is written as its own file, which is exactly how
                 * a player consumes this kind of channel in the first place -
                 * every join becomes a boundary it already knows how to cross.
                 * An hour is a few hundred small files nobody will ever look at,
                 * and they play as one continuous programme.
                 */
                startFreshChunk = true
                needSync = true
                // Every part has to open with the stream's table of contents,
                // or a player handed that part cold has nothing to read it
                // with. They come past several times a second, so waiting for
                // one costs nothing.
                needPat = true
                nextPartSeconds = piece.seconds
                nextPartIsBreak = piece.afterBreak

                if (fetchSegment(http, piece.url, id)) got++
            }

            // Keep the memory of what we have seen from growing all night.
            while (seen.size > REMEMBER_SEGMENTS) seen.remove(seen.first())

            if (got > 0) {
                lastPieceAt = System.currentTimeMillis()
            }

            housekeeping(id)
            runCatching { Thread.sleep(if (got > 0) POLL_WAIT_MS else POLL_WAIT_MS / 2) }
        }
        return true
    }

    /** Pulls one segment down and writes it. */
    private fun fetchSegment(http: OkHttpClient, url: String, id: String): Boolean {
        val openedAt = System.currentTimeMillis()
        pieces++
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Config.USER_AGENT)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val input = response.body?.byteStream() ?: return false
                val buffer = ByteArray(64 * 1024)
                backOnAir(id)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    feed(buffer, read, id)
                }
                true
            }
        } catch (e: Exception) {
            if (awaySince == 0L) awaySince = System.currentTimeMillis()
            false
        } finally {
            readingMs += System.currentTimeMillis() - openedAt
        }
    }

    /**
     * The segment addresses in a playlist, in order.
     *
     * A master playlist points at other playlists rather than at video; when one
     * turns up, its first variant is followed instead.
     */
    private fun segmentsIn(playlist: String, from: String): List<Piece> {
        if (!playlist.contains("#EXTM3U")) return emptyList()
        val lines = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val variant = lines.firstOrNull { !it.startsWith("#") } ?: return emptyList()
            return listOf(Piece(absolute(variant, from), false, 0.0))
        }

        // A playlist may announce that the next piece does not follow on from the
        // last - an advert break spliced in, or the encoder restarting. That is
        // the portal telling us exactly where the clock jumps, which is where a
        // recording has to be split.
        val out = ArrayList<Piece>()
        var breakHere = false
        var seconds = 0.0
        for (line in lines) {
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-DISCONTINUITY")) breakHere = true
                if (line.startsWith("#EXTINF:")) {
                    seconds = line.removePrefix("#EXTINF:")
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull() ?: 0.0
                }
                continue
            }
            out.add(Piece(absolute(line, from), breakHere, seconds))
            breakHere = false
            seconds = 0.0
        }
        return out
    }

    /**
     * One piece of a segmented channel: where it is, whether the clock jumps
     * before it, and how long it actually plays for.
     *
     * That last one matters more than it looks. The playlist written beside a
     * recording has to say how long each piece runs, and timing how long it took
     * to arrive is not the same thing at all - a twelve second piece can be
     * fetched in half a second. Told a piece lasts half a second, a player runs
     * out of material and sits there stuttering. The portal states the real
     * figure, so that is the one to keep.
     */
    private data class Piece(val url: String, val afterBreak: Boolean, val seconds: Double)

    /** Playlists may list pieces by full address or by name alone. */
    private fun absolute(reference: String, from: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        return try {
            java.net.URL(java.net.URL(from), reference).toString()
        } catch (e: Exception) {
            reference
        }
    }

    // ---------- writing ----------

    private var folder: File = File("")
    private var recordingId: String = ""
    private var startedAtWall = 0L
    private var out: FileOutputStream? = null
    private var partIndex = 0
    private var partStarted = 0L
    private var bytesTotal = 0L
    private var lastSave = 0L
    private var awaySince = 0L
    private var everWrote = false
    private var tried = 0

    /** Bytes of a half-finished packet, waiting for the rest of it. */
    private var carry = EMPTY
    /** True until the run of 0x47s has been found after a (re)connection. */
    private var needSync = true
    /** The chunk is due to roll over, as soon as a PAT comes past. */
    private var rotateWhenReady = false
    /** This connection is new, so its first bytes belong in a new chunk. */
    private var startFreshChunk = false
    /** Nothing is worth writing until the stream says where it begins. */
    private var needPat = false
    /** The newest timestamp written to disk, in 90 kHz ticks. */
    private var lastPts = -1L
    /** After a reconnect: drop what the portal resends until it moves past lastPts. */
    private var trimming = false
    /** When skipping a repeat began, so it cannot run away with the recording. */
    private var trimStartedAt = 0L
    /*
     * WHERE THE PICTURE WENT.
     *
     * A recording that runs two minutes and holds thirty-five seconds is losing
     * material between the socket and the drive, and guessing which branch is
     * doing it has cost more time than measuring it will. Every route out of
     * the data counts what it let go, and the totals go into the report.
     */
    private var bytesArrived = 0L
    private var bytesSkippedRepeats = 0L
    private var bytesSkippedNoPat = 0L
    private var bytesSkippedNoSync = 0L
    private var bytesWaitingForClock = 0L
    /** How many times a connection was opened, and how long they were open. */
    private var connections = 0
    private var pieces = 0
    private var readingMs = 0L
    /** Seconds of video actually captured, from the stream's own clock. */
    private var contentSeconds = 0.0

    /** The first timestamp in the part being written, for its true length. */
    private var partFirstPts = -1L
    /** The newest timestamp in the part being written. */
    private var partLastPts = -1L

    /** Each finished part: its name, how long it plays, and whether the clock jumped before it. */
    private val partsWritten = ArrayList<Triple<String, Double, Boolean>>()
    private var currentPart = ""
    /** Whether the part being written started a new clock. */
    private var currentPartIsBreak = false
    private var nextPartIsBreak = false

    /** How long the piece now being written plays for, as the portal stated it. */
    private var currentPartSeconds = 0.0
    private var nextPartSeconds = 0.0

    private fun openNewPart() {
        runCatching { out?.flush(); out?.close() }
        if (currentPart.isNotEmpty()) {
            val length = finishedLength()
            contentSeconds += length
            partsWritten.add(Triple(currentPart, length, currentPartIsBreak))
        }
        currentPartIsBreak = nextPartIsBreak
        nextPartIsBreak = false
        currentPartSeconds = nextPartSeconds
        nextPartSeconds = 0.0
        partFirstPts = -1L
        partLastPts = -1L
        val name = String.format("part%03d.ts", partIndex)
        partIndex++
        partStarted = System.currentTimeMillis()
        currentPart = name
        out = FileOutputStream(File(folder, name))
        RecordingStore.update(this, recordingId) { if (!it.parts.contains(name)) it.parts.add(name) }
        writePlaylist(false)
    }

    /**
     * THE RECORDING, DESCRIBED IN ITS OWN PLAYLIST.
     *
     * Handing a player a folder of stream pieces and asking it to run them
     * together does not work, however carefully the pieces are cut: each one
     * carries its own clock and its own internal numbering, and a player reading
     * them as plain files gives up at the first join - a five minute recording
     * that stops at thirty seconds with everything present on the drive.
     *
     * A playlist is how this format says "these pieces, in this order, and here
     * is where things change". Written next to the pieces, the recording opens
     * as one programme and the player's own machinery handles every join,
     * because that is exactly what it was built for.
     */
    private fun writePlaylist(finished: Boolean) {
        val all = ArrayList(partsWritten)
        if (!finished && currentPart.isNotEmpty()) {
            all.add(Triple(currentPart, finishedLength(), currentPartIsBreak))
        }
        if (all.isEmpty()) return

        val longest = all.maxOf { it.second }
        val text = StringBuilder()
        text.append("#EXTM3U\n")
        text.append("#EXT-X-VERSION:3\n")
        text.append("#EXT-X-TARGETDURATION:").append(Math.ceil(longest).toInt()).append("\n")
        text.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        text.append("#EXT-X-PLAYLIST-TYPE:EVENT\n")
        for ((index, part) in all.withIndex()) {
            /*
             * A DISCONTINUITY IS A CLAIM, NOT A COURTESY.
             *
             * Announcing one tells the player to throw away everything it knows
             * about the stream's timing and start again from what the next part
             * tells it. Where the parts actually run on from each other - which
             * is most of the time, because a reconnect usually resumes cleanly -
             * that instruction leaves it waiting for a fresh start that never
             * arrives, and the picture sits there spinning.
             *
             * So it is only declared where the clock genuinely jumped.
             */
            if (index > 0 && part.third) text.append("#EXT-X-DISCONTINUITY\n")
            text.append("#EXTINF:").append(String.format(java.util.Locale.US, "%.3f", part.second)).append(",\n")
            text.append(part.first).append("\n")
        }
        if (finished) text.append("#EXT-X-ENDLIST\n")
        runCatching { File(folder, PLAYLIST_NAME).writeText(text.toString()) }
    }

    /** Back on air after being away: that is a gap worth reporting. */
    private fun backOnAir(id: String) {
        if (awaySince <= 0L) return
        val lost = ((System.currentTimeMillis() - awaySince) / 1000L).toInt()
        if (lost > 0) {
            RecordingStore.update(this, id) {
                it.gaps.add(Gap(awaySince, lost))
                it.reconnects++
            }
        }
        awaySince = 0L
    }

    /**
     * Bytes to disk - whole packets only, repeats removed.
     *
     * A transport stream is a procession of 188-byte packets, each starting with
     * 0x47. Writing whatever happened to arrive in the last read chops the first
     * and last packet of every write in half, and the player then has to guess
     * where the next frame starts: the sound skips and the picture sits on one
     * frame until the next keyframe. So anything left over is held back and
     * glued to the front of the next read.
     */
    private fun feed(buffer: ByteArray, read: Int, id: String) {
        bytesArrived += read
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
                bytesSkippedNoSync += data.size
                carry = data.copyOfRange((data.size - SYNC_LOOKBACK).coerceAtLeast(0), data.size)
                return
            }
            bytesSkippedNoSync += at
            start = at
            needSync = false
        }

        var whole = ((data.size - start) / TS_PACKET) * TS_PACKET
        if (whole <= 0) {
            carry = data.copyOfRange(start, data.size)
            return
        }

        // A new connection: throw away everything up to the first PAT, so the
        // chunk starts where a player can start.
        if (needPat) {
            val pat = findPat(data, start, start + whole)
            if (pat < 0) {
                bytesSkippedNoPat += whole
                carry = data.copyOfRange(start + whole, data.size)
                return
            }
            bytesSkippedNoPat += pat - start
            start = pat
            needPat = false
            whole = ((data.size - start) / TS_PACKET) * TS_PACKET
            if (whole <= 0) {
                carry = data.copyOfRange(start, data.size)
                return
            }
        }

        /*
         * THE REWIND, CUT OUT.
         *
         * A portal does not resume where it left off. It reconnects you to its
         * own buffer, which usually means several seconds you already have.
         * Written down, those seconds play twice: the recording jumps backwards,
         * the sound repeats, and from then on the picture and the audio are
         * arguing about what time it is. Every packet carries a timestamp, so
         * the recorder keeps the last one it wrote and throws away what the
         * portal resends until the clock passes that point.
         */
        if (trimming && lastPts >= 0L) {
            /*
             * TRIMMING, DECIDED BY THE CLOCK.
             *
             * Skipping what a portal resends is right when it resends a couple
             * of seconds. It is ruinous when it comes back somewhere else
             * entirely - and this one drops and reconnects every few seconds,
             * each time from a different point. Skipping "until the clock
             * catches up" then throws away real television on every single
             * reconnect: two minutes recorded, thirty-five seconds kept, and
             * not one gap reported because each drop lasted under a second.
             *
             * So the decision is made from the stream's own clock, once, as
             * soon as the first timestamp of the new connection arrives. Close
             * behind where we were: skip the overlap, it is a genuine repeat.
             * Anywhere else: keep everything, start a new part, and say in the
             * playlist that the clock changed. Nothing is ever discarded on a
             * guess.
             */
            val arriving = firstPts(data, start, start + whole)
            if (arriving < 0L) {
                bytesWaitingForClock += whole
                // No timestamp in this batch yet; hold it and wait rather than
                // throwing it away.
                carry = data.copyOfRange(start, data.size)
                return
            }

            val behind = lastPts - arriving
            val overlapping = behind in 1..OVERLAP_LIMIT_TICKS

            if (overlapping && System.currentTimeMillis() - trimStartedAt > SKIP_TIME_LIMIT_MS) {
                // Skipping has gone on too long to be a repeat any more. Take
                // what is arriving and mark the join, rather than carving a
                // hole out of the recording waiting for a clock that is not
                // coming back.
                trimming = false
                startFreshChunk = true
                needPat = true
                nextPartIsBreak = true
                lastPts = -1L
            } else if (overlapping) {
                val fresh = firstPacketAfter(data, start, start + whole, lastPts)
                if (fresh < 0) {
                    bytesSkippedRepeats += whole
                    carry = data.copyOfRange(start + whole, data.size)
                    return
                }
                bytesSkippedRepeats += fresh - start
                start = fresh
                trimming = false
                whole = ((data.size - start) / TS_PACKET) * TS_PACKET
                if (whole <= 0) {
                    carry = data.copyOfRange(start, data.size)
                    return
                }
            } else {
                // A different point in the stream: this is a new clock, not a
                // repeat. Keep every frame of it.
                trimming = false
                startFreshChunk = true
                needPat = true
                nextPartIsBreak = true
                lastPts = -1L
            }
        } else if (trimming) {
            trimming = false
        }

        /*
         * A JUMP IN THE CLOCK, FOUND WITHOUT BEING TOLD.
         *
         * Portals are supposed to announce where their timestamps restart. Most
         * do not. So the recorder watches for it: the first timestamp in this
         * batch against the last one written. A second or two apart is normal
         * television; ten seconds adrift, forwards or backwards, is a new clock.
         *
         * Written into the same file, a player reaches that point and decides
         * the programme has ended - which is a five minute recording that stops
         * at thirty seconds with everything still sitting on the drive. So the
         * file is broken there: what we have goes down now, the rest starts a
         * new part, and playback runs through the join without noticing.
         */
        if (lastPts >= 0L && !startFreshChunk) {
            val jump = firstJump(data, start, start + whole, lastPts)
            if (jump > start) {
                runCatching { out?.write(data, start, jump - start) }
                bytesTotal += jump - start
                everWrote = true
                carry = data.copyOfRange(jump, data.size)
                startFreshChunk = true
                needPat = true
                needSync = true
                nextPartIsBreak = true
                return
            }
        }

        val newest = newestPts(data, start, start + whole)
        if (newest >= 0L) {
            lastPts = newest
            partLastPts = newest
            if (partFirstPts < 0L) {
                val firstHere = firstPts(data, start, start + whole)
                if (firstHere >= 0L) partFirstPts = firstHere
            }
        }

        if (startFreshChunk) {
            startFreshChunk = false
            rotateWhenReady = false
            openNewPart()
        }

        // A new chunk has to begin at a point the player can start reading cold,
        // which means a PAT packet - the stream's own table of contents.
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
            // The drive went away mid-recording. Carry on into internal storage
            // rather than ending here.
            val fallback = Storage.internal(this)?.dir ?: File(filesDir, Storage.FOLDER)
            folder = File(fallback, id).apply { mkdirs() }
            RecordingStore.update(this, id) {
                it.dirPath = folder.absolutePath
                it.note = "The drive was disconnected, so the rest was saved on the box."
            }
            openNewPart()
            rotateWhenReady = false
            runCatching { out?.write(data, start, whole) }
        }

        carry = data.copyOfRange(start + whole, data.size)
        bytesTotal += whole
        everWrote = true
    }

    /** Chunk rotation and keeping the row on screen current. */
    private fun housekeeping(id: String) {
        val now = System.currentTimeMillis()
        if (now - partStarted >= PART_LENGTH_MS) rotateWhenReady = true
        if (now - lastSave >= SAVE_EVERY_MS) {
            lastSave = now
            val soFar = bytesTotal
            RecordingStore.update(this, id) { it.bytes = soFar }
        }
    }

    /**
     * How long the part just written plays for: what the portal said where it
     * said anything, and the time it took to arrive otherwise - which is right
     * for a continuous stream, where the two are the same thing.
     */
    private fun finishedLength(): Double {
        // The video's own clock is the only honest measure. Wall time says how
        // long we sat there, which after a reconnect is not the same thing at
        // all - and a playlist that overstates a part leaves the player waiting
        // for material that does not exist.
        if (partFirstPts >= 0L && partLastPts > partFirstPts) {
            return (partLastPts - partFirstPts) / 90_000.0
        }
        if (currentPartSeconds > 0.0) return currentPartSeconds
        return ((System.currentTimeMillis() - partStarted) / 1000.0).coerceAtLeast(0.1)
    }

    private fun mb(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))

    private fun closeUp(id: String) {
        runCatching { out?.flush(); out?.close() }
        if (currentPart.isNotEmpty()) {
            partsWritten.add(Triple(currentPart, finishedLength(), currentPartIsBreak))
            currentPart = ""
        }
        writePlaylist(true)
        releaseLocks()
        val finishedAt = System.currentTimeMillis()
        val wrote = everWrote
        val total = bytesTotal
        val arrived = bytesArrived
        val repeats = bytesSkippedRepeats
        val noPat = bytesSkippedNoPat
        val noSync = bytesSkippedNoSync
        val waiting = bytesWaitingForClock
        val opened = connections
        val fetched = pieces
        val reading = readingMs
        val wall = ((finishedAt - startedAtWall) / 1000L).coerceAtLeast(1L)
        val content = contentSeconds + (if (partFirstPts >= 0L && partLastPts > partFirstPts)
            (partLastPts - partFirstPts) / 90_000.0 else 0.0)
        RecordingStore.update(this, id) {
            it.bytes = total
            it.endedAt = finishedAt
            it.state = if (wrote) STATE_DONE else STATE_FAILED
            // Always written, not only when a lot went missing. "Nothing was
            // dropped" and "the portal only sent us forty seconds" look
            // identical from the outside, and they need opposite fixes.
            it.note = "Recorded over " + wall + "s: the feed delivered " +
                String.format(java.util.Locale.US, "%.0f", content) + "s of video in " +
                mb(arrived) + " across " + opened + " connection(s) and " + fetched +
                " piece(s), reading for " +
                (reading / 1000L) + "s. Kept " + mb(total) +
                " — repeats " + mb(repeats) + ", contents " + mb(noPat) +
                ", clock " + mb(waiting) + ", unreadable " + mb(noSync) + "."
            if (!wrote && it.note.isBlank()) {
                it.note = "Nothing arrived from the server for this channel."
            }
        }
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

        /** More than this far from the last timestamp is a new clock, not a programme. */
        private const val CLOCK_JUMP_TICKS = 10L * 90_000L

        /**
         * The first packet whose timestamp does not follow on from [after].
         *
         * Returns -1 when the run continues sensibly, which is the normal case
         * and costs one pass over packets that mostly carry no timestamp at all.
         */
        private fun firstJump(data: ByteArray, from: Int, until: Int, after: Long): Int {
            var i = from
            while (i + TS_PACKET <= until) {
                val pts = ptsAt(data, i)
                if (pts >= 0L) {
                    val drift = pts - after
                    val wrapped = drift < -(PTS_WRAP / 2) || drift > PTS_WRAP / 2
                    if (!wrapped && (drift > CLOCK_JUMP_TICKS || drift < -CLOCK_JUMP_TICKS)) return i
                }
                i += TS_PACKET
            }
            return -1
        }

        /**
         * How far back counts as the portal repeating itself.
         *
         * Within a few seconds of where we were is a genuine overlap worth
         * skipping. Beyond that it is simply a different moment, and skipping
         * to reach the old one would mean discarding everything in between.
         */
        /**
         * How far behind a reconnection may land and still count as a repeat.
         *
         * Generous, because portals hand back a minute or more of their buffer
         * and every second of that is material we already hold. Written down it
         * plays twice: the recording jumps backwards at each join.
         */
        private const val OVERLAP_LIMIT_TICKS = 120L * 90_000L

        /**
         * How long skipping a repeat may take before it is abandoned.
         *
         * The safety net. Skipping "until the clock catches up" once cost
         * ninety seconds of a two minute recording, because the stream never
         * did catch up. After this long the material is taken as it comes.
         */
        private const val SKIP_TIME_LIMIT_MS = 8_000L

        /** The first timestamp in a run of packets. */
        private fun firstPts(data: ByteArray, from: Int, until: Int): Long {
            var i = from
            while (i + TS_PACKET <= until) {
                val pts = ptsAt(data, i)
                if (pts >= 0L) return pts
                i += TS_PACKET
            }
            return -1L
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

        /**
         * A connection that ends tidily inside this long was a segment, not a
         * stream. HLS pieces are usually six to twelve seconds; a real stream
         * that ends this fast twice in a row is telling us the same thing.
         */
        private const val SEGMENT_LOOKS_LIKE_MS = 25_000L

        /**
         * How much of a segmented channel goes in one file.
         *
         * Short enough that a timestamp jump we did not spot costs a break
         * rather than the rest of the recording, long enough that an hour is
         * thirty files and not three hundred.
         */
        private const val SEGMENT_PART_MS = 2L * 60L * 1000L

        /** How long to wait before asking a segmented channel what is new. */
        private const val POLL_WAIT_MS = 4_000L

        /** How many segment names to remember, so nothing is fetched twice. */
        private const val REMEMBER_SEGMENTS = 400

        /**
         * How long to wait before opening a new connection after a drop.
         *
         * Short, because on a channel that closes every fifteen seconds this
         * pause is time off air. A portal that is refusing outright still gets
         * the read timeout above before we come back to it.
         */
        private const val RECONNECT_WAIT_MS = 250L

        /** How long segment mode has to produce a piece before it is abandoned. */
        private const val SEGMENTS_MUST_DELIVER_MS = 15_000L

        /** How often the row on the Recordings screen is brought up to date. */
        private const val SAVE_EVERY_MS = 5_000L

        /** An upper bound on the wake lock, so a stuck recording cannot hold the box awake for ever. */
        private const val MAX_RECORDING_MS = 12L * 60L * 60L * 1000L

        /** The playlist written beside the pieces, which is what gets played. */
        const val PLAYLIST_NAME = "recording.m3u8"

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
