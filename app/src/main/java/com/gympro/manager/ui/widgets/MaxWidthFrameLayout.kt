package com.gympro.manager.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.gympro.manager.R

/**
 * البند 8 (قابلية الاستخدام على الشاشات الكبيرة): لا توجد نسخ تخطيط للتابلت أو الوضع
 * الأفقي، فتتمدد البطاقات والأرقام بعرض الشاشة كاملاً وتفقد اتجاهها البصري.
 *
 * بدل مضاعفة التخطيطات عبر مجلدات layout-sw600dp / layout-land منفصلة (عرضة لتفرّق
 * لاحق بين النسخ)، تحدّ هذه الحاوية عرض محتواها بحد أقصى (افتراضياً R.dimen.content_max_width
 * ≈ 600dp) وتتوسّطه تلقائياً، وتبقى شفافة تماماً على الشاشات الأضيق من الحد الأقصى (الحالة
 * الشائعة على الهاتف) لأن onMeasure لا يقيّد العرض إلا عند تجاوزه فعلياً.
 *
 * الاستخدام: توضع كحاوية وحيدة داخل ScrollView، حول المحتوى الرأسي الحالي بلا أي تغيير
 * آخر عليه (نفس اللحشوة، نفس التباعد الداخلي).
 */
class MaxWidthFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val maxWidthPx: Int =
        context.resources.getDimensionPixelSize(R.dimen.content_max_width)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val originalWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (originalWidth > maxWidthPx) {
            val cappedSpec = MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
            super.onMeasure(cappedSpec, heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
