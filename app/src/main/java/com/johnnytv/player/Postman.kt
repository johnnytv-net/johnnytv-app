package com.johnnytv.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * THE POSTMAN
 *
 * Somebody at work sets a recording on their phone. It goes into the letterbox
 * on the website, and this is the part that goes and looks.
 *
 * The looking has to happen with the app closed and the television off, which
 * is the whole point - a recording you can only set while sitting in front of
 * the box is not worth setting from anywhere. So it is an alarm rather than a
 * loop: the system wakes the app every few minutes, it reads the letterbox,
 * schedules anything waiting, and goes straight back to sleep. Nothing runs in
 * between.
 *
 * Five minutes is the compromise. A recording for tonight does not care, and
 * one set to start in the next few minutes is late by a little rather than
 * missed - which the two minutes of padding at the front mostly covers anyway.
 */
object Postman {

    /** How often to look in the letterbox. */
    private const val EVERY_MS = 5L * 60L * 1000L

    private const val ACTION_COLLECT = "com.johnnytv.player.COLLECT_RECORDINGS"

    /** Sets the standing alarm. Safe to call as often as you like. */
    fun start(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (prefs.username.isBlank()) return

        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, PostmanReceiver::class.java).setAction(ACTION_COLLECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Inexact and repeating: this is a chore, not an appointment, and an
        // inexact alarm lets the system fold it in with everything else it was
        // going to wake up for anyway. It costs the box almost nothing.
        runCatching {
            manager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + EVERY_MS,
                EVERY_MS,
                pending
            )
        }
    }

    /**
     * Reads the letterbox and schedules what is in it.
     *
     * Blocking, so call it off the main thread. Quiet about failure: a phone
     * with no signal, a website having a moment, a box with the internet
     * unplugged - none of those should produce anything a customer sees.
     */
    /** Collects, and describes what happened - for the row in Settings. */
    fun collectAndDescribe(context: Context): String {
        val app = context.applicationContext
        val prefs = Prefs(app)
        prefs.rememberCurrentAccount()
        val lines = prefs.knownAccounts().joinToString("\n") { account ->
            val waiting = CastLink.pendingRecordings(account.username, account.password).size
            account.username + " — waiting: " + waiting
        }
        val before = Schedules.upcoming(app).size
        runCatching { collect(app) }
        val after = Schedules.upcoming(app).size
        return "Lines this box knows:\n" + lines +
            "\n\nScheduled before: " + before + "\nScheduled now: " + after
    }

    fun collect(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        // Whatever line the box is on now joins the list, so the next time it
        // is switched to another one this line is still looked after.
        prefs.rememberCurrentAccount()

        /*
         * EVERY LINE'S LETTERBOX, NOT ONLY THE ONE ON SCREEN.
         *
         * Each line has its own box in the letterbox, because the note is filed
         * under the username that sent it. Looking only in the box of the line
         * the television is showing meant a recording set on Dino, while the
         * house watched Edge, was never collected at all - it sat there until it
         * expired, and nothing said why.
         */
        for (account in prefs.knownAccounts()) {
            collectFor(app, account)
        }
    }

    private fun collectFor(app: Context, account: Prefs.Account) {
        val waiting = CastLink.pendingRecordings(account.username, account.password)
        if (waiting.isEmpty()) return

        val taken = ArrayList<String>()
        for (request in waiting) {
            // Already on the list: the confirmation must have gone astray on a
            // previous round. Say so again rather than scheduling it twice.
            val already = Schedules.all(app).any {
                it.streamId == request.streamId && it.startAt == request.startAt
            }
            if (already) {
                taken.add(request.rid)
                continue
            }

            // A second note for the same channel on the same day is somebody
            // changing their mind, not asking for two recordings. The newest
            // wins; the one it replaces is taken off the list.
            val sameDay = 12L * 60L * 60L * 1000L
            for (old in Schedules.all(app)) {
                val clash = old.streamId == request.streamId &&
                    old.account.equals(account.username, true) &&
                    Math.abs(old.startAt - request.startAt) < sameDay &&
                    old.id.startsWith("p")
                if (clash) Schedules.remove(app, old.id)
            }

            val item = Scheduled(
                id = "p" + request.rid,
                title = request.title.ifBlank { request.channel },
                channel = request.channel,
                streamId = request.streamId,
                startAt = request.startAt,
                endAt = request.endAt,
                // Set from a phone, usually for something with no guide behind
                // it, so the same generous padding as recording by time.
                padStartMs = 2L * 60_000L,
                padEndMs = 10L * 60_000L,
                series = false,
                account = account.username
            )
            Schedules.put(app, item)
            taken.add(request.rid)
        }

        CastLink.confirmRecordings(account.username, account.password, taken)

        // Tell the phone what the list looks like now, so somebody at work sees
        // their recording confirmed rather than wondering all afternoon.
        val clock = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val upcoming = Schedules.upcoming(app)
            .filter { it.account.isBlank() || it.account.equals(account.username, true) }
            .sortedBy { it.startAt }
            .take(3)
        val text = if (upcoming.isEmpty()) {
            "Nothing scheduled"
        } else {
            upcoming.joinToString(" · ") {
                it.title.take(28) + " " + clock.format(Date(it.startAt))
            }
        }
        CastLink.reportScheduled(account.username, account.password, text)
    }
}

/** The alarm going off: go and look, then go back to sleep. */
class PostmanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            runCatching { Postman.collect(app) }
            // Alarms do not survive an app being force-stopped, and the system
            // can drop a repeating one. Setting it again on every round means
            // it mends itself rather than quietly stopping for good.
            runCatching { Postman.start(app) }
            pending.finish()
        }
    }
}
