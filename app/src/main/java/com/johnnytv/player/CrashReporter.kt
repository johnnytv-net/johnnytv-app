package com.johnnytv.player

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the last uncaught exception to a file so it can be read back in Settings.
 *
 * Without this, diagnosing a crash on someone else's TV means asking them to plug it
 * into a computer. With it, they can just read the error off the screen.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        val when_ = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date())
        val text = buildString {
            appendLine("JohnnyTV crash")
            appendLine(when_)
            appendLine("thread: ${thread.name}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine()
            append(stack.toString())
        }
        File(context.filesDir, FILE).writeText(text)
    }

    fun lastCrash(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }
}
