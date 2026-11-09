package com.johnnytv.player

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * THE GUIDE LOG, SENT HOME.
 *
 * The guide log answers "what was the box doing when the guide died" - but
 * only if somebody photographs a television. The one customer whose Firestick
 * kills the guide is never the one standing next to it with the right person
 * on the phone, so the answer has sat on boxes we cannot see.
 *
 * So the box posts it home instead. Every app start looks at the last guide
 * visit: a log that ends with its goodbye line was a visit the viewer ended,
 * and is nobody's business. One that stops mid-sentence is a visit the box
 * ended - that log is set aside and posted to the website, with the version
 * and the friendly name on top, where it can be read without anybody's
 * television or anybody's afternoon.
 *
 * Quiet about everything: a box with no internet keeps the report and tries
 * again on the next start, a website having a moment loses nothing, and no
 * customer ever sees any of it.
 */
object GuideReporter {

    /** A death waiting to be posted. Survives restarts until it gets through. */
    private const val PENDING = "guide_report_pending.txt"

    /** https first; the certificate is poorly, so plain http is the fallback. */
    private val POSTBOXES = arrayOf(
        "https://johnnytv.net/jtv-guide-report.php",
        "http://johnnytv.net/jtv-guide-report.php"
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Call once per app start, off the main thread.
     *
     * Two jobs, deliberately separate: notice a death and set the log aside,
     * then try to deliver whatever is set aside. Separate, because delivery
     * can fail for days on end and the evidence must not be lost meanwhile -
     * and because the guide log itself is overwritten the moment the guide is
     * next opened, so it has to be rescued before then.
     */
    fun checkAndSend(context: Context) {
        val app = context.applicationContext
        rescue(app)
        deliver(app)
    }

    private fun rescue(app: Context) {
        val log = GuideLog.read(app) ?: return
        val lastLine = log.trim().lines().lastOrNull().orEmpty()
        // "guide left" is onStop, "guide closed" is onDestroy: either one means
        // the visit ended the ordinary way. A log ending on anything else means
        // the process was taken mid-visit - which is exactly the Firestick
        // guide death, seen from the inside.
        val endedProperly = lastLine.contains("guide left") || lastLine.contains("guide closed")
        // A couple of lines is the guide barely opened; not worth a report.
        if (endedProperly || log.lines().size < 3) return

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        val head = StringBuilder()
            .append("version: ").append(Config.USER_AGENT).append('\n')
            .append("box: ").append(Prefs(app).username.ifBlank { "(not signed in)" }).append('\n')
            .append("noticed: ").append(stamp).append('\n')
            .append('\n')
        runCatching {
            File(app.filesDir, PENDING).writeText(head.toString() + log)
            // Cleared so the same death is never reported twice. The next
            // guide visit was going to overwrite it anyway.
            GuideLog.clear(app)
        }
    }

    private fun deliver(app: Context) {
        val pending = File(app.filesDir, PENDING)
        if (!pending.exists()) return
        val body = runCatching { pending.readText() }.getOrNull() ?: return
        if (body.isBlank()) {
            runCatching { pending.delete() }
            return
        }
        for (url in POSTBOXES) {
            val sent = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Config.USER_AGENT)
                    .post(FormBody.Builder().add("log", body).build())
                    .build()
                http.newCall(request).execute().use { response ->
                    // Only the site's own yes counts: a hotel login page or a
                    // provider error page also answers 200, and treating that
                    // as delivered would throw the report away unread.
                    response.isSuccessful && (response.body?.string() ?: "").trim() == "ok"
                }
            }.getOrDefault(false)
            if (sent) {
                runCatching { pending.delete() }
                return
            }
        }
        // Not today, then. The report stays for the next start.
    }
}
