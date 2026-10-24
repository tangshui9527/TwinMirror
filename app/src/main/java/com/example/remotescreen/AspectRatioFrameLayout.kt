package com.example.remotescreen

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * FrameLayout that sizes itself to a fixed width/height aspect ratio, fitting inside the
 * measured constraints (aspect-fit). Used to letterbox the mirrored video so the decoded
 * frame fills the SurfaceView exactly and touch mapping has no black-bar offsets.
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    @Volatile var aspectRatio = 0f // width / height; 0 = fall through to default measuring

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ratio = aspectRatio
        if (ratio <= 0f) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (maxW / ratio).toInt()
        if (h > maxH) {
            h = maxH
            w = (maxH * ratio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}
