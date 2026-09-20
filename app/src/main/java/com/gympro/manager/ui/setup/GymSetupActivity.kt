package com.gympro.manager.ui.setup

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.databinding.ActivityGymSetupBinding
import com.gympro.manager.databinding.DialogCurrencyPickerBinding
import com.gympro.manager.ui.main.MainActivity
import com.gympro.manager.utils.applyHorizontalGradient
import com.gympro.manager.utils.limitDecimalPlaces
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.setupClearFocusOnOutsideTouch
import com.gympro.manager.utils.toSafeDoubleOrNull
import com.gympro.manager.utils.toast
import kotlinx.coroutines.launch

/**
 * خيار عملة في منتقي "العملة والأسعار" (خطوة 2 من 4). ثلاث عملات جاهزة
 * (ILS، USD، JOD) بالإضافة لخيار "عملة أخرى" حرّ (code = [CUSTOM_CURRENCY_CODE])
 * يسمح بأي رمز يكتبه صاحب النادي — راجع البند 23: سابقاً كانت هذه القائمة
 * مقصورة على الثلاث فقط بلا أي مخرج، فأي نادٍ بعملة مختلفة (جنيه مصري، ريال
 * سعودي...) لم يكن يستطيع استخدام عملته الحقيقية إطلاقاً.
 */
private data class CurrencyOption(val code: String, val symbol: String, val nameRes: Int)

/** يميّز عملة مُدخَلة يدوياً عبر "عملة أخرى" عن الثلاث الجاهزة — راجع showCustomCurrencyDialog. */
private const val CUSTOM_CURRENCY_CODE = "OTHER"

/** حدّ أقصى معقول لطول رمز العملة المُدخَل يدوياً (رموز العملات الحقيقية لا تتجاوز هذا عملياً). */
private const val CUSTOM_CURRENCY_MAX_LENGTH = 6

private const val TAG = "GymSetupActivity"
private const val KEY_CURRENT_STEP = "gym_setup_current_step"
private const val KEY_CURRENCY_CODE = "gym_setup_currency_code"
private const val KEY_CUSTOM_CURRENCY_SYMBOL = "gym_setup_custom_currency_symbol"
private const val KEY_SELECTED_PREFIX = "gym_setup_selected_prefix"

/**
 * معالج إعداد النادي (Onboarding — شاشة 4 من 4)، أربع خطوات ضمن نشاط واحد لا
 * أربعة أنشطة منفصلة:
 *   - اسم النادي
 *   - العملة والأسعار
 *   - مقدّمة واتساب
 *   - الإنهاء
 *
 * كل الحقول موجودة دوماً في نفس التخطيط (activity_gym_setup.xml) — التنقّل بين
 * الخطوات يُظهر/يُخفي حاويتها فقط عبر renderStep()، لذا لا يوجد أي فقدان بيانات
 * عند التنقّل للأمام أو للخلف، ويبقى validateAndSave() يقرأ من نفس الحقول تماماً
 * كما كان في النسخة أحادية الصفحة السابقة.
 *
 * الخطوات الأربع جميعها مصمَّمة بدقّة على مراجع تصميم المستخدم الأربعة.
 */
class GymSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGymSetupBinding
    private val repository by lazy { (application as GymApplication).repository }

    private var currentStep = 1
    private val totalSteps = 4

    // العملات الجاهزة الثلاث بهذا الترتيب — بالإضافة لخيار "عملة أخرى" الحرّ
    // (راجع CUSTOM_CURRENCY_CODE) المعروض كصفّ رابع في showCurrencyPicker.
    private val currencyOptions = listOf(
        CurrencyOption("ILS", "₪", R.string.currency_name_ils),
        CurrencyOption("USD", "$", R.string.currency_name_usd),
        CurrencyOption("JOD", "د.أ", R.string.currency_name_jod)
    )
    private var selectedCurrency = currencyOptions.first()

    /**
     * يصبح true بمجرد أن يختار المستخدم عملة يدوياً من المنتقي — عندها يكون
     * اختياره هو المصدر النهائي، ولا يجوز لتحميل العملة المحفوظة سابقاً
     * (الجاري بالتوازي في الخلفية عند فتح الشاشة، انظر onCreate) أن يستبدله
     * إن انتهى متأخراً بعد اختيار المستخدم (Race Condition).
     */
    private var userChangedCurrency = false

    /** null = تلقائي (اكتشاف المقدّمة من الرقم نفسه) */
    private var selectedPrefix: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGymSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClearFocusOnOutsideTouch(binding.root)

        // استعادة حالة المعالج بعد إعادة إنشاء الـ Activity (تدوير الشاشة مثلاً)
        // حتى لا يعود المستخدم لخطوة 1 دون سبب رغم بقائه في خطوة لاحقة.
        savedInstanceState?.let { state ->
            currentStep = state.getInt(KEY_CURRENT_STEP, 1)
            val savedCode = state.getString(KEY_CURRENCY_CODE)
            selectedCurrency = when {
                savedCode == CUSTOM_CURRENCY_CODE -> {
                    // عملة مخصّصة: رمزها الفعلي لا يُطابق أي code في currencyOptions الثابتة،
                    // لذا يُحفظ ويُستعاد بمفتاح منفصل بدل الاعتماد على القائمة الثلاثية وحدها.
                    val savedSymbol = state.getString(KEY_CUSTOM_CURRENCY_SYMBOL)
                    if (!savedSymbol.isNullOrBlank()) {
                        CurrencyOption(CUSTOM_CURRENCY_CODE, savedSymbol, R.string.currency_name_custom)
                    } else {
                        currencyOptions.first()
                    }
                }
                else -> currencyOptions.firstOrNull { it.code == savedCode } ?: currencyOptions.first()
            }
            selectedPrefix = state.getString(KEY_SELECTED_PREFIX)
        }

        binding.tvGymNameTitle.applyHorizontalGradient(
            startColor = ContextCompat.getColor(this, R.color.brand_gradient_start),
            endColor = ContextCompat.getColor(this, R.color.brand_gradient_end)
        )
        binding.tvPricingTitle.applyHorizontalGradient(
            startColor = ContextCompat.getColor(this, R.color.brand_gradient_start),
            endColor = ContextCompat.getColor(this, R.color.brand_gradient_end)
        )
        binding.tvWhatsappTitle.applyHorizontalGradient(
            startColor = ContextCompat.getColor(this, R.color.brand_gradient_start),
            endColor = ContextCompat.getColor(this, R.color.brand_gradient_end)
        )
        binding.tvStep4Title.applyHorizontalGradient(
            startColor = ContextCompat.getColor(this, R.color.brand_gradient_start),
            endColor = ContextCompat.getColor(this, R.color.brand_gradient_end)
        )

        // البند 4: بلا هذا كان يمكن إدخال سعر مثل 12.123456 وحفظه كما هو في أول
        // إعداد للنادي، فيكسر اتساق تنسيق العملة لاحقاً في كل شاشة تعرض الأسعار
        // (CurrencyFormatter يفترض خانتين عشريتين كحد أقصى) — راجع نفس القيد
        // المطبَّق على etPrice/etPaidAmount في AddEditMemberActivity.
        binding.etMonthlyPrice.limitDecimalPlaces(2)
        binding.etWeeklyPrice.limitDecimalPlaces(2)
        binding.etDailyPrice.limitDecimalPlaces(2)

        renderCurrency()
        updatePriceSuffixes()
        binding.rowCurrencyPicker.setOnSingleClickListener { showCurrencyPicker() }

        // تحميل احترازي: إن وُجد صفّ إعدادات سابق (مثلاً من نسخة تطبيق أقدم أو
        // حساب Google مرتبط مسبقاً في الشاشة السابقة) وعملته إحدى العملات
        // الثلاث المدعومة، اعرضها كمحدَّدة بدل الافتراضي دائماً. لا يُنفَّذ هذا
        // إلا عند بداية فعلية جديدة (لا تدوير شاشة) حتى لا يُصادم استرجاع الحالة
        // أعلاه. المستخدمون القدامى بلا قيمة مطابقة (أو بلا صفّ أصلاً) يبقون على
        // الافتراضي "ILS" تلقائياً لأن currencyOptions.first() هو ILS بالضبط.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                try {
                    val existing = repository.getSettingsOnce()
                    // حارس Race Condition: إن اختار المستخدم عملة يدوياً بنفسه
                    // بينما كان هذا التحميل ما يزال جارياً في الخلفية، يبقى
                    // اختياره اليدوي هو المصدر النهائي ولا يُستبدل بالقيمة
                    // القديمة المتأخرة القادمة من القاعدة.
                    if (!userChangedCurrency) {
                        val matched = currencyOptions.firstOrNull { it.symbol == existing.currencySymbol }
                        val resolved = matched ?: existing.currencySymbol
                            .takeIf { it.isNotBlank() && it != selectedCurrency.symbol }
                            ?.let { CurrencyOption(CUSTOM_CURRENCY_CODE, it, R.string.currency_name_custom) }
                        if (resolved != null && resolved.code != selectedCurrency.code) {
                            selectedCurrency = resolved
                            renderCurrency()
                            updatePriceSuffixes()
                        }
                    }
                } catch (e: Exception) {
                    // تحميل احترازي غير حرج: عند الفشل تبقى العملة على الافتراضي
                    // الآمن (ILS) دون أي أثر على المستخدم — لا داعي لمقاطعته
                    // برسالة خطأ لعملية خلفية كهذه، لكن نُسجّل الاستثناء بدل
                    // ابتلاعه بصمت تام حتى يمكن تتبّعه لاحقاً.
                    Log.e(TAG, "Failed to preload existing currency from settings", e)
                }
            }
        }

        setupPriceErrorClearing()

        renderPrefixSelection()
        binding.cardPrefixAuto.setOnSingleClickListener { selectPrefix(null) }
        binding.cardPrefix972.setOnSingleClickListener { selectPrefix("+972") }
        binding.cardPrefix970.setOnSingleClickListener { selectPrefix("+970") }

        binding.btnStepNext.setOnSingleClickListener { onNextClicked() }
        binding.ivStepBack.setOnSingleClickListener { goBack() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentStep > 1) {
                    goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        renderStep()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_STEP, currentStep)
        outState.putString(KEY_CURRENCY_CODE, selectedCurrency.code)
        if (selectedCurrency.code == CUSTOM_CURRENCY_CODE) {
            outState.putString(KEY_CUSTOM_CURRENCY_SYMBOL, selectedCurrency.symbol)
        }
        selectedPrefix?.let { outState.putString(KEY_SELECTED_PREFIX, it) }
    }

    private fun onNextClicked() {
        when (currentStep) {
            1 -> if (validateStep1()) goToStep(2)
            2 -> if (validateStep2()) goToStep(3)
            3 -> goToStep(4)
            4 -> validateAndSave()
        }
    }

    private fun goBack() {
        if (currentStep > 1) goToStep(currentStep - 1)
    }

    private fun goToStep(step: Int) {
        currentStep = step
        renderStep()
        binding.scrollSetupContent.post { binding.scrollSetupContent.smoothScrollTo(0, 0) }
    }

    private fun renderStep() {
        binding.layoutStep1.visibility = if (currentStep == 1) View.VISIBLE else View.GONE
        binding.layoutStep2.visibility = if (currentStep == 2) View.VISIBLE else View.GONE
        binding.layoutStep3.visibility = if (currentStep == 3) View.VISIBLE else View.GONE
        binding.layoutStep4.visibility = if (currentStep == 4) View.VISIBLE else View.GONE

        binding.ivStepBack.visibility = if (currentStep > 1) View.VISIBLE else View.GONE

        binding.tvStepIndicator.text =
            getString(R.string.onboarding_step_indicator, currentStep, totalSteps)

        val segments = listOf(binding.segment1, binding.segment2, binding.segment3, binding.segment4)
        val checks = listOf(binding.checkSegment1, binding.checkSegment2, binding.checkSegment3, binding.checkSegment4)
        segments.forEachIndexed { index, segment ->
            val stepNumber = index + 1
            segment.setBackgroundResource(
                if (stepNumber <= currentStep) R.drawable.bg_step_segment_active
                else R.drawable.bg_step_segment_inactive
            )
            checks[index].visibility =
                if (stepNumber < currentStep || currentStep == totalSteps) View.VISIBLE else View.GONE
        }

        binding.tvStepNextLabel.text =
            if (currentStep == totalSteps) getString(R.string.setup_finish)
            else getString(R.string.onboarding_next_label)
    }

    private fun validateStep1(): Boolean {
        val gymName = binding.etGymName.text?.toString()?.trim().orEmpty()
        binding.tvGymNameError.visibility = if (gymName.isEmpty()) View.VISIBLE else View.GONE
        return gymName.isNotEmpty()
    }

    /**
     * تحقّق لكل حقل على حدة برسالة مخصّصة بجانبه (لا رسالة عامة واحدة) — يفحص
     * الثلاثة دائماً (لا يتوقف عند أول خطأ) حتى تظهر كل الأخطاء دفعة واحدة إن
     * وُجدت أكثر من واحدة، بدل إجبار المستخدم على تصحيحها واحداً تلو الآخر.
     * toSafeDoubleOrNull() (لا toDoubleOrNull() القياسية) لقبول الأرقام
     * العربية-الهندية (١٢٣) والفارسية الممتدة أيضاً، لا اللاتينية فقط — يجب أن
     * تبقى هذه هي نفس الدالة المستخدمة لاحقاً في validateAndSave() تماماً حتى
     * لا يمرّ إدخال هنا كصالح ثم ينهار عند إعادة تحليله بدالة مختلفة هناك.
     *
     * كل حقل يُرفض إن كان غير قابل للتحليل *أو* أقل من/يساوي صفر — بدونها كان
     * يمكن حفظ سعر 0 أو سالب كسعر افتراضي دائم للنادي. الرسالة تتغيّر حسب نوع
     * الخطأ (حقل فارغ/غير رقمي مقابل رقم صفري أو سالب) حتى يفهم المستخدم فوراً
     * أي تصحيح مطلوب دون تخمين.
     */
    private fun validateStep2(): Boolean {
        val monthly = binding.etMonthlyPrice.text?.toString()?.toSafeDoubleOrNull()
        val weekly = binding.etWeeklyPrice.text?.toString()?.toSafeDoubleOrNull()
        val daily = binding.etDailyPrice.text?.toString()?.toSafeDoubleOrNull()

        val monthlyValid = monthly != null && monthly > 0
        val weeklyValid = weekly != null && weekly > 0
        val dailyValid = daily != null && daily > 0

        binding.tvMonthlyPriceError.text = getString(
            if (monthly != null && monthly <= 0) R.string.onboarding_price_error_monthly_positive
            else R.string.onboarding_price_error_monthly
        )
        binding.tvWeeklyPriceError.text = getString(
            if (weekly != null && weekly <= 0) R.string.onboarding_price_error_weekly_positive
            else R.string.onboarding_price_error_weekly
        )
        binding.tvDailyPriceError.text = getString(
            if (daily != null && daily <= 0) R.string.onboarding_price_error_daily_positive
            else R.string.onboarding_price_error_daily
        )

        binding.tvMonthlyPriceError.visibility = if (monthlyValid) View.GONE else View.VISIBLE
        binding.tvWeeklyPriceError.visibility = if (weeklyValid) View.GONE else View.VISIBLE
        binding.tvDailyPriceError.visibility = if (dailyValid) View.GONE else View.VISIBLE

        return monthlyValid && weeklyValid && dailyValid
    }

    /** يُخفي رسالة خطأ حقل سعر بمجرد أن يبدأ المستخدم بتعديله، بدل تركها ظاهرة حتى الضغط على "التالي" مجدداً. */
    private fun setupPriceErrorClearing() {
        fun clearOnEdit(field: android.widget.EditText, errorView: View) {
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (errorView.visibility == View.VISIBLE) errorView.visibility = View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        clearOnEdit(binding.etMonthlyPrice, binding.tvMonthlyPriceError)
        clearOnEdit(binding.etWeeklyPrice, binding.tvWeeklyPriceError)
        clearOnEdit(binding.etDailyPrice, binding.tvDailyPriceError)
    }

    private fun renderCurrency() {
        binding.tvCurrencyBadgeSymbol.text = selectedCurrency.symbol
        binding.tvCurrencyName.text = getString(selectedCurrency.nameRes)
        // للعملة المخصّصة لا يوجد كود دولي حقيقي (CUSTOM_CURRENCY_CODE قيمة داخلية فقط
        // "OTHER")، فعرضه كان سيظهر للمستخدم كأنه كود عملة فعلي مربك — يُكتفى بالرمز وحده.
        binding.tvCurrencyCode.text = if (selectedCurrency.code == CUSTOM_CURRENCY_CODE) {
            selectedCurrency.symbol
        } else {
            "${selectedCurrency.symbol} ${selectedCurrency.code}"
        }
    }

    /**
     * منتقي عملة مخصّص (BottomSheetDialog بخلفية شفافة + محتوى داكن مطابق
     * لهوية التطبيق) بدل AlertDialog النظامي الأبيض. خلفية الورقة نفسها
     * (design_bottom_sheet) تُجعل شفافة برمجياً لأن Material يرسم إطاراً
     * أبيض خلفها افتراضياً بصرف النظر عن تصميم المحتوى الداخلي.
     */
    private fun showCurrencyPicker() {
        val dialog = BottomSheetDialog(this)
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
                if (option.code == selectedCurrency.code) R.drawable.bg_onboarding_feature_card
                else R.drawable.bg_onboarding_selectable_card_unselected
            )
            row.setOnSingleClickListener {
                // تغيير العملة لا يُحوّل القيم المُدخَلة فعلياً — فقط يُبدّل رمز
                // العملة المعروض بجانب كل حقل (updatePriceSuffixes)؛ أرقام
                // etMonthlyPrice/etWeeklyPrice/etDailyPrice نفسها لا تُمسّ إطلاقاً.
                userChangedCurrency = true
                selectedCurrency = option
                renderCurrency()
                updatePriceSuffixes()
                dialog.dismiss()
            }
        }

        // صفّ "عملة أخرى" (راجع البند 23): يُظهر رمز العملة المخصّصة الحالية إن كانت
        // مختارة بالفعل (بدل "+" الثابت) حتى يرى المستخدم اختياره السابق عند إعادة فتح
        // المنتقي، ويُبرَز كبقية الصفوف عند اختياره.
        val isCustomSelected = selectedCurrency.code == CUSTOM_CURRENCY_CODE
        sheet.rowCurrencyCustom.setBackgroundResource(
            if (isCustomSelected) R.drawable.bg_onboarding_feature_card
            else R.drawable.bg_onboarding_selectable_card_unselected
        )
        sheet.tvCurrencyCustomSymbol.text = if (isCustomSelected) selectedCurrency.symbol else "+"
        sheet.tvCurrencyCustomSubtitle.text = if (isCustomSelected) {
            selectedCurrency.symbol
        } else {
            getString(R.string.onboarding_currency_custom_subtitle)
        }
        sheet.rowCurrencyCustom.setOnSingleClickListener {
            dialog.dismiss()
            showCustomCurrencyDialog()
        }

        dialog.show()
    }

    /**
     * حوار إدخال حرّ لرمز عملة غير مدرجة ضمن الثلاث الجاهزة — راجع البند 23. حدّ طول
     * (CUSTOM_CURRENCY_MAX_LENGTH) يمنع إدخال نص طويل يكسر شارات العملة في بقية الشاشات
     * (نفس مبدأ حدود الطول الأخرى في هذا المعالج، كحقول الأسعار).
     */
    private fun showCustomCurrencyDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.currency_custom_dialog_hint)
            filters = arrayOf(InputFilter.LengthFilter(CUSTOM_CURRENCY_MAX_LENGTH))
            setText(selectedCurrency.symbol.takeIf { selectedCurrency.code == CUSTOM_CURRENCY_CODE })
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
        val inputContainer = android.widget.FrameLayout(this).apply {
            setPadding(paddingH, paddingV, paddingH, 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.currency_custom_dialog_title)
            .setView(inputContainer)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val symbol = input.text?.toString()?.trim().orEmpty()
                if (symbol.isEmpty()) {
                    toast(getString(R.string.currency_custom_dialog_error_empty))
                    return@setPositiveButton
                }
                userChangedCurrency = true
                selectedCurrency = CurrencyOption(CUSTOM_CURRENCY_CODE, symbol, R.string.currency_name_custom)
                renderCurrency()
                updatePriceSuffixes()
            }
            .show()
    }

    private fun updatePriceSuffixes() {
        binding.tvMonthlySuffix.text = selectedCurrency.symbol
        binding.tvWeeklySuffix.text = selectedCurrency.symbol
        binding.tvDailySuffix.text = selectedCurrency.symbol
    }

    private fun selectPrefix(prefix: String?) {
        selectedPrefix = prefix
        renderPrefixSelection()
    }

    private fun renderPrefixSelection() {
        binding.cardPrefixAuto.setBackgroundResource(
            if (selectedPrefix == null) R.drawable.bg_onboarding_feature_card
            else R.drawable.bg_onboarding_selectable_card_unselected
        )
        binding.cardPrefix972.setBackgroundResource(
            if (selectedPrefix == "+972") R.drawable.bg_onboarding_feature_card
            else R.drawable.bg_onboarding_selectable_card_unselected
        )
        binding.cardPrefix970.setBackgroundResource(
            if (selectedPrefix == "+970") R.drawable.bg_onboarding_feature_card
            else R.drawable.bg_onboarding_selectable_card_unselected
        )
    }

    private fun validateAndSave() {
        if (!validateStep1()) {
            goToStep(1)
            return
        }
        if (!validateStep2()) {
            goToStep(2)
            return
        }

        val gymName = binding.etGymName.text!!.toString().trim()
        // نفس toSafeDoubleOrNull() المستخدمة للتو في validateStep2() بالضبط —
        // لا .toDouble()/!! مباشرة هنا. لو استُخدمت دالة تحليل مختلفة (أو
        // القياسية toDoubleOrNull) في هذا الموضع، سيمرّ رقم عربي-هندي بنجاح من
        // التحقق أعلاه ثم يُفشل بصمت أو يُعطّل التطبيق هنا عند إعادة تحليله.
        val daily = binding.etDailyPrice.text?.toString()?.toSafeDoubleOrNull()
        val weekly = binding.etWeeklyPrice.text?.toString()?.toSafeDoubleOrNull()
        val monthly = binding.etMonthlyPrice.text?.toString()?.toSafeDoubleOrNull()

        // حارس أمان صريح بدل !! غير آمن: نظرياً غير قابل للوصول لأن
        // validateStep2() تحقّقت للتو من نفس الحقول بنفس الدالة تماماً (تحليل
        // صالح و> 0)، لكن هذا يمنع أي احتمال انهيار أو حفظ سعر صفري/سالب
        // مستقبلاً حتى لو تغيّر شرط التحقق يوماً ونُسي تحديث هذا الموضع بالتوازي.
        if (daily == null || weekly == null || monthly == null ||
            daily <= 0 || weekly <= 0 || monthly <= 0
        ) {
            Log.e(TAG, "validateAndSave reached with invalid (unparsable or non-positive) price after validateStep2 passed")
            goToStep(2)
            return
        }

        val prefix = selectedPrefix

        val settings = GymSettingsEntity(
            id = 1,
            gymName = gymName,
            dailyPrice = daily,
            weeklyPrice = weekly,
            monthlyPrice = monthly,
            whatsappPrefix = prefix,
            currencySymbol = selectedCurrency.symbol,
            isSetupComplete = true
        )

        setSaving(true)
        lifecycleScope.launch {
            try {
                repository.saveSettings(settings)
                // مسح كامل الـ Back Stack هنا فقط (نهاية المسار الفعلية، وبعد
                // نجاح الحفظ فعلياً لا قبله): بعد اكتمال الإعداد لا نريد أن يعيد
                // زر الرجوع من الشاشة الرئيسية المستخدم إلى شاشات الإعداد
                // الأولي التي أنهاها بالفعل. الشاشات السابقة (الترحيب/فكرة
                // التطبيق/السحابة) تعمّدنا عدم إنهائها فور الانتقال بينها حتى
                // يعمل زر الرجوع الطبيعي أثناء المسار — هنا فقط، عند النجاح
                // النهائي المؤكَّد، يُعاد ضبط المهمّة بالكامل.
                val intent = Intent(this@GymSetupActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            } catch (e: Exception) {
                // فشل حرج: لا ينهار التطبيق، ولا ننتقل لـ MainActivity إطلاقاً
                // (الانتقال أعلاه داخل نفس try، فلن يُنفَّذ إن رمى saveSettings
                // استثناءً) — نعيد تفعيل الزر ونُظهر رسالة مفهومة للمستخدم كي
                // يستطيع إعادة المحاولة فوراً بلا فقدان أي بيانات أدخلها (كل
                // الحقول تبقى معبّأة كما هي، لم تُفرَّغ).
                Log.e(TAG, "Failed to save gym settings", e)
                setSaving(false)
                toast(getString(R.string.onboarding_setup_save_failed))
            }
        }
    }

    private fun setSaving(isSaving: Boolean) {
        binding.btnStepNext.isEnabled = !isSaving
        binding.tvStepNextLabel.visibility = if (isSaving) View.INVISIBLE else View.VISIBLE
        binding.ivStepNextArrow.visibility = if (isSaving) View.INVISIBLE else View.VISIBLE
        binding.progressFinishSetup.visibility = if (isSaving) View.VISIBLE else View.GONE
    }
}
