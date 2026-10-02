package com.gympro.manager.utils

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.core.content.ContextCompat
import com.gympro.manager.R
import java.util.Locale
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

    // 9.2: عازلا اتجاه Unicode (FSI...PDI) يثبّتان ترتيب "رمز ثم رقم" داخل سياق عربي RTL
    // أينما ظهر المبلغ، بدل اعتماد انعكاس العرض على محرك الخط في كل جهاز على حدة.
    private const val ISOLATE_START = "\u2066" // First Strong Isolate
    private const val ISOLATE_END = "\u2069"   // Pop Directional Isolate

    private fun roundedText(amount: Double): String {
        val rounded = (amount * 100).roundToInt() / 100.0
        return if (rounded == rounded.toInt().toDouble()) {
            rounded.toInt().toString()
        } else {
            // البند 6: Locale.US صريحة إلزامية هنا — بدونها يستخدم "%.2f".format(rounded)
            // locale الجهاز الافتراضي داخلياً، فعلى أي جهاز يكون نظام الأرقام فيه
            // "عربي-هندي" (إعداد نظام حقيقي)، يطبع مثلاً "٥٠.٥٠" بدل "50.50" — يكسر كل
            // مبلغ مالي بالتطبيق رغم أن هذه الدالة بالذات هي نقطة التنسيق المركزية
            // الوحيدة المقصود منها ثبات الشكل في كل مكان (راجع تعليق الملف أعلاه).
            "%.2f".format(Locale.US, rounded)
        }
    }

    fun format(amount: Double, currencySymbol: String): String {
        return "$ISOLATE_START$currencySymbol ${roundedText(amount)}$ISOLATE_END"
    }

    /**
     * نسخة مُنسَّقة بصرياً (9.2) للمبالغ البارزة فقط (أرقام البطاقات الكبيرة): الرمز أكبر
     * قليلاً (×1.15) وبلون خافت (text_secondary) بجانب الرقم الأساسي، بدل تطابقهما الكامل
     * في البطاقات التي يكون المبلغ فيها أهم عنصر بصري. تُستخدم انتقائياً حيث تُعرض بصرياً
     * فقط (binding.tv*.text) لا حيث تُدمَج داخل نص أطول (رسائل واتساب، تصدير Excel)، لأن
     * التنسيقات هناك تحوّل القيمة تلقائياً لنص عادي فتفقد التنسيق بلا أي ضرر.
     */
    fun formatEmphasized(context: android.content.Context, amount: Double, currencySymbol: String): CharSequence {
        val text = format(amount, currencySymbol)
        val spannable = SpannableString(text)
        val symbolEnd = 1 + currencySymbol.length // بعد ISOLATE_START + الرمز
        val dimColor = ContextCompat.getColor(context, R.color.text_secondary)
        spannable.setSpan(RelativeSizeSpan(1.15f), 1, symbolEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(dimColor), 1, symbolEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }
}
