package com.johnnytv.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WHAT THE PLAYER DID, WRITTEN DOWN.
 *
 * A recording that will not play has cost half a dozen builds of guesswork:
 * the files were checked and found perfect, the sizes were right, the codec
 * ordinary, and still the screen went black and the app vanished without an
 * error or a crash report - which means whatever is failing is below the level
 * where the app can see it.
 *
 * So the app writes down what it is doing as it does it. Every state the
 * player reports, every part it moves to, every error, with the time. When it
 * disappears mid-sentence the last line is the answer, and it can be read off
 * the television rather than guessed at from another room.
 *
 * Small, plain, and overwritten each time a recording is opened: this is a
 * note for the next five minutes, not a history.
 */
object PlaybackLog {

    private const val FILE = "last_playback.txt"
    private const val LIMIT = 300

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)
    private val lines = ArrayList<String>()

    @Synchronized
    fun start(context: Context, what: String) {
        lines.clear()
        add(context, "--- " + what + " ---")
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

    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
