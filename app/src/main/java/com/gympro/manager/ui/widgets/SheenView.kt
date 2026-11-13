package com.gympro.manager.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import com.gympro.manager.ui.widgets.Motion.inOutCubic
import com.gympro.manager.ui.widgets.Motion.seg

/**
 * لمعة ضوء مائلة تعبر سطح أبيه من اتجاه القراءة (يمين→يسار في RTL) كل بضع ثوانٍ.
 * تُوضع فوق العنصر (زر الإضافة، البطاقة الشبحية...) ولا تلتقط اللمس.
 * تُضبط برمجياً عبر [configure]: نصف قطر الزوايا (لقص اللمعة داخل الشكل) وقوة اللمعة.
 */
class SheenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LivingView(context, attrs, defStyleAttr) {

    private var cornerPx = 0f
    private var peakAlpha = 0.25f
    private var periodMs = 3_800L
    private var sweepMs = 1_100L
    private var delayMs = 900L

    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bandW = 0f

    /**
     * @param cornerDp نصف قطر زوايا الشكل الذي تُقصّ اللمعة داخله (99 للأزرار الكبسولية).
     * @param peakAlpha أقصى شفافية للون الأبيض في مركز اللمعة (0..1).
     */
    fun configure(
        cornerDp: Float,
        peakAlpha: Float = 0.25f,
        periodMs: Long = 3_800L,
        sweepMs: Long = 1_100L,
        delayMs: Long = 900L
    ) {
        this.cornerPx = cornerDp * resources.displayMetrics.density
        this.peakAlpha = peakAlpha
        this.periodMs = periodMs
        this.sweepMs = sweepMs
        this.delayMs = delayMs
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        val r = cornerPx.coerceAtMost(minOf(width, height) / 2f)
        clip.rewind()
        clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW)
        bandW = width * 0.34f
        val peak = Color.argb((peakAlpha * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        paint.shader = LinearGradient(
            0f, 0f, bandW, 0f,
            intArrayOf(Color.argb(0, 255, 255, 255), peak, Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (staticMode || bandW <= 0f) return
        val c = now() - delayMs
        if (c < 0) return
        val m = c % periodMs
        if (m > sweepMs) return
        val p = inOutCubic(seg(m, 0, sweepMs))

        val skew = 0.3f
        val h = height.toFloat()
        val from = -bandW - h * skew
        val to = width + h * skew

        canvas.save()
        if (layoutDirection == LAYOUT_DIRECTION_RTL) canvas.scale(-1f, 1f, width / 2f, 0f)
        canvas.clipPath(clip)
        canvas.translate(from + (to - from) * p, 0f)
        canvas.skew(-skew, 0f)
        canvas.drawRect(0f, 0f, bandW, h, paint)
        canvas.restore()
    }
}
