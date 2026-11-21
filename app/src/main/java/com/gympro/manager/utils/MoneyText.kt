package com.gympro.manager.utils

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan

/**
 * مبلغ مالي للأرقام البارزة: الرمز أصغر (70%) وأخفت من الرقم. رمز العملة (₪ مثلاً) غير موجود
 * في خط Cairo فيُرسم بخط بديل أثقل؛ تصغيره يوحّد الإيقاع البصري بين الرمز والرقم.
 * يعتمد على CurrencyFormatter.format (الترتيب "رمز ثم رقم" + عازلا الاتجاه) فلا يكرّر منطق التنسيق.
 */
fun moneyText(amount: Double, currencySymbol: String, symbolColor: Int): CharSequence {
    val text = CurrencyFormatter.format(amount, currencySymbol)
    val spannable = SpannableString(text)
    val start = 1 // بعد عازل الاتجاه الافتتاحي
    val end = (start + currencySymbol.length).coerceAtMost(text.length)
    if (end > start) {
        spannable.setSpan(RelativeSizeSpan(0.7f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(symbolColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return spannable
}
