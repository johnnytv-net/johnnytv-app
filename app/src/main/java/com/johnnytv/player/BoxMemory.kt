package com.johnnytv.player

import android.app.ActivityManager
import android.content.Context

/**
 * HOW MUCH ROOM THIS BOX HAS.
 *
 * A Shield has three gigabytes and the 2018 Firestick 4K has one and a half,
 * of which Fire OS keeps a good share for itself. Everything in this app was
 * written and tested on the Shield, and the first sign that the Firestick was
 * different was customers' guides going black and the app vanishing - no
 * error, no crash report, because it was not the app that fell over: the box
 * ran short of memory and took the app away.
 *
 * So the app asks the box how much it has, once, and the screens that can
 * spend less on a smaller box do.
 */
object BoxMemory {

    /** Boxes with less than this are treated as light: no second decoder in the guide. */
    private const val LIGHT_BELOW_MB = 2_500L

    @Volatile private var totalMbCache = -1L

    fun totalMb(context: Context): Long {
        if (totalMbCache >= 0) return totalMbCache
        val info = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        }
        totalMbCache = info.totalMem / (1024L * 1024L)
        return totalMbCache
    }

    /** True on a Firestick-class box, false on a Shield. */
    fun isLight(context: Context): Boolean {
        val total = totalMb(context)
        return total in 1 until LIGHT_BELOW_MB
    }

    /** One line for the logs: what the app holds, and what the box has left. */
    fun snapshot(context: Context): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)
        val info = ActivityManager.MemoryInfo()
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        }
        val availMb = info.availMem / (1024L * 1024L)
        val low = if (info.lowMemory) ", box says LOW" else ""
        return "heap ${usedMb}/${maxMb}MB, box free ${availMb}MB of ${totalMb(context)}MB$low"
    }
}
