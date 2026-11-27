package com.gympro.manager.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.gympro.manager.R

/**
 * شريط تقدّم رفيع (حصة نوع الاشتراك من إجمالي الشهر): مسار زجاجي خافت + تعبئة بلون النوع بأطراف
 * مدوّرة حقيقية (لا scaleX فيتشوّه التدوير). يدعم RTL: تبدأ التعبئة من اليمين في الواجهة العربية.
 * لا يحمل نصاً؛ القيمة تُقرأ من الصف المحيط به (importantForAccessibility=no).
 */
class RevenueShareBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val rect = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.home_progress_track)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rev_type_monthly)
    }
    private var shown = 0f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setShare(fraction: Float, color: Int, animate: Boolean, delayMs: Long = 0L) {
        fillPaint.color = color
        val target = fraction.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate) {
            shown = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 650L
            startDelay = delayMs
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, trackPaint)
        if (shown <= 0f) return
        val w = (width * shown).coerceAtLeast(h)   // لا تقلّ التعبئة عن كبسولة كاملة
        if (layoutDirection == LAYOUT_DIRECTION_RTL) rect.set(width - w, 0f, width.toFloat(), h) else rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, fillPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
