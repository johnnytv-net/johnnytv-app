package com.johnnytv.player

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * THE ONE PRESS
 *
 * Everything else in here is machinery; this is the whole feature as far as
 * anybody watching television is concerned. Hold OK on a programme, read one
 * short box, press Record.
 *
 * So the box decides as much as it can on the viewer's behalf: which drive,
 * how much padding, whether this is a recording that starts now or one that
 * waits until tonight, and whether the line can manage it at all. What is left
 * is a yes, a "catch every episode", and a way out.
 */
object RecordDialog {

    /** Sport runs over. Everything else is padded politely. */
    private const val PAD_START_MS = 60_000L
    private const val PAD_END_MS = 5L * 60_000L
    private const val PAD_END_SPORT_MS = 30L * 60_000L

    private val sportWords = listOf(
        "nhl", "nfl", "nba", "mlb", "ufc", "boxing", "soccer", "football", "hockey",
        "baseball", "basketball", "rugby", "cricket", "tennis", "golf", "f1", "motogp",
        "live", "vs", " x ", "game", "match", "fight", "racing", "sport"
    )

    fun show(
        activity: Activity,
        channel: StreamItem,
        programme: Programme,
        /** Called once something has actually been set, so the guide can redraw. */
        onDone: () -> Unit = {}
    ) {
        val now = System.currentTimeMillis()
        val sport = looksLikeSport(programme.title, channel.name)
        val padEnd = if (sport) PAD_END_SPORT_MS else PAD_END_MS
        val end = if (programme.end > programme.start) programme.end else programme.start + 60L * 60_000L

        val item = Scheduled(
            id = "s" + programme.start + channel.streamId.hashCode(),
            title = programme.title,
            channel = channel.name,
            streamId = channel.streamId,
            startAt = programme.start,
            endAt = end,
            padStartMs = PAD_START_MS,
            padEndMs = padEnd,
            series = false
        )

        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.record_title))
            .setMessage(describe(activity, item, programme, sport))
            .setNegativeButton(R.string.cancel, null)

        if (programme.start <= now && end > now) {
            // On now: start writing immediately, and run to the end of the
            // programme with its padding.
            builder.setPositiveButton(R.string.record_now) { _, _ ->
                startNow(activity, channel, programme.title, item.recordUntil)
                onDone()
            }
        } else {
            builder.setPositiveButton(R.string.record_this) { _, _ ->
                schedule(activity, item)
                onDone()
            }
        }
        builder.setNeutralButton(R.string.record_series) { _, _ ->
            schedule(activity, item.copy(series = true))
            onDone()
        }
        builder.show()
    }

    /** Record what is playing right now, for as long as the viewer says. */
    fun showForLive(activity: Activity, channel: StreamItem, minutes: Int = 120) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.record_title))
            .setMessage(
                activity.getString(
                    R.string.record_live_message,
                    channel.name,
                    minutes / 60,
                    spaceLine(activity, minutes * 60_000L)
                )
            )
            .setPositiveButton(R.string.record_now) { _, _ ->
                startNow(activity, channel, channel.name, end)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- doing it ----------

    private fun startNow(context: Context, channel: StreamItem, title: String, endAt: Long) {
        if (RecorderService.isRecording) {
            Toast.makeText(context, R.string.record_busy, Toast.LENGTH_LONG).show()
            return
        }
        val started = RecorderService.start(context, title, channel, endAt)
        Toast.makeText(
            context,
            if (started != null) context.getString(R.string.record_started, title)
            else context.getString(R.string.record_busy),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun schedule(context: Context, item: Scheduled) {
        val clash = Schedules.clashOf(context, item)
        if (clash != null) {
            AlertDialog.Builder(context)
                .setTitle(R.string.record_clash_title)
                .setMessage(context.getString(R.string.record_clash_message, clash.title))
                .setPositiveButton(R.string.record_anyway) { _, _ -> save(context, item) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        save(context, item)
    }

    private fun save(context: Context, item: Scheduled) {
        Schedules.put(context, item)
        val clock = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        Toast.makeText(
            context,
            context.getString(R.string.record_scheduled, clock.format(Date(item.recordFrom))),
            Toast.LENGTH_LONG
        ).show()
    }

    // ---------- what the box says ----------

    private fun describe(
        context: Context,
        item: Scheduled,
        programme: Programme,
        sport: Boolean
    ): String {
        val clock = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())
        val lines = ArrayList<String>()
        lines.add("${item.channel}  ·  ${clock.format(Date(programme.start))}")
        lines.add(
            context.getString(
                R.string.record_padding,
                item.padStartMs / 60_000L,
                item.padEndMs / 60_000L
            ) + if (sport) context.getString(R.string.record_padding_sport) else ""
        )
        lines.add(spaceLine(context, item.recordUntil - item.recordFrom))
        lines.add(context.getString(R.string.record_one_connection))
        return lines.joinToString("\n\n")
    }

    /** "Needs about 5.5 GB. USB drive has 412 GB free." - or a warning if it does not. */
    private fun spaceLine(context: Context, lengthMs: Long): String {
        val hours = lengthMs.toDouble() / (60L * 60L * 1000L)
        val needed = hours * StorageTarget.BYTES_PER_HOUR
        val target = Storage.chosen(context)
            ?: return context.getString(R.string.record_no_storage)
        val free = target.freeBytes
        return if (free > needed * 1.1) {
            context.getString(
                R.string.record_space_ok,
                gb(needed),
                target.label,
                gb(free.toDouble())
            )
        } else {
            context.getString(
                R.string.record_space_low,
                gb(needed),
                target.label,
                gb(free.toDouble())
            )
        }
    }

    private fun gb(bytes: Double): String {
        val value = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (value >= 10) String.format(Locale.getDefault(), "%.0f GB", value)
        else String.format(Locale.getDefault(), "%.1f GB", value)
    }

    private fun looksLikeSport(title: String, channel: String): Boolean {
        val hay = (title + " " + channel).lowercase(Locale.US)
        return sportWords.any { hay.contains(it) }
    }
}

/**
 * "Check my device" - the one screen that saves a support message.
 *
 * Everything a recording needs, tested in order, written in words a customer
 * can act on rather than a list of flags.
 */
object DeviceCheck {

    fun report(context: Context): String {
        val lines = ArrayList<String>()
        val targets = Storage.targets(context)
        val chosen = Storage.chosen(context)

        if (targets.none { it.removable }) {
            lines.add(context.getString(R.string.check_no_drive))
        } else {
            val drive = targets.first { it.removable }
            lines.add(context.getString(R.string.check_drive_found, drive.label))
        }

        if (chosen == null) {
            lines.add(context.getString(R.string.check_nowhere_to_write))
        } else {
            val hours = chosen.freeHours
            lines.add(
                context.getString(
                    R.string.check_space,
                    chosen.label,
                    String.format(Locale.getDefault(), "%.0f", hours)
                )
            )
            // The old probe wrote a dotfile, which some volumes quietly refuse
            // even when everything else about them is writable - so a perfectly
            // good drive was told it could not be written to. An ordinary file
            // name, and the app's own folder as a second opinion: Android made
            // that folder on the drive itself, so if it is there, writing works.
            val writable = runCatching {
                chosen.dir.mkdirs()
                val probe = java.io.File(chosen.dir, "check.tmp")
                probe.writeText("ok")
                val ok = probe.exists() && probe.length() > 0L
                probe.delete()
                ok
            }.getOrDefault(false) || runCatching {
                chosen.dir.mkdirs() || chosen.dir.isDirectory
            }.getOrDefault(false)
            lines.add(
                context.getString(
                    if (writable) R.string.check_writable else R.string.check_not_writable
                )
            )
        }

        lines.add(
            context.getString(
                if (RecorderService.isRecording) R.string.check_recording_now
                else R.string.check_idle
            )
        )
        return lines.joinToString("\n\n")
    }
}
