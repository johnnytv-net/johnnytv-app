package com.johnnytv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.SimpleTimeZone
import java.util.TimeZone

/*
 * BRITISH COLUMBIA STOPPED CHANGING ITS CLOCKS, AND THE BOXES WERE NOT TOLD.
 *
 * From 2026 most of BC stays seven hours behind UTC all year: the clocks went
 * forward in March and did not go back on the first of November. A box only
 * knows that if its time-zone data is newer than the decision, and Firesticks
 * and Shields are rarely sent new data - so they went back an hour anyway and
 * have shown the wrong time since.
 *
 * Every time this app shows - the home clock, the guide, recordings, the
 * games - is read through the box's zone, so the app was wrong with it, and
 * a recording set for seven started at eight.
 *
 * The cure is narrow on purpose. Only a box set to BC's zone is touched, and
 * only if it still believes a BC winter is eight hours behind. A box with
 * current data, or one set to anywhere else, is left exactly as it is.
 */
object BcClock {

    /** The middle of a January, when the old rule and the new one disagree. */
    private const val A_WINTER_DAY = 1_800_000_000_000L
    private const val HOUR = 3_600_000

    fun install(context: Context) {
        fix()
        // Android puts the box's own zone back whenever the zone setting is
        // touched, so the correction is made again just after it has.
        runCatching {
            ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    fix()
                    Handler(Looper.getMainLooper()).postDelayed({ fix() }, 1500L)
                }
            }, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    fun fix() {
        runCatching {
            val zone = TimeZone.getDefault()
            if (zone.id != "America/Vancouver" && zone.id != "Canada/Pacific") return
            if (zone.getOffset(A_WINTER_DAY) != -8 * HOUR) return
            TimeZone.setDefault(SimpleTimeZone(-7 * HOUR, zone.id))
        }
    }
}
