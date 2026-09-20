package com.gympro.manager.utils

import kotlin.math.roundToInt

/**
 * تنسيق الأرقام المالية بشكل موحّد عبر التطبيق (بدون كسور غير ضرورية).
 *
 * الترتيب "الرمز ثم الرقم" (مثل "₪ 50"، "$ 50"، "د.أ 50") مقصود ومطابق
 * لمواصفة صاحب المشروع الصريحة (مهمة "عرض العملة أينما ظهر مبلغ")، ويُطبَّق
 * مركزياً هنا فقط — كل شاشة تعرض مبلغاً مالياً في هذا التطبيق (تفاصيل العضو،
 * الإيرادات، لوحة التحكم، الأرشيف، رسائل واتساب، تأكيد الحذف...) تستدعي هذه
 * الدالة نفسها بدل بناء النص يدوياً، فتغيير الترتيب هنا ينعكس فوراً وبثبات في
 * كل مكان دون الحاجة لتعديل كل شاشة على حدة. لا مكان آخر في المشروع يُحلّل
 * (parse) هذا النص المُنسَّق لاحقاً، فتبديل الترتيب آمن تماماً بلا أثر جانبي.
 */
object CurrencyFormatter {

    fun format(amount: Double, currencySymbol: String): String {
        val rounded = (amount * 100).roundToInt() / 100.0
        val text = if (rounded == rounded.toInt().toDouble()) {
            rounded.toInt().toString()
        } else {
            "%.2f".format(rounded)
        }
        return "$currencySymbol $text"
    }
}
