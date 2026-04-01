package com.hik.pipeplayer.view

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * 保持宽高比的 TextureView
 *
 * 根据视频尺寸自动调整显示比例，保持视频原始比例不被拉伸
 * 在 FrameLayout 中使用，设置 layout_gravity="center" 实现居中显示
 */
class AspectRatioTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    /**
     * 设置视频尺寸
     *
     * @param width 视频宽度
     * @param height 视频高度
     */
    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 获取父容器给定的尺寸限制
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        // 如果没有设置视频尺寸，使用默认的测量
        if (videoWidth == 0 || videoHeight == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

        val finalWidth: Int
        val finalHeight: Int

        // 根据父容器的尺寸限制计算合适的显示尺寸
        when {
            // 如果宽度和高度都是精确值，按照比例适配
            widthMode == MeasureSpec.EXACTLY && heightMode == MeasureSpec.EXACTLY -> {
                val viewRatio = widthSize.toFloat() / heightSize.toFloat()
                if (viewRatio > videoRatio) {
                    // 容器比视频宽，以高度为基准
                    finalHeight = heightSize
                    finalWidth = (heightSize * videoRatio).toInt()
                } else {
                    // 容器比视频窄，以宽度为基准
                    finalWidth = widthSize
                    finalHeight = (widthSize / videoRatio).toInt()
                }
            }
            // 只有宽度是精确值
            widthMode == MeasureSpec.EXACTLY -> {
                finalWidth = widthSize
                finalHeight = (widthSize / videoRatio).toInt()
            }
            // 只有高度是精确值
            heightMode == MeasureSpec.EXACTLY -> {
                finalHeight = heightSize
                finalWidth = (heightSize * videoRatio).toInt()
            }
            // 都不是精确值，使用视频原始尺寸
            else -> {
                finalWidth = videoWidth
                finalHeight = videoHeight
            }
        }

        setMeasuredDimension(finalWidth, finalHeight)
    }
}
