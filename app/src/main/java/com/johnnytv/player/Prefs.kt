package com.johnnytv.player

import android.content.Context
import org.json.JSONObject

class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("johnnytv", Context.MODE_PRIVATE)

    // ---------- account ----------

    var server: String
        get() = sp.getString(KEY_SERVER, "") ?: ""
        set(value) = sp.edit().putString(KEY_SERVER, value).apply()

    var username: String
        get() = sp.getString(KEY_USER, "") ?: ""
        set(value) = sp.edit().putString(KEY_USER, value).apply()

    var password: String
        get() = sp.getString(KEY_PASS, "") ?: ""
        set(value) = sp.edit().putString(KEY_PASS, value).apply()

    /** A server typed in through the hidden support screen beats config.json on this device. */
    var manualServer: String
        get() = sp.getString(KEY_MANUAL, "") ?: ""
        set(value) = sp.edit().putString(KEY_MANUAL, value).apply()

    val isLoggedIn: Boolean
        get() = server.isNotBlank() && username.isNotBlank()

    fun saveCredentials(server: String, username: String, password: String) {
        sp.edit()
            .putString(KEY_SERVER, server)
            .putString(KEY_USER, username)
            .putString(KEY_PASS, password)
            .apply()
        // Every line signed into joins the list, so a recording set on it from a
        // phone is still collected after the box moves to another one.
        rememberCurrentAccount()
    }

    /** Signs the customer out but keeps the server, so they only re-enter user + password. */
    fun clearCredentials() {
        sp.edit().remove(KEY_USER).remove(KEY_PASS).apply()
    }

    fun client(): XtreamClient = XtreamClient(server, username, password)

    /** One line this box has been signed into. */
    data class Account(val server: String, val username: String, val password: String) {
        fun client(): XtreamClient = XtreamClient(server, username, password)
    }

    /**
     * EVERY LINE THIS BOX HAS KNOWN.
     *
     * A recording belongs to the line that asked for it, not to whichever one
     * the television happens to be showing. Set a recording on the Dino line
     * from work while the house watches Edge, and the box has to know Dino's
     * details to collect it and to record it - so it keeps them, on the box,
     * exactly where it already kept the one it is using.
     *
     * Nothing leaves the device. This is the same information the sign-in
     * screen stored; there is simply more than one of it.
     */
    fun knownAccounts(): List<Account> {
        val out = ArrayList<Account>()
        runCatching {
            val array = org.json.JSONArray(sp.getString(KEY_ACCOUNTS, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val u = o.optString("u", "")
                if (u.isBlank()) continue
                out.add(Account(o.optString("s", ""), u, o.optString("p", "")))
            }
        }
        // The one in use is always among them, even before it has been saved.
        if (username.isNotBlank() && out.none { it.username.equals(username, true) }) {
            out.add(0, Account(server, username, password))
        }
        return out
    }

    /** Adds, or refreshes, the account in use now. */
    fun rememberCurrentAccount() {
        if (username.isBlank()) return
        val list = knownAccounts().filter { !it.username.equals(username, true) }.toMutableList()
        list.add(0, Account(server, username, password))
        val array = org.json.JSONArray()
        for (a in list.take(MAX_ACCOUNTS)) {
            array.put(org.json.JSONObject().put("s", a.server).put("u", a.username).put("p", a.password))
        }
        sp.edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    fun accountNamed(user: String): Account? =
        knownAccounts().firstOrNull { it.username.equals(user, true) }

    // ---------- catalogue ----------

    /** The last channel-logo overrides downloaded, kept so they survive offline. */
    var logoPack: String
        get() = sp.getString(KEY_LOGOS, "") ?: ""
        set(value) = sp.edit().putString(KEY_LOGOS, value).apply()

    /** Live TV opens as a list rather than a grid. */
    var liveListView: Boolean
        get() = sp.getBoolean(KEY_LIST_VIEW, false)
        set(value) = sp.edit().putBoolean(KEY_LIST_VIEW, value).apply()

    /**
     * Whether the highlighted channel plays silently in list view. On by default,
     * but a preview holds a connection, so anyone on a single-connection line who
     * runs into trouble can turn it off.
     */
    var previewEnabled: Boolean
        get() = sp.getBoolean(KEY_PREVIEW, true)
        set(value) = sp.edit().putBoolean(KEY_PREVIEW, value).apply()

    /**
     * When this customer's line runs out, or 0 if the portal never said. Kept on
     * the device so the reminder still appears when the portal is unreachable.
     */
    var expiresAt: Long
        get() = sp.getLong(KEY_EXPIRES, 0L)
        set(value) = sp.edit().putLong(KEY_EXPIRES, value).apply()

    /** The day the expiry reminder was last shown, so it appears once a day. */
    var expiryShownOn: Long
        get() = sp.getLong(KEY_EXPIRY_SHOWN, 0L)
        set(value) = sp.edit().putLong(KEY_EXPIRY_SHOWN, value).apply()

    /** How to renew, taken from config.json so it changes without a new build. */
    var renewalContact: String
        get() = sp.getString(KEY_RENEWAL, "") ?: ""
        set(value) = sp.edit().putString(KEY_RENEWAL, value).apply()

    /**
     * The message from JohnnyTV that has already been read.
     *
     * The message itself is the record, not a number beside it - so a new
     * message appears simply because it is different, and there is nothing to
     * remember to bump when writing one.
     */
    var messageRead: String
        get() = sp.getString(KEY_MESSAGE_READ, "") ?: ""
        set(value) = sp.edit().putString(KEY_MESSAGE_READ, value).apply()

    /** The last message we were given, so it survives config.json being unreachable. */
    var message: String
        get() = sp.getString(KEY_MESSAGE, "") ?: ""
        set(value) = sp.edit().putString(KEY_MESSAGE, value).apply()

    /**
     * Which drive recordings go to, remembered as the volume's own path so a
     * stick plugged back in later is recognised as the same one.
     */
    /**
     * The town for the weather, when the address lookup got it wrong.
     *
     * Blank means "work it out", which is the case for nearly everybody.
     */
    var weatherTown: String
        get() = sp.getString(KEY_WEATHER_TOWN, "") ?: ""
        set(value) = sp.edit().putString(KEY_WEATHER_TOWN, value).apply()

    var weatherLatitude: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_WEATHER_LAT, 0L))
        set(value) = sp.edit().putLong(KEY_WEATHER_LAT, java.lang.Double.doubleToRawLongBits(value)).apply()

    var weatherLongitude: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_WEATHER_LON, 0L))
        set(value) = sp.edit().putLong(KEY_WEATHER_LON, java.lang.Double.doubleToRawLongBits(value)).apply()

    var recordingVolume: String
        get() = sp.getString(KEY_REC_VOLUME, "") ?: ""
        set(value) = sp.edit().putString(KEY_REC_VOLUME, value).apply()

    /**
     * Channels that have stalled on this line before.
     *
     * A channel that buffers once is bad luck; one that buffers twice is a
     * channel this connection cannot keep up with at the normal settings, so it
     * is given a deeper head start from then on. Remembered rather than
     * re-learned every time, because the second stall is the one the viewer
     * notices and there is no reason to make them sit through it twice.
     */
    fun isJumpy(streamId: String): Boolean = jumpySet().contains(streamId)

    fun noteStall(streamId: String): Boolean {
        if (streamId.isBlank()) return false
        val counted = sp.getInt(stallKey(streamId), 0) + 1
        sp.edit().putInt(stallKey(streamId), counted).apply()
        if (counted < STALLS_BEFORE_DEEPER_BUFFER) return false
        val set = HashSet(jumpySet())
        if (set.add(streamId)) sp.edit().putStringSet(KEY_JUMPY, set).apply()
        return true
    }

    private fun stallKey(streamId: String) = "stalls:" + streamId

    private fun jumpySet(): Set<String> = sp.getStringSet(KEY_JUMPY, emptySet()) ?: emptySet()

    var lastSync: Long
        get() = sp.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC, value).apply()

    // ---------- favourites ----------

    private fun favouriteKey(kind: Kind, id: String) = "${kind.name}:$id"

    fun isFavourite(kind: Kind, id: String): Boolean =
        favouriteSet().contains(favouriteKey(kind, id))

    fun toggleFavourite(kind: Kind, id: String): Boolean {
        val set = HashSet(favouriteSet())
        val key = favouriteKey(kind, id)
        val nowFavourite = if (set.contains(key)) {
            set.remove(key); false
        } else {
            set.add(key); true
        }
        sp.edit().putStringSet(KEY_FAVS, set).apply()
        return nowFavourite
    }

    fun favouriteIds(kind: Kind): Set<String> {
        val prefix = "${kind.name}:"
        return favouriteSet().filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    private fun favouriteSet(): Set<String> = sp.getStringSet(KEY_FAVS, emptySet()) ?: emptySet()

    // ---------- continue watching ----------

    /** Playback position in milliseconds, keyed by kind + id. Live is never stored. */
    fun savePosition(kind: Kind, id: String, positionMs: Long, durationMs: Long) {
        if (kind == Kind.LIVE || positionMs < 60_000L) return
        // Finished (or nearly) - drop it rather than offering to resume the credits.
        if (durationMs > 0 && positionMs > durationMs - 120_000L) {
            removePosition(kind, id)
            return
        }
        val map = positionMap()
        map.put(favouriteKey(kind, id), positionMs)
        sp.edit().putString(KEY_POSITIONS, map.toString()).apply()
        pushRecent(KEY_CONTINUE, favouriteKey(kind, id), 30)
    }

    fun position(kind: Kind, id: String): Long = positionMap().optLong(favouriteKey(kind, id), 0L)

    fun removePosition(kind: Kind, id: String) {
        val map = positionMap()
        map.remove(favouriteKey(kind, id))
        sp.edit().putString(KEY_POSITIONS, map.toString()).apply()
        val remaining = recentList(KEY_CONTINUE).filter { it != favouriteKey(kind, id) }
        sp.edit().putString(KEY_CONTINUE, remaining.joinToString("\n")).apply()
    }

    /** Ids of things part-watched, most recent first, for the given kind. */
    fun continueWatching(kind: Kind): List<String> {
        val prefix = "${kind.name}:"
        return recentList(KEY_CONTINUE).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

    private fun positionMap(): JSONObject = try {
        JSONObject(sp.getString(KEY_POSITIONS, "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    // ---------- recent searches ----------

    fun addRecentSearch(term: String) {
        val cleaned = term.trim()
        if (cleaned.length < 2) return
        pushRecent(KEY_SEARCHES, cleaned, 12)
    }

    fun recentSearches(): List<String> = recentList(KEY_SEARCHES)

    fun clearRecentSearches() = sp.edit().remove(KEY_SEARCHES).apply()

    // ---------- shared list helpers ----------

    private fun recentList(key: String): List<String> =
        (sp.getString(key, "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun pushRecent(key: String, value: String, limit: Int) {
        val list = ArrayList(recentList(key))
        list.remove(value)
        list.add(0, value)
        while (list.size > limit) list.removeAt(list.size - 1)
        sp.edit().putString(key, list.joinToString("\n")).apply()
    }

    fun clearAll() = sp.edit().clear().apply()

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_MANUAL = "manual_server"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_EXPIRY_SHOWN = "expiry_shown_on"
        const val KEY_RENEWAL = "renewal_contact"
        const val KEY_MESSAGE = "message"
        const val KEY_MESSAGE_READ = "message_read"
        const val KEY_LOGOS = "logo_pack"
        const val KEY_LIST_VIEW = "live_list_view"
        const val KEY_PREVIEW = "channel_preview"
        const val KEY_FAVS = "favourites"
        const val KEY_POSITIONS = "positions"
        const val KEY_CONTINUE = "continue_watching"
        const val KEY_SEARCHES = "recent_searches"
        const val KEY_REC_VOLUME = "recording_volume"
        const val KEY_WEATHER_TOWN = "weather_town"
        const val KEY_ACCOUNTS = "known_accounts"

        /** More lines than anyone has; a list that can never grow for ever. */
        const val MAX_ACCOUNTS = 6
        const val KEY_WEATHER_LAT = "weather_lat"
        const val KEY_WEATHER_LON = "weather_lon"
        const val KEY_JUMPY = "jumpy_channels"

        /** How many stalls before a channel gets the deeper buffer for good. */
        const val STALLS_BEFORE_DEEPER_BUFFER = 2
    }
}
