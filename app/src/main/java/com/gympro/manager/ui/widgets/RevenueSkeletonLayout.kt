package com.gympro.manager.ui.widgets

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.gympro.manager.R

/**
 * حاوية Skeleton بلمعان (shimmer) يعبر أشكال الهيكل فقط لا الفراغات بينها:
 * تُرسم الأبناء داخل طبقة منفصلة ثم يُرسم شريط التدرّج بوضع SRC_ATOP فيظهر حيث توجد بكسلات فقط.
 *
 * - يعمل فقط حين يكون ظاهراً فعلاً (مرتبطاً + ظاهراً + النافذة مرئية)، ويتوقف تلقائياً عند الإخفاء
 *   أو خروج التطبيق للخلفية (نفس منطق LivingView) — فلا تسريب ولا استهلاك بطارية.
 * - يحترم ANIMATOR_DURATION_SCALE=0 (ValueAnimator.areAnimatorsEnabled): يرسم هيكلاً ثابتاً بلا لمعان.
 * - الاتجاه: يعبر من اتجاه القراءة (يمين→يسار في RTL).
 */
class RevenueSkeletonLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    }
    private val shaderMatrix = Matrix()
    private var shader: LinearGradient? = null
    private var bandW = 0f
    private var progress = -1f
    private var animator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bandW = w * 0.45f
        val shine = ContextCompat.getColor(context, R.color.rev_skel_shine)
        val clear = shine and 0x00FFFFFF
        shader = LinearGradient(
            0f, 0f, bandW, 0f,
            intArrayOf(clear, shine, clear), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        shinePaint.shader = shader
    }

    override fun dispatchDraw(canvas: Canvas) {
        val animating = progress >= 0f && shader != null
        if (!animating) {
            super.dispatchDraw(canvas)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val travel = width + bandW
        val x = -bandW + travel * progress
        shaderMatrix.reset()
        shaderMatrix.setTranslate(if (rtl) width - x - bandW else x, 0f)
        shader?.setLocalMatrix(shaderMatrix)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shinePaint)
        canvas.restoreToCount(layer)
    }

    private fun sync() {
        val shouldRun = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (!shouldRun || !ValueAnimator.areAnimatorsEnabled()) {
            stop()
            return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        if (progress != -1f) {
            progress = -1f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) sync()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) sync()
    }
}
