package com.gympro.manager.model

import com.gympro.manager.data.local.entities.SubscriptionEntity

/**
 * حالة دفع الاشتراك الثلاثية، مُشتقّة من isPaid + paidAmount (راجع الشرح الكامل في
 * SubscriptionEntity.paidAmount) — لا تُخزَّن كعمود مستقل في قاعدة البيانات عمداً، حتى لا
 * يوجد مصدران للحقيقة قد يتعارضان.
 *
 * PAID: isPaid = true. السعر الكامل دخل في الإيراد، لا دَين.
 * PARTIAL: isPaid = false و paidAmount > 0. جزء من السعر دخل في الإيراد (paidAmount)،
 *          والباقي (price - paidAmount) يبقى دَيناً على العضو.
 * UNPAID: isPaid = false و paidAmount = null (أو 0). لا شيء دخل في الإيراد، كامل السعر دَين.
 */
enum class PaymentStatus {
    PAID,
    PARTIAL,
    UNPAID
}

fun SubscriptionEntity.paymentStatus(): PaymentStatus = when {
    isPaid -> PaymentStatus.PAID
    paidAmount != null && paidAmount > 0.0 -> PaymentStatus.PARTIAL
    else -> PaymentStatus.UNPAID
}

/**
 * المبلغ المتبقي (الدَّين) لهذا الاشتراك تحديداً — 0 إذا PAID، الفرق (price - paidAmount)
 * إذا PARTIAL، والسعر كاملاً إذا UNPAID. يُستخدم بدل price مباشرة في كل مكان يعرض "الدَّين
 * المتبقي" على مستوى اشتراك واحد (بنر تسديد الدَّين، شارة السجل التاريخي).
 */
fun SubscriptionEntity.remainingAmount(): Double = when (paymentStatus()) {
    PaymentStatus.PAID -> 0.0
    PaymentStatus.PARTIAL -> (price - (paidAmount ?: 0.0)).coerceAtLeast(0.0)
    PaymentStatus.UNPAID -> price
}

/**
 * المبلغ الذي دخل فعلياً في الإيراد لهذا الاشتراك تحديداً — السعر كاملاً إذا PAID،
 * المبلغ الجزئي إذا PARTIAL، صفر إذا UNPAID.
 */
fun SubscriptionEntity.collectedAmount(): Double = when (paymentStatus()) {
    PaymentStatus.PAID -> price
    PaymentStatus.PARTIAL -> paidAmount ?: 0.0
    PaymentStatus.UNPAID -> 0.0
}
