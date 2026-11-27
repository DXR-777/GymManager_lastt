package com.gympro.manager.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.gympro.manager.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * حلقة Donut لتوزيع إيراد الشهر حسب نوع الاشتراك (الجزء 2).
 *
 * - الشرائح تُرسم بحركة sweep متتابعة (واحدة تلو الأخرى) عند أول ظهور للبيانات، وبانتقال سلس
 *   بين النسب القديمة والجديدة عند تغيّر البيانات لاحقاً.
 * - setSelected(index) يبرز شريحة (أسمك + تعتيم الباقي)؛ ولمس الحلقة نفسها يُبلغ [onSliceSelected]
 *   (الفقرة المسؤولة عن الأسطورة تقرّر التبديل) — فتتزامن الحلقة والصفوف.
 * - وسط الحلقة نصّان (المبلغ + وصف) يضبطهما [setCenter] ويُصغَّران تلقائياً ليتّسعا داخل الحلقة.
 * - كل القيم صفر: حلقة شبحية منقطة.
 * - RTL: البداية من الأعلى وبعكس عقارب الساعة. لا تخصيص كائنات داخل onDraw.
 */
class RevenueDonutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** يُستدعى بفهرس الشريحة التي لُمست (أو -1 للمس خارجها). */
    var onSliceSelected: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var n = 0
    private var colors = IntArray(0)
    private var fromFrac = FloatArray(0)
    private var toFrac = FloatArray(0)
    private var emph = FloatArray(0)
    private var emphFrom = FloatArray(0)
    private var emphTo = FloatArray(0)
    private var selAnyFrom = 0f
    private var selAnyTo = 0f
    private var selAny = 0f
    private var selT = 1f
    private var allZero = true
    private var hasData = false
    private var sequential = true
    private var animT = 1f

    private var primaryText = ""
    private var secondaryText = ""
    private var primarySize = sp(22f)
    private var secondarySize = sp(11.5f)

    private val oval = RectF()
    private val strokeBase = dp(15f)
    private val strokeBoost = dp(5f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeBase
        color = ContextCompat.getColor(context, R.color.home_progress_track)
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(5f)
        color = ContextCompat.getColor(context, R.color.home_ghost_stroke)
        pathEffect = DashPathEffect(floatArrayOf(0.1f, dp(10f)), 0f)
    }
    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.home_ink)
        textSize = primarySize
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.home_mute)
        textSize = secondarySize
    }

    private val easeOut = DecelerateInterpolator(1.7f)
    private var animator: ValueAnimator? = null
    private var selAnimator: ValueAnimator? = null
    private var selected = -1

    init {
        loadFont(R.font.cairo_extrabold) { primaryPaint.typeface = it }
        loadFont(R.font.cairo_medium) { secondaryPaint.typeface = it }
    }

    private fun loadFont(res: Int, onReady: (Typeface) -> Unit) {
        try {
            ResourcesCompat.getFont(context, res, object : ResourcesCompat.FontCallback() {
                override fun onFontRetrieved(typeface: Typeface) {
                    onReady(typeface)
                    fitCenter()
                    invalidate()
                }

                override fun onFontRetrievalFailed(reason: Int) {}
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------ واجهة الاستخدام
    /** [values] بترتيب الشرائح و[sliceColors] ألوانها؛ مجموع صفر = حلقة شبحية. */
    fun setData(values: DoubleArray, sliceColors: IntArray, animate: Boolean = true) {
        val count = values.size
        val total = values.sum()
        val newTo = FloatArray(count) { if (total > 0.0) (values[it] / total).toFloat() else 0f }
        val newZero = total <= 0.0

        val unchanged = hasData && count == n && newZero == allZero && newTo.contentEquals(toFrac)
        colors = sliceColors.copyOf(count)
        if (unchanged) {
            invalidate()
            return
        }
        // أول ظهور أو بعد حالة الصفر: sweep متتابع. وإلا: انتقال بين النسبتين.
        val wasEmpty = !hasData || allZero || count != n
        val newFrom = FloatArray(count) { if (!wasEmpty) fracNow(it) else 0f }
        if (emph.size != count) {
            emph = FloatArray(count); emphFrom = FloatArray(count); emphTo = FloatArray(count)
        }
        n = count
        allZero = newZero
        toFrac = newTo
        fromFrac = newFrom
        sequential = wasEmpty
        hasData = true
        if (selected >= n) selected = -1
        startMain(animate && !newZero)
    }

    /** يبرز شريحة (أو يلغي الإبراز بـ -1) بانتقال قصير. */
    fun setSelected(index: Int) {
        val target = if (index in 0 until n) index else -1
        if (target == selected) return
        selected = target
        if (emph.size != n) return
        for (i in 0 until n) {
            emphFrom[i] = emph[i]
            emphTo[i] = if (i == target) 1f else 0f
        }
        selAnyFrom = selAny
        selAnyTo = if (target >= 0) 1f else 0f
        selAnimator?.cancel()
        selAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                for (i in 0 until n) emph[i] = emphFrom[i] + (emphTo[i] - emphFrom[i]) * t
                selAny = selAnyFrom + (selAnyTo - selAnyFrom) * t
                invalidate()
            }
            start()
        }
    }

    fun setCenter(primary: String, secondary: String) {
        primaryText = primary
        secondaryText = secondary
        fitCenter()
        invalidate()
    }

    // ------------------------------------------------------------------ الحركة
    private fun fracNow(i: Int): Float {
        if (i >= toFrac.size || i >= fromFrac.size) return 0f
        return if (sequential) toFrac[i]
        else fromFrac[i] + (toFrac[i] - fromFrac[i]) * easeOut.getInterpolation(animT)
    }

    private fun startMain(animate: Boolean) {
        animator?.cancel()
        if (!animate) {
            animT = 1f
            invalidate()
            return
        }
        animT = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (sequential) 1100L else 600L
            interpolator = LinearInterpolator()
            addUpdateListener {
                animT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // ------------------------------------------------------------------ القياس
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(208f).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = strokeBase / 2f + strokeBoost + dp(2f)
        val side = min(w, h).toFloat()
        val left = (w - side) / 2f + inset
        val top = (h - side) / 2f + inset
        oval.set(left, top, left + side - 2 * inset, top + side - 2 * inset)
        fitCenter()
    }

    /** يصغّر نصّي الوسط حتى يتّسعا داخل الحلقة الداخلية (بلا قصّ مهما كبر المبلغ). */
    private fun fitCenter() {
        if (oval.width() <= 0f) return
        val inner = oval.width() - strokeBase * 2f - dp(10f)
        var size = sp(24f)
        primaryPaint.textSize = size
        while (size > sp(11f) && primaryPaint.measureText(primaryText) > inner) {
            size -= sp(1f)
            primaryPaint.textSize = size
        }
        primarySize = size
        var s2 = sp(11.5f)
        secondaryPaint.textSize = s2
        while (s2 > sp(9f) && secondaryPaint.measureText(secondaryText) > inner) {
            s2 -= sp(0.5f)
            secondaryPaint.textSize = s2
        }
        secondarySize = s2
    }

    // ------------------------------------------------------------------ الرسم
    override fun onDraw(canvas: Canvas) {
        if (!hasData || oval.width() <= 0f) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val dir = if (rtl) -1f else 1f
        val radius = oval.width() / 2f

        if (allZero) {
            canvas.drawArc(oval, 0f, 360f, false, ghostPaint)
        } else {
            canvas.drawOval(oval, trackPaint)
            drawSlices(canvas, dir, radius)
        }
        drawCenter(canvas)
    }

    private fun drawSlices(c: Canvas, dir: Float, radius: Float) {
        var nonZero = 0
        for (i in 0 until n) if (toFrac[i] > 0f) nonZero++
        // الفجوة بين الشرائح = سُمك الغطاء الدائري + 3dp مقيسة بالدرجات
        val gapDeg = if (nonZero > 1) Math.toDegrees(((strokeBase + dp(3f)) / radius).toDouble()).toFloat() else 0f
        val reveal = if (sequential) easeOut.getInterpolation(animT) else 1f
        var cum = 0f
        for (i in 0 until n) {
            val alloc = fracNow(i)
            if (alloc <= 0f) continue
            val shown = if (sequential) (reveal - cum).coerceIn(0f, alloc) else alloc
            val startDeg = cum * 360f
            cum += alloc
            if (shown <= 0f) continue
            val complete = shown >= alloc - 0.0001f
            var vis = shown * 360f - gapDeg
            if (complete) vis = max(vis, 0.4f)
            if (vis <= 0f) continue
            val e = emph.getOrElse(i) { 0f }
            val dim = selAny * (1f - e)
            slicePaint.color = colors[i]
            slicePaint.strokeWidth = strokeBase + strokeBoost * e
            slicePaint.alpha = (255 * (1f - 0.62f * dim)).toInt()
            c.drawArc(oval, -90f + dir * (startDeg + gapDeg / 2f), dir * vis, false, slicePaint)
        }
    }

    private fun drawCenter(c: Canvas) {
        val cx = oval.centerX()
        val cy = oval.centerY()
        val pm = primaryPaint.fontMetrics
        val sm = secondaryPaint.fontMetrics
        val ph = pm.descent - pm.ascent
        val sh = sm.descent - sm.ascent
        val gap = dp(1f)
        val top = cy - (ph + gap + sh) / 2f
        c.drawText(primaryText, cx, top - pm.ascent, primaryPaint)
        c.drawText(secondaryText, cx, top + ph + gap - sm.ascent, secondaryPaint)
    }

    // ------------------------------------------------------------------ اللمس
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasData || allZero || onSliceSelected == null) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                onSliceSelected?.invoke(sliceAt(event.x, event.y))
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** الشريحة تحت نقطة اللمس (حلقة بسماحية 12dp)، أو -1 خارجها. */
    private fun sliceAt(x: Float, y: Float): Int {
        val dx = x - oval.centerX()
        val dy = y - oval.centerY()
        val dist = hypot(dx, dy)
        val radius = oval.width() / 2f
        val tol = strokeBase / 2f + dp(12f)
        if (dist < radius - tol || dist > radius + tol) return -1
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f   // 0 = أعلى، مع عقارب الساعة
        deg = ((deg % 360f) + 360f) % 360f
        if (layoutDirection == LAYOUT_DIRECTION_RTL) deg = (360f - deg) % 360f
        val f = deg / 360f
        var cum = 0f
        for (i in 0 until n) {
            cum += toFrac[i]
            if (f < cum && toFrac[i] > 0f) return i
        }
        return -1
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        selAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
