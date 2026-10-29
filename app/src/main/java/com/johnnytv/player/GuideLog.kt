package com.johnnytv.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WHAT THE GUIDE DID, WRITTEN DOWN.
 *
 * The same idea as the playback log, for the same reason: a Firestick
 * scrolling down the guide went black and the app vanished, with no error and
 * no crash report, which means the box killed it rather than the app failing.
 * The only way to know what it was doing at that moment is to have written it
 * down as it went.
 *
 * Every category change, every request for a channel's listings and how long
 * it took, every preview started and stopped, every warning from the box that
 * memory is short - with the time and how much room there was. When the app
 * disappears mid-sentence, the last line is the answer, read off the
 * television in Settings.
 *
 * Overwritten each time the guide opens: a note about the last visit, not a
 * history.
 */
object GuideLog {

    private const val FILE = "last_guide.txt"
    private const val LIMIT = 400

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
    private val lines = ArrayList<String>()

    @Synchronized
    fun start(context: Context, what: String) {
        lines.clear()
        add(context, "--- " + what + " ---")
        add(
            context,
            "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
                "android ${android.os.Build.VERSION.RELEASE}, " +
                (if (BoxMemory.isLight(context)) "light box" else "full box") +
                ", " + BoxMemory.snapshot(context)
        )
    }

    @Synchronized
    fun add(context: Context, line: String) {
        if (lines.size >= LIMIT) return
        lines.add(clock.format(Date()) + "  " + line)
        // Written every time rather than at the end, because there may not be
        // an end: if the app is killed, the file is all that survives.
        runCatching {
            File(context.filesDir, FILE).writeText(lines.joinToString("\n"))
        }
    }

    /** A line with the memory position on the end, for the moments that matter. */
    fun addWithMemory(context: Context, line: String) {
        add(context, line + "  [" + BoxMemory.snapshot(context) + "]")
    }

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
