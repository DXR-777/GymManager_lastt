package com.gympro.manager.utils

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * هل الحركة مفعّلة؟ يُرجع false إذا ضبط المستخدم "مقياس مدة الرسوم المتحركة" على صفر
 * (تقليل الحركة) أو كان وضع توفير الطاقة يعطّل الرسوم. كل حركات شاشة تفاصيل العضو تعتمد عليه:
 * عند false تُعرض القيم النهائية فوراً بلا إزاحة ولا عدّ ولا رسم تدريجي.
 */
fun areAnimationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

/**
 * استجابة لمس موحّدة: تصغير إلى [pressed] (0.97) عند الضغط وعودة ناعمة عند الرفع أو الإلغاء.
 * لا تستهلك الحدث (تُرجع false) فيبقى النقر والريبل وإمكانية الوصول كما هي.
 * لا تعمل على عنصر معطَّل، وتُعطَّل كلياً مع تقليل الحركة.
 */
@SuppressLint("ClickableViewAccessibility")
fun View.attachPressScale(pressed: Float = 0.97f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                if (v.isEnabled && areAnimationsEnabled()) {
                    v.animate().scaleX(pressed).scaleY(pressed).setDuration(80L)
                        .setInterpolator(DecelerateInterpolator()).start()
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(140L)
                    .setInterpolator(DecelerateInterpolator()).start()
        }
        false
    }
}
