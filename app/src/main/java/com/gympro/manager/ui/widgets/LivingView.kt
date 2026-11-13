package com.gympro.manager.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * أساس الرسوم "الحيّة": View يرسم نفسه كدالة في الزمن (t بالميلي ثانية منذ ظهوره).
 *
 * - يبدأ تلقائياً حين يصبح ظاهراً فعلاً (مرتبطاً بالنافذة + ظاهراً + النافذة مرئية) ويتوقف تلقائياً
 *   عند الإخفاء/الانفصال/خروج التطبيق للخلفية → لا استهلاك بطارية وهو غير مرئي.
 * - كل مرة يظهر فيها من جديد يُعاد مشهد الدخول (intro) من الصفر.
 * - يحترم إعداد النظام "إزالة الحركات": يرسم الحالة النهائية الثابتة (staticMode) بلا أي حركة.
 * - الرسم في فضاء تصميم ثابت (designSize × designSize) يُكبَّر تلقائياً ليناسب حجم الـ View.
 */
abstract class LivingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var ticker: ValueAnimator? = null
    private var startedAt = SystemClock.uptimeMillis()

    /** true عند إيقاف الحركات من إعدادات النظام: نرسم الحالة النهائية فقط. */
    protected var staticMode = false
        private set

    /** الزمن الحالي للمشهد. في الوضع الثابت يكون كبيراً بما يكفي ليكتمل كل intro. */
    protected fun now(): Long =
        if (staticMode) STATIC_T else SystemClock.uptimeMillis() - startedAt

    /** يُستدعى كل إطار قبل الرسم (لمن يحتاج منطقاً غير الرسم، كاستدعاء callback). */
    protected open fun onTick(t: Long) {}

    private fun sync() {
        val shouldRun = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (!shouldRun) {
            stopTicker()
            return
        }
        staticMode = !ValueAnimator.areAnimatorsEnabled()
        if (staticMode) {
            stopTicker()
            invalidate()
            return
        }
        if (ticker != null) return
        startedAt = SystemClock.uptimeMillis()
        ticker = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                onTick(SystemClock.uptimeMillis() - startedAt)
                invalidate()
            }
            start()
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        stopTicker()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) sync()
    }

    protected companion object {
        const val STATIC_T = 60_000L
    }
}

/** دوال تسهيل (easing) صغيرة مشتركة بين الرسوم. */
internal object Motion {
    fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    /** تقدّم مقطع زمني: 0 قبل start، 1 بعد start+dur. */
    fun seg(t: Long, start: Long, dur: Long): Float = clamp01((t - start).toFloat() / dur.toFloat())

    fun outCubic(x: Float): Float {
        val a = 1f - x
        return 1f - a * a * a
    }

    fun inOutCubic(x: Float): Float {
        if (x < 0.5f) return 4f * x * x * x
        val a = -2f * x + 2f
        return 1f - a * a * a / 2f
    }

    /** ارتداد خفيف عند الوصول (يتجاوز 1 قليلاً ثم يستقر). */
    fun outBack(x: Float, s: Float = 1.70158f): Float {
        val a = x - 1f
        return 1f + (s + 1f) * a * a * a + s * a * a
    }

    /** نبضة 0→1→0 على مدى x∈[0,1]. */
    fun bump(x: Float): Float = if (x <= 0f || x >= 1f) 0f else sin(PI.toFloat() * x)

    /** موجة جيبية 0..1 بدورة period ms. */
    fun wave(t: Long, period: Long, phase: Float = 0f): Float =
        0.5f + 0.5f * sin(2f * PI.toFloat() * t.toFloat() / period.toFloat() + phase)
}
