package com.johnnytv.player

import android.app.Activity
import android.app.TimePickerDialog
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

    private fun pickChannel(activity: Activity, matches: List<StreamItem>, onDone: () -> Unit) {
        activity.showOptions(
            activity.getString(R.string.record_by_time_which_channel),
            matches.map { it.name }
        ) { which -> pickStart(activity, matches[which], onDone) }
    }

    private fun pickStart(activity: Activity, channel: StreamItem, onDone: () -> Unit) {
        val now = Calendar.getInstance()
        TimePickerDialog(
            activity,
            { _, hour, minute ->
                val start = Calendar.getInstance()
                start.set(Calendar.HOUR_OF_DAY, hour)
                start.set(Calendar.MINUTE, minute)
                start.set(Calendar.SECOND, 0)
                // A time that has already gone today is meant for tomorrow.
                if (start.timeInMillis < System.currentTimeMillis() - 60_000L) {
                    start.add(Calendar.DAY_OF_YEAR, 1)
                }
                pickLength(activity, channel, start.timeInMillis, onDone)
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            false
        ).show()
    }

    private fun pickLength(
        activity: Activity,
        channel: StreamItem,
        startAt: Long,
        onDone: () -> Unit
    ) {
        val choices = listOf(30, 60, 90, 120, 180, 240)
        activity.showOptions(
            activity.getString(R.string.record_by_time_how_long),
            choices.map { activity.getString(R.string.record_by_time_minutes, it) }
        ) { which ->
            val minutes = choices[which]
            val item = Scheduled(
                id = "t" + System.currentTimeMillis(),
                title = channel.name,
                channel = channel.name,
                streamId = channel.streamId,
                startAt = startAt,
                endAt = startAt + minutes * 60_000L,
                // No listings means no way to know if it starts late, so this
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
                return@showOptions
            }
            save(activity, item, onDone)
        }
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
