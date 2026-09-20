package com.gympro.manager.model

import android.content.Context
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.SubscriptionEntity

/**
 * طريقة الدفع التي استخدمها العضو (أو ينوي استخدامها) لتسديد قيمة الاشتراك — أي "كيف
 * دُفع"، وليس "هل دُفع؟". هذا الحقل مستقل تماماً عن SubscriptionEntity.isPaid:
 *
 * isPaid = true, paymentMethod = PALPAY → دُفع فعلاً عبر PalPay.
 * isPaid = false, paymentMethod = JAWWAL_PAY → لم يُدفَع بعد، لكن الطريقة المتوقَّعة
 * عند التحصيل لاحقاً هي جوال باي.
 *
 * الافتراضي PALPAY: لأن التطبيق موجَّه لسوق غزة، حيث المحافظ الإلكترونية (PalPay
 * تحديداً) أكثر استخداماً بكثير من الدفع النقدي المباشر.
 */
enum class PaymentMethod {
    PALPAY,
    JAWWAL_PAY,
    BANK_OF_PALESTINE,
    CASH;

    companion object {
        fun fromString(value: String?): PaymentMethod =
            values().firstOrNull { it.name == value } ?: PALPAY
    }
}

/**
 * النص المعروض لكل طريقة دفع في الواجهة (نموذج الإضافة/التعديل، تفاصيل العضو، سجل
 * الاشتراكات، وتصدير CSV) — مُجمَّع هنا في مكان واحد بدل تكراره في كل شاشة، حتى لا
 * تختلف تسمية نفس القيمة بين شاشتين (خصوصاً أن نموذج الإضافة يحتاج مطابقة عكسية من
 * النص المعروض إلى القيمة الأصلية عند الحفظ).
 */
fun PaymentMethod.label(context: Context): String = when (this) {
    PaymentMethod.PALPAY -> context.getString(R.string.payment_method_palpay)
    PaymentMethod.JAWWAL_PAY -> context.getString(R.string.payment_method_jawwal_pay)
    PaymentMethod.BANK_OF_PALESTINE -> context.getString(R.string.payment_method_bank_of_palestine)
    PaymentMethod.CASH -> context.getString(R.string.payment_method_cash)
}

/**
 * طريقة الدفع "الحقيقية" للعرض فقط — تُعيد النص إن كان الاشتراك مدفوعاً فعلياً
 * (isPaid == true)، أو null إن لم يُدفَع بعد.
 *
 * السبب: حقل paymentMethod في قاعدة البيانات يُحفَظ دائماً (حتى قبل الدفع، بقيمته
 * الافتراضية PALPAY من الدروب-داون عند إنشاء العضو) لأنه يمثل "الطريقة المتوقَّعة عند
 * التحصيل"، وليس دليلاً على أن التحصيل حدث. لذلك يجب ألا تُعرض أي شاشة قيمة هذا الحقل
 * كما لو كانت واقعاً — إلا بعد أن يصبح isPaid == true فعلياً.
 *
 * هذه دالة العرض الوحيدة التي يجب أن تستخدمها كل شاشة (تفاصيل العضو، سجل الاشتراكات)
 * بدل الوصول المباشر لـ paymentMethod.label()، حتى يبقى السلوك موحّداً بينها ولا
 * تتكرر شروط isPaid في كل شاشة على حدة. لا تُغيّر هذه الدالة أي شيء في قاعدة البيانات
 * أو حسابات الإيراد/الاشتراكات — عرض فقط.
 *
 * تُعيد النص أيضاً للاشتراك المدفوع جزئياً (PARTIAL): جزء حقيقي من المبلغ دخل الإيراد
 * فعلاً عبر طريقة الدفع هذه، فلا سبب لإخفائها كما لو لم يحدث أي تحصيل إطلاقاً — بخلاف
 * UNPAID حيث لا تحصيل حقيقي بعد. راجع PaymentStatus.kt.
 */
fun SubscriptionEntity.paidPaymentMethodLabel(context: Context): String? =
    if (paymentStatus() != PaymentStatus.UNPAID) paymentMethod.label(context) else null
