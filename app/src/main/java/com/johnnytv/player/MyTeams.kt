package com.johnnytv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/** A team somebody follows. */
data class Team(val league: String, val name: String, val nickname: String, val logo: String) {
    val key: String get() = "$league|$nickname"
}

/** One of their games, and the channel it is on. */
data class TeamGame(val team: Team, val channel: StreamItem, val startsAt: Long, val title: String)

/**
 * MY TEAMS.
 *
 * Pick the teams you follow, and their games come to you: on the home screen
 * for today, and as a nudge ten minutes before the start.
 *
 * WHERE THE GAMES COME FROM. The league's own schedule says who is playing
 * and when; the portal's channel list only says where to watch it.
 *
 * It was the other way round at first - the start time read out of the channel
 * name, since the name is the only field a portal has. That is how the folders
 * are put in time order and it works well enough for sorting, but it is no
 * basis for telling somebody when a game starts: portals write those times in
 * whatever zone their server keeps, so a name saying 04:00 can mean nine in
 * the evening here, and the countdown is hours out. Worse, a name is just
 * text, and a replay or a 24/7 channel carrying a team's name reads exactly
 * like a fixture.
 *
 * So the schedule is asked, and the channel list is searched only for a
 * channel whose name carries both teams. A game with nowhere to watch it is
 * not shown, and if the schedule cannot be reached the old reading of the
 * names takes over, countdown and all.
 *
 * WHERE THE TEAMS COME FROM. The league's own list, with proper names and
 * logos, fetched once and kept for a week. If that cannot be had, a list of
 * names built into the app stands in - no logos, but every team is there.
 */
object MyTeams {

    val LEAGUES = listOf("NHL", "NFL", "NBA", "MLB", "MLS", "CFL", "UFC", "PFL")

    /**
     * Fighting is followed by the promotion, not by the man. A card is called
     * "UFC Fight Night: Allen vs Duncan" and a portal's channel for it is
     * named something like "UFC FIGHT NIGHT 10/10" - the headline fighters may
     * not appear at all, and a card nobody has topped yet has no names to
     * follow. So UFC and PFL offer one thing to tick each, and ticking it says
     * "tell me when there is a card on".
     */
    private val PROMOTIONS = mapOf("UFC" to "UFC", "PFL" to "PFL")

    private val PATHS = mapOf(
        "NHL" to "hockey/nhl",
        "NFL" to "football/nfl",
        "NBA" to "basketball/nba",
        "MLB" to "baseball/mlb",
        "MLS" to "soccer/usa.1",
        "CFL" to "football/cfl",
        "UFC" to "mma/ufc",
        "PFL" to "mma/pfl"
    )

    /** How long a day's fixtures are kept before asking again. */
    private const val FIXTURES_AGE_MS = 20L * 60L * 1000L

    private const val STORE = "my_teams"
    private const val KEY_CHOSEN = "chosen"
    private const val KEY_REMINDED = "reminded"

    private const val LIST_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /** How long before the start the reminder goes up. */
    const val REMIND_AHEAD_MS = 10L * 60L * 1000L

    /** A game that began this long ago is still worth showing as on now. */
    private const val ON_NOW_MS = 3L * 60L * 60L * 1000L

    /** "Today", looking forward. */
    private const val AHEAD_MS = 18L * 60L * 60L * 1000L

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    // ---------- the teams somebody follows ----------

    fun chosen(context: Context): List<Team> =
        (store(context).getString(KEY_CHOSEN, "") ?: "").split("\n").mapNotNull { parse(it) }

    private fun parse(line: String): Team? {
        val parts = line.split("\t")
        if (parts.size < 4 || parts[0].isBlank() || parts[2].isBlank()) return null
        return Team(parts[0], parts[1], parts[2], parts[3])
    }

    /** Adds the team, or takes it off. True when it is now followed. */
    fun toggle(context: Context, team: Team): Boolean {
        val list = ArrayList(chosen(context))
        val had = list.removeAll { it.key == team.key }
        if (!had) list.add(team)
        val text = list.joinToString("\n") { listOf(it.league, it.name, it.nickname, it.logo).joinToString("\t") }
        store(context).edit().putString(KEY_CHOSEN, text).apply()
        return !had
    }

    // ---------- a league's teams ----------

    /** Blocking. Call off the main thread. */
    fun teams(context: Context, league: String): List<Team> {
        PROMOTIONS[league]?.let { return listOf(Team(league, it, it, "")) }
        val file = File(context.filesDir, "teams_$league.json")
        val kept = runCatching { readList(file, league) }.getOrDefault(emptyList())
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < LIST_AGE_MS
        if (kept.isNotEmpty() && fresh) return kept

        val fetched = runCatching { fetch(league) }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            runCatching { writeList(file, fetched) }
            return fetched
        }
        // An old list is better than a bare one, and a bare one better than none.
        if (kept.isNotEmpty()) return kept
        return builtIn(league)
    }

    private fun fetch(league: String): List<Team> {
        val path = PATHS[league] ?: return emptyList()
        val http = URL("https://site.api.espn.com/apis/site/v2/sports/$path/teams?limit=100")
            .openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 8_000
            http.readTimeout = 10_000
            http.setRequestProperty("User-Agent", Config.USER_AGENT)
            if (http.responseCode !in 200..299) return emptyList()
            val body = http.inputStream.bufferedReader().use { it.readText() }
            val list = JSONObject(body).optJSONArray("sports")?.optJSONObject(0)
                ?.optJSONArray("leagues")?.optJSONObject(0)
                ?.optJSONArray("teams") ?: return emptyList()
            val out = ArrayList<Team>(list.length())
            for (i in 0 until list.length()) {
                val team = list.optJSONObject(i)?.optJSONObject("team") ?: continue
                val name = team.optString("displayName", "").trim()
                val nickname = team.optString("name", "").trim()
                    .ifBlank { team.optString("shortDisplayName", "").trim() }
                if (name.isBlank() || nickname.isBlank()) continue
                val logo = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href", "").orEmpty()
                out.add(Team(league, name, nickname, logo.replace("http://", "https://")))
            }
            return out.sortedBy { it.name }
        } finally {
            http.disconnect()
        }
    }

    private fun readList(file: File, league: String): List<Team> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        val out = ArrayList<Team>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val nickname = o.optString("k", "")
            if (nickname.isBlank()) continue
            out.add(Team(league, o.optString("n", ""), nickname, o.optString("l", "")))
        }
        return out
    }

    private fun writeList(file: File, teams: List<Team>) {
        val array = JSONArray()
        for (team in teams) {
            array.put(JSONObject().put("n", team.name).put("k", team.nickname).put("l", team.logo))
        }
        file.writeText(array.toString())
    }

    private fun builtIn(league: String): List<Team> =
        (BUILT_IN[league] ?: "").split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size < 2) null
            else Team(league, parts[0].trim() + " " + parts[1].trim(), parts[1].trim(), "")
        }.sortedBy { it.name }

    // ---------- the leagues' schedules ----------

    /** One fixture from the league's own schedule. */
    private class Fixture(
        val league: String,
        val sides: List<String>,
        val startsAt: Long,
        /** For a fight card, what it is called; empty for a fixture between two teams. */
        val card: String = ""
    )

    private val fixtures = HashMap<String, Pair<Long, List<Fixture>>>()

    /**
     * Blocking. The league's fixtures around now, or null when the schedule
     * could not be reached at all.
     *
     * The difference matters: no fixtures for a team means they are not
     * playing today, which is a real answer. Not being able to ask is not an
     * answer, and only then is there any reason to go back to reading names.
     */
    private fun fixtures(league: String, now: Long): List<Fixture>? {
        synchronized(fixtures) {
            val held = fixtures[league]
            if (held != null && now - held.first < FIXTURES_AGE_MS) return held.second
        }
        val fetched = runCatching { fetchFixtures(league, now) }.getOrNull()
        synchronized(fixtures) {
            // Nothing came back: an answer from earlier today still stands.
            if (fetched == null) return fixtures[league]?.second
            fixtures[league] = now to fetched
            return fetched
        }
    }

    private fun fetchFixtures(league: String, now: Long): List<Fixture> {
        val path = PATHS[league] ?: return emptyList()
        // Asked in the league's own terms: whole days, by the clock in London,
        // since that is what the schedule is written against.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        stamp.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val from = stamp.format(java.util.Date(now - 24L * 60L * 60L * 1000L))
        // Cards are announced weeks out and there is one every week or two, so
        // a fight promotion is asked about the month ahead rather than tomorrow.
        val ahead = if (PROMOTIONS.containsKey(league)) 31L else 2L
        val to = stamp.format(java.util.Date(now + ahead * 24L * 60L * 60L * 1000L))
        val http = URL("https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard?dates=$from-$to")
            .openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 8_000
            http.readTimeout = 10_000
            http.setRequestProperty("User-Agent", Config.USER_AGENT)
            if (http.responseCode !in 200..299) return emptyList()
            val body = http.inputStream.bufferedReader().use { it.readText() }
            val events = JSONObject(body).optJSONArray("events") ?: return emptyList()
            val when_ = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US)
            when_.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val out = ArrayList<Fixture>(events.length())
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val at = runCatching { when_.parse(event.optString("date", ""))?.time }.getOrNull() ?: continue
                if (PROMOTIONS.containsKey(league)) {
                    val card = event.optString("name", "").trim()
                        .ifBlank { event.optString("shortName", "").trim() }
                    if (card.isNotBlank()) out.add(Fixture(league, listOf(league), at, card))
                    continue
                }
                val sides = ArrayList<String>(2)
                val teams = event.optJSONArray("competitions")?.optJSONObject(0)
                    ?.optJSONArray("competitors")
                for (k in 0 until (teams?.length() ?: 0)) {
                    val team = teams?.optJSONObject(k)?.optJSONObject("team") ?: continue
                    val nickname = team.optString("name", "").trim()
                        .ifBlank { team.optString("shortDisplayName", "").trim() }
                    if (nickname.isNotBlank()) sides.add(nickname)
                }
                if (sides.size >= 2) out.add(Fixture(league, sides, at))
            }
            return out
        } finally {
            http.disconnect()
        }
    }

    // ---------- their games ----------

    fun games(context: Context, now: Long = System.currentTimeMillis()): List<TeamGame> {
        val mine = chosen(context)
        if (mine.isEmpty()) return emptyList()
        val scheduled = fromSchedule(mine, now)
        // Null means not one league could be asked. An empty list is an answer:
        // nobody they follow is playing, and inventing a game from a channel
        // name - a replay, or a team's own 24/7 channel - is worse than saying
        // nothing at all.
        return scheduled ?: fromNames(mine, now)
    }

    /**
     * The league's fixtures for the teams being followed, each paired with a
     * channel whose name carries both sides.
     */
    private fun fromSchedule(mine: List<Team>, now: Long): List<TeamGame>? {
        val out = ArrayList<TeamGame>()
        val seen = HashSet<String>()
        var asked = false
        for (league in mine.map { it.league }.distinct()) {
            val list = fixtures(league, now) ?: continue
            asked = true
            for (fixture in list) {
                if (fixture.startsAt < now - ON_NOW_MS || fixture.startsAt > now + AHEAD_MS) continue
                val team = mine.firstOrNull { me ->
                    me.league == league && fixture.sides.any { it.equals(me.nickname, true) }
                } ?: continue
                if (!seen.add(team.key + "@" + (fixture.startsAt / 600_000L))) continue
                val channel = channelFor(fixture, team) ?: continue
                val title = if (fixture.card.isNotBlank()) fixture.card
                    else fixture.sides.joinToString(" v ")
                out.add(TeamGame(team, channel, fixture.startsAt, title))
            }
        }
        return if (asked) out.sortedBy { it.startsAt } else null
    }

    /**
     * Where to watch this fixture.
     *
     * A channel naming both sides is the right answer and is taken first. But
     * portals write these names every way there is - "Canucks vs Golden
     * Knights", "VAN vs VGK", "Vancouver @ Vegas" - and insisting on both
     * names in full meant a real game, with a real time from the schedule,
     * was quietly dropped because the channel said it differently. So failing
     * that, any channel naming the team being followed will do: the worst case
     * is their own channel rather than tonight's game, which is still a
     * sensible place to send somebody at kick-off.
     */
    private fun channelFor(fixture: Fixture, team: Team): StreamItem? {
        if (fixture.card.isNotBlank()) return channelForCard(fixture)
        val words = fixture.sides.map { nameParts(it) }
        var loose: StreamItem? = null
        var mine: StreamItem? = null
        val team_ = Pattern.compile("\\b" + Pattern.quote(team.nickname) + "\\b", Pattern.CASE_INSENSITIVE)
        for (channel in Catalog.live) {
            val name = channel.name
            val bothNamed = words.all { side -> side.any { it.matcher(name).find() } }
            if (bothNamed) {
                // A listing for this particular game beats a channel that
                // merely mentions the two teams, so one carrying a time wins.
                if (EventOrder.timeIn(name) != null) return channel
                if (loose == null) loose = channel
            } else if (mine == null && team_.matcher(name).find()) {
                mine = channel
            }
        }
        return loose ?: mine
    }

    /**
     * The ways a side's name might be written. "Golden Knights" is also
     * "Knights"; "Maple Leafs" is also "Leafs" - so each word of three
     * letters or more counts, as well as the whole.
     */
    private fun nameParts(side: String): List<Pattern> {
        val out = ArrayList<Pattern>(3)
        out.add(Pattern.compile("\\b" + Pattern.quote(side) + "\\b", Pattern.CASE_INSENSITIVE))
        val words = side.split(' ').filter { it.length >= 3 }
        if (words.size > 1) {
            words.forEach { out.add(Pattern.compile("\\b" + Pattern.quote(it) + "\\b", Pattern.CASE_INSENSITIVE)) }
        }
        return out
    }

    /**
     * A channel for this fight card.
     *
     * The card's name and the channel's rarely read the same - "UFC Fight
     * Night: Allen vs. Duncan" against "UFC FIGHT NIGHT 10/10" - so what is
     * looked for is the promotion plus whatever else the two have in common:
     * a number like 320, or a fighter's surname. A card with nothing in common
     * but the word UFC is not matched, or every UFC channel would answer to
     * every card.
     */
    private fun channelForCard(fixture: Fixture): StreamItem? {
        val promotion = Pattern.compile("\\b" + Pattern.quote(fixture.league) + "\\b", Pattern.CASE_INSENSITIVE)
        val words = WORD.findAll(fixture.card.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in CARD_FILLER && !it.equals(fixture.league, true) }
            .toList()
        var best: StreamItem? = null
        var bestScore = 0
        for (channel in Catalog.live) {
            if (!promotion.matcher(channel.name).find()) continue
            val name = channel.name.lowercase()
            val score = words.count { name.contains(it) }
            if (score > bestScore) {
                best = channel
                bestScore = score
            }
        }
        return if (bestScore > 0) best else null
    }

    private val WORD = Regex("[a-z0-9]+")
    private val CARD_FILLER = setOf(
        "the", "and", "vs", "versus", "fight", "night", "card", "main", "event",
        "prelims", "early", "season", "week", "dana", "white", "contender", "series"
    )

    /** What the channel names alone can tell us, when the schedule cannot be had. */
    private fun fromNames(mine: List<Team>, now: Long): List<TeamGame> {
        val folders = HashMap<String, String>()
        for (category in Catalog.liveCategories) folders[category.id] = category.name
        val patterns: List<Pair<Team, Pattern>> = mine.map { team ->
            team to Pattern.compile("\\b" + Pattern.quote(team.nickname) + "\\b", Pattern.CASE_INSENSITIVE)
        }
        val out = ArrayList<TeamGame>()
        val seen = HashSet<String>()
        for (channel in Catalog.live) {
            val name = channel.name
            for ((team, pattern) in patterns) {
                if (!pattern.matcher(name).find()) continue
                // Kings, Jets, Giants, Panthers: a nickname alone can belong to
                // two leagues, so where a league is named it has to be the right one.
                if (!leagueFits(team.league, name + " " + folders[channel.categoryId].orEmpty())) continue
                val at = EventOrder.timeIn(name, now) ?: continue
                if (at < now - ON_NOW_MS || at > now + AHEAD_MS) continue
                // The same game on three feeds is one game.
                if (!seen.add(team.key + "@" + (at / 600_000L))) continue
                out.add(TeamGame(team, channel, at, tidy(name)))
            }
        }
        return out.sortedBy { it.startsAt }
    }

    private val LEAGUE_WORDS: List<Pair<String, Pattern>> = LEAGUES.map {
        it to Pattern.compile("\\b$it\\b", Pattern.CASE_INSENSITIVE)
    }

    private fun leagueFits(league: String, text: String): Boolean {
        var named = false
        for ((code, pattern) in LEAGUE_WORDS) {
            if (pattern.matcher(text).find()) {
                if (code == league) return true
                named = true
            }
        }
        return !named
    }

    private val BRACKETS = Regex("[\\(\\[][^\\)\\]]*[\\)\\]]")
    private val CLOCK = Regex("(?i)\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)\\b|\\b\\d{1,2}:\\d{2}\\b")
    private val DATE = Regex("\\b\\d{1,2}\\s*[/.\\-]\\s*\\d{1,2}\\b")
    private val SPACES = Regex("\\s+")

    /** The channel's name with the folder tag, the date and the time taken off. */
    private fun tidy(name: String): String {
        var text = name
        val bar = text.indexOf('|')
        if (bar in 1..16) text = text.substring(bar + 1)
        text = text.replace(BRACKETS, " ").replace(CLOCK, " ").replace(DATE, " ")
        text = text.replace(SPACES, " ").trim().trim('-', '|', ':', '@', ',', ' ')
        return text.ifBlank { name }
    }

    /**
     * A game that starts within the next ten minutes and has not been
     * mentioned yet - marked as mentioned as it is handed over, so it is said
     * once however many screens ask.
     */
    fun dueReminder(context: Context, now: Long = System.currentTimeMillis()): TeamGame? {
        val reminded = (store(context).getString(KEY_REMINDED, "") ?: "").split("\n").filter { it.isNotBlank() }
        val due = games(context, now).firstOrNull { game ->
            val wait = game.startsAt - now
            wait in 0L..REMIND_AHEAD_MS && !reminded.contains(tokenFor(game))
        } ?: return null
        val kept = (reminded + tokenFor(due)).takeLast(40)
        store(context).edit().putString(KEY_REMINDED, kept.joinToString("\n")).apply()
        return due
    }

    private fun tokenFor(game: TeamGame): String = game.team.key + "@" + (game.startsAt / 600_000L)

    // Location|Nickname, for when the league's own list cannot be fetched.
    private val BUILT_IN = mapOf(
        "NHL" to "Anaheim|Ducks;Boston|Bruins;Buffalo|Sabres;Calgary|Flames;Carolina|Hurricanes;Chicago|Blackhawks;Colorado|Avalanche;Columbus|Blue Jackets;Dallas|Stars;Detroit|Red Wings;Edmonton|Oilers;Florida|Panthers;Los Angeles|Kings;Minnesota|Wild;Montreal|Canadiens;Nashville|Predators;New Jersey|Devils;New York|Islanders;New York|Rangers;Ottawa|Senators;Philadelphia|Flyers;Pittsburgh|Penguins;San Jose|Sharks;Seattle|Kraken;St. Louis|Blues;Tampa Bay|Lightning;Toronto|Maple Leafs;Utah|Mammoth;Vancouver|Canucks;Vegas|Golden Knights;Washington|Capitals;Winnipeg|Jets",
        "NFL" to "Arizona|Cardinals;Atlanta|Falcons;Baltimore|Ravens;Buffalo|Bills;Carolina|Panthers;Chicago|Bears;Cincinnati|Bengals;Cleveland|Browns;Dallas|Cowboys;Denver|Broncos;Detroit|Lions;Green Bay|Packers;Houston|Texans;Indianapolis|Colts;Jacksonville|Jaguars;Kansas City|Chiefs;Las Vegas|Raiders;Los Angeles|Chargers;Los Angeles|Rams;Miami|Dolphins;Minnesota|Vikings;New England|Patriots;New Orleans|Saints;New York|Giants;New York|Jets;Philadelphia|Eagles;Pittsburgh|Steelers;San Francisco|49ers;Seattle|Seahawks;Tampa Bay|Buccaneers;Tennessee|Titans;Washington|Commanders",
        "NBA" to "Atlanta|Hawks;Boston|Celtics;Brooklyn|Nets;Charlotte|Hornets;Chicago|Bulls;Cleveland|Cavaliers;Dallas|Mavericks;Denver|Nuggets;Detroit|Pistons;Golden State|Warriors;Houston|Rockets;Indiana|Pacers;Los Angeles|Clippers;Los Angeles|Lakers;Memphis|Grizzlies;Miami|Heat;Milwaukee|Bucks;Minnesota|Timberwolves;New Orleans|Pelicans;New York|Knicks;Oklahoma City|Thunder;Orlando|Magic;Philadelphia|76ers;Phoenix|Suns;Portland|Trail Blazers;Sacramento|Kings;San Antonio|Spurs;Toronto|Raptors;Utah|Jazz;Washington|Wizards",
        "MLB" to "Arizona|Diamondbacks;Atlanta|Braves;Baltimore|Orioles;Boston|Red Sox;Chicago|Cubs;Chicago|White Sox;Cincinnati|Reds;Cleveland|Guardians;Colorado|Rockies;Detroit|Tigers;Houston|Astros;Kansas City|Royals;Los Angeles|Angels;Los Angeles|Dodgers;Miami|Marlins;Milwaukee|Brewers;Minnesota|Twins;New York|Mets;New York|Yankees;Athletics|Athletics;Philadelphia|Phillies;Pittsburgh|Pirates;San Diego|Padres;San Francisco|Giants;Seattle|Mariners;St. Louis|Cardinals;Tampa Bay|Rays;Texas|Rangers;Toronto|Blue Jays;Washington|Nationals",
        "CFL" to "BC|Lions;Calgary|Stampeders;Edmonton|Elks;Hamilton|Tiger-Cats;Montreal|Alouettes;Ottawa|Redblacks;Saskatchewan|Roughriders;Toronto|Argonauts;Winnipeg|Blue Bombers"
    )
}

/**
 * The nudge before a game: who is playing, how soon, and three things to do
 * about it. It sits at the foot of the screen so that what is being watched
 * carries on above it, and it goes away on its own if nobody answers.
 */
fun android.app.Activity.showGameReminder(game: TeamGame) {
    val minutes = ((game.startsAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
    val view = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_options, null, false)
    val column = view.findViewById<android.widget.LinearLayout>(R.id.optionsColumn)
    val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(getString(R.string.team_reminder_title, game.title, minutes.toInt()))
        .setView(view)
        .create()
    val labels = listOf(
        getString(R.string.team_reminder_watch),
        getString(R.string.record_this),
        getString(R.string.team_reminder_dismiss)
    )
    labels.forEachIndexed { index, label ->
        val row = android.view.LayoutInflater.from(this)
            .inflate(R.layout.item_option, column, false) as android.widget.TextView
        row.text = label
        row.setOnClickListener {
            dialog.dismiss()
            when (index) {
                0 -> PlayerActivity.start(
                    this,
                    urls = Prefs(this).client().liveUrls(game.channel.streamId),
                    title = game.channel.name,
                    kind = Kind.LIVE,
                    contentId = game.channel.streamId,
                    category = game.channel.categoryId
                )
                1 -> RecordDialog.showForLive(this, game.channel)
            }
        }
        column.addView(row)
    }
    dialog.setOnShowListener { column.getChildAt(0)?.requestFocus() }
    dialog.window?.setGravity(android.view.Gravity.BOTTOM)
    dialog.show()
    // Unanswered, it clears itself rather than sitting over the picture.
    view.postDelayed({ if (dialog.isShowing && !isFinishing) runCatching { dialog.dismiss() } }, 25_000L)
}

/** Looks for a game about to start and, if there is one, says so. */
fun android.app.Activity.checkTeamReminder(watching: String = "") {
    val activity = this
    kotlin.concurrent.thread {
        val game = runCatching { MyTeams.dueReminder(activity) }.getOrNull() ?: return@thread
        // Already on it: nothing to be reminded of.
        if (game.channel.streamId == watching) return@thread
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                runCatching { activity.showGameReminder(game) }
            }
        }
    }
}
