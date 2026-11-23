package com.gympro.manager.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * رسم "السجل فارغ" المتحرّك: ثلاث بطاقات زجاجية متراصّة.
 *  • دخول: البطاقات ترتفع وتظهر تباعاً، ثم تتمدّد خطوط البطاقة الأمامية، وتنبثق شارة البحث (عند الفلتر) بارتداد.
 *  • سكون حيّ: تنفّس عمودي هادئ بأطوار مختلفة للطبقات، لمعة ضوء تعبر البطاقة الأمامية كل ~4ث،
 *    وموجة خفيفة حول الدائرة؛ والعدسة تتمايل قليلاً.
 * الرسم دالّة في الزمن (لا حالة متراكمة). يتوقف تلقائياً عند الإخفاء/الفصل، ويصير ثابتاً إن عُطّلت
 * الحركات في النظام. الأبعاد المرجعية 160×112dp وتُعكس تلقائياً في RTL (الدائرة يمين الشاشة).
 */
class EmptyHistoryArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** شارة العدسة: تظهر فقط في حالة "لا نتائج للفلتر". */
    var showBadge: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                restartEntrance()
            }
        }

    private class Card(
        val x: Float, val y: Float, val w: Float, val h: Float, val r: Float,
        val base: Int, val glass: Int, val line: Int, val delay: Long, val amp: Float, val phase: Float
    )

    private val cards = listOf(
        Card(30f, 4f, 100f, 34f, 12f, 0xFF121337.toInt(), 0x0DFFFFFF, 0x1AFFFFFF, 0L, 1.2f, 0.00f),
        Card(19f, 22f, 122f, 40f, 14f, 0xFF16173D.toInt(), 0x14FFFFFF, 0x26FFFFFF, 90L, 1.7f, 0.18f),
        Card(8f, 42f, 144f, 62f, 18f, 0xFF1B1C45.toInt(), 0x1CFFFFFF, 0x38FFFFFF, 180L, 2.2f, 0.36f)
    )

    private val lav = 0xE4D9FF
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    private var anim: ValueAnimator? = null
    private var t0 = 0L

    private fun dens() = resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((REF_W * dens()).toInt(), widthMeasureSpec),
            resolveSize((REF_H * dens()).toInt(), heightMeasureSpec)
        )
    }

    // ───── دورة الحياة ─────
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartEntrance()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) restartEntrance() else stopAnim()
    }

    private fun stopAnim() {
        anim?.cancel()
        anim = null
    }

    private fun restartEntrance() {
        stopAnim()
        if (!isAttachedToWindow || !isShown) {
            invalidate()
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            invalidate()
            return
        }
        t0 = SystemClock.uptimeMillis()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
        invalidate()
    }

    // ───── أدوات زمنية ─────
    private fun seg(t: Long, start: Long, dur: Long) = ((t - start).toFloat() / dur).coerceIn(0f, 1f)
    private fun outCubic(x: Float): Float { val k = 1f - x; return 1f - k * k * k }
    private fun outBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val k = x - 1f
        return 1f + c3 * k * k * k + c1 * k * k
    }

    private fun withAlpha(color: Int, f: Float): Int =
        Color.argb((Color.alpha(color) * f.coerceIn(0f, 1f)).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun lavA(a: Float): Int = Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), 0xE4, 0xD9, 0xFF)

    // ───── الرسم ─────
    override fun onDraw(canvas: Canvas) {
        val animated = anim != null
        val t = if (animated) SystemClock.uptimeMillis() - t0 else 100_000L

        val s = min(width / REF_W, height / REF_H)
        canvas.save()
        canvas.translate((width - REF_W * s) / 2f, (height - REF_H * s) / 2f)
        canvas.scale(s, s)
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, REF_W / 2f, 0f)

        cards.forEachIndexed { i, c -> drawCard(canvas, c, i == cards.lastIndex, t, animated) }
        if (showBadge) drawBadge(canvas, t)
        canvas.restore()
    }

    private fun drawCard(canvas: Canvas, c: Card, front: Boolean, t: Long, animated: Boolean) {
        val e = outCubic(seg(t, c.delay, 650))
        if (e <= 0f) return
        val bob = if (animated) sin(2.0 * PI * (((t - 700).coerceAtLeast(0)) / 3400.0 + c.phase)).toFloat() * c.amp * e else 0f
        canvas.save()
        canvas.translate(0f, (1f - e) * 16f + bob)
        rect.set(c.x, c.y, c.x + c.w, c.y + c.h)

        fill.style = Paint.Style.FILL
        fill.color = withAlpha(c.base, e)
        canvas.drawRoundRect(rect, c.r, c.r, fill)
        fill.color = withAlpha(c.glass, e)
        canvas.drawRoundRect(rect, c.r, c.r, fill)
        stroke.strokeWidth = if (front) 1.2f else 1f
        stroke.color = withAlpha(c.line, e)
        canvas.drawRoundRect(rect, c.r - 0.5f, c.r - 0.5f, stroke)

        if (front) drawFrontContent(canvas, c, t, animated)
        canvas.restore()
    }

    private fun drawFrontContent(canvas: Canvas, c: Card, t: Long, animated: Boolean) {
        // الدائرة (صورة العضو الهيكلية) + موجة خفيفة حولها كل 3.2ث
        val cx = 36f
        val cy = 75f
        fill.color = lavA(0.18f)
        canvas.drawCircle(cx, cy, 13f, fill)
        stroke.strokeWidth = 1.2f
        stroke.color = lavA(0.45f)
        canvas.drawCircle(cx, cy, 13f, stroke)
        if (animated && t > 900) {
            val m = ((t - 900) % 3_200L) / 1_600f
            if (m < 1f) {
                stroke.strokeWidth = 1f
                stroke.color = lavA(0.35f * (1f - m))
                canvas.drawCircle(cx, cy, 13f + 7f * outCubic(m), stroke)
            }
        }

        // الخطوط: تتمدّد عند الدخول
        val g1 = outCubic(seg(t, 520, 520))
        val g2 = outCubic(seg(t, 640, 520))
        fill.color = Color.argb(0x8C, 255, 255, 255)
        rect.set(58f, 63f, 58f + 54f * g1, 71f)
        if (g1 > 0f) canvas.drawRoundRect(rect, 4f, 4f, fill)
        fill.color = Color.argb(0x38, 255, 255, 255)
        rect.set(58f, 78f, 58f + 34f * g2, 85f)
        if (g2 > 0f) canvas.drawRoundRect(rect, 3.5f, 3.5f, fill)

        // الكبسولة: تنبض بهدوء
        val g3 = outCubic(seg(t, 760, 450))
        val pulse = if (animated && t > 1_300) 0.5f + 0.5f * sin(2.0 * PI * (t - 1_300) / 2_400.0).toFloat() else 1f
        fill.color = lavA(0.18f * g3 * (0.7f + 0.3f * pulse))
        rect.set(118f, 61f, 142f, 75f)
        canvas.drawRoundRect(rect, 7f, 7f, fill)
        stroke.strokeWidth = 1f
        stroke.color = lavA(0.35f * g3)
        canvas.drawRoundRect(rect, 7f, 7f, stroke)

        // لمعة تعبر البطاقة كل ~4.2ث
        if (animated && t > 1_500) {
            val m = (t - 1_500) % 4_200L
            if (m < 1_100L) {
                val p = m / 1_100f
                val eased = p * p * (3f - 2f * p)
                val band = 46f
                clip.rewind()
                clip.addRoundRect(c.x, c.y, c.x + c.w, c.y + c.h, c.r, c.r, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                canvas.translate(c.x - band + (c.w + band * 2f) * eased, 0f)
                canvas.skew(-0.25f, 0f)
                shimmerPaint.shader = LinearGradient(
                    0f, 0f, band, 0f,
                    intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
                )
                canvas.drawRect(0f, c.y, band, c.y + c.h, shimmerPaint)
                canvas.restore()
            }
        }
    }

    private fun drawBadge(canvas: Canvas, t: Long) {
        val p = outBack(seg(t, 700, 520))
        if (p <= 0f) return
        val bob = sin(2.0 * PI * ((t - 1_200).coerceAtLeast(0) / 2_800.0)).toFloat() * 1.6f
        val cx = 141f
        val cy = 91f + bob
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(p, p)
        fill.style = Paint.Style.FILL
        fill.color = 0xFF1B1C45.toInt()
        canvas.drawCircle(0f, 0f, 19f, fill)
        fill.color = lavA(0.15f)
        canvas.drawCircle(0f, 0f, 19f, fill)
        stroke.strokeWidth = 1f
        stroke.color = lavA(0.35f)
        canvas.drawCircle(0f, 0f, 18.5f, stroke)

        // العدسة: تتمايل ±9° كأنها تبحث
        val sway = if (t > 1_200) sin(2.0 * PI * (t - 1_200) / 3_000.0).toFloat() * 9f else 0f
        canvas.rotate(sway)
        fill.color = lavA(0.28f)
        canvas.drawCircle(-1.6f, -1.6f, 5.8f, fill)
        stroke.strokeWidth = 1.9f
        stroke.color = lavA(0.95f)
        canvas.drawCircle(-1.6f, -1.6f, 5.8f, stroke)
        canvas.drawLine(2.6f, 2.6f, 7.2f, 7.2f, stroke)
        canvas.restore()
    }

    private companion object {
        const val REF_W = 160f
        const val REF_H = 112f
    }
}
