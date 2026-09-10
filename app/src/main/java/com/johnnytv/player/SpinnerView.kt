package com.johnnytv.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * THE LOADING INDICATOR, USED EVERYWHERE.
 *
 * One blue half circle turning on nothing. No ring behind it and no mark inside
 * it: both only ever competed with the one thing on screen that is actually
 * moving, and a faded logo sitting inside the arc reads as a dark blob rather
 * than as a logo. An arc is also what reads as motion at all - a complete ring
 * rotating looks like nothing is happening.
 *
 * Starts and stops itself with its own visibility, so screens show and hide it
 * like any other view and nothing has to be remembered.
 */
class SpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.spinner_arc)
        strokeWidth = STROKE_DP * density
    }

    private val ring = RectF()

    private var angle = 0f
    private val turn = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = TURN_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = STROKE_DP * density / 2f
        val size = minOf(w, h).toFloat()
        val left = (w - size) / 2f + inset
        val top = (h - size) / 2f + inset
        ring.set(left, top, left + size - inset * 2, top + size - inset * 2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(ring, angle, SWEEP_DEGREES, false, arcPaint)
    }

    // ---------- running only while on screen ----------

    private fun sync() {
        val shouldTurn = isShown && windowVisibility == View.VISIBLE
        if (shouldTurn == turn.isStarted) return
        if (shouldTurn) turn.start() else turn.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        turn.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        sync()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        sync()
    }

    private companion object {
        /** A little under half the ring - enough to read as a shape, not a ring. */
        const val SWEEP_DEGREES = 160f
        const val STROKE_DP = 4f
        const val TURN_MS = 1_150L
    }
}
