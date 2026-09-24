package com.johnnytv.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap

/**
 * WHICH COLOUR OF PLATE A LOGO NEEDS.
 *
 * Channel logos come two ways and nobody labels which is which: dark artwork
 * meant for a white background, and white artwork meant for a dark one. The
 * guide used to put every one of them on a white tile, so MSG, MSGSN and the
 * rest - white lettering on transparent - became blank white rectangles. They
 * had loaded perfectly; there was simply nothing to see.
 *
 * So the picture is asked. A handful of pixels are sampled, the transparent
 * ones ignored, and what is left decides the plate: pale lettering gets a dark
 * tile, dark lettering keeps the white one.
 *
 * Answers are remembered by address, because a guide scrolls past the same
 * logos many times and this should cost nothing after the first look.
 */
object LogoTint {

    private val answers = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private const val LIMIT = 4_000

    /** True when the logo is pale and wants a dark plate behind it. */
    fun needsDarkPlate(url: String, drawable: Drawable?): Boolean {
        answers[url]?.let { return it }
        val pale = isPale(drawable)
        if (answers.size < LIMIT) answers[url] = pale
        return pale
    }

    /** What we decided last time, or null if this logo has not been seen yet. */
    fun remembered(url: String): Boolean? = answers[url]

    private fun isPale(drawable: Drawable?): Boolean {
        val source = (drawable as? BitmapDrawable)?.bitmap
            ?: runCatching { drawable?.toBitmap() }.getOrNull()
            ?: return false
        // Small enough to be free, large enough to be representative.
        val sample = runCatching {
            Bitmap.createScaledBitmap(source, 12, 12, true)
        }.getOrNull() ?: return false

        var brightness = 0.0
        var counted = 0
        for (x in 0 until sample.width) {
            for (y in 0 until sample.height) {
                val pixel = sample.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                // Edges of lettering are half-transparent; only solid pixels
                // say anything useful about the colour.
                if (alpha < 140) continue
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                brightness += 0.299 * r + 0.587 * g + 0.114 * b
                counted++
            }
        }
        runCatching { if (sample != source) sample.recycle() }
        if (counted == 0) return false
        // Anything above this is lettering that would disappear on white.
        return brightness / counted > 185.0
    }
}
