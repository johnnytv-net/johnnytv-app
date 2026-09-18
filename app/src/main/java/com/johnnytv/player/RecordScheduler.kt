package com.johnnytv.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * SCHEDULED RECORDINGS
 *
 * A recording somebody set for tonight has to happen tonight, whatever the box
 * is doing at the time - asleep with the television off, or freshly restarted
 * after the power went out. So none of this lives in the app's memory: each
 * one is a row in a small file plus an alarm with the operating system, and
 * every alarm is set again from that file after a reboot.
 *
 * Padding is added at both ends because broadcast times are approximate and
 * sport is worse than approximate. Five minutes over is enough for a drama;
 * half an hour is what a hockey game needs, and a viewer who has to watch the
 * last minute somewhere else will not use the recorder again.
 */
data class Scheduled(
    val id: String,
    var title: String,
    var channel: String,
    var streamId: String,
    /** When the programme itself starts and ends, before padding. */
    var startAt: Long,
    var endAt: Long,
    var padStartMs: Long = 60_000L,
    var padEndMs: Long = 5L * 60_000L,
    /** Catch every airing of this title on this channel, not just this one. */
    var series: Boolean = false
) {
    val recordFrom: Long get() = startAt - padStartMs
    val recordUntil: Long get() = endAt + padEndMs

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("t", title)
        .put("c", channel)
        .put("sid", streamId)
        .put("s", startAt)
        .put("e", endAt)
        .put("ps", padStartMs)
        .put("pe", padEndMs)
        .put("sr", series)

    companion object {
        fun fromJson(o: JSONObject): Scheduled = Scheduled(
            id = o.optString("id", ""),
            title = o.optString("t", ""),
            channel = o.optString("c", ""),
            streamId = o.optString("sid", ""),
            startAt = o.optLong("s", 0L),
            endAt = o.optLong("e", 0L),
            padStartMs = o.optLong("ps", 60_000L),
            padEndMs = o.optLong("pe", 5L * 60_000L),
            series = o.optBoolean("sr", false)
        )
    }
}

object Schedules {

    private const val FILE = "schedules.json"

    @Synchronized
    fun all(context: Context): List<Scheduled> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val out = ArrayList<Scheduled>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                out.add(Scheduled.fromJson(o))
            }
            out.sortedBy { it.startAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun put(context: Context, item: Scheduled) {
        val list = ArrayList(all(context).filter { it.id != item.id })
        list.add(item)
        write(context, list)
        RecordScheduler.arm(context, item)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val list = ArrayList(all(context))
        val found = list.firstOrNull { it.id == id } ?: return
        list.remove(found)
        write(context, list)
        RecordScheduler.disarm(context, found)
    }

    /** Anything still to come, with finished one-offs cleared out. */
    fun upcoming(context: Context): List<Scheduled> {
        val now = System.currentTimeMillis()
        return all(context).filter { it.series || it.recordUntil > now }
    }

    /** Two recordings cannot run at once on a one-connection line. */
    fun clashOf(context: Context, item: Scheduled): Scheduled? = upcoming(context)
        .firstOrNull {
            it.id != item.id &&
                it.recordFrom < item.recordUntil &&
                item.recordFrom < it.recordUntil
        }

    private fun write(context: Context, list: List<Scheduled>) {
        val array = JSONArray()
        for (item in list) array.put(item.toJson())
        runCatching { File(context.filesDir, FILE).writeText(array.toString()) }
    }
}

object RecordScheduler {

    const val ACTION_FIRE = "com.johnnytv.player.START_SCHEDULED"
    const val EXTRA_ID = "scheduled_id"

    /** Sets, or resets, the alarm for one scheduled recording. */
    fun arm(context: Context, item: Scheduled) {
        val app = context.applicationContext
        val at = item.recordFrom
        if (at <= System.currentTimeMillis() && item.recordUntil <= System.currentTimeMillis()) return
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingFor(app, item.id)

        // Exact, and allowed to fire in doze - a recording that starts "some time
        // after" the programme did is not a recording of that programme. Where the
        // system will not grant exact alarms, an inexact one still fires within a
        // few minutes, which the padding partly covers.
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
        runCatching {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    fun disarm(context: Context, item: Scheduled) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { manager.cancel(pendingFor(app, item.id)) }
    }

    /** Every alarm set again - after a reboot, or when the app starts. */
    fun armAll(context: Context) {
        for (item in Schedules.upcoming(context)) arm(context, item)
    }

    private fun pendingFor(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.hashCode(),
        Intent(context, RecordAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/**
 * The alarm going off.
 *
 * Starting a foreground service from a receiver is normally refused on modern
 * Android; an exact alarm the user themselves set is one of the few cases where
 * it is allowed, which is exactly what this is.
 */
class RecordAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(RecordScheduler.EXTRA_ID) ?: return
        val app = context.applicationContext
        val item = Schedules.all(app).firstOrNull { it.id == id } ?: return

        val channel = Catalog.live.firstOrNull { it.streamId == item.streamId }
            ?: StreamItem(
                streamId = item.streamId,
                num = "",
                name = item.channel,
                icon = "",
                containerExtension = "ts",
                categoryId = "",
                added = 0L,
                kind = Kind.LIVE
            )

        RecorderService.start(app, item.title, channel, item.recordUntil)

        if (item.series) {
            // Look for the next airing of the same title on the same channel and
            // set that one up too. Done off the main thread with the broadcast
            // held open, and quietly abandoned if the portal is not answering -
            // a failed lookup must never cost us the recording that just started.
            val pending = goAsync()
            thread {
                runCatching { scheduleNextAiring(app, item) }
                pending.finish()
            }
        } else {
            Schedules.remove(app, item.id)
        }
    }

    private fun scheduleNextAiring(context: Context, item: Scheduled) {
        val prefs = Prefs(context)
        val listings = runCatching { prefs.client().epg(item.streamId) }.getOrDefault(emptyList())
        val after = item.endAt + 60_000L
        val next = listings
            .filter { it.start > after && it.title.equals(item.title, ignoreCase = true) }
            .minByOrNull { it.start } ?: return

        Schedules.put(
            context,
            item.copy(
                id = "s" + next.start,
                startAt = next.start,
                endAt = if (next.end > next.start) next.end else next.start + 60L * 60_000L
            )
        )
    }
}

/** After a restart the alarms are gone, so they are all set again from the file. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        RecordScheduler.armAll(context.applicationContext)
    }
}
