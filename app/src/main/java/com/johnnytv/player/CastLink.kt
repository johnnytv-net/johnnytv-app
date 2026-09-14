package com.johnnytv.player

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * THE LETTERBOX
 *
 * The website and this app never speak to each other - they are two devices
 * with no line between them. So johnnytv.net holds one short line per customer
 * in each direction: what the phone asked for, and what this television is
 * showing. The phone drops a note, this app picks it up a few seconds later.
 *
 * That second direction is the quiet half and the more useful one. On a line
 * that allows a single connection, somebody opening the website while their
 * television is on gets a dead screen and no explanation. Because this app says
 * what it is watching, the website can say "your TV is on TSN" instead - which
 * turns the most common support call into something that answers itself.
 *
 * Nothing here handles a password. The latch below is derived from details both
 * halves already hold, and the worst anyone achieves by defeating it is turning
 * somebody else's television over.
 */
object CastLink {

    /** Lives beside the web player, on the address the customer already uses. */
    const val URL = "http://johnnytv.net/jtv-cast.php"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * The latch, and it must agree with the website exactly.
     *
     * Plain 32-bit wrapping multiplication, which is what Math.imul does in the
     * browser. An ordinary JavaScript multiply would drift here: the products
     * exceed what a double can hold precisely, so the two halves would quietly
     * disagree and nothing would ever arrive. This is the one piece of code in
     * the project that has to be identical in two languages.
     */
    fun token(user: String, pass: String): String {
        val seed = "$user:$pass"
        var a = 0x811c9dc5.toInt()
        for (ch in seed) {
            a = a xor ch.code
            a *= 0x01000193.toInt()
        }
        var c = 0x9e3779b9.toInt()
        for (i in seed.indices.reversed()) {
            c = c xor seed[i].code
            c *= 0x85ebca6b.toInt()
        }
        return String.format("%08x%08x", a, c)
    }

    data class Command(val id: String, val name: String, val seq: Long)

    private fun ready(prefs: Prefs) = prefs.username.isNotBlank() && prefs.password.isNotBlank()

    /** Blocking. Whatever is waiting, or null - including on any failure. */
    fun peek(prefs: Prefs): Command? {
        if (!ready(prefs)) return null
        return try {
            val url = URL + "?peek=" + enc(prefs.username) + "&t=" + token(prefs.username, prefs.password)
            val request = Request.Builder().url(url)
                .header("User-Agent", Config.USER_AGENT)
                .header("Cache-Control", "no-cache")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) return null
                val cmd = json.optJSONObject("cmd") ?: return null
                val id = cmd.optString("id", "")
                if (id.isBlank()) return null
                Command(id, cmd.optString("name", ""), cmd.optLong("seq", 0L))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Taken. Clearing it stops the same note being obeyed twice after a restart. */
    fun ack(prefs: Prefs) {
        post(prefs, FormBody.Builder().add("action", "ack"))
    }

    /** What this television is showing, so the website can stop guessing. */
    fun report(prefs: Prefs, streamId: String, name: String) {
        post(
            prefs,
            FormBody.Builder()
                .add("action", "status")
                .add("id", streamId)
                .add("name", name.take(80))
        )
    }

    private fun post(prefs: Prefs, builder: FormBody.Builder) {
        if (!ready(prefs)) return
        try {
            val form = builder
                .add("user", prefs.username)
                .add("t", token(prefs.username, prefs.password))
                .build()
            val request = Request.Builder().url(URL)
                .post(form)
                .header("User-Agent", Config.USER_AGENT)
                .build()
            http.newCall(request).execute().use { /* the answer is of no interest */ }
        } catch (e: Exception) {
            // The letterbox being unreachable must never affect television.
        }
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
