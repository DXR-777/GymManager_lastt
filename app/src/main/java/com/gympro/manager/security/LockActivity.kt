package com.gympro.manager.security

import android.animation.ObjectAnimator
import android.app.KeyguardManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.R
import com.gympro.manager.databinding.ActivityLockBinding

/**
 * شاشة القفل الواحدة لكل الحالات (راجع MODE_*):
 *  UNLOCK: فتح التطبيق  | SETUP: تفعيل القفل (رمز جديد + تأكيد + رمز استرداد + عرض البصمة)
 *  CHANGE: تغيير الرمز (الحالي ← جديد ← تأكيد) | VERIFY: تأكيد الرمز قبل إجراء حساس (يعيد RESULT_OK)
 *
 * "نسيت الرمز؟" ← إما قفل الهاتف نفسه أو رمز الاسترداد ← ثم اختيار رمز جديد (ورمز استرداد جديد).
 */
class LockActivity : AppCompatActivity() {

    private enum class Step { CURRENT, NEW, CONFIRM }

    private lateinit var binding: ActivityLockBinding
    private lateinit var mode: String
    private var step = Step.CURRENT
    private val entered = StringBuilder()
    private var firstNewPin: String? = null
    private var recoveryReset = false
    private var keysEnabled = true
    private var countDown: CountDownTimer? = null
    private var biometricAutoShown = false

    private val digitKeys by lazy {
        listOf(
            binding.key0, binding.key1, binding.key2, binding.key3, binding.key4,
            binding.key5, binding.key6, binding.key7, binding.key8, binding.key9
        )
    }
    private val dots by lazy {
        listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4, binding.dot5, binding.dot6)
    }

    private val deviceCredentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) beginRecoveryReset()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK
        if (mode != MODE_SETUP && !AppLockManager.isEnabled()) {
            // لا قفل مفعّل (حالة قديمة/متبقية): لا داعي لحجب المستخدم
            AppLockManager.markUnlocked()
            finishNoAnim()
            return
        }
        step = if (mode == MODE_SETUP) Step.NEW else Step.CURRENT

        digitKeys.forEachIndexed { digit, view -> view.setOnClickListener { onDigit(digit) } }
        binding.keyBackspace.setOnClickListener { onBackspace() }
        binding.keyBackspace.setOnLongClickListener { clearEntry(); true }
        binding.keyBiometric.setOnClickListener { showBiometricPrompt() }
        binding.tvForgot.setOnClickListener { showForgotDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        render()
    }

    override fun onResume() {
        super.onResume()
        if (!biometricAutoShown && step == Step.CURRENT && !recoveryReset && canUseBiometric()) {
            biometricAutoShown = true
            showBiometricPrompt()
        }
    }

    override fun onStop() {
        super.onStop()
        biometricAutoShown = false   // عند العودة من الخلفية نعرض البصمة من جديد
    }

    override fun onDestroy() {
        countDown?.cancel()
        if (isFinishing) AppLockManager.onLockScreenClosed()
        super.onDestroy()
    }

    // ───────────────────────────── الإدخال ─────────────────────────────

    private fun onDigit(digit: Int) {
        if (!keysEnabled || entered.length >= PinPolicy.LENGTH) return
        entered.append(digit)
        renderDots()
        if (entered.length == PinPolicy.LENGTH) {
            // تأخير قصير كي تظهر النقطة الأخيرة قبل المعالجة
            binding.dotsContainer.postDelayed({ onPinComplete() }, 120)
        }
    }

    private fun onBackspace() {
        if (!keysEnabled || entered.isEmpty()) return
        entered.deleteCharAt(entered.length - 1)
        renderDots()
    }

    private fun clearEntry() {
        entered.clear()
        renderDots()
    }

    private fun onPinComplete() {
        val pin = entered.toString()
        when (step) {
            Step.CURRENT -> handleCurrentPin(pin)
            Step.NEW -> {
                if (PinPolicy.isWeak(pin)) {
                    clearEntry()
                    showError(getString(R.string.lock_error_weak))
                } else {
                    firstNewPin = pin
                    step = Step.CONFIRM
                    clearEntry()
                    render()
                }
            }
            Step.CONFIRM -> {
                if (pin == firstNewPin) {
                    finishNewPin(pin)
                } else {
                    firstNewPin = null
                    step = Step.NEW
                    clearEntry()
                    render()
                    showError(getString(R.string.lock_error_mismatch))
                }
            }
        }
    }

    private fun handleCurrentPin(pin: String) {
        when (AppLockManager.verifyPin(pin)) {
            AppLockManager.VerifyResult.SUCCESS -> onCurrentVerified()
            AppLockManager.VerifyResult.WRONG -> {
                clearEntry()
                if (AppLockManager.remainingLockoutMs() > 0L) {
                    shake()
                    startLockoutIfNeeded()
                } else {
                    showError(getString(R.string.lock_error_wrong, AppLockManager.attemptsLeftInRound()))
                }
            }
            AppLockManager.VerifyResult.LOCKED_OUT -> {
                clearEntry()
                startLockoutIfNeeded()
            }
            AppLockManager.VerifyResult.UNAVAILABLE -> {
                clearEntry()
                showError(getString(R.string.lock_unavailable))
            }
        }
    }

    private fun onCurrentVerified() {
        when (mode) {
            MODE_UNLOCK -> {
                AppLockManager.markUnlocked()
                finishNoAnim()
            }
            MODE_VERIFY -> {
                setResult(RESULT_OK)
                finish()
            }
            MODE_CHANGE -> {
                step = Step.NEW
                clearEntry()
                render()
            }
        }
    }

    private fun finishNewPin(pin: String) {
        if (!AppLockManager.setPin(pin)) {
            Toast.makeText(this, R.string.lock_setup_failed, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        AppLockManager.markUnlocked()

        if (mode == MODE_CHANGE && !recoveryReset) {
            Toast.makeText(this, R.string.lock_pin_changed, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
            return
        }

        // تفعيل جديد أو إعادة تعيين بعد نسيان: رمز استرداد جديد يُعرض مرة واحدة
        val code = AppLockManager.generateRecoveryCode()
        if (code == null) {
            completeFlow()
            return
        }
        AppLockUi.showRecoveryCodeDialog(this, code) {
            if (mode == MODE_SETUP && canUseBiometric()) offerBiometric() else completeFlow()
        }
    }

    private fun completeFlow() {
        if (mode == MODE_UNLOCK) {
            finishNoAnim()
        } else {
            setResult(RESULT_OK)
            finish()
        }
    }

    // ───────────────────────────── البصمة ─────────────────────────────

    private fun canUseBiometric() =
        AppLockManager.isBiometricEnabled() && AppLockManager.biometricAvailable(this)

    private fun showBiometricPrompt() {
        if (!canUseBiometric()) return
        AppLockUi.authenticate(
            activity = this,
            title = getString(R.string.lock_biometric_title),
            subtitle = getString(R.string.lock_biometric_subtitle),
            negativeText = getString(R.string.lock_use_pin),
            onSuccess = {
                AppLockManager.resetFailures()
                countDown?.cancel()
                onCurrentVerified()
            }
        )
    }

    private fun offerBiometric() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_biometric_offer_title)
            .setMessage(R.string.lock_biometric_offer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.lock_biometric_enable) { _, _ ->
                AppLockUi.authenticate(
                    activity = this,
                    title = getString(R.string.lock_biometric_title),
                    subtitle = null,
                    negativeText = getString(R.string.lock_cancel),
                    onSuccess = {
                        AppLockManager.setBiometricEnabled(true)
                        completeFlow()
                    },
                    onError = { completeFlow() }
                )
            }
            .setNegativeButton(R.string.lock_later) { _, _ -> completeFlow() }
            .show()
    }

    // ───────────────────────────── نسيت الرمز ─────────────────────────────

    private fun showForgotDialog() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (keyguard?.isDeviceSecure == true) {
            labels += getString(R.string.lock_forgot_option_device)
            actions += { launchDeviceCredential(keyguard) }
        }
        labels += getString(R.string.lock_forgot_option_code)
        actions += { showRecoveryCodeInput() }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_forgot_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.lock_cancel, null)
            .show()
    }

    private fun launchDeviceCredential(keyguard: KeyguardManager) {
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.lock_device_credential_title),
            getString(R.string.lock_device_credential_desc)
        )
        if (intent != null) deviceCredentialLauncher.launch(intent)
    }

    private fun showRecoveryCodeInput() {
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.lock_recovery_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(19))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setTextColor(ContextCompat.getColor(this@LockActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@LockActivity, R.color.text_disabled))
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_recovery_input_title)
            .setView(container)
            .setPositiveButton(R.string.lock_recovery_confirm, null)
            .setNegativeButton(R.string.lock_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (AppLockManager.verifyRecoveryCode(input.text.toString())) {
                    dialog.dismiss()
                    beginRecoveryReset()
                } else {
                    input.error = getString(R.string.lock_recovery_invalid)
                }
            }
        }
        dialog.show()
    }

    /** بعد إثبات الهوية (قفل الهاتف أو رمز الاسترداد): يختار المستخدم رمزاً جديداً. */
    private fun beginRecoveryReset() {
        AppLockManager.resetFailures()
        countDown?.cancel()
        recoveryReset = true
        step = Step.NEW
        firstNewPin = null
        clearEntry()
        setKeysEnabled(true)
        render()
    }

    private fun handleBack() {
        when {
            recoveryReset -> {
                recoveryReset = false
                firstNewPin = null
                step = Step.CURRENT
                clearEntry()
                render()
            }
            step == Step.CONFIRM -> {
                firstNewPin = null
                step = Step.NEW
                clearEntry()
                render()
            }
            mode == MODE_UNLOCK -> finishAffinity()   // الخروج من التطبيق، ويبقى مقفلاً
            else -> {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    // ───────────────────────────── العرض ─────────────────────────────

    private fun render() {
        val (title, subtitle) = when {
            step == Step.NEW && recoveryReset -> R.string.lock_reset_title to R.string.lock_subtitle_new
            step == Step.NEW -> R.string.lock_title_new to R.string.lock_subtitle_new
            step == Step.CONFIRM -> R.string.lock_title_confirm to R.string.lock_subtitle_confirm
            mode == MODE_UNLOCK -> R.string.lock_title_unlock to R.string.lock_subtitle_unlock
            else -> R.string.lock_title_verify to R.string.lock_subtitle_verify
        }
        binding.tvTitle.setText(title)
        setSubtitle(getString(subtitle), isError = false)
        renderDots()

        binding.tvForgot.visibility =
            if (step == Step.CURRENT && !recoveryReset) View.VISIBLE else View.GONE
        binding.keyBiometric.visibility =
            if (step == Step.CURRENT && !recoveryReset && canUseBiometric()) View.VISIBLE else View.INVISIBLE

        if (step == Step.CURRENT && !recoveryReset) startLockoutIfNeeded() else setKeysEnabled(true)
    }

    private fun renderDots() {
        dots.forEachIndexed { i, dot -> dot.isActivated = i < entered.length }
    }

    private fun setSubtitle(text: String, isError: Boolean) {
        binding.tvSubtitle.text = text
        binding.tvSubtitle.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.status_error else R.color.text_secondary)
        )
    }

    private fun showError(message: String) {
        setSubtitle(message, isError = true)
        shake()
    }

    private fun shake() {
        val d = resources.displayMetrics.density
        ObjectAnimator.ofFloat(
            binding.dotsContainer, View.TRANSLATION_X,
            0f, 24f * d, -24f * d, 16f * d, -16f * d, 8f * d, -8f * d, 0f
        ).setDuration(380).start()
        binding.root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun setKeysEnabled(enabled: Boolean) {
        keysEnabled = enabled
        (digitKeys + binding.keyBackspace).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.35f
        }
    }

    private fun startLockoutIfNeeded() {
        countDown?.cancel()
        val remaining = AppLockManager.remainingLockoutMs()
        if (remaining <= 0L) {
            setKeysEnabled(true)
            return
        }
        setKeysEnabled(false)
        countDown = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(msLeft: Long) {
                setSubtitle(getString(R.string.lock_lockout, PinPolicy.formatDuration(msLeft)), isError = true)
            }

            override fun onFinish() {
                setKeysEnabled(true)
                render()
            }
        }.start()
    }

    private fun finishNoAnim() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MODE = "lock_mode"
        const val MODE_UNLOCK = "unlock"
        const val MODE_SETUP = "setup"
        const val MODE_CHANGE = "change"
        const val MODE_VERIFY = "verify"
    }
}
