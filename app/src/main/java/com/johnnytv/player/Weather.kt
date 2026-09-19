package com.johnnytv.player

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * THE WEATHER
 *
 * A small thing at the right of the home screen: what it is outside, and where
 * "outside" is taken to be.
 *
 * Two decisions worth writing down.
 *
 * The town comes from the box's own address rather than from asking. A
 * television is the worst place to make somebody type the name of their town
 * with a remote, and a question on first run is a question every customer has
 * to answer before the app is any use to them. The address gets it right nearly
 * always, and the times it does not - somebody on a VPN, mostly - there is a
 * box in Settings to put it straight. Anyone who has typed their town once is
 * never asked again.
 *
 * And when the lookup fails, nothing appears at all. No error, no dash, no
 * empty box: the bar simply looks the way it did before this existed. Weather
 * on a television is a nicety, and a nicety that announces its own failure is
 * worse than one that quietly stays away.
 */
object Weather {

    /** What the home screen needs to draw one line. */
    data class Now(
        val town: String,
        val degrees: Int,
        val icon: String
    )

    private const val LOOKUP_TOWN = "http://ip-api.com/json/?fields=city,lat,lon"
    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"

    /** Weather does not change in a minute, and neither does anybody's town. */
    private const val GOOD_FOR_MS = 30L * 60L * 1000L

    @Volatile
    private var cached: Now? = null
    @Volatile
    private var fetchedAt = 0L

    /** The last reading, if it is recent enough to still be true. */
    fun lastKnown(): Now? {
        val held = cached ?: return null
        return if (System.currentTimeMillis() - fetchedAt < GOOD_FOR_MS) held else null
    }

    /**
     * Fetches the weather. Call from a background thread; returns null on any
     * failure at all, which the home screen treats as "show nothing".
     */
    fun fetch(context: Context): Now? {
        lastKnown()?.let { return it }

        val prefs = Prefs(context)
        val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        // Where. The customer's own answer wins over the address lookup, since
        // they only ever set it when the lookup was wrong.
        var town = prefs.weatherTown
        var latitude: Double
        var longitude: Double

        if (town.isNotBlank() && prefs.weatherLatitude != 0.0) {
            latitude = prefs.weatherLatitude
            longitude = prefs.weatherLongitude
        } else {
            val located = runCatching {
                val body = get(http, LOOKUP_TOWN) ?: return@runCatching null
                val json = JSONObject(body)
                Triple(
                    json.optString("city", ""),
                    json.optDouble("lat", 0.0),
                    json.optDouble("lon", 0.0)
                )
            }.getOrNull() ?: return null
            if (located.second == 0.0 && located.third == 0.0) return null
            town = located.first
            latitude = located.second
            longitude = located.third
        }

        // What. Open-Meteo needs no account and no key, which matters for an app
        // that is handed to customers - there is no quota to run out and nothing
        // of mine for them to depend on.
        val url = FORECAST +
            "?latitude=" + latitude +
            "&longitude=" + longitude +
            "&current=temperature_2m,weather_code" +
            "&timezone=auto"

        val reading = runCatching {
            val body = get(http, url) ?: return@runCatching null
            val current = JSONObject(body).optJSONObject("current") ?: return@runCatching null
            val degrees = Math.round(current.optDouble("temperature_2m", Double.NaN)).toInt()
            val code = current.optInt("weather_code", -1)
            Now(town.ifBlank { "" }, degrees, iconFor(code))
        }.getOrNull() ?: return null

        cached = reading
        fetchedAt = System.currentTimeMillis()
        return reading
    }

    /**
     * Finds a town by name, for somebody correcting the guess.
     *
     * Returns its proper name and where it is, so the home screen shows the
     * town as the weather service spells it rather than as it was typed.
     */
    fun findTown(typed: String): Triple<String, Double, Double>? {
        val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&name=" +
            java.net.URLEncoder.encode(typed, "UTF-8")
        return runCatching {
            val body = get(http, url) ?: return@runCatching null
            val results = JSONObject(body).optJSONArray("results") ?: return@runCatching null
            if (results.length() == 0) return@runCatching null
            val first = results.getJSONObject(0)
            Triple(
                first.optString("name", typed),
                first.optDouble("latitude", 0.0),
                first.optDouble("longitude", 0.0)
            )
        }.getOrNull()
    }

    /** Forces the next look to go out to the network - after the town is changed. */
    fun forget() {
        cached = null
        fetchedAt = 0L
    }

    private fun get(http: OkHttpClient, url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Config.USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    /**
     * One character for the sky.
     *
     * The codes are the standard WMO set. Grouped rather than translated one for
     * one - nobody watching television needs to know the difference between
     * light drizzle and moderate drizzle.
     */
    private fun iconFor(code: Int): String = when (code) {
        0 -> "☀"
        1, 2 -> "⛅"
        3 -> "☁"
        45, 48 -> "🌫"
        in 51..57 -> "🌦"
        in 61..67 -> "🌧"
        in 71..77 -> "❄"
        in 80..82 -> "🌧"
        in 85..86 -> "❄"
        in 95..99 -> "⛈"
        else -> "☁"
    }
}
