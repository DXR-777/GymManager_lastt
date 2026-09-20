package com.gympro.manager.model

import android.content.Context
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.SubscriptionEntity

/**
 * نص عرض موحّد لبيانات المُرسِل (senderName/senderPhone) لأي اشتراك، تُستخدم في كل
 * شاشة تعرض بيانات الاشتراك (تفاصيل العضو الحالية، وسجل الاشتراكات) بدل تكرار نفس
 * منطق التنسيق في كل شاشة على حدة.
 *
 * تُعيد null عند غياب الحقلين معاً (لا شيء لعرضه)، فتقرر كل شاشة استدعاء هذه الدالة
 * إخفاء الـ View المخصص بالكامل في تلك الحالة بدل ترك سطر فارغ.
 */
fun SubscriptionEntity.senderInfoLabel(context: Context): String? {
    val name = senderName?.trim()?.takeIf { it.isNotEmpty() }
    val phone = senderPhone?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        name != null && phone != null -> context.getString(R.string.subscription_sender_both, name, phone)
        name != null -> context.getString(R.string.subscription_sender_name_only, name)
        phone != null -> context.getString(R.string.subscription_sender_phone_only, phone)
        else -> null
    }
}

/**
 * نص عرض بيانات المُرسِل مخصَّص لشاشة تفاصيل العضو فقط: كل حقل موجود فعلياً (اسم صاحب
 * المحفظة / رقم المحفظة) على سطر مستقل بأيقونته (👤/📱)، بدل دمجهما بفاصلة في سطر واحد.
 * تُعيد null فقط عند غياب الحقلين معاً، فتُخفي شاشة التفاصيل الـ View بالكامل حينها.
 */
fun SubscriptionEntity.senderInfoDetailLabel(context: Context): String? {
    val name = senderName?.trim()?.takeIf { it.isNotEmpty() }
    val phone = senderPhone?.trim()?.takeIf { it.isNotEmpty() }
    val lines = listOfNotNull(
        name?.let { context.getString(R.string.detail_sender_name, it) },
        phone?.let { context.getString(R.string.detail_sender_phone, it) }
    )
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
