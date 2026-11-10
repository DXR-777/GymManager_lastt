package com.gympro.manager.utils

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/**
 * حركة صندوق "الأرشيف فارغ": الصندوق يبدأ مغلقاً، ثم (≈ ثانية بعد ظهور الشاشة):
 *   1) ينضغط قليلاً (تمهيد) ثم يُرفع الغطاء ويميل بارتداد خفيف،
 *   2) يتصاعد غبار ناعم من الداخل ليُظهر أنه فارغ،
 *   3) يهتز الصندوق بتخامد تدريجي (كأنه يقول "لا شيء هنا") والغطاء يتمايل معه،
 *   4) يعود الغطاء ويُغلق بسقوط سريع، وينضغط الصندوق عند الإغلاق ثم يستقر (مع تمدّد الظل).
 * تتكرر الدورة كل ~7 ثوانٍ، ويمكن إعادتها بالنقر على الصندوق (replay).
 * تحترم "إزالة الحركات" في النظام: يبقى الصندوق مغلقاً وثابتاً. start/stop آمنان ومتكرران.
 *
 * @param group  حاوية الصندوق والغطاء والغبار (الاهتزاز والانضغاط عليها؛ الطفو على أبيها OrbitAnimator).
 */
class ArchiveBoxAnimator(
    private val root: View,
    private val floater: View,
    private val group: View,
    private val lid: View,
    private val dust: List<View>,
    private val shadow: View,
    private val glow: View,
    private val title: View
) {
    private val ambient = mutableListOf<Animator>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var current: AnimatorSet? = null
    private val density get() = group.resources.displayMetrics.density

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            play { if (running) handler.postDelayed(this, REPEAT_MS) }
        }
    }

    fun start(initialDelayMs: Long = 1_100L) {
        if (running) return
        running = true
        reset()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        startAmbient()
        handler.postDelayed(loop, initialDelayMs)
    }

    /** دخول ناعم + طفو دائم للصندوق + تنفّس الهالة (طبقة هادئة تحت حركة الفتح). */
    private fun startAmbient() {
        val d = density
        root.alpha = 0f; root.scaleX = 0.85f; root.scaleY = 0.85f
        title.alpha = 0f; title.translationY = 16 * d
        ambient += ObjectAnimator.ofPropertyValuesHolder(
            root,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.85f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.85f, 1f)
        ).apply { duration = 650; interpolator = OvershootInterpolator(1.1f) }
        ambient += ObjectAnimator.ofPropertyValuesHolder(
            title,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 16 * d, 0f)
        ).apply { startDelay = 250; duration = 450; interpolator = DecelerateInterpolator() }
        ambient.first().addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                root.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        })
        ambient += ObjectAnimator.ofFloat(floater, View.TRANSLATION_Y, 0f, -7 * d).apply {
            duration = 2_600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        ambient += ObjectAnimator.ofPropertyValuesHolder(
            glow,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.08f)
        ).apply {
            duration = 2_600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        ambient.forEach { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(loop)
        current?.cancel()
        current = null
        ambient.forEach { it.cancel() }
        ambient.clear()
        reset()
    }

    /** إعادة التشغيل فوراً (نقر المستخدم على الصندوق) إن لم تكن الحركة تعمل حالياً. */
    fun replay() {
        if (!running || current?.isRunning == true || !ValueAnimator.areAnimatorsEnabled()) return
        handler.removeCallbacks(loop)
        loop.run()
    }

    private fun play(onEnd: () -> Unit) {
        val d = density
        val w = group.width.toFloat().coerceAtLeast(1f)
        val liftY = w * 0.34f
        val liftX = w * 0.20f
        group.pivotX = group.width / 2f
        group.pivotY = group.height.toFloat()
        lid.pivotX = lid.width / 2f
        lid.pivotY = lid.height / 2f

        fun anim(target: View, delay: Long, duration: Long, interp: android.animation.TimeInterpolator, vararg values: PropertyValuesHolder) =
            ObjectAnimator.ofPropertyValuesHolder(target, *values).apply {
                startDelay = delay
                this.duration = duration
                interpolator = interp
            }

        val list = mutableListOf<Animator>()

        // 1) تمهيد: ينضغط الصندوق قليلاً قبل أن "يفتح"
        list += anim(
            group, 0, 130, DecelerateInterpolator(),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.94f)
        )
        // يرتدّ إلى حجمه بتمدّد خفيف مع انطلاق الغطاء
        list += anim(
            group, 130, 360, OvershootInterpolator(2.2f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.04f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1f)
        )
        // رفع الغطاء: يصعد ويميل بارتداد
        list += anim(
            lid, 110, 520, OvershootInterpolator(1.5f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -liftY),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, liftX),
            PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 13f)
        )

        // 2) غبار ناعم يتصاعد من داخل الصندوق الفارغ
        dust.forEachIndexed { i, v ->
            val rise = w * (0.24f + i * 0.07f)
            val drift = (if (i % 2 == 0) 1 else -1) * (6 + i * 3) * d
            list += anim(
                v, 520L + i * 110L, 950, DecelerateInterpolator(1.3f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -rise),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, drift),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.4f, 1.4f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.4f, 1.4f)
            )
            list += ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 0.7f, 0f).apply {
                startDelay = 520L + i * 110L
                duration = 950
            }
        }

        // 3) اهتزاز متخامد "لا شيء هنا" + تمايل الغطاء معه
        list += ObjectAnimator.ofFloat(group, View.ROTATION, 0f, -7f, 6f, -5f, 4f, -2.5f, 1.2f, 0f).apply {
            startDelay = 760
            duration = 700
            interpolator = LinearInterpolator()
        }
        list += ObjectAnimator.ofFloat(lid, View.ROTATION, 13f, 9f, 16f, 11f, 14f, 13f).apply {
            startDelay = 760
            duration = 700
            interpolator = LinearInterpolator()
        }
        list += ObjectAnimator.ofFloat(shadow, View.SCALE_X, 1f, 1.1f, 0.94f, 1.05f, 1f).apply {
            startDelay = 760
            duration = 700
        }

        // 4) إغلاق: يسقط الغطاء بتسارع ثم ينضغط الصندوق عند الهبوط ويستقر
        list += anim(
            lid, 1_650, 300, AccelerateInterpolator(1.6f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -liftY, 0f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, liftX, 0f),
            PropertyValuesHolder.ofFloat(View.ROTATION, 13f, 0f)
        )
        list += anim(
            group, 1_950, 130, DecelerateInterpolator(),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.9f)
        )
        list += anim(
            group, 2_080, 420, OvershootInterpolator(2.6f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.06f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1f)
        )
        list += ObjectAnimator.ofFloat(shadow, View.ALPHA, 1f, 0.55f, 1f).apply {
            startDelay = 1_950
            duration = 420
        }

        current = AnimatorSet().apply {
            playTogether(list)
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    reset()
                    if (!cancelled) onEnd()
                }
            })
            start()
        }
    }

    /** الحالة الساكنة: صندوق مغلق بلا غبار ولا اهتزاز. */
    private fun reset() {
        group.scaleX = 1f; group.scaleY = 1f; group.rotation = 0f
        lid.translationX = 0f; lid.translationY = 0f; lid.rotation = 0f
        dust.forEach { it.alpha = 0f; it.translationX = 0f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f }
        shadow.scaleX = 1f; shadow.alpha = 1f
        root.alpha = 1f; root.scaleX = 1f; root.scaleY = 1f
        floater.translationY = 0f
        glow.alpha = 1f; glow.scaleX = 1f; glow.scaleY = 1f
        title.alpha = 1f; title.translationY = 0f
    }

    private companion object {
        const val REPEAT_MS = 7_000L
    }
}
