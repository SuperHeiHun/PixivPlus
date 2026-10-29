package com.heihun.pixivplus.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton
import com.heihun.pixivplus.ui.theme.Md3

/**
 * Material Design 3 风格的 Switch。
 *
 * 宿主进程无法加载模块资源，因此不使用 Material 库的 drawable，
 * 全部轨道、滑块与状态层都由 Canvas 绘制。
 */
class Md3Switch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CompoundButton(context, attrs) {

    private val trackWidth = dp(52)
    private val trackHeight = dp(32)
    private val thumbRadiusMin = dp(8f).toFloat()
    private val thumbRadiusMax = dp(12f).toFloat()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = Md3.colors(context)

    private var progress: Float = if (isChecked) 1f else 0f
    private var animator: ValueAnimator? = null

    init {
        setButtonDrawable(null)
        background = null
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        minimumWidth = trackWidth
        minimumHeight = trackHeight
        foreground = RippleDrawable(
            ColorStateList.valueOf(withAlpha(colors.primary, 0x1F)),
            null,
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = trackHeight / 2f
            }
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(trackWidth, widthMeasureSpec),
            resolveSize(trackHeight, heightMeasureSpec)
        )
    }

    override fun setChecked(checked: Boolean) {
        val changed = isChecked != checked
        super.setChecked(checked)
        if (changed) animateTo(if (checked) 1f else 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = this.width.toFloat()
        val height = this.height.toFloat()
        if (width <= 0f || height <= 0f) return

        val enabledAlpha = if (isEnabled) 1f else 0.38f
        val p = progress.coerceIn(0f, 1f)

        val trackColor = blend(colors.surfaceContainerHighest, colors.primary, p)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(trackColor, (255 * enabledAlpha).toInt())
        canvas.drawRoundRect(0f, 0f, width, height, height / 2f, height / 2f, paint)

        val borderAlpha = ((1f - p) * enabledAlpha * 255f).toInt()
        if (borderAlpha > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f).toFloat()
            paint.color = withAlpha(colors.outline, borderAlpha)
            canvas.drawRoundRect(
                paint.strokeWidth / 2f,
                paint.strokeWidth / 2f,
                width - paint.strokeWidth / 2f,
                height - paint.strokeWidth / 2f,
                (height - paint.strokeWidth) / 2f,
                (height - paint.strokeWidth) / 2f,
                paint
            )
        }

        val radius = lerp(thumbRadiusMin, thumbRadiusMax, p)
        val centerX = lerp(dp(16f).toFloat(), width - dp(4f) - thumbRadiusMax, p)
        val centerY = height / 2f
        val thumbColor = blend(colors.outline, colors.onPrimary, p)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(thumbColor, (255 * enabledAlpha).toInt())
        canvas.drawCircle(centerX, centerY, radius, paint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun lerp(from: Float, to: Float, fraction: Float): Float {
        return from + (to - from) * fraction
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val start = (from shr shift) and 0xFF
            val end = (to shr shift) and 0xFF
            return (start + (end - start) * fraction).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }
}
