package com.johnnytv.player

import android.content.Context
import android.util.AttributeSet
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/**
 * A channel card: four units wide by three tall, whatever width the grid hands
 * it, with its contents clipped to the rounded corners of its background.
 *
 * Channel logos are square or wide and must never be cropped, so unlike
 * [PosterFrame] the artwork inside sits within the card rather than filling it.
 */
class ChannelFrame @JvmOverloads constructor(
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
        val height = (width * CARD_RATIO).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    companion object {
        /** 4 wide by 3 tall - room for a square logo without wasting the row. */
        private const val CARD_RATIO = 0.75f
    }
}
