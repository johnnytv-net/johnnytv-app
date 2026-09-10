package com.johnnytv.player

import android.content.Context
import android.util.AttributeSet
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * A card that is always a poster shape - three units tall for every two wide -
 * whatever width the grid hands it, with its artwork clipped to the rounded
 * corners of its background.
 */
class PosterFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val height = (width * POSTER_RATIO).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    companion object {
        /** Standard film-poster proportions: 2 wide by 3 tall. */
        private const val POSTER_RATIO = 1.5f
    }
}
