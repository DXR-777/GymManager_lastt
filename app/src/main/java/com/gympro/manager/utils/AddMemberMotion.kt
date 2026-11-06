package com.gympro.manager.utils

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import kotlin.math.min

/**
 * حركات شاشة إضافة/تعديل العضو — قصيرة وهادفة وتحترم "إزالة الحركات" في النظام:
 *  - staggerIn: بطاقات الأقسام تصعد تباعاً عند الفتح.
 *  - pop: نبضة اختيار (محدّد/طريقة دفع/صورة).
 *  - shake: هزّة قصيرة لحقل فيه خطأ.
 *  - expand: انتقال سلس عند ظهور/اختفاء مجموعات (جزئي، طريقة الدفع...).
 *  - countUp: تغيّر الأرقام (الإجمالي/المتبقي) بعدّ تصاعدي/تنازلي بدل القفز.
 */
object AddMemberMotion {
    private fun enabled() = ValueAnimator.areAnimatorsEnabled()

    fun staggerIn(container: ViewGroup, maxItems: Int = 7) {
        if (!enabled()) return
        val d = container.resources.displayMetrics.density
        for (i in 0 until min(container.childCount, maxItems)) {
            val child = container.getChildAt(i)
            child.alpha = 0f
            child.translationY = 28 * d
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L * i + 40L).setDuration(440)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    fun pop(view: View) {
        if (!enabled()) return
        view.animate().cancel()
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.animate().scaleX(1f).scaleY(1f).setDuration(240)
            .setInterpolator(OvershootInterpolator(1.8f)).start()
    }

    fun shake(view: View) {
        if (!enabled()) return
        val d = view.resources.displayMetrics.density
        ObjectAnimator.ofFloat(
            view, View.TRANSLATION_X,
            0f, -10 * d, 8 * d, -6 * d, 4 * d, -2 * d, 0f
        ).apply { duration = 380 }.start()
    }

    fun expand(root: ViewGroup) {
        if (!enabled()) return
        TransitionManager.beginDelayedTransition(
            root,
            TransitionSet()
                .addTransition(ChangeBounds())
                .addTransition(Fade())
                .setDuration(280)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
        )
    }

    /**
     * يحرّك مؤشر الاختيار خلف القطاع المختار (انزلاق مع ارتداد خفيف). target قد لا يكون مقاساً بعد عند
     * الفتح فيُؤجَّل الوضع حتى القياس (بلا حركة). thumb و target ضمن نفس الأب بنفس نظام الإحداثيات.
     */
    fun moveThumb(thumb: View, target: View, animate: Boolean) {
        if (target.width == 0 || target.height == 0) {
            target.post { if (target.width > 0) moveThumb(thumb, target, false) }
            return
        }
        val lp = thumb.layoutParams
        if (lp.width != target.width || lp.height != target.height) {
            lp.width = target.width
            lp.height = target.height
            thumb.layoutParams = lp
        }
        thumb.translationY = target.top.toFloat()
        val x = target.left.toFloat()
        val firstPlacement = thumb.visibility != View.VISIBLE
        thumb.visibility = View.VISIBLE
        thumb.animate().cancel()
        if (animate && !firstPlacement && enabled()) {
            thumb.animate().translationX(x).setDuration(320)
                .setInterpolator(OvershootInterpolator(0.9f)).start()
        } else {
            thumb.translationX = x
        }
    }

    /** يغيّر نص الرقم تدريجياً من القيمة السابقة (المخزّنة في tag) إلى الجديدة. */
    fun countUp(tv: TextView, to: Double, format: (Double) -> String) {
        val from = (tv.tag as? Double) ?: to
        tv.tag = to
        if (!enabled() || from == to || !tv.isShown) {
            tv.text = format(to)
            return
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { tv.text = format(from + (to - from) * (it.animatedValue as Float)) }
            start()
        }
    }
}
