package com.johnnytv.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.HorizontalScrollView

/**
 * A HorizontalScrollView that cannot be killed by a recycled child.
 *
 * WHAT WENT WRONG
 *
 * When something inside a scroll view takes focus, the scroll view remembers
 * that view so it can scroll it into sight after the next layout. The guide puts
 * a recycling list inside a scroll view, which means the remembered view can be
 * torn off and reused for a different row before that layout ever happens -
 * changing category is enough. The scroll view then tries to work out where its
 * remembered child sits, cannot find it, and Android throws:
 *
 *     IllegalArgumentException: parameter must be a descendant of this view
 *
 * It lands on the main thread during layout, so it takes the whole app down.
 * That is the crash: not memory, not the size of the service, just a note the
 * scroll view kept about a view that no longer exists.
 *
 * The right response is to ignore it. The only thing lost is one frame of
 * scrolling towards a view that is not on screen any more, which is exactly what
 * should happen. Every other kind of failure is left alone to be reported.
 */
class SafeHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        try {
            super.onSizeChanged(w, h, oldw, oldh)
        } catch (e: IllegalArgumentException) {
            // The child it wanted to scroll to has been recycled. Nothing to do.
        }
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        try {
            super.requestChildFocus(child, focused)
        } catch (e: IllegalArgumentException) {
            // Same cause, reached from the other direction.
        }
    }

    override fun computeScroll() {
        try {
            super.computeScroll()
        } catch (e: IllegalArgumentException) {
        }
    }
}
