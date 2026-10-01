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
    val order: List<String> = emptyList(),
    /**
     * Folders to drop from the series list, and from the film list.
     *
     * These get the blunt treatment rather than the arranging above, because
     * what wants doing to them is blunt. Half of what a portal sends here is a
     * foreign-language shelf holding three titles, or a "Server 4" holding
     * nothing whatsoever. There is nothing to merge and nothing to reorder -
     * they simply should not be on the screen.
     */
    val hideSeries: List<String> = emptyList(),
    val hideMovies: List<String> = emptyList(),
    /**
     * Individual channels to drop, wherever the panel filed them.
     *
     * Some panels put a signpost at the top of every folder - a row called
     * "##### [UK] GENERAL #####" that is not a channel and plays nothing.
     * One pattern takes all of them out at once, and the folders themselves
     * are left exactly as they were.
     */
    val hideChannels: List<String> = emptyList(),
    /**
     * The order of channels inside one folder.
     *
     * A panel files a league folder in whatever order it reached them: a
     * dozen numbered game feeds, then the teams, then another league. Named
     * here, the channels matching the first pattern come first, then the
     * second, and anything unmatched follows as it arrived. Nothing moves
     * between folders; this only arranges what is already inside one.
     */
    val sort: List<Sort> = emptyList()
) {
    data class Merge(val into: String, val from: List<String>)
    data class Move(val into: String, val channels: List<String>)
    data class Sort(val folder: String, val first: List<String>)

    val isEmpty: Boolean
        get() = hide.isEmpty() && merge.isEmpty() && move.isEmpty() && order.isEmpty() &&
            hideSeries.isEmpty() && hideMovies.isEmpty() && hideChannels.isEmpty() && sort.isEmpty()
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
    /**
     * True when the rules that just arrived are not the rules we had.
     *
     * The channel list is only arranged while it is being fetched, and a fetched
     * list is kept for a day. So a rule written this afternoon would otherwise
     * sit unused until tomorrow, on a screen showing yesterday's arrangement,
     * with nothing to say why. Whoever re-fetches the catalogue clears this.
     */
    @Volatile
    var rulesChanged = false
        private set

    fun changeHandled() {
        rulesChanged = false
    }

    fun remember(context: Context, config: RemoteConfig?) {
        if (config == null) return
        val incoming = config.lineups
        val before = write(rules)
        val after = write(incoming)
        if (before != after) rulesChanged = true
        rules = incoming
        runCatching { File(context.filesDir, FILE).writeText(after) }
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
        if (server.isBlank()) return null
        for ((address, lineup) in rules) {
            if (sameService(address, server)) return if (lineup.isEmpty) null else lineup
        }
        return null
    }

    /**
     * Whether two addresses mean the same service.
     *
     * The address in config.json and the address the box actually signed in
     * with are written by different hands. One is copied off a panel, the other
     * typed into a support screen at half past nine on a Saturday, and they
     * arrive with and without a scheme, with and without a port, with and
     * without a trailing slash, in any mixture of case. Demanding they match
     * character for character gives a lineup that silently does nothing - which
     * on a television looks exactly like a broken app, and cannot be told apart
     * from one without reading the source.
     *
     * So the host has to match, and the port only has to match when both sides
     * bothered to name one. That keeps edge.bz:8080 distinct from anything else
     * living on edge.bz, while letting edge.sb and edge.sb:80 be the single
     * service they obviously are.
     */
    private fun sameService(a: String, b: String): Boolean {
        val hostA = hostOf(a)
        if (hostA.isBlank() || hostA != hostOf(b)) return false
        val portA = portOf(a)
        val portB = portOf(b)
        return portA.isBlank() || portB.isBlank() || portA == portB
    }

    private fun authority(address: String): String {
        var s = address.trim().lowercase()
        if (s.isEmpty()) return ""
        s = s.substringAfter("://")
        s = s.substringBefore('/')
        return s.removePrefix("www.")
    }

    private fun hostOf(address: String): String = authority(address).substringBefore(':')

    private fun portOf(address: String): String {
        val a = authority(address)
        return if (a.contains(':')) a.substringAfter(':') else ""
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

        // 4c. The order of channels inside a folder, where one was asked for.
        var arrangedStreams: List<StreamItem> = keptStreams
        for (rule in lineup.sort) {
            if (rule.folder.isBlank() || rule.first.isEmpty()) continue
            val folders = out.filter { matches(it.name, rule.folder) }.map { it.id }.toHashSet()
            if (folders.isNotEmpty()) arrangedStreams = sortWithin(arrangedStreams, folders, rule.first)
        }

        // 5. The order asked for, with anything unlisted following as it arrived.
        if (lineup.order.isEmpty()) return out to arrangedStreams
        val rank = HashMap<String, Int>()
        lineup.order.forEachIndexed { at, name -> rank[name.trim().uppercase()] = at }
        val last = lineup.order.size
        val ordered = out.sortedBy { rank[it.name.trim().uppercase()] ?: last }
        return ordered to arrangedStreams
    }

    /**
     * The channels of one folder, put in the order the patterns ask for.
     * Everything outside that folder keeps its place exactly.
     */
    private fun sortWithin(streams: List<StreamItem>, folders: Set<String>, first: List<String>): List<StreamItem> {
        val inside = streams.filter { folders.contains(it.categoryId) }
        if (inside.size < 2) return streams
        val last = first.size
        val sorted = inside.sortedBy { stream ->
            val at = first.indexOfFirst { matches(stream.name, it) }
            if (at < 0) last else at
        }
        var next = 0
        return streams.map { if (folders.contains(it.categoryId)) sorted[next++] else it }
    }

    /**
     * The folders that survive, and the ids of the ones that did not.
     *
     * For films and series, where the only rule anyone wants is "not that one".
     * The ids travel back with the list because the titles themselves are filed
     * by id, and a folder removed without its contents leaves films that can
     * still be searched for and played but that live nowhere.
     */
    fun prune(patterns: List<String>, categories: List<Category>): Pair<List<Category>, Set<String>> {
        if (patterns.isEmpty() || categories.isEmpty()) return categories to emptySet()
        val dropped = HashSet<String>()
        val kept = ArrayList<Category>(categories.size)
        for (category in categories) {
            if (patterns.any { matches(category.name, it) }) dropped.add(category.id) else kept.add(category)
        }
        return kept to dropped
    }

    /**
     * Channels to drop outright, by name, wherever the panel filed them.
     *
     * Unlike hiding a folder this takes nothing else with it: the folder and
     * everything else inside it carry on untouched.
     */
    fun dropChannels(patterns: List<String>, streams: List<StreamItem>): List<StreamItem> {
        if (patterns.isEmpty() || streams.isEmpty()) return streams
        return streams.filter { stream -> patterns.none { matches(stream.name, it) } }
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
            val hideSeries = ArrayList<String>()
            one.optJSONArray("hide_series")?.let { for (i in 0 until it.length()) hideSeries.add(it.optString(i)) }
            val hideMovies = ArrayList<String>()
            one.optJSONArray("hide_movies")?.let { for (i in 0 until it.length()) hideMovies.add(it.optString(i)) }
            val hideChannels = ArrayList<String>()
            one.optJSONArray("hide_channels")?.let { for (i in 0 until it.length()) hideChannels.add(it.optString(i)) }
            val sort = ArrayList<Lineup.Sort>()
            one.optJSONArray("sort")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val folder = s.optString("folder", "").trim()
                    val first = ArrayList<String>()
                    s.optJSONArray("first")?.let { f -> for (j in 0 until f.length()) first.add(f.optString(j)) }
                    if (folder.isNotBlank() && first.isNotEmpty()) sort.add(Lineup.Sort(folder, first))
                }
            }
            val lineup = Lineup(
                hide.filter { it.isNotBlank() },
                merge,
                move,
                order.filter { it.isNotBlank() },
                hideSeries.filter { it.isNotBlank() },
                hideMovies.filter { it.isNotBlank() },
                hideChannels.filter { it.isNotBlank() },
                sort
            )
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
            one.put("hide_series", strings(lineup.hideSeries))
            one.put("hide_movies", strings(lineup.hideMovies))
            one.put("hide_channels", strings(lineup.hideChannels))
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
            val sorts = org.json.JSONArray()
            for (s in lineup.sort) {
                sorts.put(JSONObject().put("folder", s.folder).put("first", strings(s.first)))
            }
            one.put("sort", sorts)
            root.put(address, one)
        }
        return root.toString()
    }
}
