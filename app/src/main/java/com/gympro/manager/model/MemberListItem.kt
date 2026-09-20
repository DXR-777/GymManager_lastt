package com.gympro.manager.model

import com.gympro.manager.utils.DateUtils

/**
 * صفّ مُجمَّع من جدولي members و subscriptions يمثّل العضو مع آخر اشتراك مسجَّل له.
 * يُستخدم في شاشة قائمة الأعضاء ولوحة التحكم.
 */
data class MemberListItem(
    val id: Long,
    val name: String,
    val phone: String,
    val photoPath: String?,
    val notes: String,
    val type: SubscriptionType?,
    val startDate: Long?,
    val endDate: Long?,
    val price: Double?,
    val isPaid: Boolean?,
    /** المبلغ المدفوع جزئياً لآخر اشتراك مسجَّل — راجع SubscriptionEntity.paidAmount. */
    val paidAmount: Double? = null,
    /** طريقة الدفع لآخر اشتراك مسجَّل — null فقط عند عدم وجود أي اشتراك بعد لهذا العضو. */
    val paymentMethod: PaymentMethod? = null,
    /** اسم مُرسِل الدفع الإلكتروني لآخر اشتراك مسجَّل — راجع SubscriptionEntity.senderName. */
    val senderName: String? = null,
    /** رقم جوال مُرسِل الدفع الإلكتروني لآخر اشتراك مسجَّل — راجع SubscriptionEntity.senderPhone. */
    val senderPhone: String? = null,
    val deletedAt: Long? = null,
    /**
     * الدَّين الحقيقي المتراكم على العضو عبر تاريخه الكامل: مجموع price لكل اشتراكاته
     * غير المدفوعة منذ أول يوم تسجيل، وليس فقط آخر اشتراك. تُحسب في طبقة الاستعلام
     * (MemberDao) عبر subquery منفصل، لذا تبقى صحيحة حتى لو كان آخر اشتراك للعضو مدفوعاً
     * بينما لديه ديون قديمة لم تُسوَّ بعد.
     */
    val outstandingBalance: Double = 0.0
) {
    /** لا يوجد أي اشتراك مسجَّل بعد لهذا العضو */
    val hasSubscription: Boolean get() = endDate != null

    /**
     * يستخدم DateUtils.isExpired() لضمان توحيد منطق انتهاء الاشتراك في كل مكان بالتطبيق.
     * المنطق السابق (endDate < now بالميلي ثانية) كان يختلف عن DateUtils الذي يقارن بداية اليوم،
     * مما يُسبب تناقضاً: عضو ينتهي اشتراكه اليوم يُعتبر منتهياً في قائمة الأعضاء
     * لكن غير منتهٍ في الإشعارات والـ Worker.
     */
    fun isExpired(now: Long = DateUtils.now()): Boolean =
        hasSubscription && DateUtils.isExpired(endDate ?: 0L, now)

    /** هل اشتراك هذا العضو (الأحدث) لم يبدأ بعد؟ */
    fun isFuture(now: Long = DateUtils.now()): Boolean =
        hasSubscription && DateUtils.isFuture(startDate ?: 0L, now)

    /**
     * هل هذا العضو نشط فعلياً الآن؟ (لديه اشتراك بدأ فعلاً ولم ينتهِ بعد).
     * تُستخدم بدل الاعتماد على hasSubscription && !isExpired() فقط، والتي كانت تُعامل
     * عضواً باشتراك مستقبلي التاريخ (لم يبدأ بعد) كأنه نشط — راجع DateUtils.isActive.
     */
    fun isActive(now: Long = DateUtils.now()): Boolean =
        hasSubscription && DateUtils.isActive(startDate ?: 0L, endDate ?: 0L, now)

    /**
     * هل على هذا العضو دَين حقيقي غير مسدَّد؟ (= مجموع كل اشتراكاته التاريخية غير المدفوعة > 0)
     *
     * ملاحظة هامة (لا تخلط بينها وبين isPaid): isPaid يصف فقط حالة آخر دورة اشتراك،
     * وهو مناسب لعرض شارة "مدفوع هذا الشهر؟" في شاشة الأعضاء وتفاصيل العضو.
     * لكنه غير كافٍ لمعرفة الدَّين الفعلي: عضو يكون آخر اشتراك له مدفوعاً (isPaid = true)
     * بينما لا يزال يدين بثلاثة أشهر سابقة لم تُسدَّد — استخدام isPaid وحده هنا
     * كان يُخفي هذا الدَّين تماماً عن لوحة التحكم وقائمة المتأخرين والإشعارات.
     * لذلك isUnpaid() تعتمد على outstandingBalance المحسوب من كامل السجل التاريخي.
     */
    fun isUnpaid(): Boolean = outstandingBalance > 0.0

    /**
     * الحالة الثلاثية (مدفوع/جزئي/غير مدفوع) لآخر اشتراك مسجَّل فقط — null إن لم يوجد أي
     * اشتراك بعد. نفس منطق SubscriptionEntity.paymentStatus() بالضبط، لكن مبنياً على
     * isPaid/paidAmount المسطَّحين هنا بدل كائن SubscriptionEntity كامل.
     */
    fun lastSubscriptionStatus(): PaymentStatus? = when {
        type == null -> null
        isPaid == true -> PaymentStatus.PAID
        paidAmount != null && paidAmount > 0.0 -> PaymentStatus.PARTIAL
        else -> PaymentStatus.UNPAID
    }
}

/**
 * إسقاط مبسَّط (id/name/deletedAt فقط) لعضو مؤرشف قريب من موعد الحذف النهائي التلقائي —
 * يُستخدم في ArchivePurgeWorker لإرسال تنبيه قبل الحذف (سابقاً كان الحذف يحدث
 * بصمت تام بلا أي تحذير مسبق قابل للتراجع عنه). لا حاجة لحقول MemberListItem الكاملة هنا.
 */
data class ArchivePurgeCandidate(
    val id: Long,
    val name: String,
    val deletedAt: Long
)

/** إحصائيات عضو واحد لشاشة التفاصيل */
data class MemberStats(
    val totalMonthsSubscribed: Int,
    val totalPaid: Double,
    val totalOutstanding: Double,
    /** عدد سجلات الاشتراك غير المدفوعة عبر تاريخ العضو الكامل (أي نوع اشتراك) */
    val unpaidSubscriptionsCount: Int = 0
)
