package com.gympro.manager.model

/**
 * أنواع الاشتراك المتاحة في النادي.
 * اليومي: يُدفع عن كل يوم حضور فعلي فقط (لا يُحسب في أيام الإجازة).
 * الأسبوعي: سعر ثابت لمدة 7 أيام، شامل أيام الإجازة.
 * الشهري: سعر ثابت لمدة شهر كامل، شامل أيام الإجازة (لا خصم عنها).
 * المخصص (CUSTOM): سعر ثابت لعدد أيام يُدخله صاحب الجيم يدوياً (بلا يوم فوترة مرجعي،
 * بخلاف الشهري — كل اشتراك مخصص مستقل بعدد أيامه الخاص، راجع DateUtils.calculateEndDate).
 */
enum class SubscriptionType {
    DAILY,
    WEEKLY,
    MONTHLY,
    CUSTOM;

    companion object {
        fun fromString(value: String): SubscriptionType =
            values().firstOrNull { it.name == value } ?: MONTHLY
    }
}

enum class MemberFilter {
    ALL, ACTIVE, EXPIRED, UNPAID, EXPIRING_SOON
}

/**
 * ترتيب قائمة الأعضاء — راجع البند 14: سابقاً لم يوجد سوى بحث نصي وفلاتر حالة،
 * بلا أي طريقة لفرز القائمة نفسها.
 * NAME: أبجدي حسب الاسم (الترتيب الافتراضي، ونفس ترتيب استعلام قاعدة البيانات أصلاً).
 * EXPIRY_SOONEST: الأقرب لانتهاء الاشتراك أولاً؛ من لا يملك اشتراكاً بعد يُدفع لنهاية
 * القائمة بدل ظهوره أولاً بسبب endDate=null.
 * NEWEST: الأحدث تسجيلاً أولاً، بالاعتماد على id (تلقائي التزايد) كبديل عملي لعدم
 * وجود عمود createdAt على جدول members حالياً.
 */
enum class MemberSort {
    NAME, EXPIRY_SOONEST, NEWEST
}

/** نتيجة محاولة فتح واتساب: أي مقدمة دولة استُخدمت أولاً */
enum class WhatsappPrefix(val code: String) {
    PALESTINE("970"),
    ISRAEL("972");

    companion object {
        fun fromStored(stored: String?): WhatsappPrefix? = when (stored) {
            "+970" -> PALESTINE
            "+972" -> ISRAEL
            else -> null // null يعني تلقائي: جرّب فلسطين ثم إسرائيل
        }
    }
}
