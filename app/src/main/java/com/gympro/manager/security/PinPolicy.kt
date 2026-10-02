package com.gympro.manager.security

import java.util.Locale

/**
 * قواعد الرمز والإيقاف المؤقت كدوال نقية (لا تعتمد على Android) — معزولة عن AppLockManager
 * عمداً كي يمكن اختبارها كوحدة عادية (JVM) دون جهاز أو Context.
 */
object PinPolicy {

    const val LENGTH = 6
    const val ATTEMPTS_PER_ROUND = 5

    /** مدة الإيقاف بعد كل جولة من 5 محاولات خاطئة؛ تتصاعد ثم تثبت عند ساعة. */
    private val LOCKOUT_STEPS_MS = longArrayOf(
        30_000L, 60_000L, 300_000L, 900_000L, 1_800_000L, 3_600_000L
    )

    /** يرفض الرموز التي تُخمَّن أولاً: أرقام متطابقة (111111) أو متسلسلة صعوداً/نزولاً (123456/654321). */
    fun isWeak(pin: String): Boolean {
        if (pin.length < 2) return true
        if (pin.all { it == pin[0] }) return true
        val diffs = pin.zipWithNext { a, b -> b - a }
        return diffs.all { it == 1 } || diffs.all { it == -1 }
    }

    /** مدة الإيقاف (ms) التي تُفرض عند هذا العدد الإجمالي من الفشل، أو 0 إن لم تكتمل جولة بعد. */
    fun lockoutAfterFailure(totalFailures: Int): Long {
        if (totalFailures <= 0 || totalFailures % ATTEMPTS_PER_ROUND != 0) return 0L
        val step = (totalFailures / ATTEMPTS_PER_ROUND - 1).coerceAtMost(LOCKOUT_STEPS_MS.lastIndex)
        return LOCKOUT_STEPS_MS[step]
    }

    fun attemptsLeft(totalFailures: Int): Int =
        ATTEMPTS_PER_ROUND - (totalFailures % ATTEMPTS_PER_ROUND)

    /**
     * المتبقي من الإيقاف. لو رجعت ساعة الجهاز للخلف (now < start) نعتبر المدة كاملة متبقية
     * بدل أن يفتح التلاعب بالوقت القفل مبكراً.
     */
    fun remainingLockoutMs(startMs: Long, durationMs: Long, nowMs: Long): Long {
        if (durationMs <= 0L) return 0L
        if (nowMs < startMs) return durationMs
        return (startMs + durationMs - nowMs).coerceAtLeast(0L)
    }

    /** "0:27" — أرقام غربية ثابتة (راجع البند 6: Locale.US صريحة). */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms + 999L) / 1000L
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
