package com.gympro.manager.ui.members

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.gympro.manager.R
import kotlin.math.max
import kotlin.math.min

/**
 * عرض قصّ صورة: نافذة دائرية ثابتة في المنتصف (تطابق شكل الأفاتار)، والصورة تُحرَّك وتُكبَّر خلفها.
 *  - سحب بإصبع، قرص بإصبعين للتكبير حول نقطة التركيز، ونقر مزدوج لتكبير/تصغير سريع بحركة.
 *  - الصورة لا تترك فراغاً داخل النافذة أبداً (حدّ أدنى للتكبير + تقييد الإزاحة).
 *  - شبكة أثلاث خفيفة تظهر أثناء اللمس فقط. تدوير 90° بحركة ثم تبديل الصورة.
 *  - crop(): يرسم ما داخل النافذة في صورة مربعة (تُعرض لاحقاً دائرية).
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private val cropRect = RectF()
    private val tmp = RectF()
    private val values = FloatArray(9)
    private var minScale = 1f
    private val maxRelativeZoom = 6f
    private val density = resources.displayMetrics.density

    /** يُستدعى عند تغيّر التكبير النسبي (1 = الحد الأدنى). */
    var onZoomChanged: ((Float) -> Unit)? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = context.getColor(R.color.home_crop_dim) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = context.getColor(R.color.home_crop_ring)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = context.getColor(R.color.home_crop_grid)
    }
    private val dimPath = Path()
    private val circlePath = Path()

    private var gridAlpha = 0f
    private var rotationAngle = 0f
    private var rotating = false
    private var zoomAnimator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            drawMatrix.postTranslate(-dx, -dy)
            clamp()
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoomTo(if (relativeZoom() < 1.5f) 2.5f else 1f, e.x, e.y)
            return true
        }
    })

    fun setBitmap(b: Bitmap) {
        bitmap = b
        resetToFit()
    }

    fun relativeZoom(): Float {
        drawMatrix.getValues(values)
        return if (minScale > 0f) values[Matrix.MSCALE_X] / minScale else 1f
    }

    fun reset() {
        zoomAnimator?.cancel()
        resetToFit()
    }

    /** تكبير مطلق نسبة إلى الحد الأدنى (1..6) حول مركز النافذة — يستعمله الشريط. */
    fun zoomTo(relative: Float) {
        val target = relative.coerceIn(1f, maxRelativeZoom)
        val factor = (minScale * target) / currentScale()
        drawMatrix.postScale(factor, factor, cropRect.centerX(), cropRect.centerY())
        clamp()
        invalidate()
        onZoomChanged?.invoke(relativeZoom())
    }

    fun rotate90() {
        val src = bitmap ?: return
        if (rotating) return
        rotating = true
        ValueAnimator.ofFloat(0f, 90f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { rotationAngle = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    val m = Matrix().apply { postRotate(90f) }
                    bitmap = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                    rotationAngle = 0f
                    rotating = false
                    resetToFit()
                }
            })
            start()
        }
    }

    /** يرسم ما داخل نافذة القصّ في صورة مربعة بطول outSize (أو أقل إن كانت الصورة الأصلية أصغر). */
    fun crop(outSize: Int): Bitmap? {
        val bmp = bitmap ?: return null
        val inv = Matrix()
        drawMatrix.invert(inv)
        tmp.set(cropRect)
        inv.mapRect(tmp)
        val side = min(outSize, max(1, min(tmp.width(), tmp.height()).toInt()))
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val m = Matrix(drawMatrix)
        m.postTranslate(-cropRect.left, -cropRect.top)
        m.postScale(side / cropRect.width(), side / cropRect.height())
        Canvas(out).drawBitmap(bmp, m, bitmapPaint)
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = min(w, h) - 2 * 20 * density
        cropRect.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        circlePath.reset()
        circlePath.addCircle(cropRect.centerX(), cropRect.centerY(), side / 2f, Path.Direction.CW)
        if (bitmap != null) resetToFit()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.save()
        if (rotationAngle != 0f) canvas.rotate(rotationAngle, cropRect.centerX(), cropRect.centerY())
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
        canvas.restore()

        dimPath.reset()
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addCircle(cropRect.centerX(), cropRect.centerY(), cropRect.width() / 2f, Path.Direction.CCW)
        dimPath.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(dimPath, dimPaint)
        canvas.drawCircle(cropRect.centerX(), cropRect.centerY(), cropRect.width() / 2f, ringPaint)

        if (gridAlpha > 0f) {
            gridPaint.alpha = (gridAlpha * 90).toInt()
            canvas.save()
            canvas.clipPath(circlePath)
            val w3 = cropRect.width() / 3f
            val h3 = cropRect.height() / 3f
            for (i in 1..2) {
                canvas.drawLine(cropRect.left + w3 * i, cropRect.top, cropRect.left + w3 * i, cropRect.bottom, gridPaint)
                canvas.drawLine(cropRect.left, cropRect.top + h3 * i, cropRect.right, cropRect.top + h3 * i, gridPaint)
            }
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || rotating) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                animateGrid(1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animateGrid(0f)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun resetToFit() {
        val bmp = bitmap ?: return
        if (cropRect.isEmpty) return
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        minScale = max(cropRect.width() / bw, cropRect.height() / bh)
        drawMatrix.reset()
        drawMatrix.postScale(minScale, minScale)
        drawMatrix.postTranslate(cropRect.centerX() - bw * minScale / 2f, cropRect.centerY() - bh * minScale / 2f)
        invalidate()
        onZoomChanged?.invoke(1f)
    }

    private fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val target = (currentScale() * factor).coerceIn(minScale, minScale * maxRelativeZoom)
        val f = target / currentScale()
        drawMatrix.postScale(f, f, fx, fy)
        clamp()
        invalidate()
        onZoomChanged?.invoke(relativeZoom())
    }

    private fun animateZoomTo(targetRelative: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        val from = relativeZoom()
        zoomAnimator = ValueAnimator.ofFloat(from, targetRelative).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val now = relativeZoom()
                if (now > 0f) zoomBy((it.animatedValue as Float) / now, fx, fy)
            }
            start()
        }
    }

    private fun animateGrid(to: Float) {
        ValueAnimator.ofFloat(gridAlpha, to).apply {
            duration = 180
            addUpdateListener { gridAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** يمنع ظهور فراغ داخل نافذة القصّ: حواف الصورة دائماً خلف حواف النافذة. */
    private fun clamp() {
        val bmp = bitmap ?: return
        tmp.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        drawMatrix.mapRect(tmp)
        var dx = 0f
        var dy = 0f
        if (tmp.left > cropRect.left) dx = cropRect.left - tmp.left
        else if (tmp.right < cropRect.right) dx = cropRect.right - tmp.right
        if (tmp.top > cropRect.top) dy = cropRect.top - tmp.top
        else if (tmp.bottom < cropRect.bottom) dy = cropRect.bottom - tmp.bottom
        if (dx != 0f || dy != 0f) drawMatrix.postTranslate(dx, dy)
    }
}
