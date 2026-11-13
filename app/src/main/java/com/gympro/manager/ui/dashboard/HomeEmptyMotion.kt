package com.gympro.manager.ui.dashboard

import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import com.gympro.manager.databinding.FragmentDashboardBinding
import com.gympro.manager.ui.widgets.Motion

/**
 * حركة بطاقات "الحالات الفارغة" في الشاشة الرئيسية. أربعة مشاهد، كل مشهد دالة في الزمن:
 *
 *  1) الأمان:   النصوص تصعد تباعاً، شرائح الأيام تدخل من اليمين لليسار وتُفعَّل نقاطها بارتداد،
 *               ثم كل 5ث "موجة" تعبر النقاط (تكبر وترتفع شريحة شريحة)، شريحة اليوم تتنفّس،
 *               نقطة اليوم الرابع المنتظر تومض، وساعة "أقرب انتهاء" تدور دورة كاملة كل 4ث.
 *  2) التحصيل: النصوص تصعد فقط (الدائرة ثابتة).
 *  3) صالة جديدة: النصوص تصعد فقط (زر الإضافة ثابت).
 *  4) الصفوف الشبحية: تدخل تباعاً ثم تنبض بموجة تنزل من الأعلى للأسفل (واللمعة في SheenView).
 *
 * التشغيل مرتبط برؤية المشهد + نشاط الشاشة ([setActive]) + إعداد النظام "إزالة الحركات".
 * عند الإيقاف تعود كل الخصائص لحالتها الطبيعية (alpha=1، بلا إزاحة).
 */
class HomeEmptyMotion(private val b: FragmentDashboardBinding) {

    private val density = b.root.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var active = false

    private val dayChips = listOf(b.dayChip0, b.dayChip1, b.dayChip2, b.dayChip3, b.dayChip4)
    private val dayDots = listOf(b.dayDot0, b.dayDot1, b.dayDot2, b.dayDot3, b.dayDot4)

    // ───── محرّك المشاهد ─────
    private inner class Scene(
        val root: View,
        val frame: (Long) -> Unit,
        val reset: () -> Unit
    ) {
        private var anim: ValueAnimator? = null
        private var t0 = 0L
        val running get() = anim != null

        fun start() {
            if (anim != null) return
            t0 = SystemClock.uptimeMillis()
            frame(0L) // الحالة الابتدائية فوراً (قبل أول رسم) فلا يومض المحتوى بشكله النهائي
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1_000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { frame(SystemClock.uptimeMillis() - t0) }
                start()
            }
        }

        fun stop() {
            anim?.cancel()
            anim = null
            reset()
        }
    }

    private val scenes = listOf(
        Scene(b.tvEmptyExpiring, ::safeFrame, ::safeReset),
        Scene(b.tvEmptyUnpaid, ::collectFrame, ::collectReset),
        Scene(b.emptyNewExpiring, ::newGymFrame, ::newGymReset),
        Scene(b.emptyNewUnpaid, ::ghostFrame, ::ghostReset)
    )

    /** يُستدعى بعد كل تغيّر في ظهور البطاقات أو نشاط الشاشة: يشغّل الظاهر ويوقف المخفي. */
    fun refresh() {
        val canAnimate = active && ValueAnimator.areAnimatorsEnabled()
        for (s in scenes) {
            val should = canAnimate && s.root.visibility == View.VISIBLE
            if (should && !s.running) s.start() else if (!should && s.running) s.stop()
        }
    }

    fun setActive(value: Boolean) {
        active = value
        refresh()
    }

    fun release() {
        active = false
        for (s in scenes) if (s.running) s.stop()
    }

    // ───── أدوات مساعدة ─────
    private fun seg(t: Long, start: Long, dur: Long) = Motion.seg(t, start, dur)

    /** صعود + ظهور للعنصر (نص/زر) بتأخير محدد. */
    private fun rise(v: View, t: Long, delay: Long, dur: Long = 480, dist: Float = 10f) {
        val e = Motion.outCubic(seg(t, delay, dur))
        v.alpha = e
        v.translationY = (1f - e) * dp(dist)
    }

    private fun clear(v: View) {
        v.alpha = 1f
        v.translationY = 0f
        v.translationX = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.rotation = 0f
    }

    // ════════ 1) الأمان ════════
    private fun safeFrame(t: Long) {
        rise(b.tvSafeTitle, t, 200)
        rise(b.tvSafeSub, t, 290)
        rise(b.chipNextExpiry, t, 700)

        // ساعة "أقرب انتهاء": دورة كاملة سلسة كل 4.2ث
        val c = t - 1_800L
        b.ivNextExpiryClock.rotation =
            if (c >= 0 && c % 4_200L < 900L) 360f * Motion.inOutCubic((c % 4_200L) / 900f) else 0f

        for (i in 0..4) {
            val ce = Motion.outCubic(seg(t, 380L + i * 85L, 520))
            val chip = dayChips[i]
            chip.alpha = ce
            chip.translationX = (1f - ce) * dp(14f)
            // علامات الصح ثابتة بلا حركة؛ فقط نقطة اليوم المنتظر (+4) تومض بهدوء
            if (i == 4) dayDots[i].alpha = if (t > 1_000L) 0.5f + 0.5f * Motion.wave(t, 1_900L) else ce
        }
    }

    private fun safeReset() {
        listOf(b.tvSafeTitle, b.tvSafeSub, b.chipNextExpiry, b.ivNextExpiryClock).forEach(::clear)
        dayChips.forEach(::clear)
        dayDots.forEach(::clear)
    }

    // ════════ 2) التحصيل ════════
    private fun collectFrame(t: Long) {
        rise(b.tvCollectTitle, t, 200)
        rise(b.tvCollectSub, t, 290)
        rise(b.chipCollected, t, 420)
    }

    private fun collectReset() {
        listOf(b.tvCollectTitle, b.tvCollectSub, b.chipCollected).forEach(::clear)
    }

    // ════════ 3) صالة جديدة ════════
    private fun newGymFrame(t: Long) {
        rise(b.tvNewTitle, t, 200)
        rise(b.tvNewSub, t, 290)
    }

    private fun newGymReset() {
        listOf(b.tvNewTitle, b.tvNewSub).forEach(::clear)
    }

    // ════════ 4) الصفوف الشبحية ════════
    private fun ghostFrame(t: Long) {
        val rows = listOf(b.ghostRow1, b.ghostRow2)
        rows.forEachIndexed { i, row ->
            val e = Motion.outCubic(seg(t, 150L + i * 140L, 520))
            // موجة نبض تنزل من الصف الأول للثاني
            val pulse = if (t > 900L) 0.72f + 0.28f * Motion.wave(t - i * 420L, 2_600L) else 1f
            row.alpha = e * pulse
            row.translationY = (1f - e) * dp(12f)
        }
        rise(b.tvGhostCaption, t, 520)
    }

    private fun ghostReset() {
        listOf(b.ghostRow1, b.ghostRow2, b.tvGhostCaption).forEach(::clear)
    }
}
