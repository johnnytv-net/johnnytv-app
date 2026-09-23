package com.johnnytv.player

import android.content.Context
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WHAT HAS BEEN KEPT.
 *
 * A recording is two files sitting next to each other: the video itself, and a
 * small note saying what it is. The note matters more than it looks - a folder
 * of files called 1758153600.ts tells nobody anything, and a television has no
 * file manager to go digging with. So every recording carries its own channel
 * name, title and times, and the list on screen is built by reading those notes
 * rather than by keeping a separate index that could drift out of step with
 * what is actually on the disk.
 *
 * Everything lives in the app's own folder on external storage. That needs no
 * permission on any Android this app runs on, and it means an uninstall takes
 * the recordings with it rather than leaving gigabytes behind on someone's
 * television for ever.
 */
object Recordings {

    /** Below this much free space we refuse to start, and stop if we reach it. */
    const val FREE_SPACE_FLOOR = 300L * 1024 * 1024

    /** Nothing runs longer than this unattended. A forgotten recording fills a disk. */
    const val MAX_LENGTH_MS = 4L * 60 * 60 * 1000

    fun folder(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "Recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * A file name that cannot surprise anybody.
     *
     * Channel names arrive from the portal and contain whatever the provider felt
     * like typing - colons, slashes, emoji. The date goes first so the folder
     * sorts itself, and the name is only there to make the file recognisable if
     * somebody does go looking with a USB cable.
     */
    fun newFile(context: Context, channelName: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = channelName
            .replace(Regex("[^A-Za-z0-9 ._-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .take(40)
            .ifBlank { "channel" }
        return File(folder(context), "$stamp-$safe.ts")
    }

    fun freeSpace(context: Context): Long = try {
        val stat = StatFs(folder(context).absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (e: Exception) {
        Long.MAX_VALUE   // Unknowable is not the same as full; let it try.
    }

    // ---------- the note beside each recording ----------

    private fun noteFor(video: File) = File(video.absolutePath + ".json")

    fun writeNote(
        video: File,
        channelName: String,
        programme: String,
        startedAt: Long,
        endedAt: Long,
        finished: Boolean
    ) {
        runCatching {
            val json = JSONObject()
                .put("channel", channelName)
                .put("programme", programme)
                .put("started", startedAt)
                .put("ended", endedAt)
                .put("finished", finished)
            noteFor(video).writeText(json.toString())
        }
    }

    data class Recording(
        val file: File,
        val channel: String,
        val programme: String,
        val startedAt: Long,
        val endedAt: Long,
        val finished: Boolean
    ) {
        val bytes: Long get() = file.length()

        /** What to call it on screen: the programme if we knew one, else the channel. */
        val title: String get() = if (programme.isNotBlank()) programme else channel

        val lengthMs: Long get() = (endedAt - startedAt).coerceAtLeast(0L)
    }

    /**
     * Everything on the disk, newest first.
     *
     * A video with no note still appears - it is somebody's recording and losing
     * it silently would be worse than showing it under a plain name. A note with
     * no video does not: that is leftovers from a recording that never wrote
     * anything, and it gets cleared up here.
     */
    fun list(context: Context): List<Recording> {
        val dir = folder(context)
        val files = dir.listFiles() ?: return emptyList()

        files.filter { it.name.endsWith(".json") }
            .filter { !File(it.absolutePath.removeSuffix(".json")).exists() }
            .forEach { runCatching { it.delete() } }

        return files
            .filter { it.isFile && it.name.endsWith(".ts") && it.length() > 0 }
            .map { video ->
                val note = runCatching { JSONObject(noteFor(video).readText()) }.getOrNull()
                Recording(
                    file = video,
                    channel = note?.optString("channel").orEmpty().ifBlank { video.nameWithoutExtension },
                    programme = note?.optString("programme").orEmpty(),
                    startedAt = note?.optLong("started") ?: video.lastModified(),
                    endedAt = note?.optLong("ended") ?: video.lastModified(),
                    finished = note?.optBoolean("finished") ?: true
                )
            }
            .sortedByDescending { it.startedAt }
    }

    fun delete(recording: Recording) {
        runCatching { recording.file.delete() }
        runCatching { noteFor(recording.file).delete() }
    }

    // ---------- how it reads on screen ----------

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    fun lengthText(ms: Long): String {
        val minutes = (ms / 60000L).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    fun whenText(at: Long): String =
        SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(at))
}
