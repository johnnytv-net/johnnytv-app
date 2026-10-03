package com.johnnytv.player

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/*
 * SEVERAL CHANNELS ON A LINE THAT ALLOWS ONE CONNECTION.
 *
 * How the other players manage it, and why it stutters when they do: they
 * simply open a second stream. The portal does not refuse it - it lets the new
 * one in and cuts the old one off. The old one reconnects, which cuts the new
 * one off, and the two spend the evening knocking each other over. It holds
 * together at all only because of one habit every portal has: a connection
 * does not start at "now", it starts with a lump of the last several seconds
 * (often a minute) sent as fast as the wire allows. Each reconnect therefore
 * arrives with enough to cover the time spent away, the picture survives, and
 * the sound trips at every join because nobody is matching the joins up.
 *
 * This does the same thing on purpose instead of by accident:
 *
 *   - Only one connection is ever open. A screen takes the line, swallows the
 *     lump the portal hands it, and lets go the moment it has caught up with
 *     live. Nobody is ever knocked off, because nobody is ever on at the same
 *     time.
 *   - Each screen plays out of what it swallowed while the other one has its
 *     turn, and asks for the line back when its reserve runs low.
 *   - The join is exact. The portal resends bytes we already hold, so the last
 *     packets we passed on are found again in the new lump and the stream
 *     carries on from the very next packet. The player never sees a reconnect
 *     at all - no repeat, no hole, nothing for the sound to trip over.
 *
 * Nothing in this file knows about Android, so all of it can be run and
 * tested on an ordinary computer against a pretend portal.
 */

/** Milliseconds on a clock that never jumps. */
internal fun steadyNow(): Long = System.nanoTime() / 1_000_000L

/**
 * The line itself: one connection, handed round in the order it was asked for.
 */
class LineShare {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayList<LineFeed>()
    private val members = ArrayList<LineFeed>()
    private var holder: LineFeed? = null
    private var freeAt = 0L
    private var governor: Thread? = null

    /** How long to leave between one screen letting go and the next connecting. */
    @Volatile var settleMs: Long = SETTLE_START_MS

    /** The longest any screen has recently needed to swallow its lump. */
    @Volatile var gulpMs: Long = 1_500L

    fun join(feed: LineFeed) = lock.withLock {
        if (!members.contains(feed)) members.add(feed)
        if (governor == null) {
            val thread = Thread({ govern() }, "jtv-line")
            thread.isDaemon = true
            governor = thread
            thread.start()
        }
        changed.signalAll()
    }

    fun leave(feed: LineFeed) = lock.withLock {
        members.remove(feed)
        queue.remove(feed)
        if (holder === feed) {
            holder = null
            freeAt = steadyNow() + settleMs
        }
        changed.signalAll()
    }

    fun count(): Int = lock.withLock { members.size }

    /** Somebody other than [feed] is waiting for the line. */
    fun wanted(feed: LineFeed): Boolean = lock.withLock { queue.any { it !== feed } }

    /** Waits for the line. False means the feed was closed while waiting. */
    fun take(feed: LineFeed): Boolean {
        lock.withLock {
            if (!queue.contains(feed)) queue.add(feed)
            try {
                while (true) {
                    if (feed.closed) {
                        queue.remove(feed)
                        changed.signalAll()
                        return false
                    }
                    // The pause after a handover is for the portal to notice the
                    // last connection has gone. A channel on its own is handing
                    // over to nobody, so it does not wait.
                    val wait = if (members.size <= 1) 0L else freeAt - steadyNow()
                    if (holder == null && queue.firstOrNull() === feed && wait <= 0L) {
                        queue.remove(feed)
                        holder = feed
                        return true
                    }
                    val nap = if (holder == null && wait > 0L) wait else 200L
                    changed.await(nap, TimeUnit.MILLISECONDS)
                }
            } catch (interrupted: InterruptedException) {
                queue.remove(feed)
                changed.signalAll()
                return false
            }
        }
    }

    fun give(feed: LineFeed) = lock.withLock {
        if (holder === feed) {
            holder = null
            freeAt = steadyNow() + settleMs
            changed.signalAll()
        }
    }

    /**
     * How low a screen lets its reserve fall before asking for the line back.
     *
     * Low enough that it is not forever reconnecting, high enough to cover
     * waiting for everybody else's turn. Both ends are measured, not guessed:
     * how much the portal hands back on a reconnect, and how long a turn has
     * actually been taking.
     */
    fun lowWaterMs(): Long {
        val list = lock.withLock { ArrayList(members) }
        val known = list.map { it.depthMs }.filter { it > 0L }
        val depth = if (known.isEmpty()) ASSUMED_DEPTH_MS else known.minOrNull() ?: ASSUMED_DEPTH_MS
        val others = (list.size - 1).coerceAtLeast(1)
        val forTurns = others * (gulpMs + settleMs + 400L) + 1_500L
        // A line that has already missed a join gets a wider margin still.
        val missed = (list.maxOfOrNull { it.gaps } ?: 0).coerceAtMost(3)
        var low = maxOf((depth * (62L + 6L * missed)) / 100L, forTurns)
        low = minOf(low, LOW_WATER_MAX_MS)
        low = minOf(low, maxOf(1_500L, depth - 1_000L))
        return low
    }

    /** A turn took this long from connecting to letting go. */
    fun noteGulp(ms: Long) {
        // Rises at once, falls slowly: one slow turn is worth remembering.
        gulpMs = if (ms > gulpMs) ms.coerceAtMost(GULP_REMEMBER_MAX_MS) else (gulpMs * 3L + ms) / 4L
    }

    /** A connection made straight after a handover came back empty. */
    fun noteRefused() {
        settleMs = (settleMs * 2L).coerceAtMost(SETTLE_MAX_MS)
    }

    private fun govern() {
        while (true) {
            val list = lock.withLock {
                if (members.isEmpty()) {
                    governor = null
                    return
                }
                ArrayList(members)
            }
            val now = steadyNow()
            for (feed in list) runCatching { feed.tick(now) }
            try {
                Thread.sleep(GOVERN_EVERY_MS)
            } catch (interrupted: InterruptedException) {
                return
            }
        }
    }

    companion object {
        private const val SETTLE_START_MS = 250L
        private const val SETTLE_MAX_MS = 1_500L
        private const val ASSUMED_DEPTH_MS = 8_000L
        private const val LOW_WATER_MAX_MS = 20_000L
        private const val GULP_REMEMBER_MAX_MS = 15_000L
        private const val GOVERN_EVERY_MS = 150L
    }
}

/**
 * A queue of bytes between the thread fetching a channel and the player
 * showing it.
 */
class BytePipe {

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var headAt = 0
    private var held = 0
    private var shut = false

    fun size(): Int = lock.withLock { held }

    fun put(data: ByteArray, from: Int, length: Int) {
        if (length <= 0) return
        val copy = data.copyOfRange(from, from + length)
        lock.withLock {
            if (shut) return
            chunks.addLast(copy)
            held += length
            arrived.signalAll()
        }
    }

    /** Blocks until there is something to hand over. -1 once shut and drained. */
    @Throws(InterruptedException::class)
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        lock.withLock {
            while (held == 0) {
                if (shut) return -1
                arrived.await(250L, TimeUnit.MILLISECONDS)
            }
            val head = chunks.peekFirst() ?: return -1
            val take = minOf(length, head.size - headAt)
            System.arraycopy(head, headAt, target, offset, take)
            headAt += take
            held -= take
            if (headAt >= head.size) {
                chunks.removeFirst()
                headAt = 0
            }
            return take
        }
    }

    fun shut() = lock.withLock {
        shut = true
        chunks.clear()
        held = 0
        headAt = 0
        arrived.signalAll()
    }
}

/**
 * Makes one unbroken stream out of a run of separate connections.
 *
 * Everything that reaches [out] is whole 188-byte packets, in order, each one
 * exactly once.
 */
class TsStitcher(
    /**
     * What to do when no exact join can be found. A screen starts again at the
     * next complete picture. The recorder would rather be handed everything
     * and sort it out with its own, older method, which loses nothing - so
     * for it this is true.
     */
    private val handOverWhole: Boolean = false,
    private val out: (ByteArray, Int, Int) -> Unit
) {

    private enum class Mode { PASS, SEEK, RESYNC }

    private var mode = Mode.PASS
    private var carry = EMPTY
    private var synced = false

    /** The last packets passed on - the thing looked for in the next lump. */
    private val tail = ByteArray(TAIL_BYTES)
    private var tailLen = 0

    /** Packets from the new connection still being searched. */
    private var hold = EMPTY
    private var holdLen = 0
    private var scanFrom = 0

    /** True while [hold] still starts where the connection started. */
    private var holdWhole = true
    private var soughtBytes = 0L
    private var resyncBytes = 0L
    private var resyncSince = 0L

    /** The newest timestamp passed on, and the first one ever passed on. */
    var lastPts = -1L
        private set
    private var firstPts = -1L
    private var spanBytes = 0L

    /** The first and newest timestamps seen on the current connection. */
    var burstFirstPts = -1L
        private set
    var arrivingPts = -1L
        private set

    /** Bytes passed on since the current connection opened. */
    var passedThisTurn = 0L
        private set

    var joins = 0
        private set
    var gaps = 0
        private set

    /** How much the portal resent last time, in milliseconds of television. */
    var overlapMs = -1L
        private set

    /** True once the current connection is flowing straight through. */
    val flowing: Boolean get() = mode == Mode.PASS

    @Synchronized
    fun connectionOpened() {
        carry = EMPTY
        synced = false
        holdLen = 0
        scanFrom = 0
        holdWhole = true
        soughtBytes = 0L
        resyncBytes = 0L
        burstFirstPts = -1L
        arrivingPts = -1L
        passedThisTurn = 0L
        // Too little passed on to recognise again: treat it as a first start.
        mode = if (tailLen >= MIN_MATCH_BYTES) Mode.SEEK else Mode.PASS
    }

    /** Roughly how many bytes make a second of this channel. */
    fun bytesPerSecond(): Long {
        if (firstPts < 0L || lastPts < 0L) return ASSUMED_BYTES_PER_SECOND
        val ms = ptsDiff(lastPts, firstPts) / 90L
        if (ms < 2_000L || spanBytes <= 0L) return ASSUMED_BYTES_PER_SECOND
        return (spanBytes * 1000L / ms).coerceIn(50_000L, 6_000_000L)
    }

    /** The overlap never turned up. Stop looking and pick up from here. */
    @Synchronized
    fun stopSeeking() {
        if (mode != Mode.SEEK) return
        settleWithoutMatch(true)
    }

    @Synchronized
    fun feed(buffer: ByteArray, read: Int) {
        if (read <= 0) return
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
        if (!synced) {
            val at = findSync(data)
            if (at < 0) {
                carry = data.copyOfRange((data.size - SYNC_LOOKBACK).coerceAtLeast(0), data.size)
                return
            }
            start = at
            synced = true
        }
        val whole = ((data.size - start) / TS_PACKET) * TS_PACKET
        if (whole <= 0) {
            carry = data.copyOfRange(start, data.size)
            return
        }
        carry = data.copyOfRange(start + whole, data.size)

        // A packet that does not start with the sync byte means the alignment
        // has slipped; find it again rather than passing on rubbish.
        if (data[start] != SYNC_BYTE) {
            synced = false
            carry = data.copyOfRange(start, data.size)
            return
        }

        val first = firstPtsIn(data, start, start + whole)
        if (burstFirstPts < 0L && first >= 0L) burstFirstPts = first
        val newest = newestPtsIn(data, start, start + whole)
        if (newest >= 0L) arrivingPts = newest

        when (mode) {
            Mode.PASS -> pass(data, start, start + whole)
            Mode.SEEK -> seek(data, start, start + whole)
            Mode.RESYNC -> resync(data, start, start + whole)
        }
    }

    private fun pass(data: ByteArray, from: Int, until: Int) {
        val length = until - from
        if (length <= 0) return
        out(data, from, length)
        passedThisTurn += length

        if (length >= TAIL_BYTES) {
            System.arraycopy(data, until - TAIL_BYTES, tail, 0, TAIL_BYTES)
            tailLen = TAIL_BYTES
        } else {
            val keep = minOf(tailLen, TAIL_BYTES - length)
            System.arraycopy(tail, tailLen - keep, tail, 0, keep)
            System.arraycopy(data, from, tail, keep, length)
            tailLen = keep + length
        }

        val newest = newestPtsIn(data, from, until)
        if (newest >= 0L) {
            if (firstPts < 0L) {
                firstPts = newest
                spanBytes = 0L
            }
            lastPts = newest
        }
        if (firstPts >= 0L) spanBytes += length
    }

    /**
     * Looking for the place we left off.
     *
     * The portal serves every connection out of the same stored pieces, so the
     * packets it resends are byte for byte the ones already passed on. Finding
     * the last few dozen of them again gives a join that is exact to the
     * packet, which is the whole difference between this and a stutter.
     */
    private fun seek(data: ByteArray, from: Int, until: Int) {
        val add = until - from
        if (hold.size < holdLen + add) {
            hold = hold.copyOf(maxOf(hold.size * 2, holdLen + add, 512 * 1024))
        }
        System.arraycopy(data, from, hold, holdLen, add)
        holdLen += add
        soughtBytes += add

        val at = findTail(hold, scanFrom, holdLen)
        if (at >= 0) {
            mode = Mode.PASS
            joins++
            if (lastPts >= 0L && burstFirstPts >= 0L) {
                overlapMs = (ptsDiff(lastPts, burstFirstPts) / 90L).coerceAtLeast(0L)
            }
            val rest = at + tailLen
            val end = holdLen
            holdLen = 0
            if (rest < end) pass(hold, rest, end)
            return
        }
        // Only what has not been looked at yet needs looking at next time,
        // less enough to catch a match that straddles two reads.
        scanFrom = (holdLen - tailLen + TS_PACKET).coerceAtLeast(0)

        // Nothing we hold is in this lump at all: it starts after we stopped.
        val startsAhead = lastPts >= 0L && burstFirstPts >= 0L &&
            ptsDiff(burstFirstPts, lastPts) > 0L
        // Or it has run on past where we stopped without the bytes matching.
        val ranPast = lastPts >= 0L && arrivingPts >= 0L &&
            ptsDiff(arrivingPts, lastPts) > PAST_MARGIN_TICKS
        // Or its clock is nowhere near ours - a different feed, or one that has
        // restarted - and no amount of waiting will bring the two together.
        val elsewhere = lastPts >= 0L && burstFirstPts >= 0L &&
            ptsDiff(burstFirstPts, lastPts) < -(if (handOverWhole) ELSEWHERE_RECORDER_TICKS else ELSEWHERE_TICKS)
        if (startsAhead || ranPast || elsewhere || soughtBytes > SEEK_LIMIT_BYTES) {
            settleWithoutMatch(false)
            return
        }

        // A deep lump is mostly television we already have. Keep the recent
        // end of it and let the rest go.
        if (holdLen > HOLD_MAX_BYTES) {
            val drop = ((holdLen - HOLD_KEEP_BYTES) / TS_PACKET) * TS_PACKET
            System.arraycopy(hold, drop, hold, 0, holdLen - drop)
            holdLen -= drop
            scanFrom = (scanFrom - drop).coerceAtLeast(0)
            holdWhole = false
        }
    }

    /**
     * No exact join. Two different things look like that, and only one of them
     * loses any television.
     *
     * The portal hands its buffer back in whole pieces. If we stopped exactly
     * at the end of one piece and it starts us at the beginning of the next,
     * there is no overlap to find - and nothing missing either. That is a
     * clean continuation and is taken whole, from its first packet.
     *
     * Anything else really is a break, and starts again at the first complete
     * picture after the point we had reached.
     */
    private fun settleWithoutMatch(atLiveEdge: Boolean) {
        val end = holdLen
        holdLen = 0
        val known = lastPts >= 0L && burstFirstPts >= 0L
        val delta = if (known) ptsDiff(burstFirstPts, lastPts) else 0L
        // The recorder only takes a piece as following on when it starts
        // strictly after the last one; anything that might repeat a moment
        // goes to its own method instead, which trims repeats by the clock.
        val behindOk = if (handOverWhole) 0L else BEHIND_OK_TICKS
        val follows = known && holdWhole && delta > -behindOk && delta <= AHEAD_OK_TICKS
        if (follows && end > 0) {
            mode = Mode.PASS
            joins++
            overlapMs = 0L
            pass(hold, 0, end)
            return
        }
        if (handOverWhole) {
            mode = Mode.PASS
            gaps++
            overlapMs = 0L
            if (end > 0) pass(hold, 0, end)
            return
        }
        beginResync()
        if (end > 0) resync(hold, 0, end)
        // Nothing usable was held and the feed has gone quiet: take whatever
        // comes next rather than waiting on a picture that is not coming.
        if (atLiveEdge && mode == Mode.RESYNC) resyncSince = steadyNow() - RESYNC_LIMIT_MS
    }

    private fun beginResync() {
        mode = Mode.RESYNC
        gaps++
        overlapMs = 0L
        resyncBytes = 0L
        resyncSince = steadyNow()
    }

    /**
     * No exact join to be had, so start again somewhere a decoder can: the
     * first complete picture after the point we had reached.
     */
    private fun resync(data: ByteArray, from: Int, until: Int) {
        val patient = resyncBytes < RESYNC_LIMIT_BYTES &&
            steadyNow() - resyncSince < RESYNC_LIMIT_MS
        var i = from
        while (i + TS_PACKET <= until) {
            if (data[i] == SYNC_BYTE && (data[i + 1].toInt() and 0x40) != 0) {
                val pts = ptsAt(data, i)
                val fresh = lastPts < 0L || pts < 0L || ptsDiff(pts, lastPts) > 0L
                val startable = if (patient) isKeyframeStart(data, i) else pts >= 0L
                if (fresh && startable) {
                    mode = Mode.PASS
                    pass(data, i, until)
                    return
                }
            }
            i += TS_PACKET
        }
        resyncBytes += (until - from)
    }

    private fun findTail(window: ByteArray, from: Int, until: Int): Int {
        val n = tailLen
        if (n < MIN_MATCH_BYTES || until - from < n) return -1
        val last = until - n
        var i = from
        while (i <= last) {
            if (window[i + 1] == tail[1] && window[i + 2] == tail[2] && window[i + 3] == tail[3]) {
                var k = 4
                while (k < n && window[i + k] == tail[k]) k++
                if (k == n) return i
            }
            i += TS_PACKET
        }
        return -1
    }

    companion object {
        private val EMPTY = ByteArray(0)

        const val TS_PACKET = 188
        private const val SYNC_BYTE: Byte = 0x47
        private const val SYNC_LOOKBACK = TS_PACKET * 4

        /** How many packets are remembered and looked for. */
        private const val TAIL_BYTES = TS_PACKET * 48
        private const val MIN_MATCH_BYTES = TS_PACKET * 8

        private const val PTS_WRAP = 1L shl 33
        private const val PAST_MARGIN_TICKS = 135_000L          // a second and a half
        private const val SEEK_LIMIT_BYTES = 160L * 1024L * 1024L

        /** How much of an unmatched lump is kept while the search goes on. */
        private const val HOLD_MAX_BYTES = 6 * 1024 * 1024
        private const val HOLD_KEEP_BYTES = 4 * 1024 * 1024

        /** How near to where we stopped a new piece must start to count as following on. */
        private const val AHEAD_OK_TICKS = 67_500L              // three quarters of a second
        private const val BEHIND_OK_TICKS = 135_000L            // a second and a half
        private const val ELSEWHERE_TICKS = 300L * 90_000L      // five minutes
        private const val ELSEWHERE_RECORDER_TICKS = 120L * 90_000L
        private const val RESYNC_LIMIT_BYTES = 8L * 1024L * 1024L
        private const val RESYNC_LIMIT_MS = 4_000L
        private const val ASSUMED_BYTES_PER_SECOND = 600_000L

        /** How far [a] is ahead of [b] on a clock that wraps every 26 hours. */
        fun ptsDiff(a: Long, b: Long): Long {
            var d = (a - b) % PTS_WRAP
            if (d > PTS_WRAP / 2) d -= PTS_WRAP
            if (d < -PTS_WRAP / 2) d += PTS_WRAP
            return d
        }

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

        /** The timestamp on a packet, or -1 if it carries none. */
        fun ptsAt(data: ByteArray, packet: Int): Long {
            if (data[packet] != SYNC_BYTE) return -1L
            val unitStart = (data[packet + 1].toInt() and 0x40) != 0
            if (!unitStart) return -1L

            val adaptation = (data[packet + 3].toInt() shr 4) and 0x03
            var payload = packet + 4
            if (adaptation == 2 || adaptation == 0) return -1L
            if (adaptation == 3) payload += (data[packet + 4].toInt() and 0xFF) + 1
            if (payload + 14 > packet + TS_PACKET) return -1L

            if (data[payload].toInt() != 0x00 ||
                data[payload + 1].toInt() != 0x00 ||
                data[payload + 2].toInt() != 0x01
            ) return -1L
            val streamId = data[payload + 3].toInt() and 0xFF
            if (streamId !in 0xC0..0xEF && streamId != 0xBD) return -1L

            val flags = data[payload + 7].toInt() and 0xC0
            if (flags == 0) return -1L
            val p = payload + 9
            if ((data[p].toInt() and 0xF0) == 0) return -1L

            return (((data[p].toLong() and 0x0E) shl 29) or
                ((data[p + 1].toLong() and 0xFF) shl 22) or
                ((data[p + 2].toLong() and 0xFE) shl 14) or
                ((data[p + 3].toLong() and 0xFF) shl 7) or
                ((data[p + 4].toLong() and 0xFE) shr 1))
        }

        private fun firstPtsIn(data: ByteArray, from: Int, until: Int): Long {
            var i = from
            while (i + TS_PACKET <= until) {
                val pts = ptsAt(data, i)
                if (pts >= 0L) return pts
                i += TS_PACKET
            }
            return -1L
        }

        private fun newestPtsIn(data: ByteArray, from: Int, until: Int): Long {
            var i = from
            var best = -1L
            while (i + TS_PACKET <= until) {
                val pts = ptsAt(data, i)
                if (pts >= 0L && (best < 0L || ptsDiff(pts, best) > 0L)) best = pts
                i += TS_PACKET
            }
            return best
        }

        /**
         * Whether a picture a decoder can start from begins in this packet.
         *
         * Either the stream says so itself (the random-access flag), or the
         * video in it opens with picture parameters or a keyframe.
         */
        private fun isKeyframeStart(data: ByteArray, i: Int): Boolean {
            val adaptation = (data[i + 3].toInt() shr 4) and 0x03
            if (adaptation == 2 || adaptation == 0) return false
            var p = i + 4
            if (adaptation == 3) {
                val length = data[i + 4].toInt() and 0xFF
                if (length > 0 && (data[i + 5].toInt() and 0x40) != 0) return true
                p += length + 1
            }
            if (p + 20 >= i + TS_PACKET) return false
            if (data[p].toInt() != 0 || data[p + 1].toInt() != 0 || data[p + 2].toInt() != 1) return false
            if ((data[p + 3].toInt() and 0xFF) !in 0xE0..0xEF) return false
            p += 9 + (data[p + 8].toInt() and 0xFF)
            var q = p
            while (q + 4 < i + TS_PACKET) {
                if (data[q].toInt() == 0 && data[q + 1].toInt() == 0 && data[q + 2].toInt() == 1) {
                    val nal = data[q + 3].toInt() and 0x1F
                    if (nal == 7 || nal == 5) return true
                    q += 3
                } else {
                    q++
                }
            }
            return false
        }
    }
}

/**
 * One channel, fetched in turns and handed to a player as a single stream.
 */
class LineFeed(
    private val url: String,
    private val line: LineShare,
    private val userAgent: String,
    /**
     * Stop trying after this many failures in a row if the channel has never
     * produced anything, so whoever is waiting can try another address. Zero
     * means keep trying for ever.
     */
    private val giveUpAfter: Int = 0,
    /**
     * A connection that has sent nothing for this long is dead, whatever the
     * socket says: hang up and reconnect. Zero leaves that to the read
     * timeout.
     */
    private val quietCutMs: Long = 0L,
    /** Asked before every connection; false means wait. */
    private val mayConnect: () -> Boolean = { true }
) {
    val pipe = BytePipe()

    @Volatile var closed = false
        private set

    /** True once this feed has stopped trying because the channel never opened. */
    @Volatile var gaveUp = false
        private set

    /** How much the player itself is holding, told to us by the screen. */
    @Volatile var playerBufferedMs: Long = 0L

    /** How much television the portal hands back on a fresh connection. */
    @Volatile var depthMs: Long = -1L
        private set

    /**
     * The last few measurements of that. The portal hands back whole pieces,
     * so the figure swings by a piece from one reconnect to the next; planning
     * on the smallest recent one is what keeps a join from being missed.
     */
    private val depths = LongArray(4) { -1L }
    private var depthAt = 0
    private var gaveAt = 0L

    private fun noteDepth(ms: Long) {
        if (ms <= 0L) return
        depths[depthAt % depths.size] = ms
        depthAt++
        var least = Long.MAX_VALUE
        for (d in depths) if (d > 0L && d < least) least = d
        if (least != Long.MAX_VALUE) depthMs = least
    }

    @Volatile var connects = 0
        private set
    @Volatile var joins = 0
        private set
    @Volatile var gaps = 0
        private set
    @Volatile var connected = false
        private set
    @Volatile var everPlayed = false
        private set
    @Volatile var lastError: String = ""
        private set

    private val stitcher = TsStitcher(false) { data, from, length -> pipe.put(data, from, length) }

    private var thread: Thread? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var stream: InputStream? = null
    @Volatile private var letGo = false
    @Volatile private var caughtUp = false
    @Volatile private var turnStartedAt = 0L
    @Volatile private var lastBytesAt = 0L
    @Volatile private var bytesThisTurn = 0L
    private var sampleAt = 0L
    private var samplePts = -1L

    fun start() {
        if (thread != null) return
        line.join(this)
        val worker = Thread({ run() }, "jtv-feed")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    fun close() {
        closed = true
        line.leave(this)
        pipe.shut()
        thread?.interrupt()
        // Called from the screen's own thread, which must never be the one
        // to touch a socket - so the hanging up is done to one side.
        val hangUp = Thread({ cut() }, "jtv-feed-close")
        hangUp.isDaemon = true
        hangUp.start()
    }

    /** What is in hand: the player's own buffer plus what is queued for it. */
    fun reserveMs(): Long = playerBufferedMs + pipe.size().toLong() * 1000L / stitcher.bytesPerSecond()

    private fun sharing(): Boolean = line.count() > 1

    private fun needsLine(): Boolean {
        if (!sharing()) return true
        if (!everPlayed) return true
        return reserveMs() < line.lowWaterMs()
    }

    private fun run() {
        var failures = 0
        while (!closed) {
            // Plenty in hand: stay off the line so somebody else can have it.
            while (!closed && !needsLine()) nap(60L)
            // Somebody else has first call on the line just now.
            while (!closed && !mayConnect()) nap(500L)
            if (closed) break
            if (!line.take(this)) break

            val began = steadyNow()
            val away = if (gaveAt > 0L) began - gaveAt else 0L
            val gapsBefore = stitcher.gaps
            var worked = false
            try {
                worked = readTurn()
            } catch (failure: Exception) {
                if (!letGo && !closed) lastError = failure.javaClass.simpleName + " " + (failure.message ?: "")
                worked = bytesThisTurn > 0L
            } finally {
                connected = false
                cut()
                joins = stitcher.joins
                gaps = stitcher.gaps
                gaveAt = steadyNow()
                line.give(this)
            }
            // A missed join says the portal had less saved than we were away
            // for, whatever it measured last time.
            if (stitcher.gaps > gapsBefore && away > 0L) noteDepth(away * 9L / 10L)
            if (closed) break

            if (worked) {
                failures = 0
                lastError = ""
                if (sharing()) line.noteGulp(steadyNow() - began)
            } else {
                failures++
                if (giveUpAfter > 0 && !everPlayed && failures >= giveUpAfter) {
                    // It never opened. Say so rather than spin: whoever is
                    // reading can move on to another address for the channel.
                    gaveUp = true
                    pipe.shut()
                    break
                }
                if (sharing()) line.noteRefused()
                nap((300L * failures).coerceAtMost(2_500L))
            }
        }
    }

    /** One connection, read until it is time to hand the line on. */
    private fun readTurn(): Boolean {
        letGo = false
        caughtUp = false
        bytesThisTurn = 0L
        samplePts = -1L
        turnStartedAt = steadyNow()
        lastBytesAt = turnStartedAt
        sampleAt = turnStartedAt

        val http = URL(url).openConnection() as HttpURLConnection
        connection = http
        http.connectTimeout = 10_000
        http.readTimeout = 12_000
        http.instanceFollowRedirects = true
        http.setRequestProperty("User-Agent", userAgent)
        // Asked for exactly as the player itself asks: as it is, uncompressed.
        // Left to itself this connection offers to take the stream zipped,
        // and a server that obliges holds bytes back while it packs them.
        http.setRequestProperty("Accept-Encoding", "identity")
        connects++

        val code = http.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val input = http.inputStream
        stream = input
        connected = true
        stitcher.connectionOpened()

        val buffer = ByteArray(64 * 1024)
        while (!closed && !letGo) {
            if (pipe.size() >= PIPE_FULL_BYTES) {
                // The player has all it can hold. With company, that is the
                // cue to hand the line on; alone, just wait for room.
                if (sharing() && stitcher.flowing) break
                nap(30L)
                continue
            }
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            val now = steadyNow()
            lastBytesAt = now
            bytesThisTurn += read
            stitcher.feed(buffer, read)
            if (stitcher.passedThisTurn > 0L) everPlayed = true
            watchPace(now)
            if (shouldLetGo(now)) break
        }
        return bytesThisTurn > 0L
    }

    /**
     * Has the lump finished?
     *
     * While the portal is emptying its buffer down the wire, a second of real
     * time brings many seconds of television. Once that ratio falls to about
     * one for one, we are level with live and there is nothing more to gain by
     * staying connected.
     */
    private fun watchPace(now: Long) {
        if (caughtUp) return
        val pts = stitcher.arrivingPts
        if (pts < 0L) return
        if (samplePts < 0L) {
            samplePts = pts
            sampleAt = now
            return
        }
        val waited = now - sampleAt
        if (waited < PACE_SAMPLE_MS) return
        val advanced = TsStitcher.ptsDiff(pts, samplePts) / 90L
        if (stitcher.flowing && advanced <= waited * 2L) reachedLive(now)
        samplePts = pts
        sampleAt = now
    }

    private fun reachedLive(now: Long) {
        if (caughtUp) return
        caughtUp = true
        val first = stitcher.burstFirstPts
        val newest = stitcher.arrivingPts
        if (first >= 0L && newest >= 0L) {
            // What arrived, less what would have arrived anyway in the time
            // it took, is what the portal had saved up for us.
            val span = TsStitcher.ptsDiff(newest, first) / 90L
            val measured = span - (now - turnStartedAt)
            noteDepth(measured)
        }
    }

    private fun shouldLetGo(now: Long): Boolean {
        if (!sharing()) return false
        if (now - turnStartedAt < MIN_TURN_MS) return false
        if (now - turnStartedAt > GULP_LIMIT_MS) return true
        return caughtUp && stitcher.flowing && stitcher.passedThisTurn > 0L
    }

    /**
     * Called a few times a second from the line's own thread, because the
     * reading thread may be sitting inside a read with nothing arriving - and
     * nothing arriving is itself the sign that the lump is finished.
     */
    internal fun tick(now: Long) {
        if (!connected || closed || letGo) return
        val quiet = now - lastBytesAt
        if (quietCutMs > 0L && quiet >= quietCutMs) {
            // The socket is open and nothing is coming down it. The portal
            // has stopped sending without saying so; a fresh connection picks
            // up where this one left off.
            letGo = true
            cut()
            return
        }
        if (bytesThisTurn > 0L && quiet >= QUIET_MEANS_LIVE_MS) {
            // Level with live and still no sign of where we left off.
            if (!stitcher.flowing) stitcher.stopSeeking()
            reachedLive(now)
        }
        if (shouldLetGo(now) || (sharing() && pipe.size() >= PIPE_FULL_BYTES && stitcher.flowing)) {
            letGo = true
            cut()
        }
    }

    private fun cut() {
        val input = stream
        val http = connection
        stream = null
        connection = null
        runCatching { http?.disconnect() }
        runCatching { input?.close() }
    }

    private fun nap(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (interrupted: InterruptedException) {
            // Closing; the loop conditions notice.
        }
    }

    /** One line for the corner of the screen while this is being proved. */
    fun summary(): String {
        val depth = if (depthMs > 0L) (depthMs / 1000L).toString() + "s" else "?"
        return "reserve " + (reserveMs() / 1000L) + "s  portal buffer " + depth +
            "  joins " + joins + "  gaps " + gaps + "  connects " + connects +
            (if (connected) "  ON LINE" else "") +
            (if (lastError.isNotBlank()) "  " + lastError else "")
    }

    companion object {
        /** About fifteen seconds of an HD channel queued for the player. */
        private const val PIPE_FULL_BYTES = 12 * 1024 * 1024
        private const val MIN_TURN_MS = 900L
        private const val GULP_LIMIT_MS = 25_000L
        private const val PACE_SAMPLE_MS = 350L
        private const val QUIET_MEANS_LIVE_MS = 450L
    }
}
