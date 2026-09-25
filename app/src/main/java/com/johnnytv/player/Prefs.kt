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

    /**
     * The name the customer actually types, when they have been given one.
     *
     * The real line is what everything else in the app uses, so that is what
     * gets stored; this is kept beside it so the site can be asked again at
     * each start. That is what lets somebody be moved to another panel without
     * being told anything - their box notices by itself.
     */
    var friendlyName: String
        get() = sp.getString(KEY_FRIENDLY, "") ?: ""
        set(value) = sp.edit().putString(KEY_FRIENDLY, value.trim()).apply()

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

    // ---------- remembered logins ----------

    /**
     * ONE LINE, ONE TAP, NEXT TIME.
     *
     * Every login that has worked on this box is kept here, so signing out is no
     * longer a punishment: the name comes back as a button on the sign-in screen
     * and the password comes with it. Nothing is ever drawn on screen but the
     * name, and none of it leaves the device - the password for the line being
     * watched was already stored here to keep the session alive, so nothing is
     * exposed that was not exposed a moment ago.
     */
    data class SavedLogin(val server: String, val username: String, val password: String)

    val savedLogins: List<SavedLogin>
        get() = try {
            val out = ArrayList<SavedLogin>()
            val array = org.json.JSONArray(sp.getString(KEY_LOGINS, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val one = array.optJSONObject(i) ?: continue
                val user = one.optString("u").trim()
                if (user.isBlank()) continue
                out.add(SavedLogin(one.optString("s").trim(), user, one.optString("p")))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }

    /** Newest first, one entry per username, and a short list - this is a television, not a vault. */
    fun rememberLogin(server: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        val list = ArrayList(savedLogins.filter { !it.username.equals(username, true) })
        list.add(0, SavedLogin(server, username, password))
        while (list.size > MAX_LOGINS) list.removeAt(list.size - 1)
        writeLogins(list)
    }

    fun forgetLogin(username: String) {
        writeLogins(savedLogins.filter { !it.username.equals(username, true) })
    }

    private fun writeLogins(list: List<SavedLogin>) {
        val array = org.json.JSONArray()
        for (one in list) {
            array.put(
                JSONObject()
                    .put("s", one.server)
                    .put("u", one.username)
                    .put("p", one.password)
            )
        }
        sp.edit().putString(KEY_LOGINS, array.toString()).apply()
    }


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
    /**
     * Whether this box offers its recordings to the other televisions in the
     * house. Off unless somebody switches it on: a box with nothing to share
     * has no business listening on the network.
     */
    var shareRecordings: Boolean
        get() = sp.getBoolean(KEY_SHARE, false)
        set(value) = sp.edit().putBoolean(KEY_SHARE, value).apply()

    /**
     * A BOX THAT IS NOT ON THIS NETWORK.
     *
     * Televisions find each other at home by announcing themselves, which is
     * how the Google TV upstairs sees the Shield. Those announcements go no
     * further than the house: on mobile data, or through Tailscale from
     * somewhere else entirely, nothing is announced and nothing is found.
     *
     * So the address can be written down instead - a Tailscale address, which
     * is the same wherever either end happens to be.
     */
    var awayBox: String
        get() = sp.getString(KEY_AWAY_BOX, "") ?: ""
        set(value) = sp.edit().putString(KEY_AWAY_BOX, value.trim()).apply()

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

    /*
     * FAVOURITES THAT STAY REMOVED.
     *
     * These were kept in a string set, which is the one preference type
     * Android tells you not to treat as ordinary storage: the set handed back
     * is the very object the system is holding, and edits made around it are
     * not guaranteed to be written. In practice that showed as channels a
     * customer had removed reappearing after a restart - the screen had
     * updated, the file had not.
     *
     * They are one string now, a line per favourite, which has none of that
     * behaviour. Anything stored the old way is read once and carried over, so
     * nobody loses the list they already had, and the old key is cleared so
     * there is only one answer to where favourites live.
     */
    fun isFavourite(kind: Kind, id: String): Boolean =
        favouriteSet().contains(favouriteKey(kind, id))

    fun toggleFavourite(kind: Kind, id: String): Boolean {
        val list = ArrayList(favouriteSet())
        val key = favouriteKey(kind, id)
        val nowFavourite = if (list.contains(key)) {
            list.remove(key); false
        } else {
            list.add(key); true
        }
        // Written with commit rather than apply: a television box that loses
        // power, or is unplugged straight after somebody tidies their list,
        // must not come back with the removals undone.
        sp.edit().putString(KEY_FAVS_TEXT, list.joinToString("\n")).commit()
        return nowFavourite
    }

    fun favouriteIds(kind: Kind): Set<String> {
        val prefix = "${kind.name}:"
        return favouriteSet().filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    /** Every favourite, in the order they were added. */
    private fun favouriteSet(): List<String> {
        val text = sp.getString(KEY_FAVS_TEXT, null)
        if (text != null) return text.split("\n").filter { it.isNotBlank() }

        // First run after the change: take what the old set holds, write it
        // the new way, and forget the old key.
        val old = runCatching { sp.getStringSet(KEY_FAVS, emptySet()) ?: emptySet() }
            .getOrDefault(emptySet())
        val carried = old.filter { it.isNotBlank() }.sorted()
        sp.edit()
            .putString(KEY_FAVS_TEXT, carried.joinToString("\n"))
            .remove(KEY_FAVS)
            .commit()
        return carried
    }

    /**
     * What is actually stored, in plain words, for the row in Settings.
     *
     * Favourites that will not go away are either a screen not refreshing or a
     * file not changing, and from the sofa those look identical. This says
     * which: the raw contents of both the current key and the old one it
     * replaced.
     */
    fun favouritesReport(): String {
        val text = sp.getString(KEY_FAVS_TEXT, null)
        val old = runCatching { sp.getStringSet(KEY_FAVS, emptySet()) ?: emptySet() }
            .getOrDefault(emptySet())
        val lines = text?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        return buildString {
            append("Stored now: ").append(lines.size).append('\n')
            append("(some may point at channels the portal has removed;\n")
            append("those are cleared at the next sync)\n")
            if (lines.isNotEmpty()) append(lines.take(8).joinToString("\n")).append('\n')
            append("\nOld store: ")
            append(if (text == null) "not yet carried over" else "cleared")
            append(" (").append(old.size).append(")\n")
            if (old.isNotEmpty()) append(old.take(8).joinToString("\n"))
        }
    }

    /**
     * Drops favourites whose channel or film no longer exists.
     *
     * Portals renumber their streams, and when they do, every favourite
     * pointing at an old id becomes invisible - present in the count, absent
     * from the list, impossible to select and therefore impossible to remove.
     * After a sync has brought back a real catalogue, anything not in it is
     * cleared out.
     *
     * Only ever called with a catalogue that actually loaded: pruning against
     * an empty list would wipe somebody's favourites the first time a portal
     * was slow to answer.
     */
    fun pruneFavourites(kind: Kind, existingIds: Set<String>) {
        if (existingIds.isEmpty()) return
        val prefix = "${kind.name}:"
        val all = favouriteSet()
        val kept = all.filter { !it.startsWith(prefix) || existingIds.contains(it.removePrefix(prefix)) }
        if (kept.size == all.size) return
        sp.edit().putString(KEY_FAVS_TEXT, kept.joinToString("\n")).commit()
    }

    /** Empties the list outright - the way out of a list that has gone wrong. */
    fun clearFavourites() {
        sp.edit().putString(KEY_FAVS_TEXT, "").remove(KEY_FAVS).commit()
    }

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
        const val KEY_FRIENDLY = "friendly_name"
        const val KEY_FAVS = "favourites"                 // the old string set
        const val KEY_FAVS_TEXT = "favourites_list"        // one per line
        const val KEY_POSITIONS = "positions"
        const val KEY_CONTINUE = "continue_watching"
        const val KEY_SEARCHES = "recent_searches"
        const val KEY_LOGINS = "saved_logins"
        const val MAX_LOGINS = 8
        const val KEY_REC_VOLUME = "recording_volume"
        const val KEY_WEATHER_TOWN = "weather_town"
        const val KEY_ACCOUNTS = "known_accounts"
        const val KEY_SHARE = "share_recordings"
        const val KEY_AWAY_BOX = "away_box"

        /** More lines than anyone has; a list that can never grow for ever. */
        const val MAX_ACCOUNTS = 6
        const val KEY_WEATHER_LAT = "weather_lat"
        const val KEY_WEATHER_LON = "weather_lon"
        const val KEY_JUMPY = "jumpy_channels"

        /** How many stalls before a channel gets the deeper buffer for good. */
        const val STALLS_BEFORE_DEEPER_BUFFER = 2
    }
}
