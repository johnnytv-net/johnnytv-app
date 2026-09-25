package com.johnnytv.player

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * EASY LOGINS.
 *
 * The panels hand out usernames like 908008efg and passwords like 809656425,
 * which nobody can read down a telephone and nobody can type on a remote
 * control. So a customer is given their own name instead - the one from the
 * e-transfer, lowercase and run together - with one shared password, and the
 * site turns that into the real line.
 *
 * Only the site knows both halves. The customer never sees the real
 * credentials and the panel never sees the friendly ones, which also means
 * somebody can be moved to a different panel by changing one line on the site:
 * their box asks again at every start, so it follows without anybody being
 * told anything.
 *
 * Everything here is best-effort. If the site cannot be reached, or the name
 * means nothing to it, sign-in carries on exactly as it always did - so the
 * real credentials keep working, and a customer set up before any of this is
 * unaffected.
 */
object FriendlyLogin {

    /** Where the lookup lives. On the same site as the newspaper sign-in. */
    private const val LOOKUP = "https://johnnytv.net/jtv-line.php"

    data class RealLine(val server: String, val username: String, val password: String)

    /**
     * Asks the site who this is. Returns null for "no idea", which is not an
     * error - it simply means the typed pair are real credentials already.
     */
    fun lookUp(name: String, password: String): RealLine? {
        if (name.isBlank() || password.isBlank()) return null
        // A real Xtream username is a long code; a friendly name is a person's
        // name. Asking the site about obvious codes would only waste a second
        // on every sign-in.
        if (name.length > 24) return null

        return try {
            val url = LOOKUP +
                "?u=" + URLEncoder.encode(name.trim().lowercase(), "UTF-8") +
                "&p=" + URLEncoder.encode(password, "UTF-8")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", Config.USER_AGENT)

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val answer = JSONObject(body)
            if (!answer.optBoolean("ok", false)) return null

            val username = answer.optString("username", "")
            val realPassword = answer.optString("password", "")
            if (username.isBlank() || realPassword.isBlank()) return null

            RealLine(
                server = answer.optString("server", ""),
                username = username,
                password = realPassword
            )
        } catch (e: Exception) {
            // The site being down must never stop somebody signing in.
            null
        }
    }
}
