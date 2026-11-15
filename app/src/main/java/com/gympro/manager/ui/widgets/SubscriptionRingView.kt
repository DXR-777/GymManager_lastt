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
 * حلقة تقدّم الاشتراك حول صورة العضو: قوس 270° (الفجوة في الأسفل) بسُمك 6dp وأطراف دائرية.
 * المسار الخلفي ثابت، والقوس الملوَّن يمثّل الأيام المتبقية (fraction من 0 إلى 1).
 * يدعم RTL: في الواجهات العربية يبدأ القوس من أسفل اليمين ويدور عكس عقارب الساعة.
 * لا يحتوي على نص؛ الحالة تُقرأ من العنوان الكبير تحته (لذلك importantForAccessibility=no).
 */
class SubscriptionRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokePx = 6f * resources.displayMetrics.density
    private val oval = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokePx
        color = ContextCompat.getColor(context, R.color.home_ring_mid)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokePx
        color = ContextCompat.getColor(context, R.color.home_mint)
    }

    private var shownFraction = 0f
    private var animator: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** يضبط القيمة واللون؛ مع animate=true يرسم القوس نفسه خلال [durationMs]. */
    fun setRing(fraction: Float, color: Int, animate: Boolean, durationMs: Long = 800L) {
        progressPaint.color = color
        val target = fraction.coerceIn(0f, 1f)
        animator?.cancel()
        if (!animate) {
            shownFraction = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, target).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                shownFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val half = strokePx / 2f
        oval.set(half, half, width - half, height - half)
        // المسار: 270° ثابتة من 135° (أسفل-يسار) بالاتجاه الموجب؛ متناظر فلا يتأثر بـ RTL.
        canvas.drawArc(oval, START_ANGLE, SWEEP_TOTAL, false, trackPaint)
        if (shownFraction <= 0f) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        if (rtl) {
            // يبدأ من أسفل-يمين (45°) ويدور عكس عقارب الساعة.
            canvas.drawArc(oval, 45f, -SWEEP_TOTAL * shownFraction, false, progressPaint)
        } else {
            canvas.drawArc(oval, START_ANGLE, SWEEP_TOTAL * shownFraction, false, progressPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val START_ANGLE = 135f
        const val SWEEP_TOTAL = 270f
    }
}
