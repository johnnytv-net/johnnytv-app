package com.johnnytv.player

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * RECORDING - WHERE IT ALL LIVES
 *
 * A recording is a folder of ten-minute .ts chunks plus a line in an index kept
 * in the app's own storage. Two deliberate choices, both about what survives:
 *
 *  - Chunks rather than one enormous file. A power cut, a drive pulled out of
 *    the back of the box, a battery-less Firestick unplugged at the wall: with
 *    one file all of that is a broken recording. With chunks you lose the few
 *    seconds that were in flight and keep the rest, and they play back as one
 *    continuous programme because the player is handed the list.
 *
 *  - The index lives on the device, not on the drive. Pull the stick out and
 *    the app still knows what it recorded and can say so, rather than showing
 *    an empty screen as though nothing ever happened.
 *
 * Everything is written to the app's own folder on whichever volume is chosen
 * (Android/data/com.johnnytv.player/files/...). That is the one place on
 * removable storage an app may write with no permission prompt at all, which
 * matters on a television where granting a permission means finding a settings
 * screen with a remote.
 */

/** A drive or card the app can record to. */
data class StorageTarget(
    val id: String,
    val label: String,
    val dir: File,
    val removable: Boolean
) {
    val freeBytes: Long
        get() = runCatching { dir.usableSpace }.getOrDefault(0L)

    /** Roughly how many hours of HD fit in what is left. */
    val freeHours: Double
        get() = freeBytes.toDouble() / BYTES_PER_HOUR

    companion object {
        /** About 3 GB an hour, which is a fair average for these portals' HD feeds. */
        const val BYTES_PER_HOUR: Double = 3.0 * 1024 * 1024 * 1024
    }
}

/** A hole in a recording: when it happened and how long the feed was away. */
data class Gap(val at: Long, val seconds: Int)

data class Recording(
    val id: String,
    var title: String,
    var channel: String,
    var streamId: String,
    var startedAt: Long,
    var plannedEnd: Long,
    var endedAt: Long = 0L,
    var dirPath: String = "",
    var parts: MutableList<String> = ArrayList(),
    var bytes: Long = 0L,
    var gaps: MutableList<Gap> = ArrayList(),
    var reconnects: Int = 0,
    var keep: Boolean = false,
    var watched: Boolean = false,
    var state: String = STATE_RECORDING,
    var note: String = ""
) {

    val isRecording: Boolean get() = state == STATE_RECORDING

    /** How long was actually captured, in milliseconds. */
    fun lengthMs(): Long {
        val end = if (endedAt > 0L) endedAt else System.currentTimeMillis()
        return (end - startedAt).coerceAtLeast(0L)
    }

    fun lostSeconds(): Int = gaps.sumOf { it.seconds }

    /** The chunk files in order, as the player wants them. */
    fun files(): List<File> {
        val dir = File(dirPath)
        return parts.map { File(dir, it) }.filter { it.exists() && it.length() > 0L }
    }

    fun toJson(): JSONObject {
        val gapArray = JSONArray()
        for (gap in gaps) gapArray.put(JSONObject().put("at", gap.at).put("s", gap.seconds))
        return JSONObject()
            .put("id", id)
            .put("t", title)
            .put("c", channel)
            .put("sid", streamId)
            .put("s", startedAt)
            .put("pe", plannedEnd)
            .put("e", endedAt)
            .put("d", dirPath)
            .put("p", JSONArray(parts))
            .put("b", bytes)
            .put("g", gapArray)
            .put("r", reconnects)
            .put("k", keep)
            .put("w", watched)
            .put("st", state)
            .put("n", note)
    }

    companion object {
        fun fromJson(o: JSONObject): Recording {
            val parts = ArrayList<String>()
            val partArray = o.optJSONArray("p") ?: JSONArray()
            for (i in 0 until partArray.length()) parts.add(partArray.optString(i, ""))

            val gaps = ArrayList<Gap>()
            val gapArray = o.optJSONArray("g") ?: JSONArray()
            for (i in 0 until gapArray.length()) {
                val g = gapArray.optJSONObject(i) ?: continue
                gaps.add(Gap(g.optLong("at", 0L), g.optInt("s", 0)))
            }

            return Recording(
                id = o.optString("id", ""),
                title = o.optString("t", "Recording"),
                channel = o.optString("c", ""),
                streamId = o.optString("sid", ""),
                startedAt = o.optLong("s", 0L),
                plannedEnd = o.optLong("pe", 0L),
                endedAt = o.optLong("e", 0L),
                dirPath = o.optString("d", ""),
                parts = parts.filter { it.isNotBlank() }.toMutableList(),
                bytes = o.optLong("b", 0L),
                gaps = gaps,
                reconnects = o.optInt("r", 0),
                keep = o.optBoolean("k", false),
                watched = o.optBoolean("w", false),
                state = o.optString("st", STATE_DONE),
                note = o.optString("n", "")
            )
        }
    }
}

const val STATE_RECORDING = "recording"
const val STATE_DONE = "done"
const val STATE_FAILED = "failed"

/**
 * The list of recordings, kept as one small JSON file in the app's own storage.
 *
 * Every write goes through here on whatever thread is calling, so it is
 * synchronized: the recorder updates its row every few seconds from its own
 * thread while the screen may be reading the same file.
 */
object RecordingStore {

    private const val FILE = "recordings.json"

    @Synchronized
    fun all(context: Context): List<Recording> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val out = ArrayList<Recording>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                out.add(Recording.fromJson(o))
            }
            // Newest first, but anything still recording is pinned to the top -
            // it is the one row somebody is actually waiting on.
            out.sortedWith(compareByDescending<Recording> { it.isRecording }.thenByDescending { it.startedAt })
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun find(context: Context, id: String): Recording? = all(context).firstOrNull { it.id == id }

    @Synchronized
    fun put(context: Context, recording: Recording) {
        val list = ArrayList(all(context).filter { it.id != recording.id })
        list.add(recording)
        write(context, list)
    }

    @Synchronized
    fun update(context: Context, id: String, change: (Recording) -> Unit) {
        val list = ArrayList(all(context))
        val found = list.firstOrNull { it.id == id } ?: return
        change(found)
        write(context, list)
    }

    /** Removes the row and the video with it. */
    @Synchronized
    fun delete(context: Context, id: String) {
        val list = ArrayList(all(context))
        val found = list.firstOrNull { it.id == id } ?: return
        list.remove(found)
        runCatching { File(found.dirPath).deleteRecursively() }
        write(context, list)
    }

    /**
     * Makes room by dropping watched recordings, oldest first.
     *
     * Anything marked Keep is never touched, and neither is anything still
     * recording or never watched - a recording deleted before somebody has seen
     * it is worse than a recording that failed to start, because they were
     * counting on it.
     */
    @Synchronized
    fun freeUpSpace(context: Context, needBytes: Long, target: File) {
        var free = runCatching { target.usableSpace }.getOrDefault(0L)
        if (free >= needBytes) return
        val candidates = all(context)
            .filter { !it.keep && it.watched && !it.isRecording }
            .sortedBy { it.startedAt }
        for (old in candidates) {
            if (free >= needBytes) return
            val size = old.bytes
            delete(context, old.id)
            free += size
        }
    }

    private fun write(context: Context, list: List<Recording>) {
        val array = JSONArray()
        for (item in list) array.put(item.toJson())
        runCatching { File(context.filesDir, FILE).writeText(array.toString()) }
    }
}

/**
 * Which drives this box can record to, best first.
 *
 * getExternalFilesDirs hands back one folder per volume the app may write to
 * without asking anyone for permission - internal storage first, then any USB
 * stick or card. A null entry means a volume that has been unplugged since
 * Android last looked, so those are dropped rather than offered.
 */
object Storage {

    const val FOLDER = "JohnnyTV Recordings"

    fun targets(context: Context): List<StorageTarget> {
        val out = ArrayList<StorageTarget>()
        val dirs = runCatching { context.getExternalFilesDirs(null) }.getOrNull() ?: emptyArray()
        dirs.filterNotNull().forEachIndexed { index, base ->
            val removable = index > 0 || runCatching { Environment.isExternalStorageRemovable(base) }
                .getOrDefault(false)
            val dir = File(base, FOLDER)
            out.add(
                StorageTarget(
                    id = base.absolutePath,
                    label = labelFor(base, index, removable),
                    dir = dir,
                    removable = removable
                )
            )
        }
        // A plugged-in drive is what somebody wants to record to; internal
        // storage is the safety net underneath it.
        return out.sortedByDescending { if (it.removable) it.freeBytes else -1L }
    }

    /** The drive the customer chose, if it is still plugged in, else the best one going. */
    fun chosen(context: Context): StorageTarget? {
        val prefs = Prefs(context)
        val available = targets(context)
        val saved = prefs.recordingVolume
        return available.firstOrNull { it.id == saved } ?: available.firstOrNull()
    }

    /** Internal storage, which is always there - the fallback when a drive vanishes. */
    fun internal(context: Context): StorageTarget? =
        targets(context).firstOrNull { !it.removable }

    private fun labelFor(base: File, index: Int, removable: Boolean): String {
        if (!removable) return "This device"
        // /storage/1A2B-3C4D/Android/data/... - the volume id is the only name
        // Android gives us without asking for storage permission.
        val path = base.absolutePath
        val marker = "/storage/"
        val id = if (path.startsWith(marker)) {
            path.removePrefix(marker).substringBefore('/')
        } else {
            "USB"
        }
        return if (id.equals("emulated", true) || id.isBlank()) "USB drive" else "USB drive ($id)"
    }
}
