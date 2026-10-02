package com.gympro.manager.security

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.PersistableBundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.R

/** عناصر واجهة مشتركة بين شاشة القفل وشاشة الإعدادات. */
object AppLockUi {

    /**
     * يعرض رمز الاسترداد مرة واحدة. زر "متابعة" معطَّل حتى يؤكد المستخدم أنه حفظه، والنافذة
     * نفسها محمية من لقطات الشاشة (الرمز يجب أن يُكتب على ورقة لا أن يبقى صورة في المعرض).
     */
    fun showRecoveryCodeDialog(context: Context, code: String, onSaved: () -> Unit) {
        fun px(v: Int) = (v * context.resources.displayMetrics.density).toInt()

        val body = TextView(context).apply {
            text = context.getString(R.string.lock_recovery_body)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 14f
        }
        val codeView = TextView(context).apply {
            text = code
            typeface = Typeface.MONOSPACE
            textSize = 22f
            letterSpacing = 0.06f
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(px(12), px(16), px(12), px(16))
            background = ContextCompat.getDrawable(context, R.drawable.bg_card_glass)
        }
        val check = MaterialCheckBox(context).apply {
            text = context.getString(R.string.lock_recovery_saved_check)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
            addView(body)
            addView(codeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(16); bottomMargin = px(8) })
            addView(check)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lock_recovery_title)
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(R.string.lock_recovery_continue, null)
            .setNeutralButton(R.string.lock_recovery_copy, null)
            .create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false
            check.setOnCheckedChangeListener { _, checked -> positive.isEnabled = checked }
            positive.setOnClickListener {
                dialog.dismiss()
                onSaved()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                copySensitive(context, code)
                Toast.makeText(context, R.string.lock_recovery_copied, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun copySensitive(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("recovery", text)
        // يخفي المعاينة في لوحة المفاتيح/الإشعار على أندرويد 13+
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
    }

    /** مطالبة البصمة (Class 3 فقط). زر السلبي يعيد المستخدم للرمز. */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        negativeText: String,
        onSuccess: () -> Unit,
        onError: (() -> Unit)? = null
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError?.invoke() }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .apply { subtitle?.let { setSubtitle(it) } }
            .build()
        prompt.authenticate(info)
    }
}
