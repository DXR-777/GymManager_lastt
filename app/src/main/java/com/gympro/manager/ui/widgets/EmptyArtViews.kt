package com.gympro.manager.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import com.gympro.manager.R
import com.gympro.manager.ui.widgets.Motion.bump
import com.gympro.manager.ui.widgets.Motion.inOutCubic
import com.gympro.manager.ui.widgets.Motion.outBack
import com.gympro.manager.ui.widgets.Motion.outCubic
import com.gympro.manager.ui.widgets.Motion.seg
import com.gympro.manager.ui.widgets.Motion.wave
import kotlin.math.min

private const val DESIGN = 78f

/** يضبط فضاء الرسم 78×78 ويوسّطه داخل الـ View، ثم ينفّذ [block] ويستعيد الـ Canvas. */
private inline fun Canvas.inDesignSpace(w: Int, h: Int, block: () -> Unit) {
    val s = min(w, h) / DESIGN
    save()
    translate((w - DESIGN * s) / 2f, (h - DESIGN * s) / 2f)
    scale(s, s)
    block()
    restore()
}

private fun paint(style: Paint.Style, color: Int = Color.WHITE, width: Float = 0f) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        this.color = color
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

/** يرسم جزءاً (0..1) من مسار بدءاً من أوله — لتأثير "الرسم بالقلم". */
private class PathDrawer(private val path: Path) {
    private val measure = PathMeasure(path, false)
    private val length = measure.length
    private val seg = Path()
    fun draw(c: Canvas, p: Float, paint: Paint) {
        if (p <= 0f) return
        if (p >= 1f) {
            c.drawPath(path, paint)
        } else {
            seg.rewind()
            measure.getSegment(0f, length * p, seg, true)
            c.drawPath(seg, paint)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
//  1) الدرع — "اشتراكاتكم في أمان"
//     الدرع والدائرة فقط: الدرع يُرسم بالقلم ثم علامة الصح، وكل ~6ث "نبضة قلب" مع لمعة ضوء تعبره.
// ════════════════════════════════════════════════════════════════════════════════
class ShieldArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LivingView(context, attrs, defStyleAttr) {

    private val shieldPath = Path().apply {
        moveTo(39f, 22f); lineTo(50f, 26f); lineTo(50f, 35f)
        cubicTo(50f, 43f, 45f, 49f, 39f, 52f)
        cubicTo(33f, 49f, 28f, 43f, 28f, 35f)
        lineTo(28f, 26f); close()
    }
    private val checkPath = Path().apply { moveTo(34f, 37f); lineTo(38f, 41f); lineTo(45f, 33f) }
    private val shieldDrawer = PathDrawer(shieldPath)
    private val checkDrawer = PathDrawer(checkPath)

    private val discFill = paint(Paint.Style.FILL, Color.parseColor("#387C4DE8"))
    private val discStroke = paint(Paint.Style.STROKE, width = 1.5f).apply {
        shader = LinearGradient(
            12f, 12f, 66f, 66f, Color.parseColor("#FF8FA0"), Color.parseColor("#8B5CF6"), Shader.TileMode.CLAMP
        )
    }
    private val shieldStroke = paint(Paint.Style.STROKE, width = 1.9f).apply {
        shader = LinearGradient(
            28f, 22f, 50f, 52f, Color.parseColor("#FF8FA0"), Color.parseColor("#8B5CF6"), Shader.TileMode.CLAMP
        )
    }
    private val shieldFill = paint(Paint.Style.FILL, Color.parseColor("#8B5CF6"))
    private val checkPaint = paint(Paint.Style.STROKE, Color.WHITE, 2.1f)
    private val shinePaint = paint(Paint.Style.FILL).apply {
        shader = LinearGradient(
            0f, 0f, 14f, 0f,
            intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = now()
        val idle = !staticMode
        val idleT = t - IDLE_AT

        canvas.inDesignSpace(width, height) {
            // القرص الخلفي (يكبر بارتداد ثم "يتنفّس")
            val discP = seg(t, 0, 550)
            if (discP > 0f) {
                val breath = if (idle) 0.8f + 0.2f * wave(t, 2_800L) else 1f
                val sc = 0.6f + 0.4f * outBack(discP)
                canvas.save()
                canvas.scale(sc, sc, 39f, 39f)
                discFill.alpha = (0x38 * discP * breath).toInt()
                canvas.drawCircle(39f, 39f, 27f, discFill)
                discStroke.alpha = (255 * discP).toInt()
                canvas.drawCircle(39f, 39f, 27f, discStroke)
                canvas.restore()
            }

            // نبضة قلب كل 6ث بعد اكتمال المشهد: الدرع يكبر قليلاً + لمعة تعبره
            val beatT = if (idle && idleT >= 0) idleT % BEAT_PERIOD else -1L
            val beat = if (beatT in 0L..BEAT_DUR) bump(beatT.toFloat() / BEAT_DUR) else 0f
            val sScale = 1f + 0.05f * beat

            canvas.save()
            // الدرع أكبر (×1.3) ومتمركز تماماً في مركز الدائرة (39,39) بدل مركزه الأصلي (39,37)
            canvas.translate(39f, 39f)
            canvas.scale(SHIELD_K, SHIELD_K)
            canvas.translate(-39f, -37f)
            canvas.scale(sScale, sScale, 39f, 37f)

            val fillIn = seg(t, 700, 500)
            if (fillIn > 0f) {
                shieldFill.alpha = (0x2E * fillIn).toInt()
                canvas.drawPath(shieldPath, shieldFill)
            }
            shieldDrawer.draw(canvas, inOutCubic(seg(t, 150, 800)), shieldStroke)

            val pop = bump(seg(t, 1_280, 320))
            canvas.save()
            canvas.scale(1f + 0.18f * pop, 1f + 0.18f * pop, 39.5f, 37f)
            checkDrawer.draw(canvas, inOutCubic(seg(t, 900, 380)), checkPaint)
            canvas.restore()

            if (beatT in 0L..BEAT_DUR) {
                val p = beatT.toFloat() / BEAT_DUR
                canvas.save()
                canvas.clipPath(shieldPath)
                canvas.translate(14f + 38f * p, 0f)
                canvas.rotate(18f, 7f, 39f)
                canvas.drawRect(0f, 8f, 14f, 70f, shinePaint)
                canvas.restore()
            }
            canvas.restore()
        }
    }

    private companion object {
        const val IDLE_AT = 1_700L
        const val SHIELD_K = 1.3f
        const val BEAT_PERIOD = 6_000L
        const val BEAT_DUR = 900L
    }
}

// ════════════════════════════════════════════════════════════════════════════════
//  3) التقويم — "صالة جديدة: لا اشتراكات بعد"
//     مخطط منقّط تسير فيه الشرطات ("نمل يمشي")، الحلقتان تسقطان، الخط يُرسم من الوسط،
//     زر + يقفز بارتداد ثم يبعث موجات دعوة للنقر ويدور 90° كل دورة، والنقطتان تطفوان.
// ════════════════════════════════════════════════════════════════════════════════
class CalendarArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LivingView(context, attrs, defStyleAttr) {

    private val crim = ContextCompat.getColor(context, R.color.home_crim)
    private val vio = ContextCompat.getColor(context, R.color.home_vio)

    private val fillColor = ContextCompat.getColor(context, R.color.home_empty_cal_fill)
    private val dashColor = ContextCompat.getColor(context, R.color.home_empty_dash)
    private val outlineFill = paint(Paint.Style.FILL, fillColor)
    private val outlineStroke = paint(Paint.Style.STROKE, dashColor, 1.6f).apply {
        strokeCap = Paint.Cap.BUTT
    }
    private val marks = paint(Paint.Style.STROKE, Color.parseColor("#C9B5FF"), 2.4f)
    private val badge = paint(Paint.Style.FILL).apply {
        shader = LinearGradient(27f, 36f, 51f, 60f, crim, vio, Shader.TileMode.CLAMP)
    }
    private val glow = paint(Paint.Style.FILL).apply {
        shader = RadialGradient(
            39f, 48f, 24f,
            intArrayOf(0x66D63A56, 0x00D63A56), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }
    private val ripple = paint(Paint.Style.STROKE, crim, 1.6f)
    private val plus = paint(Paint.Style.STROKE, Color.WHITE, 1.7f)

    override fun onDraw(canvas: Canvas) {
        val t = now()
        val idle = !staticMode
        val idleT = t - IDLE_AT

        canvas.inDesignSpace(width, height) {
            // المخطط المنقّط: يكبر مع دخول ثم تسير شرطاته ببطء
            val oIn = seg(t, 0, 520)
            if (oIn > 0f) {
                val sc = 0.85f + 0.15f * outBack(oIn)
                canvas.save()
                canvas.scale(sc, sc, 39f, 40f)
                val phase = if (idle) t / 1000f * 6f else 0f
                outlineStroke.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), phase)
                outlineFill.alpha = (Color.alpha(fillColor) * oIn).toInt()
                outlineStroke.alpha = (Color.alpha(dashColor) * oIn).toInt()
                canvas.drawRoundRect(13f, 16f, 65f, 64f, 13f, 13f, outlineFill)
                canvas.drawRoundRect(13f, 16f, 65f, 64f, 13f, 13f, outlineStroke)
                canvas.restore()
            }

            // الخط العلوي يُرسم من المنتصف للجانبين
            val half = 26f * outCubic(seg(t, 250, 500))
            if (half > 0f) canvas.drawLine(39f - half, 31f, 39f + half, 31f, marks)

            // الحلقتان تسقطان بارتداد
            for ((i, x) in floatArrayOf(28f, 50f).withIndex()) {
                val d = seg(t, 300L + i * 90L, 430)
                if (d <= 0f) continue
                val dy = -10f * (1f - outBack(d))
                marks.alpha = (255 * outCubic(d)).toInt()
                canvas.drawLine(x, 10f + dy, x, 23f + dy, marks)
            }
            marks.alpha = 255

            // زر + : هالة نابضة، موجتان دعوة للنقر، ودوران 90°
            val bIn = seg(t, 650, 450)
            if (bIn > 0f) {
                val cyc = if (idle && idleT >= 0) idleT % 2_800L else -1L
                val pulse = if (cyc in 0L..600L) bump(cyc.toFloat() / 600f) else 0f
                val sc = outBack(bIn) * (1f + 0.08f * pulse)

                glow.alpha = (255 * bIn * (if (idle) 0.6f + 0.4f * wave(t, 2_800L) else 0.7f)).toInt()
                canvas.drawCircle(39f, 48f, 24f, glow)

                if (cyc >= 0) {
                    for (k in 0..1) {
                        val c = (cyc - k * 380L).toFloat() / 1_700f
                        if (c <= 0f || c >= 1f) continue
                        ripple.alpha = ((1f - c) * (if (k == 0) 0.55f else 0.3f) * 255f).toInt()
                        canvas.drawCircle(39f, 48f, 12f + 13f * outCubic(c), ripple)
                    }
                }

                canvas.save()
                canvas.scale(sc, sc, 39f, 48f)
                canvas.drawCircle(39f, 48f, 12f, badge)
                val rot = if (cyc in 0L..700L) 90f * inOutCubic(cyc.toFloat() / 700f) else 0f
                canvas.rotate(rot, 39f, 48f)
                canvas.drawLine(34.8f, 48f, 43.2f, 48f, plus)
                canvas.drawLine(39f, 43.8f, 39f, 52.2f, plus)
                canvas.restore()
            }
        }
    }

    private companion object {
        const val IDLE_AT = 1_300L
    }
}
