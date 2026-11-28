package com.gympro.manager.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.gympro.manager.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * مخطط أعمدة آخر 7 أيام (الجزء 2 من إعادة تصميم الإيرادات) — يرسم كل شيء على Canvas
 * (الأعمدة، الشبكة، خط المتوسط، تسميات الأيام العربية، الفقاعة) بلا أي TextView منفصل،
 * لأن MPAndroidChart لا يعرض العربية/RTL جيداً.
 *
 * - RTL كامل: في الواجهة العربية يكون أقدم يوم على اليمين و«اليوم» أقصى اليسار (نفس اتجاه الرئيسية).
 * - اللمس: لمس عمود يحدّده فتظهر فقاعة (اليوم + المبلغ) مع اهتزاز خفيف، والسحب يتنقّل بين الأعمدة،
 *   ولمس العمود المحدّد مرة ثانية يلغي التحديد.
 * - الأداء: لا تخصيص كائنات داخل onDraw؛ كل الـ Paint/Path/RectF/Matrix مُعاد استخدامها،
 *   والتدرّج هو Shader واحد بحجم الوحدة يُمدَّد لكل عمود بـ setLocalMatrix.
 * - الحركات: ValueAnimator واحد لنمو الأعمدة المتتابع (عند الظهور وعند تغيّر البيانات)
 *   وآخر خفيف للفقاعة.
 * - بلا بيانات: أعمدة شبحية منقطة مع رسالة (emptyMessage).
 */
class RevenueBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun color(res: Int) = ContextCompat.getColor(context, res)

    // ------------------------------------------------------------------ البيانات
    private var n = 0
    private var fromFrac = FloatArray(0)
    private var toFrac = FloatArray(0)
    private var cx = FloatArray(0)
    private var tipW = FloatArray(0)
    private var axisLabels: Array<String> = emptyArray()
    private var tipTitles: Array<String> = emptyArray()
    private var tipAmounts: Array<String> = emptyArray()
    private var fromAvg = 0f
    private var toAvg = 0f
    private var avgText = ""
    private var avgTextW = 0f
    private var emptyText = ""
    private var emptyTextW = 0f
    private var hasData = false
    private var noData = false

    // ------------------------------------------------------------------ الحالة
    private var animT = 1f
    private var selected = -1
    private var shownSel = -1
    private var selT = 0f
    private var labelSizePx = sp(11f)

    // ------------------------------------------------------------------ الهندسة
    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f
    private var plotH = 1f
    private var slotW = 0f
    private var barW = 0f
    private var labelY = 0f

    // ------------------------------------------------------------------ أدوات رسم مُعاد استخدامها
    private val rect = RectF()
    private val pillRect = RectF()
    private val arrow = Path()
    private val shaderMatrix = Matrix()

    private val normalShader = LinearGradient(
        0f, 0f, 0f, 1f, color(R.color.rev_bar_top), color(R.color.rev_bar_bottom), Shader.TileMode.CLAMP
    )
    private val todayShader = LinearGradient(
        0f, 0f, 0f, 1f, color(R.color.rev_bar_today_top), color(R.color.rev_bar_today_bottom), Shader.TileMode.CLAMP
    )

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.home_crim) }
    private val stubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.home_ghost_bar) }
    private val ghostStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.3f)
        color = color(R.color.home_ghost_stroke)
        pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(4f)), 0f)
    }
    private val dotGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.3f)
        color = color(R.color.rev_grid_dot)
        pathEffect = DashPathEffect(floatArrayOf(0.1f, dp(5.5f)), 0f)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1f)
        color = color(R.color.home_line)
    }
    private val avgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(1.4f)
        color = color(R.color.rev_avg_line)
        pathEffect = DashPathEffect(floatArrayOf(dp(7f), dp(5f)), 0f)
    }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.home_empty_fade) }
    private val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(R.color.rev_avg_line)
    }
    private val tipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.rev_tip_fill) }
    private val tipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = color(R.color.rev_tip_stroke)
    }

    private val labelPaint = textPaint(11f, R.color.home_mute)
    private val avgTextPaint = textPaint(10.5f, R.color.home_mint)
    private val tipTitlePaint = textPaint(10.5f, R.color.home_mute)
    private val tipAmountPaint = textPaint(14f, R.color.home_ink)
    private val emptyPaint = textPaint(12f, R.color.home_mute)

    private fun textPaint(sizeSp: Float, colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(sizeSp)
        color = color(colorRes)
    }

    private var mediumFace: Typeface? = null
    private var boldFace: Typeface? = null

    private val growInterp = OvershootInterpolator(0.8f)
    private val easeOut = DecelerateInterpolator(2f)
    private var growAnim: ValueAnimator? = null
    private var selAnim: ValueAnimator? = null

    // ------------------------------------------------------------------ اللمس
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var selectedAtDown = -1

    init {
        loadFont(R.font.cairo_medium) { tf ->
            mediumFace = tf
            labelPaint.typeface = tf
            tipTitlePaint.typeface = tf
            emptyPaint.typeface = tf
        }
        loadFont(R.font.cairo_bold) { tf ->
            boldFace = tf
            avgTextPaint.typeface = tf
            tipAmountPaint.typeface = tf
        }
    }

    /** الخط قابل للتنزيل (Google Fonts): نطلبه بـ callback بدل getFont المتزامن الذي قد يرمي استثناءً. */
    private fun loadFont(res: Int, onReady: (Typeface) -> Unit) {
        try {
            ResourcesCompat.getFont(context, res, object : ResourcesCompat.FontCallback() {
                override fun onFontRetrieved(typeface: Typeface) {
                    onReady(typeface)
                    measureTexts()
                    relayout()
                    invalidate()
                }

                override fun onFontRetrievalFailed(reason: Int) {}
            }, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            // نبقى على الخط الافتراضي
        }
    }

    // ------------------------------------------------------------------ واجهة الاستخدام
    /**
     * @param values إيراد كل يوم (الأقدم أولاً).
     * @param axisLabels تسميات الأيام تحت الأعمدة (آخرها «اليوم»).
     * @param tooltipTitles عنوان الفقاعة لكل عمود (اسم اليوم).
     * @param tooltipAmounts المبلغ المنسّق (CurrencyFormatter) لكل عمود.
     * @param averageText نص تسمية خط المتوسط (مُنسّق مسبقاً).
     * @param emptyMessage رسالة حالة عدم وجود إيرادات.
     */
    fun setData(
        values: List<Double>,
        axisLabels: List<String>,
        tooltipTitles: List<String>,
        tooltipAmounts: List<String>,
        averageText: String,
        emptyMessage: String,
        animate: Boolean = true
    ) {
        val size = values.size
        val max = values.maxOrNull() ?: 0.0
        val newNoData = size == 0 || max <= 0.0
        val newTo = FloatArray(size) { if (max > 0.0) (values[it] / max).toFloat() else 0f }
        val newAvg = if (max > 0.0 && size > 0) (values.sum() / size / max).toFloat() else 0f

        val sameShape = hasData && size == n && newNoData == noData && newTo.contentEquals(toFrac)
        val newLabels = Array(size) { axisLabels.getOrElse(it) { "" } }
        val newTitles = Array(size) { tooltipTitles.getOrElse(it) { "" } }
        val newAmounts = Array(size) { tooltipAmounts.getOrElse(it) { "" } }

        // النقطة الحالية المعروضة تصبح نقطة بداية الحركة الجديدة (انتقال سلس بين بيانين).
        val newFrom = FloatArray(size) { i ->
            if (hasData && !noData && i < n) fracAt(i) else 0f
        }
        val curAvg = if (hasData && !noData) currentAvg() else 0f

        n = size
        noData = newNoData
        this.axisLabels = newLabels
        tipTitles = newTitles
        tipAmounts = newAmounts
        avgText = averageText
        emptyText = emptyMessage
        toFrac = newTo
        toAvg = newAvg
        if (selected >= n) selected = -1
        if (selected < 0 && selT == 0f) shownSel = -1
        hasData = true

        measureTexts()
        relayout()

        if (sameShape) {
            fromFrac = newTo
            fromAvg = newAvg
            invalidate()
            return
        }
        fromFrac = newFrom
        fromAvg = curAvg
        startGrow(animate)
    }

    fun clearSelection() = select(-1, haptic = false)

    // ------------------------------------------------------------------ الحركة
    private fun startGrow(animate: Boolean) {
        growAnim?.cancel()
        if (!animate || n == 0) {
            animT = 1f
            invalidate()
            return
        }
        animT = 0f
        growAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BAR_DUR_MS + max(n - 1, 0) * STAGGER_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                animT = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startSelAnim(target: Float) {
        selAnim?.cancel()
        selAnim = ValueAnimator.ofFloat(selT, target).apply {
            duration = 170L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                selT = it.animatedValue as Float
                if (selT <= 0f && selected < 0) shownSel = -1
                invalidate()
            }
            start()
        }
    }

    private fun barProgress(i: Int): Float {
        val total = BAR_DUR_MS + max(n - 1, 0) * STAGGER_MS
        val elapsed = animT * total - i * STAGGER_MS
        return (elapsed / BAR_DUR_MS).coerceIn(0f, 1f)
    }

    private fun fracAt(i: Int): Float {
        if (i >= toFrac.size || i >= fromFrac.size) return 0f
        return fromFrac[i] + (toFrac[i] - fromFrac[i]) * growInterp.getInterpolation(barProgress(i))
    }

    private fun currentAvg(): Float = fromAvg + (toAvg - fromAvg) * easeOut.getInterpolation(animT)

    // ------------------------------------------------------------------ القياس والتخطيط
    private fun measureTexts() {
        if (n == 0) return
        // تصغير خط التسميات إن ضاقت الخانة (خط نظام كبير مثلاً)
        var size = sp(11f)
        val minSize = sp(8f)
        val avail = if (slotW > 0f) slotW - dp(2f) else Float.MAX_VALUE
        labelPaint.textSize = size
        while (size > minSize && maxLabelWidth() > avail) {
            size -= sp(0.5f)
            labelPaint.textSize = size
        }
        labelSizePx = size
        if (tipW.size != n) tipW = FloatArray(n)
        for (i in 0 until n) {
            tipW[i] = max(tipTitlePaint.measureText(tipTitles[i]), tipAmountPaint.measureText(tipAmounts[i])) + dp(26f)
        }
        avgTextW = avgTextPaint.measureText(avgText) + dp(18f)
        emptyTextW = emptyPaint.measureText(emptyText) + dp(28f)
    }

    private fun maxLabelWidth(): Float {
        var w = 0f
        for (s in axisLabels) w = max(w, labelPaint.measureText(s))
        return w
    }

    private fun relayout() {
        if (width == 0 || height == 0 || n == 0) return
        plotLeft = dp(6f)
        plotRight = width - dp(6f)
        plotTop = dp(46f)
        plotBottom = height - dp(30f)
        plotH = max(plotBottom - plotTop, 1f)
        slotW = (plotRight - plotLeft) / n
        barW = min(slotW * 0.5f, dp(30f))
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        if (cx.size != n) cx = FloatArray(n)
        for (i in 0 until n) {
            cx[i] = if (rtl) plotRight - slotW * (i + 0.5f) else plotLeft + slotW * (i + 0.5f)
        }
        labelPaint.textSize = labelSizePx
        labelY = plotBottom + dp(9f) - labelPaint.fontMetrics.ascent
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureTexts()
        relayout()
    }

    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        relayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = dp(176f).toInt()
        val h = resolveSize(desiredH, heightMeasureSpec)
        setMeasuredDimension(resolveSize(dp(280f).toInt(), widthMeasureSpec), h)
    }

    // ------------------------------------------------------------------ الرسم
    override fun onDraw(canvas: Canvas) {
        if (!hasData || n == 0 || width == 0) return
        drawGrid(canvas)
        if (noData) {
            drawGhost(canvas)
        } else {
            drawBars(canvas)
            drawAverage(canvas)
            drawTooltip(canvas)
        }
        drawLabels(canvas)
    }

    private fun drawGrid(c: Canvas) {
        for (k in 1..3) {
            val y = plotBottom - plotH * k / 3f
            c.drawLine(plotLeft, y, plotRight, y, dotGridPaint)
        }
        c.drawLine(plotLeft, plotBottom, plotRight, plotBottom, baselinePaint)
    }

    private fun drawGhost(c: Canvas) {
        val a = (255 * animT).toInt()
        ghostStrokePaint.alpha = (a * 0.7f).toInt()
        stubPaint.alpha = (a * 0.45f).toInt()
        for (i in 0 until n) {
            val h = plotH * GHOST[i % GHOST.size]
            val l = cx[i] - barW / 2f
            rect.set(l, plotBottom - h, l + barW, plotBottom)
            val r = barW * 0.42f
            c.drawRoundRect(rect, r, r, stubPaint)
            c.drawRoundRect(rect, r, r, ghostStrokePaint)
        }
        // رسالة «لا إيرادات» داخل كبسولة وسط المنطقة
        val midX = (plotLeft + plotRight) / 2f
        val midY = plotTop + plotH * 0.42f
        val hh = dp(18f)
        pillRect.set(midX - emptyTextW / 2f, midY - hh, midX + emptyTextW / 2f, midY + hh)
        pillFill.alpha = (a * 0.9f).toInt()
        pillStroke.alpha = (a * 0.3f).toInt()
        c.drawRoundRect(pillRect, hh, hh, pillFill)
        c.drawRoundRect(pillRect, hh, hh, pillStroke)
        emptyPaint.alpha = a
        c.drawText(emptyText, midX, midY - (emptyPaint.fontMetrics.ascent + emptyPaint.fontMetrics.descent) / 2f, emptyPaint)
    }

    private fun drawBars(c: Canvas) {
        val today = n - 1
        val minH = dp(5f)
        for (i in 0 until n) {
            val frac = fracAt(i)
            val h = max(frac * plotH, minH)
            val l = cx[i] - barW / 2f
            val top = plotBottom - h
            val isSel = i == shownSel && selT > 0f
            val dim = if (selected >= 0 && i != selected) 1f - 0.58f * selT else 1f
            val r = barW * 0.42f
            val rr = min(r, h / 2f)

            if (toFrac[i] <= 0f && frac <= 0.001f) {
                // يوم بلا إيراد: كبسولة شبحية صغيرة
                stubPaint.alpha = (255 * dim).toInt()
                rect.set(l, plotBottom - minH, l + barW, plotBottom)
                c.drawRoundRect(rect, rr, rr, stubPaint)
                continue
            }
            if (i == today || (isSel && selected == i)) {
                glowPaint.alpha = ((if (i == today) 46 else 38) * dim).toInt()
                rect.set(l - dp(3f), top - dp(3f), l + barW + dp(3f), plotBottom + dp(1f))
                c.drawRoundRect(rect, r + dp(3f), r + dp(3f), glowPaint)
            }
            shaderMatrix.setScale(1f, h)
            shaderMatrix.postTranslate(0f, top)
            val sh = if (i == today) todayShader else normalShader
            sh.setLocalMatrix(shaderMatrix)
            barPaint.shader = sh
            barPaint.alpha = (255 * dim).toInt()
            rect.set(l, top, l + barW, plotBottom)
            c.drawRoundRect(rect, r, rr, barPaint)
        }
    }

    private fun drawAverage(c: Canvas) {
        if (toAvg <= 0f && fromAvg <= 0f) return
        val y = plotBottom - plotH * currentAvg()
        val a = (255 * easeOut.getInterpolation(animT)).toInt()
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        avgLinePaint.alpha = (a * 0.85f).toInt()
        c.drawLine(plotLeft, y, plotRight, y, avgLinePaint)
        // التسمية على الطرف الذي تبدأ منه القراءة (يمين في RTL)
        val ph = dp(10f)
        val pl = if (rtl) plotRight - avgTextW else plotLeft
        pillRect.set(pl, y - ph, pl + avgTextW, y + ph)
        pillFill.alpha = a
        pillStroke.alpha = (a * 0.6f).toInt()
        c.drawRoundRect(pillRect, ph, ph, pillFill)
        c.drawRoundRect(pillRect, ph, ph, pillStroke)
        avgTextPaint.alpha = a
        c.drawText(avgText, pl + avgTextW / 2f, y - (avgTextPaint.fontMetrics.ascent + avgTextPaint.fontMetrics.descent) / 2f, avgTextPaint)
    }

    private fun drawTooltip(c: Canvas) {
        val i = shownSel
        if (i < 0 || i >= n || selT <= 0.01f) return
        val a = (255 * selT).toInt()
        val bw = tipW[i]
        val bh = dp(46f)
        val ax = cx[i]
        val barTop = plotBottom - max(fracAt(i) * plotH, dp(5f))
        val slide = (1f - selT) * dp(6f)
        val by = barTop - dp(12f) + slide            // أسفل الفقاعة (حيث يبدأ السهم)
        var bl = ax - bw / 2f
        bl = bl.coerceIn(dp(2f), max(width - bw - dp(2f), dp(2f)))
        rect.set(bl, by - bh, bl + bw, by)
        val rad = dp(14f)
        tipFill.alpha = a
        tipStroke.alpha = (a * 0.25f).toInt()
        c.drawRoundRect(rect, rad, rad, tipFill)
        c.drawRoundRect(rect, rad, rad, tipStroke)
        // السهم: مثلث يتداخل 1.5dp مع الفقاعة ليخفي خط الحد عند القاعدة
        val aw = dp(6f)
        arrow.reset()
        arrow.moveTo(ax - aw, by - dp(1.5f))
        arrow.lineTo(ax + aw, by - dp(1.5f))
        arrow.lineTo(ax, by + dp(6f))
        arrow.close()
        c.drawPath(arrow, tipFill)
        c.drawLine(ax - aw, by, ax, by + dp(6f), tipStroke)
        c.drawLine(ax + aw, by, ax, by + dp(6f), tipStroke)
        // النصوص: اسم اليوم صغير، والمبلغ بارز
        val mid = bl + bw / 2f
        tipTitlePaint.alpha = a
        tipAmountPaint.alpha = a
        c.drawText(tipTitles[i], mid, rect.top + dp(17f), tipTitlePaint)
        c.drawText(tipAmounts[i], mid, rect.top + dp(36f), tipAmountPaint)
    }

    private fun drawLabels(c: Canvas) {
        val today = n - 1
        for (i in 0 until n) {
            val emphasized = i == today || i == selected
            labelPaint.typeface = if (emphasized) (boldFace ?: labelPaint.typeface) else (mediumFace ?: labelPaint.typeface)
            labelPaint.color = when {
                i == selected -> color(R.color.home_ink)
                i == today -> color(R.color.home_lav)
                else -> color(R.color.home_mute)
            }
            c.drawText(axisLabels[i], cx[i], labelY, labelPaint)
        }
    }

    // ------------------------------------------------------------------ اللمس
    private fun indexAt(x: Float): Int {
        if (slotW <= 0f) return -1
        val raw = ((x - plotLeft) / slotW).toInt().coerceIn(0, n - 1)
        return if (layoutDirection == LAYOUT_DIRECTION_RTL) n - 1 - raw else raw
    }

    private fun select(index: Int, haptic: Boolean = true) {
        if (index == selected) return
        selected = index
        if (index >= 0) {
            shownSel = index
            if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        startSelAnim(if (index >= 0) 1f else 0f)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasData || noData || n == 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                selectedAtDown = selected
                select(indexAt(event.x))
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > touchSlop && abs(event.x - downX) > abs(event.y - downY)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) select(indexAt(event.x))
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                // نقرة على العمود المحدّد سابقاً بلا سحب: إلغاء التحديد
                if (!dragging && selectedAtDown >= 0 && selectedAtDown == indexAt(downX)) {
                    select(-1, haptic = false)
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        growAnim?.cancel()
        selAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BAR_DUR_MS = 480L
        const val STAGGER_MS = 70L
        val GHOST = floatArrayOf(0.38f, 0.58f, 0.46f, 0.74f, 0.52f, 0.86f, 0.64f)
    }
}
