package com.gympro.manager.utils

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * حركة رسم "الحالة الفارغة" (حلقات + نقاط + دائرة مركزية). طبقات هادئة لا تشتّت:
 *  1) دخول: الرسم يكبر بارتداد خفيف، ثم تظهر النصوص تباعاً من الأسفل.
 *  2) حلقة خارجية تدور ببطء (26ث) وداخلية تدور عكسها (18ث) — النقاط تدور معهما.
 *  3) الدائرة المركزية "تتنفّس" وتطفو قليلاً (2.6ث، عودة سلسة).
 *  4) موجتان تنتشران من المركز وتتلاشيان (3.4ث، الثانية متأخرة نصف دورة فتبدو الحركة متصلة).
 * تحترم إعداد النظام "إزالة الحركات" (ValueAnimator.areAnimatorsEnabled) فتعرض الحالة النهائية ثابتة.
 * استدعاء start() آمن ومتكرر (لا يعيد التشغيل إن كانت تعمل)، و stop() يلغي كل شيء ويعيد الوضع الطبيعي.
 */
class OrbitAnimator(
    private val root: View,
    private val rotorOuter: View,
    private val rotorInner: View,
    private val ripple1: View,
    private val ripple2: View,
    private val core: View,
    private val staggered: List<View>,
    private val glow: View? = null
) {
    private val animators = mutableListOf<Animator>()
    var isRunning = false
        private set

    private val density get() = root.resources.displayMetrics.density

    fun start() {
        if (isRunning) return
        isRunning = true
        reset()
        if (!ValueAnimator.areAnimatorsEnabled()) return

        // 1) دخول
        root.alpha = 0f
        root.scaleX = 0.82f
        root.scaleY = 0.82f
        animators += ObjectAnimator.ofPropertyValuesHolder(
            root,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.82f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.82f, 1f)
        ).apply {
            duration = 650
            interpolator = OvershootInterpolator(1.1f)
        }
        staggered.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 18 * density
            animators += ObjectAnimator.ofPropertyValuesHolder(
                view,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 18 * density, 0f)
            ).apply {
                duration = 450
                startDelay = 220L + index * 90L
                interpolator = DecelerateInterpolator()
            }
        }

        // 2) دوران متعاكس
        animators += loopRotation(rotorOuter, 360f, 26_000)
        animators += loopRotation(rotorInner, -360f, 18_000)

        // 3) تنفّس وطفو للمركز
        animators += ObjectAnimator.ofPropertyValuesHolder(
            core,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -4 * density)
        ).apply {
            duration = 2_600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }

        // هالة خلف المركز تنبض مع التنفّس
        glow?.let { g ->
            animators += ObjectAnimator.ofPropertyValuesHolder(
                g,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.08f)
            ).apply {
                duration = 2_600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }

        // 4) موجتان
        animators += rippleAnimator(ripple1, 0L)
        animators += rippleAnimator(ripple2, 1_700L)

        animators.forEach { it.start() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        animators.forEach { it.cancel() }
        animators.clear()
        reset()
    }

    private fun loopRotation(view: View, to: Float, durationMs: Long) =
        ObjectAnimator.ofFloat(view, View.ROTATION, 0f, to).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }

    private fun rippleAnimator(view: View, delay: Long) =
        ObjectAnimator.ofPropertyValuesHolder(
            view,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 2.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 2.1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)
        ).apply {
            duration = 3_400
            startDelay = delay
            interpolator = DecelerateInterpolator(1.4f)
            repeatCount = ValueAnimator.INFINITE
        }

    /** الحالة الثابتة النهائية (أيضاً ما يظهر عند إيقاف الحركات من إعدادات النظام). */
    private fun reset() {
        root.alpha = 1f; root.scaleX = 1f; root.scaleY = 1f
        rotorOuter.rotation = 0f
        rotorInner.rotation = 0f
        core.scaleX = 1f; core.scaleY = 1f; core.translationY = 0f
        listOf(ripple1, ripple2).forEach { it.alpha = 0f; it.scaleX = 1f; it.scaleY = 1f }
        glow?.let { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
        staggered.forEach { it.alpha = 1f; it.translationY = 0f }
    }
}
