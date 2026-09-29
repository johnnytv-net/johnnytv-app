package com.johnnytv.player

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * WHAT A SERVICE'S CHANNEL LIST LOOKS LIKE, DECIDED HERE RATHER THAN THERE.
 *
 * One of the services hands over nine thousand channels in a hundred and thirty
 * folders - half of it looping box sets, most of the rest split so finely that
 * the sidebar is longer than anything in it. The panel can decide which channels
 * a line receives, but not what the folders are called, how many there are, or
 * what order they come in. Those belong to whoever runs the service.
 *
 * So they are decided here instead, from config.json, per service:
 *
 *   "lineups": {
 *     "http://edge.sb": {
 *       "hide":  ["24/7 Adult", "NFHS Network", "Adults*", "USA Latin*"],
 *       "merge": [
 *         { "into": "CANADA", "from": ["CA: Canada General", "CA: Canada News"] },
 *         { "into": "SPORTS", "from": ["USA Sports", "Sport Cricket"] }
 *       ],
 *       "move":  [ { "into": "PPV", "channels": ["USA UFC Fight Pass"] } ],
 *       "order": ["USA Local Channels ( Full List )", "SPORTS", "CANADA"]
 *     }
 *   }
 *
 * A name ending in * matches anything starting that way. Everything is matched
 * on the folder's name, never its number, because the numbers belong to the
 * panel and change without warning.
 *
 * A service with no entry here is left exactly as it arrives - which is the
 * point. Only the service named gets touched, and the others cannot be affected
 * by anything written for it.
 */
data class Lineup(
    /** Folders to drop entirely, with their channels. */
    val hide: List<String> = emptyList(),
    /** Several folders becoming one. */
    val merge: List<Merge> = emptyList(),
    /** Individual channels sent to a folder of their own, wherever the panel filed them. */
    val move: List<Move> = emptyList(),
    /** The order the survivors appear in. Anything unlisted follows, as it arrived. */
    val order: List<String> = emptyList()
) {
    data class Merge(val into: String, val from: List<String>)
    data class Move(val into: String, val channels: List<String>)

    val isEmpty: Boolean
        get() = hide.isEmpty() && merge.isEmpty() && move.isEmpty() && order.isEmpty()
}

object Lineups {

    private const val FILE = "lineups.json"

    /** Everything config.json had to say, by service address. */
    @Volatile
    private var rules: Map<String, Lineup> = emptyMap()

    @Volatile
    private var loaded = false

    /**
     * Keep what config.json said, so a start with no internet still shows the
     * lineup the customer saw yesterday rather than nine thousand channels.
     */
    fun remember(context: Context, config: RemoteConfig?) {
        if (config == null) return
        rules = config.lineups
        runCatching { File(context.filesDir, FILE).writeText(write(config.lineups)) }
        loaded = true
    }

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val file = File(context.filesDir, FILE)
            if (file.exists()) rules = read(JSONObject(file.readText()))
        }
    }

    /**
     * The rules for one service, or null when it has none.
     *
     * Addresses are compared on host and port only: config.json may carry a
     * trailing slash or a different scheme from the one the client ended up
     * using, and a lineup that silently stops applying is worse than no lineup.
     */
    fun forServer(server: String): Lineup? {
        val wanted = hostKey(server)
        if (wanted.isBlank()) return null
        for ((address, lineup) in rules) {
            if (hostKey(address) == wanted) return if (lineup.isEmpty) null else lineup
        }
        return null
    }

    private fun hostKey(address: String): String {
        var s = address.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.substringAfter("://")
        s = s.substringBefore('/')
        return s
    }

    /**
     * The categories and channels as the lineup says they should be.
     *
     * Returns them unchanged when there is nothing to do, so the ordinary case
     * costs one comparison.
     */
    fun apply(
        lineup: Lineup?,
        categories: List<Category>,
        streams: List<StreamItem>
    ): Pair<List<Category>, List<StreamItem>> {
        if (lineup == null || lineup.isEmpty || categories.isEmpty()) return categories to streams

        // 1. What is being dropped.
        val hidden = categories.filter { category -> lineup.hide.any { matches(category.name, it) } }
            .map { it.id }
            .toHashSet()

        // 2. Where everything ends up. A merge target is a folder of our own,
        //    with an id that cannot collide with one the panel issued.
        val movedTo = HashMap<String, String>()          // old category id -> new id
        val mergedNames = LinkedHashMap<String, String>() // new id -> its name
        for (merge in lineup.merge) {
            if (merge.into.isBlank() || merge.from.isEmpty()) continue
            val id = "jtv:" + merge.into
            var used = false
            for (category in categories) {
                if (hidden.contains(category.id)) continue
                if (movedTo.containsKey(category.id)) continue
                if (merge.from.any { matches(category.name, it) }) {
                    movedTo[category.id] = id
                    used = true
                }
            }
            if (used) mergedNames[id] = merge.into
        }

        // 3. The channels, with the folders they now belong to. A channel named
        //    in a move rule goes where the rule says, whatever folder the panel
        //    put it in and whatever its own folder has been merged into - so a
        //    single UFC channel can leave a sports folder of four hundred and
        //    sit with the other one, without either folder being disturbed.
        val movedChannels = LinkedHashMap<String, String>()   // new id -> its name
        val keptStreams = ArrayList<StreamItem>(streams.size)
        for (stream in streams) {
            if (hidden.contains(stream.categoryId)) continue
            val rule = lineup.move.firstOrNull { r -> r.channels.any { matches(stream.name, it) } }
            if (rule != null) {
                val id = "jtv:" + rule.into
                movedChannels[id] = rule.into
                keptStreams.add(stream.copy(categoryId = id))
                continue
            }
            val moved = movedTo[stream.categoryId]
            keptStreams.add(if (moved == null) stream else stream.copy(categoryId = moved))
        }

        // 4. The folders themselves: merge targets in the position of the first
        //    folder that fed them, so an unordered lineup still reads sensibly.
        val out = ArrayList<Category>()
        val seenMerges = HashSet<String>()
        for (category in categories) {
            if (hidden.contains(category.id)) continue
            val moved = movedTo[category.id]
            if (moved == null) {
                out.add(category)
            } else if (seenMerges.add(moved)) {
                out.add(Category(moved, mergedNames[moved] ?: category.name))
            }
        }

        // 4b. A folder that exists only because channels were moved into it.
        for ((id, name) in movedChannels) {
            if (out.none { it.id == id }) out.add(Category(id, name))
        }

        // 5. The order asked for, with anything unlisted following as it arrived.
        if (lineup.order.isEmpty()) return out to keptStreams
        val rank = HashMap<String, Int>()
        lineup.order.forEachIndexed { at, name -> rank[name.trim().uppercase()] = at }
        val last = lineup.order.size
        val ordered = out.sortedBy { rank[it.name.trim().uppercase()] ?: last }
        return ordered to keptStreams
    }

    /** "USA Latin*" matches "USA Latin TELEMUNDO"; anything else is the whole name. */
    private fun matches(name: String, pattern: String): Boolean {
        val n = name.trim()
        val p = pattern.trim()
        if (p.isEmpty()) return false
        return if (p.endsWith("*")) {
            n.startsWith(p.dropLast(1).trim(), ignoreCase = true)
        } else {
            n.equals(p, ignoreCase = true)
        }
    }

    // ---- config.json <-> disk -------------------------------------------------

    fun read(block: JSONObject): Map<String, Lineup> {
        val out = LinkedHashMap<String, Lineup>()
        for (address in block.keys()) {
            val one = block.optJSONObject(address) ?: continue
            val hide = ArrayList<String>()
            one.optJSONArray("hide")?.let { for (i in 0 until it.length()) hide.add(it.optString(i)) }
            val order = ArrayList<String>()
            one.optJSONArray("order")?.let { for (i in 0 until it.length()) order.add(it.optString(i)) }
            val merge = ArrayList<Lineup.Merge>()
            one.optJSONArray("merge")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val into = m.optString("into", "").trim()
                    val from = ArrayList<String>()
                    m.optJSONArray("from")?.let { f -> for (j in 0 until f.length()) from.add(f.optString(j)) }
                    if (into.isNotBlank() && from.isNotEmpty()) merge.add(Lineup.Merge(into, from))
                }
            }
            val move = ArrayList<Lineup.Move>()
            one.optJSONArray("move")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val into = m.optString("into", "").trim()
                    val channels = ArrayList<String>()
                    m.optJSONArray("channels")?.let { c -> for (j in 0 until c.length()) channels.add(c.optString(j)) }
                    if (into.isNotBlank() && channels.isNotEmpty()) move.add(Lineup.Move(into, channels))
                }
            }
            val lineup = Lineup(hide.filter { it.isNotBlank() }, merge, move, order.filter { it.isNotBlank() })
            if (!lineup.isEmpty) out[address] = lineup
        }
        return out
    }

    private fun strings(list: List<String>): org.json.JSONArray {
        val a = org.json.JSONArray()
        for (s in list) a.put(s)
        return a
    }

    private fun write(map: Map<String, Lineup>): String {
        val root = JSONObject()
        for ((address, lineup) in map) {
            val one = JSONObject()
            one.put("hide", strings(lineup.hide))
            one.put("order", strings(lineup.order))
            val merges = org.json.JSONArray()
            for (m in lineup.merge) {
                merges.put(JSONObject().put("into", m.into).put("from", strings(m.from)))
            }
            one.put("merge", merges)
            val moves = org.json.JSONArray()
            for (m in lineup.move) {
                moves.put(JSONObject().put("into", m.into).put("channels", strings(m.channels)))
            }
            one.put("move", moves)
            root.put(address, one)
        }
        return root.toString()
    }
}
