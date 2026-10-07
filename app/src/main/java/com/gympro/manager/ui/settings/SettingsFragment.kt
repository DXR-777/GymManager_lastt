package com.gympro.manager.ui.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.BuildConfig
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.databinding.DialogCurrencyPickerBinding
import com.gympro.manager.databinding.FragmentSettingsBinding
import com.gympro.manager.security.AppLockManager
import com.gympro.manager.security.AppLockUi
import com.gympro.manager.security.LockActivity
import com.gympro.manager.ui.archive.ArchiveActivity
import com.gympro.manager.utils.BackupManager
import com.gympro.manager.utils.AutoBackupRunner
import com.gympro.manager.utils.BackupPrefs
import com.gympro.manager.utils.ThemePrefs
import android.text.format.DateUtils as AndroidDateUtils
import com.gympro.manager.utils.limitDecimalPlaces
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.setupClearFocusOnOutsideTouch
import com.gympro.manager.utils.toCleanString
import com.gympro.manager.utils.toSafeMoneyOrNull
import com.gympro.manager.utils.toast
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.gympro.manager.utils.SnackType
import com.gympro.manager.utils.showSnack
import androidx.appcompat.app.AlertDialog
import com.gympro.manager.databinding.DialogBackupPasswordBinding
import com.gympro.manager.utils.BackupPasswordRequiredException
import com.gympro.manager.utils.BackupWrongPasswordException

/**
 * خيار عملة في منتقي الإعدادات — مطابق تماماً (بنفس الترتيب والرموز) لقائمة
 * [com.gympro.manager.ui.setup.GymSetupActivity]، بما في ذلك خيار "عملة أخرى"
 * الحرّ (راجع البند 23) كي لا تتصرف نفس شاشة الأسعار بشكل مختلف بين الإعداد
 * الأولي والإعدادات لاحقاً.
 */
private data class SettingsCurrencyOption(val code: String, val symbol: String, val nameRes: Int)

/** حدّ أقصى معقول لطول رمز العملة المُدخَل يدوياً عبر "عملة أخرى" — راجع البند 23. */
private const val CUSTOM_CURRENCY_MAX_LENGTH = 6

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val repository by lazy { (requireActivity().application as GymApplication).repository }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore(uri)
    }

    private var pendingTreeUri: android.net.Uri? = null

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            if (_binding != null) refreshAutoBackupUi()
            return@registerForActivityResult
        }
        requireContext().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        pendingTreeUri = uri
        showBackupPasswordDialog(allowSkip = false, onCancel = { refreshAutoBackupUi() }) { password ->
            enableAutoBackup(uri, password)
        }
    }

    /** الإجراء الأمني الجاري تنفيذه عبر LockActivity — يحدد ما يحدث عند نجاحها. */
    private enum class LockAction { ENABLE, DISABLE, CHANGE, NEW_RECOVERY }
    private var pendingLockAction: LockAction? = null

    private val lockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pendingLockAction
        pendingLockAction = null
        if (result.resultCode == Activity.RESULT_OK) {
            when (action) {
                LockAction.ENABLE -> view?.showSnack(getString(R.string.lock_enabled_toast), SnackType.SUCCESS)
                LockAction.DISABLE -> {
                    AppLockManager.disable()
                    view?.showSnack(getString(R.string.lock_disabled_toast), SnackType.INFO)
                }
                LockAction.NEW_RECOVERY -> AppLockManager.generateRecoveryCode()?.let {
                    AppLockUi.showRecoveryCodeDialog(requireContext(), it) {}
                }
                else -> Unit
            }
        }
        if (_binding != null) refreshSecurityUi()
    }

    // نفس الترتيب والرموز الثلاثة بالضبط الموجودة في GymSetupActivity — أي تعديل
    // هنا يجب أن يُطابَق هناك أيضاً حتى لا تتفرّق الشاشتان مجدداً.
    private val currencyOptions = listOf(
        SettingsCurrencyOption("ILS", "₪", R.string.currency_name_ils),
        SettingsCurrencyOption("USD", "$", R.string.currency_name_usd),
        SettingsCurrencyOption("JOD", "د.أ", R.string.currency_name_jod)
    )

    /**
     * null يعني أن القيمة الحالية في etCurrency ليست إحدى الخيارات الثلاثة الجاهزة —
     * إما عملة مخصّصة اختارها المستخدم صراحةً عبر "عملة أخرى" (راجع البند 23) أو رمز
     * حرّ قديم محفوظ من قبل هذا الإصلاح. في الحالتين يبقى الحقل معروضاً كما هو، ويُبرَز
     * صفّ "عملة أخرى" تحديداً في المنتقي بدل أي من الثلاث (راجع showCurrencyPicker).
     */
    private var selectedCurrency: SettingsCurrencyOption? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().setupClearFocusOnOutsideTouch(view)
        loadSettings()

        // حقل العملة نفسه غير قابل للكتابة المباشرة (يفتح المنتقي فقط) — لكن المنتقي
        // نفسه يشمل الآن ثلاث عملات جاهزة + "عملة أخرى" حرّة عبر حوار مخصّص (البند 23)،
        // فلا يُفرض على صاحب النادي طباعة عشوائية في الحقل نفسه قد تكسر تنسيق الأسعار.
        binding.etCurrency.setOnSingleClickListener { showCurrencyPicker() }
        binding.tilCurrency.setEndIconOnClickListener { showCurrencyPicker() }

        // البند 4: نفس القيد المطبَّق على etPrice في AddEditMemberActivity وعلى
        // حقول الأسعار في GymSetupActivity — بدونه يمكن حفظ سعر بخانات عشرية
        // غير محدودة (مثل 12.123456) من شاشة الإعدادات تحديداً.
        binding.etDailyPrice.limitDecimalPlaces(2)
        binding.etWeeklyPrice.limitDecimalPlaces(2)
        binding.etMonthlyPrice.limitDecimalPlaces(2)

        binding.btnSaveSettings.setOnSingleClickListener { saveSettings() }
        binding.btnBackupNow.setOnSingleClickListener { backupNow() }
        binding.btnTheme.setOnSingleClickListener { showThemePicker() }
        binding.btnAutoBackupFolder.setOnSingleClickListener { folderLauncher.launch(BackupPrefs.treeUri(requireContext())) }
        refreshAutoBackupUi()
        // "*/*": الملف المشفّر .gymbak نوعه octet-stream فلا يظهر في منتقي JSON فقط؛ المحتوى يُتحقق منه عند القراءة.
        binding.btnRestoreBackup.setOnSingleClickListener { restoreLauncher.launch(arrayOf("*/*")) }
        binding.btnExportCsv.setOnSingleClickListener { exportCsv() }
        binding.btnOpenArchive.setOnSingleClickListener { startActivity(Intent(requireContext(), ArchiveActivity::class.java)) }
        binding.btnAbout.setOnSingleClickListener { showAbout() }
        binding.btnOpenNotificationSettings.setOnSingleClickListener { openSystemNotificationSettings() }

        applyWhatsappPrefixLabels()
        setupSecuritySection()
    }

    /**
     * البند 4: كانت تسميتا rbPrefix970/972 نصاً عربياً حرفياً ثابتاً في XML، مكرَّراً
     * فعلياً لنفس المحتوى المستخدم في شاشة الإعداد الأولي (activity_gym_setup.xml،
     * onboarding_prefix_palestine/israel_*) — الاستثناء الوحيد لهذا النمط في كل شجرة
     * التخطيطات. إعادة استخدام نفس مورديّ الاسم والرمز هنا (بدل تكرارهما حرفياً) تضمن
     * مصدراً واحداً للتسمية في الشاشتين معاً، فلا يتكرّر هنا نفس انحراف ثيمات الوضع
     * الليلي الذي عالجه البند 3 (نسخة تُحدَّث، وأخرى منسية تتجمّد على القديم).
     */
    private fun applyWhatsappPrefixLabels() {
        binding.rbPrefix970.text = getString(
            R.string.whatsapp_prefix_label_format,
            getString(R.string.onboarding_prefix_palestine_label),
            getString(R.string.onboarding_prefix_palestine_code)
        )
        binding.rbPrefix972.text = getString(
            R.string.whatsapp_prefix_label_format,
            getString(R.string.onboarding_prefix_israel_label),
            getString(R.string.onboarding_prefix_israel_code)
        )
    }

    // ───────────────────────────── الأمان: قفل التطبيق ─────────────────────────────
    // كل مفتاح هنا يعتمد على click (لا onCheckedChange): نُرجع المفتاح لحالته الفعلية فوراً،
    // ثم تُحدَّث الحالة الحقيقية من AppLockManager بعد نجاح الإجراء (refreshSecurityUi) —
    // فلا يبقى مفتاح "مفعَّل" شكلياً بينما المستخدم ألغى/فشل التأكيد.

    private fun setupSecuritySection() {
        binding.switchAppLock.setOnClickListener {
            val turnOn = binding.switchAppLock.isChecked
            binding.switchAppLock.isChecked = !turnOn
            if (turnOn) launchLock(LockAction.ENABLE, LockActivity.MODE_SETUP)
            else launchLock(LockAction.DISABLE, LockActivity.MODE_VERIFY)
        }
        binding.switchBiometric.setOnClickListener {
            val turnOn = binding.switchBiometric.isChecked
            binding.switchBiometric.isChecked = !turnOn
            if (turnOn) {
                AppLockUi.authenticate(
                    activity = requireActivity(),
                    title = getString(R.string.lock_biometric_title),
                    subtitle = null,
                    negativeText = getString(R.string.lock_cancel),
                    onSuccess = {
                        AppLockManager.setBiometricEnabled(true)
                        if (_binding != null) refreshSecurityUi()
                    }
                )
            } else {
                AppLockManager.setBiometricEnabled(false)
                refreshSecurityUi()
            }
        }
        binding.rowAutoLock.setOnClickListener { showAutoLockPicker() }
        binding.switchSecureScreen.setOnClickListener {
            AppLockManager.setSecureScreen(binding.switchSecureScreen.isChecked)
            AppLockManager.applySecureFlag(requireActivity())
        }
        binding.btnChangePin.setOnSingleClickListener { launchLock(LockAction.CHANGE, LockActivity.MODE_CHANGE) }
        binding.btnNewRecovery.setOnSingleClickListener { launchLock(LockAction.NEW_RECOVERY, LockActivity.MODE_VERIFY) }
        refreshSecurityUi()
    }

    private fun launchLock(action: LockAction, mode: String) {
        pendingLockAction = action
        lockLauncher.launch(Intent(requireContext(), LockActivity::class.java).putExtra(LockActivity.EXTRA_MODE, mode))
    }

    private fun refreshSecurityUi() {
        val enabled = AppLockManager.isEnabled()
        binding.switchAppLock.isChecked = enabled
        binding.containerAppLockOptions.visibleIf(enabled)

        val biometricAvailable = AppLockManager.biometricAvailable(requireContext())
        binding.switchBiometric.isEnabled = biometricAvailable
        binding.switchBiometric.isChecked = biometricAvailable && AppLockManager.isBiometricEnabled()
        binding.tvBiometricLabel.setText(
            if (biometricAvailable) R.string.settings_biometric else R.string.settings_biometric_unavailable
        )

        binding.tvAutoLockValue.text = autoLockLabel(AppLockManager.autoLockDelayMs())
        binding.switchSecureScreen.isChecked = AppLockManager.isSecureScreenEnabled()
    }

    private fun autoLockLabel(ms: Long): String = getString(
        when (ms) {
            0L -> R.string.settings_auto_lock_immediate
            30_000L -> R.string.settings_auto_lock_30s
            300_000L -> R.string.settings_auto_lock_5m
            else -> R.string.settings_auto_lock_1m
        }
    )

    private fun showAutoLockPicker() {
        val options = AppLockManager.AUTO_LOCK_OPTIONS_MS
        val labels = options.map { autoLockLabel(it) }.toTypedArray()
        val checked = options.indexOf(AppLockManager.autoLockDelayMs()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_auto_lock)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppLockManager.setAutoLockDelayMs(options[which])
                refreshSecurityUi()
                dialog.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // يُعاد الفحص في كل onResume لا onViewCreated فقط — راجع البند 20: المستخدم قد
        // يفتح إعدادات النظام لتفعيل/تعطيل الإشعارات ثم يعود لهذه الشاشة عبر زر الرجوع
        // دون إعادة إنشاء الفرجمنت، فيجب أن يظهر الشريط أو يختفي فوراً بحسب الحالة الفعلية
        // الحالية، لا الحالة وقت فتح الشاشة أول مرة.
        updateNotificationsDisabledWarning()
        refreshSecurityUi()
    }

    /**
     * NotificationManagerCompat.areNotificationsEnabled() يغطي حالتين معاً: رفض إذن
     * POST_NOTIFICATIONS (أندرويد 13+) وتعطيل إشعارات التطبيق يدوياً من إعدادات النظام
     * (أي إصدار) — وهو أشمل من فحص الإذن وحده. لو كانت الإشعارات معطّلة فعلياً، يظهر
     * شريط تحذير فوق مفتاحي "تنبيه الانتهاء/غير المدفوع" بدل تركهما قابلين للتفعيل دون
     * أي مؤشر بأن التنبيهات لن تصل فعلياً مهما كانت حالة هذين المفتاحين.
     */
    private fun updateNotificationsDisabledWarning() {
        val enabled = NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
        binding.containerNotificationsDisabledWarning.visibleIf(!enabled)
    }

    private fun openSystemNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
        startActivity(intent)
    }

    /**
     * منتقي عملة مخصّص، بنفس التخطيط (dialog_currency_picker.xml) ونمط
     * BottomSheetDialog الشفاف المستخدمَين أصلاً في GymSetupActivity، حتى لا
     * تبدو شاشة الإعدادات وكأنها تطبيق مختلف عند اختيار العملة.
     */
    private fun showCurrencyPicker() {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = DialogCurrencyPickerBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        val rows = listOf(
            sheet.rowCurrencyIls to currencyOptions[0],
            sheet.rowCurrencyUsd to currencyOptions[1],
            sheet.rowCurrencyJod to currencyOptions[2]
        )
        rows.forEach { (row, option) ->
            row.setBackgroundResource(
                if (option.code == selectedCurrency?.code) R.drawable.bg_onboarding_feature_card
                else R.drawable.bg_onboarding_selectable_card_unselected
            )
            row.setOnSingleClickListener {
                selectedCurrency = option
                binding.etCurrency.setText(option.symbol)
                updatePriceSuffixes()
                dialog.dismiss()
            }
        }

        // صفّ "عملة أخرى" (البند 23): مُبرَز حين تكون القيمة الحالية غير فارغة ولا تطابق
        // أياً من الثلاث الجاهزة — يشمل هذا كلا الحالتين: عملة اختارها المستخدم للتو عبر
        // هذا الصفّ، أو رمزاً حراً قديماً محفوظاً من قبل هذا الإصلاح (راجع loadSettings).
        val currentSymbol = binding.etCurrency.text?.toString()?.trim().orEmpty()
        val isCustomSelected = selectedCurrency == null && currentSymbol.isNotEmpty()
        sheet.rowCurrencyCustom.setBackgroundResource(
            if (isCustomSelected) R.drawable.bg_onboarding_feature_card
            else R.drawable.bg_onboarding_selectable_card_unselected
        )
        sheet.tvCurrencyCustomSymbol.text = if (isCustomSelected) currentSymbol else "+"
        sheet.tvCurrencyCustomSubtitle.text = if (isCustomSelected) {
            currentSymbol
        } else {
            getString(R.string.onboarding_currency_custom_subtitle)
        }
        sheet.rowCurrencyCustom.setOnSingleClickListener {
            dialog.dismiss()
            showCustomCurrencyDialog()
        }

        dialog.show()
    }

    /** حوار إدخال حرّ لرمز عملة غير مدرجة ضمن الثلاث الجاهزة — راجع البند 23. */
    private fun showCustomCurrencyDialog() {
        val currentSymbol = binding.etCurrency.text?.toString()?.trim().orEmpty()
        val isCurrentlyCustom = selectedCurrency == null && currentSymbol.isNotEmpty()
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.currency_custom_dialog_hint)
            filters = arrayOf(InputFilter.LengthFilter(CUSTOM_CURRENCY_MAX_LENGTH))
            setText(currentSymbol.takeIf { isCurrentlyCustom })
            setSelection(text.length)
            // حقل زجاجي بدل الخط السفلي الافتراضي (نفس ألوان Widget.GymManager.TextInputLayout.Glass)
            setBackgroundResource(R.drawable.bg_input_glass)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary))
        }
        val paddingH = resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val paddingV = resources.getDimensionPixelSize(R.dimen.spacing_sm)
        input.setPadding(paddingH, paddingV, paddingH, paddingV)
        // الحقل داخل حاوية بهوامش حتى لا يلتصق إطاره الزجاجي بحافتَي الحوار
        val inputContainer = android.widget.FrameLayout(requireContext()).apply {
            setPadding(paddingH, paddingV, paddingH, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.currency_custom_dialog_title)
            .setView(inputContainer)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val symbol = input.text?.toString()?.trim().orEmpty()
                if (symbol.isEmpty()) {
                    view?.showSnack(getString(R.string.currency_custom_dialog_error_empty), SnackType.ERROR)
                    return@setPositiveButton
                }
                selectedCurrency = null
                binding.etCurrency.setText(symbol)
                updatePriceSuffixes()
            }
            .show()
    }

    private fun updatePriceSuffixes() {
        val currency = binding.etCurrency.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "₪"
        binding.tilDailyPrice.suffixText = currency
        binding.tilWeeklyPrice.suffixText = currency
        binding.tilMonthlyPrice.suffixText = currency
    }

    /** يعطّل كل أزرار الإجراءات ويُظهر مؤشر تحميل صغير أثناء أي عملية حفظ/نسخ/استعادة */
    private fun setBusy(isBusy: Boolean) {
        binding.progressSettings.visibleIf(isBusy)
        val enabled = !isBusy
        // الوهج يتبع حالة التعطيل (نفس نمط frameSaveGlow في AddEditMemberActivity.setSaving()).
        binding.frameSaveSettingsGlow.isEnabled = enabled
        binding.btnSaveSettings.isEnabled = enabled
        binding.btnBackupNow.isEnabled = enabled
        binding.btnRestoreBackup.isEnabled = enabled
        binding.btnExportCsv.isEnabled = enabled
    }

    private fun loadSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = repository.getSettingsOnce()
            binding.etGymName.setText(settings.gymName)
            // يُطابَق رمز العملة المحفوظ مع إحدى الخيارات الثلاثة كي يظهر مختاراً في
            // المنتقي؛ لو كان رمزاً حراً قديماً (من قبل هذا الإصلاح) لا يطابق أياً
            // منها، يبقى selectedCurrency فارغاً لكن يظل معروضاً كما هو دون فرض
            // قيمة مختلفة على المستخدم قبل أن يفتح المنتقي بنفسه.
            selectedCurrency = currencyOptions.firstOrNull { it.symbol == settings.currencySymbol }
            binding.etCurrency.setText(settings.currencySymbol)
            binding.etDailyPrice.setText(settings.dailyPrice.toCleanString())
            binding.etWeeklyPrice.setText(settings.weeklyPrice.toCleanString())
            binding.etMonthlyPrice.setText(settings.monthlyPrice.toCleanString())
            updatePriceSuffixes()

            when (settings.whatsappPrefix) {
                "+970" -> binding.rgWhatsappPrefix.check(R.id.rbPrefix970)
                "+972" -> binding.rgWhatsappPrefix.check(R.id.rbPrefix972)
                else -> binding.rgWhatsappPrefix.check(R.id.rbPrefixAuto)
            }

            binding.switchNotifyExpiry.isChecked = settings.notifyExpiry
            binding.switchNotifyUnpaid.isChecked = settings.notifyUnpaid
        }
    }

    private fun saveSettings() {
        val gymName = binding.etGymName.text?.toString()?.trim().orEmpty()
        if (gymName.isEmpty()) {
            binding.tilGymName.error = getString(R.string.setup_error_gym_name)
            return
        }
        binding.tilGymName.error = null

        // toSafeDoubleOrNull (لا toDoubleOrNull القياسية) لقبول الأرقام العربية-الهندية،
        // بنفس المعالجة المطبَّقة على نفس الحقول تماماً في شاشة الإعداد الأولي وشاشة
        // إضافة/تعديل عضو — بدونها كان نفس الرقم يُقبل هناك ويُرفَض هنا فقط.
        val daily = binding.etDailyPrice.text?.toString()?.toSafeMoneyOrNull()
        val weekly = binding.etWeeklyPrice.text?.toString()?.toSafeMoneyOrNull()
        val monthly = binding.etMonthlyPrice.text?.toString()?.toSafeMoneyOrNull()

        // كل حقل يُرفض أيضاً إن كان صفراً أو سالباً — بدونها كان يمكن حفظ سعر
        // 0/سالب كسعر افتراضي دائم للنادي من شاشة الإعدادات (بعكس شاشة الإعداد
        // الأولي التي تفحص هذا فقط). الرسالة تتغيّر حسب نوع الخطأ.
        binding.tilDailyPrice.error = when {
            daily == null -> getString(R.string.setup_error_price_field)
            daily <= 0 -> getString(R.string.setup_error_price_must_be_positive)
            else -> null
        }
        binding.tilWeeklyPrice.error = when {
            weekly == null -> getString(R.string.setup_error_price_field)
            weekly <= 0 -> getString(R.string.setup_error_price_must_be_positive)
            else -> null
        }
        binding.tilMonthlyPrice.error = when {
            monthly == null -> getString(R.string.setup_error_price_field)
            monthly <= 0 -> getString(R.string.setup_error_price_must_be_positive)
            else -> null
        }
        if (daily == null || weekly == null || monthly == null ||
            daily <= 0 || weekly <= 0 || monthly <= 0
        ) return

        val currency = binding.etCurrency.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "₪"
        val prefix = when (binding.rgWhatsappPrefix.checkedRadioButtonId) {
            R.id.rbPrefix970 -> "+970"
            R.id.rbPrefix972 -> "+972"
            else -> null
        }

        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            repository.saveSettings(
                GymSettingsEntity(
                    id = 1,
                    gymName = gymName,
                    dailyPrice = daily,
                    weeklyPrice = weekly,
                    monthlyPrice = monthly,
                    whatsappPrefix = prefix,
                    currencySymbol = currency,
                    notifyExpiry = binding.switchNotifyExpiry.isChecked,
                    notifyUnpaid = binding.switchNotifyUnpaid.isChecked,
                    isSetupComplete = true
                )
            )
            setBusy(false)
            view?.showSnack(getString(R.string.settings_save_success), SnackType.SUCCESS)
        }
    }

    private fun backupNow() {
        showBackupPasswordDialog { password -> runBackup(password) }
    }

    /** يطلب كلمة مرور (مع تأكيد) أو يسمح بنسخة بلا تشفير؛ الإلغاء لا يفعل شيئاً. */
    private fun showBackupPasswordDialog(
        allowSkip: Boolean = true,
        onCancel: (() -> Unit)? = null,
        onChosen: (CharArray?) -> Unit
    ) {
        val dialogBinding = DialogBackupPasswordBinding.inflate(layoutInflater)
        if (!allowSkip) dialogBinding.tvBackupPasswordMessage.setText(R.string.auto_backup_password_message)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_password_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .setPositiveButton(R.string.backup_password_encrypt, null) // يُربط يدوياً ليبقى الحوار مفتوحاً عند الخطأ
        if (allowSkip) builder.setNeutralButton(R.string.backup_password_skip) { _, _ -> onChosen(null) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = dialogBinding.etBackupPassword.text?.toString().orEmpty()
                val confirm = dialogBinding.etBackupPasswordConfirm.text?.toString().orEmpty()
                dialogBinding.tilBackupPassword.error = null
                dialogBinding.tilBackupPasswordConfirm.error = null
                when {
                    password.length < 6 ->
                        dialogBinding.tilBackupPassword.error = getString(R.string.backup_password_too_short)
                    password != confirm ->
                        dialogBinding.tilBackupPasswordConfirm.error = getString(R.string.backup_password_mismatch)
                    else -> {
                        dialog.dismiss()
                        onChosen(password.toCharArray())
                    }
                }
            }
        }
        dialog.show()
    }

    private fun enableAutoBackup(uri: android.net.Uri, password: CharArray?) {
        val ctx = requireContext().applicationContext
        BackupPrefs.setTree(ctx, uri)
        BackupPrefs.setPassword(ctx, password)
        password?.fill('\u0000')
        BackupPrefs.setAutoEnabled(ctx, true)
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = AutoBackupRunner.run(ctx, repository)
            if (!ok) BackupPrefs.setAutoEnabled(ctx, false)
            view?.showSnack(
                getString(if (ok) R.string.auto_backup_enabled_toast else R.string.auto_backup_failed_toast),
                if (ok) SnackType.SUCCESS else SnackType.ERROR
            )
            if (_binding != null) refreshAutoBackupUi()
        }
    }

    private fun refreshAutoBackupUi() {
        val ctx = requireContext()
        val enabled = BackupPrefs.isAutoEnabled(ctx)
        binding.switchAutoBackup.setOnCheckedChangeListener(null)
        binding.switchAutoBackup.isChecked = enabled
        binding.switchAutoBackup.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                folderLauncher.launch(BackupPrefs.treeUri(ctx))
            } else {
                BackupPrefs.disableAuto(ctx)
                refreshAutoBackupUi()
            }
        }
        binding.btnAutoBackupFolder.setText(
            if (BackupPrefs.treeUri(ctx) != null) R.string.auto_backup_change_folder else R.string.auto_backup_choose_folder
        )
        val last = BackupPrefs.lastBackupAt(ctx)
        binding.tvAutoBackupStatus.text = when {
            BackupPrefs.lastError(ctx) != null -> getString(R.string.auto_backup_status_error)
            !enabled -> getString(R.string.auto_backup_status_off)
            last == 0L -> getString(R.string.auto_backup_status_never)
            else -> getString(
                R.string.auto_backup_status_last,
                AndroidDateUtils.formatDateTime(
                    ctx, last,
                    AndroidDateUtils.FORMAT_SHOW_DATE or AndroidDateUtils.FORMAT_SHOW_TIME or AndroidDateUtils.FORMAT_ABBREV_MONTH
                )
            )
        }
    }

    private fun showThemePicker() {
        val labels = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, ThemePrefs.get(requireContext())) { d, which ->
                ThemePrefs.set(requireContext(), which)
                d.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun runBackup(password: CharArray?) {
        setBusy(true)
        showBackupProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = BackupManager.createBackupFile(requireContext(), repository, password) { processed, total ->
                    updateBackupProgress(processed, total)
                }
                view?.showSnack(
                    getString(R.string.settings_backup_success_with_size, BackupManager.formatFileSize(file.length())),
                    SnackType.SUCCESS
                )
                BackupManager.shareBackupFile(requireContext(), file)
                if (_binding != null) refreshAutoBackupUi()
            } catch (e: Exception) {
                view?.showSnack(getString(R.string.settings_backup_error), SnackType.ERROR)
            } finally {
                hideBackupProgress()
                setBusy(false)
            }
        }
    }

    /**
     * البند 26: تُظهر شريط تقدّم محدَّد (نسبة مئوية حقيقية) بدل مؤشر دوّار عام
     * أثناء النسخ الاحتياطي/الاستعادة تحديداً، حيث يهمّ حجم البيانات فعلياً.
     */
    private fun showBackupProgress() {
        binding.containerBackupProgress.visibleIf(true)
        binding.progressBackupDeterminate.progress = 0
        binding.tvBackupProgressDetail.text = getString(R.string.settings_progress_detail, 0, 0, 0)
    }

    private fun hideBackupProgress() {
        binding.containerBackupProgress.visibleIf(false)
    }

    private fun updateBackupProgress(processed: Int, total: Int) {
        val percent = if (total <= 0) 0 else (processed * 100 / total).coerceIn(0, 100)
        binding.progressBackupDeterminate.progress = percent
        binding.tvBackupProgressDetail.text = getString(R.string.settings_progress_detail, percent, processed, total)
    }

    private fun confirmRestore(uri: android.net.Uri) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_restore_confirm_title)
            .setMessage(R.string.settings_restore_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ -> restoreWith(uri, null) }
            .show()
    }

    /**
     * يستعيد النسخة؛ إن كانت مشفّرة ولا كلمة مرور (أو الكلمة خاطئة) يطلبها من المستخدم ويُعيد
     * المحاولة. الحوار يُعرض بعد انتهاء finally حتى لا يبقى التطبيق في وضع "مشغول" خلفه.
     */
    private fun restoreWith(uri: android.net.Uri, password: CharArray?) {
        setBusy(true)
        showBackupProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            var askPassword = false
            try {
                BackupManager.restoreFromUri(requireContext(), uri, repository, password) { processed, total ->
                    updateBackupProgress(processed, total)
                }
                view?.showSnack(getString(R.string.settings_restore_success), SnackType.SUCCESS)
                loadSettings()
            } catch (e: BackupPasswordRequiredException) {
                askPassword = true
            } catch (e: BackupWrongPasswordException) {
                view?.showSnack(getString(R.string.backup_wrong_password), SnackType.ERROR)
                askPassword = true
            } catch (e: Exception) {
                view?.showSnack(e.message ?: getString(R.string.settings_restore_error), SnackType.ERROR)
            } finally {
                setBusy(false)
                hideBackupProgress()
            }
            if (askPassword) promptRestorePassword(uri)
        }
    }

    private fun promptRestorePassword(uri: android.net.Uri) {
        if (_binding == null) return
        val dialogBinding = DialogBackupPasswordBinding.inflate(layoutInflater)
        dialogBinding.tvBackupPasswordMessage.setText(R.string.restore_password_message)
        dialogBinding.tilBackupPasswordConfirm.visibleIf(false)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_password_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.restore_password_action) { _, _ ->
                restoreWith(uri, dialogBinding.etBackupPassword.text?.toString().orEmpty().toCharArray())
            }
            .show()
    }

    /**
     * البند 25: كان الضغط يُصدّر النشطين فقط بلا أي خيار — الآن نسأل صراحة إن كان
     * يريد تضمين المؤرشفين/المحذوفين أيضاً (مثلاً لأرشفة محاسبية كاملة قبل تغيير
     * الهاتف) قبل بناء الملف.
     */
    private fun exportCsv() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_export_include_archived)
            .setNegativeButton(R.string.settings_export_active_only) { _, _ -> exportCsv(includeArchived = false) }
            .setPositiveButton(R.string.settings_export_include_archived_action) { _, _ -> exportCsv(includeArchived = true) }
            .show()
    }

    /**
     * البند 32: كانت هذه الدالة بلا أي try/catch إطلاقاً — على عكس restoreFromUri
     * أعلاه المُغلَّفة بمعالجة أخطاء صريحة (راجع catch block في showRestorePicker)،
     * فأي فشل هنا (تخزين ممتلئ، صلاحيات كتابة، خطأ قراءة من قاعدة البيانات...) كان
     * يتسبب بانهيار صامت للتطبيق (Crash) بدل رسالة واضحة للمستخدم. الآن تُعالَج
     * الأخطاء بنفس النمط تماماً: toast برسالة الخطأ عند الفشل، و finally يضمن أن
     * setBusy(false) يُنفَّذ دائماً حتى لا تبقى الأزرار معطّلة إلى الأبد بعد استثناء.
     */
    private fun exportCsv(includeArchived: Boolean) {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val members = repository.observeActiveMembers("").first()
                val archivedMembers = if (includeArchived) {
                    repository.observeDeletedMembers("").first()
                } else {
                    emptyList()
                }
                val file = BackupManager.exportMembersCsv(requireContext(), members, archivedMembers)
                BackupManager.shareFile(requireContext(), file, "text/csv")
            } catch (e: Exception) {
                view?.showSnack(e.message ?: getString(R.string.settings_export_csv_error), SnackType.ERROR)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(getString(R.string.about_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.action_done, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
