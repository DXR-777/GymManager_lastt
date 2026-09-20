package com.gympro.manager.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.gympro.manager.R

/**
 * كل ما يتعلق بإرسال الفاتورة/السجل عبر واتساب.
 *
 * ملاحظة هندسية مهمة: لا توجد طريقة رسمية متاحة لتطبيقات الطرف الثالث للتحقق
 * "بشكل صامت" مما إذا كان رقم ما مسجَّلاً على واتساب أم لا (هذا يتطلب واتساب
 * Business API الرسمي وربط إنترنت دائم، وهو خارج نطاق تطبيق محلي مجاني).
 * الحل العملي المعتمد هنا: نحاول فتح المحادثة بالمقدمة المفضّلة أولاً
 * (+970 ثم +972 تلقائياً، أو حسب اختيار صاحب النادي)، وإن لم تُفتح المحادثة
 * بشكل صحيح، نعرض زرّاً فورياً لإعادة المحاولة بالمقدمة الأخرى.
 */
object WhatsAppHelper {

    private const val PACKAGE_WHATSAPP = "com.whatsapp"
    private const val PACKAGE_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    /**
     * يحوّل رقماً محلياً (مثل 0599999999) إلى رقم دولي كامل بإضافة المقدمة المطلوبة.
     * toSafePhoneDigits (لا filter{isDigit()} مباشرة) كحماية إضافية لبيانات قديمة قد تكون
     * حُفظت بأرقام عربية-هندية قبل إصلاح التحقق في AddEditMemberActivity — بدونها يبني رابط
     * واتساب برقم لا يتعرّف عليه واتساب فعلياً رغم ظهوره "رقماً" في واجهة التطبيق.
     */
    fun toInternationalNumber(rawPhone: String, prefixCode: String): String {
        val digits = rawPhone.toSafePhoneDigits()
        val local = when {
            digits.startsWith("970") -> digits.removePrefix("970")
            digits.startsWith("972") -> digits.removePrefix("972")
            digits.startsWith("0") -> digits.substring(1)
            else -> digits
        }
        return "$prefixCode$local"
    }

    /**
     * يُرجع قائمة المقدمات المُرشَّحة بالترتيب المطلوب تجربتها.
     * إن اختار صاحب النادي مقدّمة محددة في الإعدادات تُجرَّب أولاً.
     * في الوضع التلقائي: فلسطين (970) أولاً ثم إسرائيل (972).
     */
    fun candidatePrefixes(settingsPrefix: String?): List<String> = when (settingsPrefix) {
        "+970" -> listOf("970", "972")
        "+972" -> listOf("972", "970")
        else -> listOf("970", "972")
    }

    fun otherPrefix(current: String): String = if (current == "970") "972" else "970"

    /** نفس الحد الأدنى المستخدم في AddEditMemberActivity عند إدخال رقم العضو. */
    private const val MIN_LOCAL_DIGITS = 9

    /**
     * حد أقصى معقول لعدد أرقام الرقم المحلي (بعد إزالة الصفر البادئ/مقدمة الدولة إن
     * وُجدت) — لا يوجد أي رقم جوال فلسطيني أو إسرائيلي محلي يتجاوز 10 أرقام. أي شيء أطول
     * غالباً بقايا رموز أو نص عالق بسبب بيانات قديمة (راجع البند 18).
     */
    private const val MAX_LOCAL_DIGITS = 10

    /**
     * تحقّق أخير قبل محاولة الإرسال الفعلي عبر واتساب — راجع البند 18: التحقق عند
     * الإدخال (AddEditMemberActivity) يمنع حفظ رقم أقل من 9 أرقام، لكن لا يوجد أي تحقق
     * مماثل هنا عند الإرسال، فرقم محفوظ ببيانات قديمة (قبل إضافة ذلك التحقق) أو مُعدَّل
     * مباشرة في قاعدة البيانات قد يُبنى منه رابط واتساب ويُرسل لجهة خاطئة (أو لا أحد)
     * بصمت دون أي تنبيه للمستخدم.
     */
    fun isSendableNumber(rawPhone: String): Boolean {
        val digits = rawPhone.toSafePhoneDigits()
        val local = when {
            digits.startsWith("970") -> digits.removePrefix("970")
            digits.startsWith("972") -> digits.removePrefix("972")
            digits.startsWith("0") -> digits.substring(1)
            else -> digits
        }
        return local.length in MIN_LOCAL_DIGITS..MAX_LOCAL_DIGITS
    }

    /**
     * البند 20: نتيجة محاولة الإرسال — ثلاث حالات مختلفة تماماً في درجة اليقين، بعد
     * أن كانت openChat() تُرجع Boolean واحداً يخلط بين حالتين مختلفتين جوهرياً:
     * فتح تطبيق واتساب الفعلي (نجاح شبه مؤكَّد) مقابل فتح رابط متصفح عام كحل أخير
     * (لا ضمان إطلاقاً أن الرسالة ستُرسَل فعلاً — قد تفتح صفحة تحميل واتساب، أو
     * واتساب ويب بلا جلسة مسجَّلة، أو المتصفح الافتراضي وحسب). الواجهة كانت تعرض
     * نفس رسالة "جاري الإرسال إلى..." الواثقة في الحالتين، مضلِّلة الموظف في
     * الحالة الثانية بالذات.
     */
    sealed class SendResult {
        /** فُتح تطبيق واتساب (أو واتساب بزنس) الفعلي مباشرة. */
        object AppOpened : SendResult()

        /** واتساب غير مثبت؛ فُتح رابط عام في المتصفح كحل أخير — النجاح غير مضمون. */
        object BrowserFallback : SendResult()

        /** تعذّر فتح أي شيء إطلاقاً (لا واتساب ولا أي متصفح/معالج روابط). */
        object Failed : SendResult()
    }

    /**
     * يحاول فتح محادثة واتساب مباشرة مع الرقم الدولي المُعطى ومعه نص الفاتورة جاهزاً.
     * راجع SendResult أعلاه — النتيجة تميّز بين فتح التطبيق الفعلي وحل المتصفح
     * الاحتياطي، حتى تعرض الشاشة المستدعية رسالة تعكس درجة اليقين الحقيقية.
     */
    fun openChat(context: Context, internationalNumber: String, message: String): SendResult {
        val encodedMessage = Uri.encode(message)
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$internationalNumber&text=$encodedMessage")

        for (pkg in listOf(PACKAGE_WHATSAPP, PACKAGE_WHATSAPP_BUSINESS)) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(pkg) }
                context.startActivity(intent)
                return SendResult.AppOpened
            } catch (e: ActivityNotFoundException) {
                // جرّب الحزمة التالية
            }
        }

        // كحلّ أخير: فتح الرابط بشكل عام (سيفتح المتصفح إن لم يكن واتساب مثبتاً) —
        // هذا ليس نجاحاً مؤكَّداً، فقط أفضل محاولة متاحة؛ راجع SendResult.BrowserFallback.
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            SendResult.BrowserFallback
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.whatsapp_not_installed), Toast.LENGTH_LONG).show()
            SendResult.Failed
        }
    }

    fun buildInvoiceMessage(
        context: Context,
        gymName: String,
        memberName: String,
        typeLabel: String,
        startDateLabel: String,
        endDateLabel: String,
        priceLabel: String,
        paidStatusLabel: String,
        daysRemainingLabel: String,
        /** ديون سابقة على العضو غير مسدَّدة — يُرسَل كسطر تحذيري إضافي أو فارغاً إذا لا ديون */
        debtLine: String = ""
    ): String = context.getString(
        R.string.whatsapp_message_template,
        gymName, memberName, typeLabel, startDateLabel, endDateLabel,
        priceLabel, paidStatusLabel, daysRemainingLabel, debtLine
    )
}
