package com.johnnytv.player

import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

/**
 * EVENT CATEGORIES, PUT IN TIME ORDER
 *
 * A pay-per-view or event block is not a list of channels, it is a schedule
 * wearing a channel list's clothes. The portal hands it over in whatever order
 * its database felt like, so a card that finished on Saturday sits above one
 * starting in an hour, and finding tonight's fight means reading the whole page.
 *
 * Nearly every portal writes the date into the channel name, because that is the
 * only field it has. So that is where the time comes from - no extra requests,
 * nothing to fetch, just the name that is already on screen.
 *
 * The order is: what is starting soonest at the top, what is on now with it,
 * then anything undated in the portal's own order, then what has already
 * finished at the bottom, newest of those first.
 *
 * THE SAFETY CATCH
 *
 * Reading dates out of names is guesswork, and guessing wrong would scramble a
 * category rather than sort it. So if fewer than a third of the names in a
 * category yield a date, the whole thing is left exactly as the portal sent it.
 * A list in the wrong order is a nuisance; a list in a random order is broken.
 */
object EventOrder {

    /** Anything older than this is treated as done, not upcoming. */
    private const val STILL_ON_MS = 4L * 60L * 60L * 1000L

    /** Below this share of names carrying a date, do nothing at all. */
    private const val CONFIDENCE = 0.33

    private val MONTHS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
        "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
    )

    // 09/15, 09-15, 15.09 - a month and a day with a separator between them.
    private val NUMERIC_DATE: Pattern =
        Pattern.compile("\\b(\\d{1,2})\\s*[/.\\-]\\s*(\\d{1,2})\\b")

    // 15 Sep, Sep 15, September 15th
    private val NAMED_DATE: Pattern = Pattern.compile(
        "\\b(?:(\\d{1,2})\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)|" +
            "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s*(\\d{1,2}))\\b",
        Pattern.CASE_INSENSITIVE
    )

    // 19:00, 7:30 PM, 7PM
    private val TIME: Pattern =
        Pattern.compile("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b|\\b(\\d{1,2}):(\\d{2})\\b",
            Pattern.CASE_INSENSITIVE)

    /** Is this category one where a schedule makes more sense than a channel list? */
    fun isEventCategory(name: String): Boolean {
        val upper = name.uppercase(Locale.ROOT)
        // Adult blocks are left alone whatever they are called.
        if (Config.CATEGORY_LAST.any { upper.contains(it.uppercase(Locale.ROOT)) }) return false
        if (Config.CATEGORY_EVENTS.any { upper.contains(it.uppercase(Locale.ROOT)) }) return true
        return upper.contains("EVENT")
    }

    /**
     * The order described above, or the list untouched when the names do not
     * carry enough dates to be sure.
     */
    fun sort(channels: List<StreamItem>, now: Long = System.currentTimeMillis()): List<StreamItem> {
        if (channels.size < 3) return channels

        val times = channels.map { timeIn(it.name, now) }
        val dated = times.count { it != null }
        if (dated < channels.size * CONFIDENCE) return channels

        // Keep the portal's order as the tie-break for anything undated.
        val originalIndex = HashMap<String, Int>(channels.size)
        channels.forEachIndexed { at, item -> originalIndex[item.streamId] = at }

        val upcoming = ArrayList<Pair<StreamItem, Long>>()
        val undated = ArrayList<StreamItem>()
        val finished = ArrayList<Pair<StreamItem, Long>>()

        channels.forEachIndexed { at, item ->
            val when1 = times[at]
            when {
                when1 == null -> undated.add(item)
                when1 >= now - STILL_ON_MS -> upcoming.add(item to when1)
                else -> finished.add(item to when1)
            }
        }

        upcoming.sortWith(compareBy({ it.second }, { originalIndex[it.first.streamId] ?: 0 }))
        finished.sortWith(compareByDescending<Pair<StreamItem, Long>> { it.second }
            .thenBy { originalIndex[it.first.streamId] ?: 0 })

        val out = ArrayList<StreamItem>(channels.size)
        upcoming.forEach { out.add(it.first) }
        out.addAll(undated)
        finished.forEach { out.add(it.first) }
        return out
    }

    /**
     * The moment this name is talking about, or null when it is not talking about
     * one. A bare two-digit number is deliberately not a date: "NFL 09" is a slot
     * number on most portals, and treating it as the ninth of the month would be
     * exactly the sort of confident mistake the safety catch exists to prevent.
     */
    fun timeIn(rawName: String, now: Long = System.currentTimeMillis()): Long? {
        // "24/7" is a promise about a channel, not the twenty-fourth of July.
        // It appears all over these lists, so it goes before anything is read.
        val name = rawName.replace(Regex("\\b24\\s*[/.\\-]\\s*7\\b"), " ")
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        val thisYear = calendar.get(Calendar.YEAR)

        var month = -1
        var day = -1

        val named = NAMED_DATE.matcher(name)
        if (named.find()) {
            if (named.group(1) != null) {
                day = named.group(1)!!.toInt()
                month = MONTHS[named.group(2)!!.lowercase(Locale.ROOT).take(3)] ?: -1
            } else {
                month = MONTHS[named.group(3)!!.lowercase(Locale.ROOT).take(3)] ?: -1
                day = named.group(4)!!.toInt()
            }
        } else {
            val numeric = NUMERIC_DATE.matcher(name)
            if (numeric.find()) {
                val first = numeric.group(1)!!.toInt()
                val second = numeric.group(2)!!.toInt()
                // Whichever number cannot be a month is the day.
                when {
                    first in 1..12 && second in 1..31 -> { month = first - 1; day = second }
                    second in 1..12 && first in 1..31 -> { month = second - 1; day = first }
                }
            }
        }

        var hour = -1
        var minute = 0
        val time = TIME.matcher(name)
        if (time.find()) {
            if (time.group(1) != null) {
                hour = time.group(1)!!.toInt() % 12
                minute = time.group(2)?.toInt() ?: 0
                if (time.group(3)!!.lowercase(Locale.ROOT) == "pm") hour += 12
            } else {
                hour = time.group(4)!!.toInt()
                minute = time.group(5)!!.toInt()
            }
            if (hour !in 0..23 || minute !in 0..59) { hour = -1; minute = 0 }
        }

        if (month < 0 && hour < 0) return null

        val at = Calendar.getInstance()
        at.timeInMillis = now
        at.set(Calendar.MILLISECOND, 0)
        at.set(Calendar.SECOND, 0)
        if (month >= 0) {
            at.set(Calendar.YEAR, thisYear)
            at.set(Calendar.MONTH, month)
            at.set(Calendar.DAY_OF_MONTH, day.coerceIn(1, 31))
        }
        at.set(Calendar.HOUR_OF_DAY, if (hour >= 0) hour else 0)
        at.set(Calendar.MINUTE, if (hour >= 0) minute else 0)

        // A date with no year, read in January, means last December - not a date
        // eleven months away. Same idea in reverse at the end of the year.
        if (month >= 0) {
            val gap = at.timeInMillis - now
            if (gap > 300L * 24 * 3600 * 1000) at.add(Calendar.YEAR, -1)
            if (gap < -300L * 24 * 3600 * 1000) at.add(Calendar.YEAR, 1)
        }
        return at.timeInMillis
    }
}
