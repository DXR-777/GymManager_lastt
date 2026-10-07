package com.gympro.manager.utils

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.gympro.manager.R
import com.google.android.material.snackbar.Snackbar

enum class SnackType { INFO, SUCCESS, ERROR }

/**
 * إشعار موحّد بدل Toast: أيقونة ولون حسب الحالة، وزر إجراء اختياري (مثل "تراجع").
 *
 * يُثبَّت فوق زر الإضافة (إن ظهر) أو البار السفلي العائم، لأن الـSnackbar العادي كان
 * يُرسم فوقهما ويغطّي التنقل. الأخطاء تبقى أطول وتتسع لأربعة أسطر (رسائل الاستثناءات).
 *
 * لا تستخدمه إذا كانت الشاشة ستُغلق مباشرة بعد الرسالة؛ فالـSnackbar يختفي معها.
 */
fun View.showSnack(
    message: CharSequence,
    type: SnackType = SnackType.INFO,
    actionText: CharSequence? = null,
    onAction: (() -> Unit)? = null
): Snackbar {
    val duration =
        if (type == SnackType.ERROR || actionText != null) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
    val snackbar = Snackbar.make(this, message, duration)

    val anchor = listOf(R.id.fabAddMember, R.id.bottomNav)
        .firstNotNullOfOrNull { id -> rootView.findViewById<View>(id)?.takeIf { it.isShown } }
    if (anchor != null) snackbar.setAnchorView(anchor)

    if (actionText != null && onAction != null) {
        snackbar.setAction(actionText) { onAction() }
        snackbar.setActionTextColor(ContextCompat.getColor(context, R.color.snackbar_action))
    }

    val (iconRes, tintRes) = when (type) {
        SnackType.SUCCESS -> R.drawable.ic_check_circle to R.color.status_success_text
        SnackType.ERROR -> R.drawable.ic_close_circle to R.color.status_error_text
        SnackType.INFO -> R.drawable.ic_info to R.color.text_secondary
    }
    val text = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
    ContextCompat.getDrawable(context, iconRes)?.let { raw ->
        val icon = DrawableCompat.wrap(raw.mutate())
        DrawableCompat.setTint(icon, ContextCompat.getColor(context, tintRes))
        val size = (20 * resources.displayMetrics.density).toInt()
        icon.setBounds(0, 0, size, size)
        text.setCompoundDrawablesRelative(icon, null, null, null)
        text.compoundDrawablePadding = (10 * resources.displayMetrics.density).toInt()
    }
    text.maxLines = if (type == SnackType.ERROR) 4 else 2
    snackbar.show()
    return snackbar
}
