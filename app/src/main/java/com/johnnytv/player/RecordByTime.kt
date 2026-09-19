package com.johnnytv.player

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * RECORD BY TIME
 *
 * Every other way into recording starts from the guide: press a programme, get
 * a recording. That is no use at all on the channels people care most about
 * getting right. PPV and event channels carry no listings - the row is empty,
 * there is nothing to press, and holding OK does nothing at all. Fight night is
 * the worst possible moment to discover that.
 *
 * So this asks the three questions the guide would otherwise have answered:
 * which channel, starting when, for how long. Everything after that is the same
 * machinery as a scheduled recording from the guide - the same alarm, the same
 * padding, the same clash check against the line's one connection.
 */
object RecordByTime {

    fun show(activity: Activity, onDone: () -> Unit) {
        if (!Catalog.isLoaded) Catalog.load(activity)
        val channels = Catalog.live
        if (channels.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.record_by_time)
                .setMessage(R.string.record_by_time_no_channels)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        // Straight to a search rather than a list: nobody scrolls three thousand
        // channels with a remote to find the pay-per-view one.
        val input = android.widget.EditText(activity)
        input.setHint(R.string.record_by_time_search_hint)
        input.setSingleLine(true)

        AlertDialog.Builder(activity)
            .setTitle(R.string.record_by_time_which_channel)
            .setView(input)
            .setPositiveButton(R.string.record_by_time_find) { _, _ ->
                val term = input.text.toString().trim()
                val matches = channels.filter { it.name.contains(term, ignoreCase = true) }
                when {
                    term.isBlank() -> show(activity, onDone)
                    matches.isEmpty() -> {
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.record_by_time)
                            .setMessage(activity.getString(R.string.record_by_time_none, term))
                            .setPositiveButton(R.string.close) { _, _ -> show(activity, onDone) }
                            .show()
                    }
                    else -> pickChannel(activity, matches.take(30), onDone)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Straight to the time, for a channel already in hand.
     *
     * Reached by holding OK on a channel in the guide, which is where somebody
     * setting a pay-per-view recording actually is - they are looking at the
     * channel. Asking them to go to another screen and type its name again was
     * a worse answer to the same question.
     */
    fun forChannel(activity: Activity, channel: StreamItem, onDone: () -> Unit = {}) {
        pickTimes(activity, channel, onDone)
    }

    private fun pickChannel(activity: Activity, matches: List<StreamItem>, onDone: () -> Unit) {
        activity.showOptions(
            activity.getString(R.string.record_by_time_which_channel),
            matches.map { it.name }
        ) { which -> pickTimes(activity, matches[which], onDone) }
    }

    /**
     * Start and stop, as two clocks.
     *
     * The first version asked for a start time on a spinner dial and then a
     * length from a list, which is two different ways of thinking about the
     * same thing: a fight starts at seven and ends when it ends, and nobody
     * works in minutes. Two clocks say it the way anybody would.
     *
     * A stop time earlier than the start means the small hours of the next
     * morning, which is where events usually finish.
     */
    private fun pickTimes(activity: Activity, channel: StreamItem, onDone: () -> Unit) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_time_range, null, false)

        val startHour = view.findViewById<android.widget.NumberPicker>(R.id.startHour)
        val startMinute = view.findViewById<android.widget.NumberPicker>(R.id.startMinute)
        val startMeridiem = view.findViewById<android.widget.NumberPicker>(R.id.startMeridiem)
        val endHour = view.findViewById<android.widget.NumberPicker>(R.id.endHour)
        val endMinute = view.findViewById<android.widget.NumberPicker>(R.id.endMinute)
        val endMeridiem = view.findViewById<android.widget.NumberPicker>(R.id.endMeridiem)
        val summary = view.findViewById<android.widget.TextView>(R.id.timeRangeSummary)

        val minutes = Array(60) { String.format(Locale.getDefault(), "%02d", it) }
        val meridiems = arrayOf("AM", "PM")

        for (picker in listOf(startHour, endHour)) {
            picker.minValue = 1
            picker.maxValue = 12
            picker.wrapSelectorWheel = true
        }
        for (picker in listOf(startMinute, endMinute)) {
            picker.minValue = 0
            picker.maxValue = 59
            picker.displayedValues = minutes
            picker.wrapSelectorWheel = true
        }
        for (picker in listOf(startMeridiem, endMeridiem)) {
            picker.minValue = 0
            picker.maxValue = 1
            picker.displayedValues = meridiems
            picker.wrapSelectorWheel = false
        }

        // Opens at the next round five minutes, running an hour - which is what
        // somebody setting a recording usually wants before they change it.
        val now = Calendar.getInstance()
        now.add(Calendar.MINUTE, 5 - (now.get(Calendar.MINUTE) % 5))
        val later = Calendar.getInstance()
        later.timeInMillis = now.timeInMillis + 60L * 60_000L

        fun set(hour: android.widget.NumberPicker, minute: android.widget.NumberPicker,
                meridiem: android.widget.NumberPicker, from: Calendar) {
            val h = from.get(Calendar.HOUR)
            hour.value = if (h == 0) 12 else h
            minute.value = from.get(Calendar.MINUTE)
            meridiem.value = from.get(Calendar.AM_PM)
        }
        set(startHour, startMinute, startMeridiem, now)
        set(endHour, endMinute, endMeridiem, later)

        fun at(hour: Int, minute: Int, meridiem: Int, afterMidnight: Boolean): Calendar {
            val time = Calendar.getInstance()
            time.set(Calendar.HOUR, if (hour == 12) 0 else hour)
            time.set(Calendar.MINUTE, minute)
            time.set(Calendar.AM_PM, meridiem)
            time.set(Calendar.SECOND, 0)
            time.set(Calendar.MILLISECOND, 0)
            if (afterMidnight) time.add(Calendar.DAY_OF_YEAR, 1)
            return time
        }

        fun refresh() {
            var startAt = at(startHour.value, startMinute.value, startMeridiem.value, false)
            if (startAt.timeInMillis < System.currentTimeMillis() - 60_000L) {
                startAt = at(startHour.value, startMinute.value, startMeridiem.value, true)
            }
            var endAt = at(endHour.value, endMinute.value, endMeridiem.value, false)
            if (endAt.timeInMillis <= startAt.timeInMillis) {
                endAt = at(endHour.value, endMinute.value, endMeridiem.value, true)
            }
            val length = ((endAt.timeInMillis - startAt.timeInMillis) / 60_000L).toInt()
            summary.text = activity.getString(R.string.record_by_time_summary, channel.name, length)
        }

        val watch = android.widget.NumberPicker.OnValueChangeListener { _, _, _ -> refresh() }
        for (picker in listOf(
            startHour, startMinute, startMeridiem, endHour, endMinute, endMeridiem
        )) picker.setOnValueChangedListener(watch)
        refresh()

        AlertDialog.Builder(activity)
            .setTitle(R.string.record_by_time)
            .setView(view)
            .setPositiveButton(R.string.record_this) { _, _ ->
                var startAt = at(startHour.value, startMinute.value, startMeridiem.value, false)
                if (startAt.timeInMillis < System.currentTimeMillis() - 60_000L) {
                    startAt = at(startHour.value, startMinute.value, startMeridiem.value, true)
                }
                var endAt = at(endHour.value, endMinute.value, endMeridiem.value, false)
                if (endAt.timeInMillis <= startAt.timeInMillis) {
                    endAt = at(endHour.value, endMinute.value, endMeridiem.value, true)
                }

                val item = Scheduled(
                    id = "t" + System.currentTimeMillis(),
                    title = channel.name,
                    channel = channel.name,
                    streamId = channel.streamId,
                    startAt = startAt.timeInMillis,
                    endAt = endAt.timeInMillis,
                    // No listings means no warning if things run late, so this
                    // gets more room at both ends than a guide recording would.
                    padStartMs = 2L * 60_000L,
                    padEndMs = 10L * 60_000L,
                    series = false
                )

                val clash = Schedules.clashOf(activity, item)
                if (clash != null) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.record_clash_title)
                        .setMessage(activity.getString(R.string.record_clash_message, clash.title))
                        .setPositiveButton(R.string.record_anyway) { _, _ -> save(activity, item, onDone) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    save(activity, item, onDone)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun save(activity: Activity, item: Scheduled, onDone: () -> Unit) {
        Schedules.put(activity, item)
        val clock = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        AlertDialog.Builder(activity)
            .setTitle(R.string.record_by_time)
            .setMessage(
                activity.getString(
                    R.string.record_by_time_set,
                    item.channel,
                    clock.format(Date(item.recordFrom)),
                    ((item.recordUntil - item.recordFrom) / 60_000L).toInt()
                )
            )
            .setPositiveButton(R.string.close) { _, _ -> onDone() }
            .show()
    }
}
