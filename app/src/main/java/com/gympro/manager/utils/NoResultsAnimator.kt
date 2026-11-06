package com.gympro.manager.utils

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * حركة "لا نتائج": عدسة تمسح قائمة أشباح باهتة كأنها تبحث ولا تجد.
 *  - دخول: المشهد يكبر قليلاً مع ظهور، ثم تظهر الأشباح فالنص فالزر تباعاً من الأسفل.
 *  - العدسة تتحرك في مسار انسيابي (بيضاوي/ليساجو): أفقياً 2.6ث ورأسياً 1.9ث بفرق إيقاع فلا يتكرر المسار
 *    بشكل آلي، مع ميلان خفيف وتنفّس بسيط في الحجم.
 *  - هالة تنبض من العدسة وتتلاشى (1.8ث).
 *  - صفوف الأشباح تلمع بالتناوب (إحساس "جارٍ التفتيش").
 * تحترم "إزالة الحركات" في النظام، و start() آمن ومتكرر، و stop() يعيد كل شيء لوضعه الطبيعي.
 */
class NoResultsAnimator(
    private val stage: View,
    private val lens: View,
    private val halo: View,
    private val ghostRows: List<View>,
    private val staggered: List<View>
) {
    private val animators = mutableListOf<Animator>()
    var isRunning = false
        private set

    private val density get() = stage.resources.displayMetrics.density

    fun start() {
        if (isRunning) return
        isRunning = true
        reset()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val d = density
        val ease = AccelerateDecelerateInterpolator()

        // دخول
        stage.alpha = 0f
        stage.scaleX = 0.9f
        stage.scaleY = 0.9f
        animators += ObjectAnimator.ofPropertyValuesHolder(
            stage,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1f)
        ).apply { duration = 550; interpolator = OvershootInterpolator(1.0f) }
        staggered.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 16 * d
            animators += ObjectAnimator.ofPropertyValuesHolder(
                v,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 16 * d, 0f)
            ).apply { duration = 420; startDelay = 240L + i * 90L; interpolator = DecelerateInterpolator() }
        }

        // العدسة: مسار انسيابي بإيقاعين مختلفين + ميلان + تنفّس
        animators += loop(ObjectAnimator.ofFloat(lens, View.TRANSLATION_X, -64 * d, 64 * d), 2_600, ease)
        animators += loop(ObjectAnimator.ofFloat(lens, View.TRANSLATION_Y, -28 * d, 28 * d), 1_900, ease)
        animators += loop(ObjectAnimator.ofFloat(lens, View.ROTATION, -12f, 12f), 2_600, ease)
        animators += loop(
            ObjectAnimator.ofPropertyValuesHolder(
                lens,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)
            ), 1_300, ease
        )
        // الهالة
        animators += ObjectAnimator.ofPropertyValuesHolder(
            halo,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.55f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.55f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f)
        ).apply {
            duration = 1_800
            interpolator = DecelerateInterpolator(1.3f)
            repeatCount = ValueAnimator.INFINITE
        }
        // لمعان الأشباح بالتناوب
        ghostRows.forEachIndexed { i, row ->
            animators += loop(ObjectAnimator.ofFloat(row, View.ALPHA, 0.32f, 0.72f), 1_400, ease, delay = i * 260L)
        }
        animators.forEach { it.start() }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        animators.forEach { it.cancel() }
        animators.clear()
        reset()
    }

    private fun loop(a: ObjectAnimator, durationMs: Long, interpolator: android.animation.TimeInterpolator, delay: Long = 0L) =
        a.apply {
            duration = durationMs
            this.interpolator = interpolator
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

    private fun reset() {
        stage.alpha = 1f; stage.scaleX = 1f; stage.scaleY = 1f
        lens.translationX = 0f; lens.translationY = 0f; lens.rotation = 0f; lens.scaleX = 1f; lens.scaleY = 1f
        halo.alpha = 0f; halo.scaleX = 1f; halo.scaleY = 1f
        ghostRows.forEach { it.alpha = 0.5f }
        staggered.forEach { it.alpha = 1f; it.translationY = 0f }
    }
}
