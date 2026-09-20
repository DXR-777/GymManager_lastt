package com.gympro.manager.utils

import android.app.Activity
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.SystemClock
import android.text.InputFilter
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.core.view.doOnPreDraw

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * مستمع نقر يتجاهل النقرات المتكررة السريعة (خلال [debounceMillis])، بدل
 * setOnClickListener العادي. يمنع هذا فتح نفس الشاشة التالية أكثر من مرة عند
 * ضغط المستخدم بسرعة على زر انتقال (مثل أزرار "التالي" في شاشات الإعداد
 * الأولي) — كل View له مؤقّته الخاص (lastClickTime مغلق ضمن الدالة عبر
 * closure)، فلا يتأثر زر بنقرات زر آخر.
 *
 * SystemClock.elapsedRealtime() لا وقت النظام الفعلي عمداً: لا يتأثر بتغيير
 * المستخدم لساعة الجهاز، ومناسب لقياس فواصل زمنية قصيرة كهذه.
 */
fun View.setOnSingleClickListener(debounceMillis: Long = 600L, action: (View) -> Unit) {
    var lastClickTime = 0L
    setOnClickListener { view ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime >= debounceMillis) {
            lastClickTime = now
            action(view)
        }
    }
}

fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(windowToken, 0)
}

fun Activity.hideKeyboard() {
    currentFocus?.hideKeyboard()
}

/**
 * يزيل التركيز (Focus) عن أي حقل إدخال ويُخفي لوحة المفاتيح عند الضغط على أي
 * مساحة فارغة داخل الشاشة. يُستدعى مرة واحدة على الـ root view في onCreate/
 * onViewCreated لأي شاشة تحتوي حقول إدخال (مثل شاشة إضافة/تعديل عضو).
 *
 * تنفيذ UI بحت: لا يغيّر أي بيانات أو منطق حفظ/تحقق، ويعيد false دائماً حتى
 * لا يُعطّل ضغط الأزرار أو أي View آخر تحت الشجرة.
 */
fun Activity.setupClearFocusOnOutsideTouch(rootView: View) {
    if (rootView is ViewGroup) {
        for (i in 0 until rootView.childCount) {
            setupClearFocusOnOutsideTouch(rootView.getChildAt(i))
        }
    }
    if (rootView !is EditText) {
        rootView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val focused = currentFocus
                if (focused is EditText) {
                    focused.clearFocus()
                    focused.hideKeyboard()
                }
            }
            false
        }
    }
}

fun View.visible() { visibility = View.VISIBLE }
fun View.gone() { visibility = View.GONE }
fun View.visibleIf(condition: Boolean) { visibility = if (condition) View.VISIBLE else View.GONE }

/** يعرض 60 بدل 60.0، ويعرض 60.5 كما هي */
fun Double.toCleanString(): String =
    if (this == this.toLong().toDouble()) this.toLong().toString() else this.toString()

/**
 * يطبّق تدرّجاً لونياً أفقياً على نص TextView (مثل عناوين شاشات الإعداد الأولي)،
 * بنفس أسلوب brand_gradient المستخدم في bg_button_gradient وغيره — Android لا
 * يدعم تدرّج النص عبر XML مباشرة، فيُطبَّق هنا برمجياً على Paint الخاص بالـ
 * TextView بعد قياس عرضه الفعلي.
 *
 * يُستخدم عرض الـ View نفسه (لا عرض النص المقاس عبر measureText) عمداً: شيدر
 * اللون يُرسَم بإحداثيات View المطلقة، فإن كان النص سطرين (مثل عنوان شاشة "فكرة
 * التطبيق") يجعل هذا كل سطر يمرّ بنفس تدرّج مرجاني→بنفسجي كاملاً على حدة (مطابقاً
 * لمرجع التصميم)، بدل تدرّج واحد ممتدّ عبر طول النص الكامل غير الملفوف الذي كان
 * سيُظهر معظم السطرين بلون واحد فقط.
 *
 * doOnPreDraw بدل post{} لأنه يضمن تطبيق التدرّج قبل أول رسم فعلي للنص (لا وميض
 * لوني ملحوظ عند فتح الشاشة)، ويُعاد تلقائياً عند أي تغيير حجم لاحق للـ View.
 */
fun TextView.applyHorizontalGradient(@ColorInt startColor: Int, @ColorInt endColor: Int) {
    doOnPreDraw {
        val width = (width - paddingStart - paddingEnd).toFloat()
        if (width <= 0f) return@doOnPreDraw
        paint.shader = LinearGradient(
            0f, 0f, width, 0f,
            startColor, endColor,
            Shader.TileMode.CLAMP
        )
        invalidate()
    }
}

/**
 * يحوّل الأرقام العربية-الهندية (٠١٢٣٤٥٦٧٨٩) والفارسية الممتدة (۰۱۲۳۴۵۶۷۸۹)،
 * وكذلك الفاصلة العشرية العربية (٫)، إلى مكافئها اللاتيني العادي — بلا أي أثر
 * على نص يحتوي أرقاماً لاتينية بالفعل (كل تحويل حرف بحرف مستقل ولا يغيّر غير
 * هذه الرموز تحديداً). يُستخدم فقط في حقول السعر/المبلغ المدفوع في شاشة إضافة
 * وتعديل العضو (راجع AddEditMemberActivity) — لا في كل حقل رقمي بالتطبيق، لأن
 * توسيعه لحقول أخرى (كعدد الأيام أو رقم الهاتف) لم يُطلب ضمن نطاق هذه المهمة
 * وقد يحمل دلالات مختلفة هناك.
 */
fun String.normalizeDigitsToLatin(): String = buildString(length) {
    for (ch in this@normalizeDigitsToLatin) {
        append(
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                '٫' -> '.'
                else -> ch
            }
        )
    }
}

/** مثل toDoubleOrNull() القياسية، لكن بعد تطبيع الأرقام العربية-الهندية أولاً. */
fun String.toSafeDoubleOrNull(): Double? = normalizeDigitsToLatin().toDoubleOrNull()

/** مثل toIntOrNull() القياسية، لكن بعد تطبيع الأرقام العربية-الهندية أولاً. */
fun String.toSafeIntOrNull(): Int? = normalizeDigitsToLatin().toIntOrNull()

/**
 * يستخرج أرقام هاتف "لاتينية" نظيفة من أي نص إدخال، بعد تطبيع الأرقام
 * العربية-الهندية/الفارسية أولاً (راجع normalizeDigitsToLatin) ثم إزالة أي
 * شيء غير رقمي (مسافات، شرطات...). ضروري لأن Char.isDigit() القياسية تعتبر
 * الأرقام العربية-الهندية (٠-٩) أرقاماً صحيحة أيضاً، فكانت phoneRaw.filter
 * { it.isDigit() } تُبقيها كما هي (عربية) بدل تحويلها للاتينية — فيُخزَّن رقم
 * هاتف لا يفهمه مكوّن الاتصال (tel:) ولا رابط واتساب (wa.me) لاحقاً، رغم
 * اجتيازه كل عمليات التحقق بنجاح ظاهرياً.
 */
fun String.toSafePhoneDigits(): String = normalizeDigitsToLatin().filter { it.isDigit() }

/**
 * يزيل فواصل تنسيق رقم الهاتف الشائعة (مسافات، شرطات، أقواس، وعلامة + الدولية)
 * فقط — لا أي حرف آخر. يُستخدم قبل فحص "هل يحتوي النص على غير الأرقام؟" في
 * التحقق النهائي، حتى لا يُرفض رقم لصقه المستخدم من جهات الاتصال بصيغة مثل
 * "059-999-9999" أو "(059) 999 9999" أو "+970 59-999-9999" برسالة "أرقام فقط"
 * رغم أنه رقم صالح فعلياً بعد إزالة هذا التنسيق فقط.
 *
 * البند 3: علامة + كانت مستثناة من هذه القائمة رغم أن حقل الهاتف
 * (android:inputType="phone") يعرضها على لوحة المفاتيح بصراحة، وrandroid:digits
 * في activity_add_edit_member.xml يسمح للمستخدم بكتابتها فعلاً — فكان أي رقم
 * دولي مكتوب بصيغة "+970..." (شائعة جداً عند اللصق من جهات الاتصال) يُرفض
 * برسالة "أرقام فقط" رغم أن اللوحة والحقل سمحا بإدخالها أصلاً. أي حرف آخر غير
 * رقمي وغير هذه الرموز (مثل حروف) يبقى كما هو ليستمر رفضه لاحقاً كما كان —
 * هذه الدالة تنظيف تنسيق لا تساهل في التحقق.
 */
fun String.stripPhoneSeparators(): String =
    filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '+' }

/**
 * يقيّد حقل رقم عشري (inputType="numberDecimal") بعدد خانات عشرية أقصى بعد
 * الفاصلة (افتراضياً خانتان، مناسب لأي عملة مدعومة حالياً ₪/$/د.أ). بدون هذا
 * كان يمكن إدخال سعر مثل 12.123456 وحفظه كما هو، فيكسر اتساق تنسيق العملة في
 * كل الشاشات الأخرى (CurrencyFormatter) التي تفترض خانتين عشريتين كحد أقصى.
 * لا يمنع الرقم الصحيح (بلا فاصلة) من أي طول ضمن android:maxLength في XML —
 * فقط يمنع تجاوز عدد الخانات بعد الفاصلة تحديداً.
 *
 * على EditText مباشرة (لا TextInputEditText تحديداً) لأنه يُستخدم أيضاً على
 * حقول @+id/EditText الخام في شاشة الإعداد الأولي (GymSetupActivity)، لا فقط
 * على TextInputEditText في شاشتَي إضافة العضو والإعدادات — TextInputEditText
 * أصلاً فئة فرعية من EditText فلا تغيير على نقاط الاستدعاء الحالية.
 */
fun EditText.limitDecimalPlaces(maxDecimals: Int = 2) {
    filters = filters + InputFilter { source, start, end, dest, dstart, dend ->
        val resultingText = StringBuilder(dest).replace(dstart, dend, source.subSequence(start, end).toString())
        val dotIndex = resultingText.indexOf('.')
        if (dotIndex == -1) return@InputFilter null
        val decimalsCount = resultingText.length - dotIndex - 1
        if (decimalsCount > maxDecimals) "" else null
    }
}
