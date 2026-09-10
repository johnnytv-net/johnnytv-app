package com.johnnytv.player

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The contents of your hosted config.json. Every field is optional except "server".
 *
 * "server" may be a plain address OR base64-encoded, so the portal address is not
 * sitting in a public file as readable text.
 *
 * {
 *   "server": "aHR0cDovL3lvdXItcG9ydGFsLmNvbTo4MDgw",
 *   "notice": "",
 *   "latest_version_code": 1,
 *   "download_url": "https://example.com/JohnnyTV.apk",
 *   "force_update": false,
 *   "renewal_contact": "Text 250-555-0123 to renew"
 * }
 */
data class RemoteConfig(
    /** The first configured service, kept so older code keeps compiling. */
    val server: String,
    /** Every configured service, in the order they should be tried. */
    val servers: List<String>,
    val notice: String,
    val latestVersionCode: Long,
    val downloadUrl: String,
    val forceUpdate: Boolean,
    /** Channel-name to logo-URL overrides written straight into config.json. */
    val logos: Map<String, String> = emptyMap(),
    /** Where the separate logos file lives. Blank means "next to config.json". */
    val logosUrl: String = "",
    /** How a customer renews, shown when their line is about to run out. */
    val renewalContact: String = ""
)

object RemoteConfigLoader {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Blocking. Returns null on any failure - the caller falls back to the cached server. */
    fun fetch(url: String): RemoteConfig? {
        if (url.isBlank()) return null
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", Config.USER_AGENT)
                .header("Cache-Control", "no-cache")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.trim()
                if (body.isNullOrEmpty()) return null
                val json = JSONObject(body)
                val list = ArrayList<String>()
                // "servers" wins when present; "server" keeps single-portal files working.
                json.optJSONArray("servers")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val one = decodeServer(arr.optString(i, "").trim())
                        if (one.isNotBlank() && !list.contains(one)) list.add(one)
                    }
                }
                val single = decodeServer(json.optString("server", "").trim())
                if (single.isNotBlank() && !list.contains(single)) list.add(single)

                val logos = LinkedHashMap<String, String>()
                json.optJSONObject("logos")?.let { block ->
                    for (key in block.keys()) {
                        val logoUrl = block.optString(key, "").trim()
                        if (logoUrl.isNotBlank()) logos[key] = logoUrl
                    }
                }

                RemoteConfig(
                    server = list.firstOrNull().orEmpty(),
                    servers = list,
                    notice = json.optString("notice", "").trim(),
                    latestVersionCode = json.optLong("latest_version_code", 0L),
                    downloadUrl = json.optString("download_url", "").trim(),
                    forceUpdate = json.optBoolean("force_update", false),
                    logos = logos,
                    logosUrl = json.optString("logos_url", "").trim(),
                    renewalContact = json.optString("renewal_contact", "").trim()
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Accepts either a plain address or a base64-encoded one. Anything that already
     * looks like a URL is passed straight through, so both forms keep working.
     */
    fun resolve(raw: String): String = decodeServer(raw)

    private fun decodeServer(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
        return try {
            val decoded = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
            if (decoded.startsWith("http://", true) || decoded.startsWith("https://", true)) {
                decoded
            } else {
                raw
            }
        } catch (e: Exception) {
            raw
        }
    }
}
