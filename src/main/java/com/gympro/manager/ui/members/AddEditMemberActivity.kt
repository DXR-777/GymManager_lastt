package com.gympro.manager.ui.members

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.data.local.entities.SubscriptionEntity
import com.gympro.manager.databinding.ActivityAddEditMemberBinding
import com.gympro.manager.model.PaymentMethod
import com.gympro.manager.model.PaymentStatus
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.model.paymentStatus
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.limitDecimalPlaces
import com.gympro.manager.utils.normalizeDigitsToLatin
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.setupClearFocusOnOutsideTouch
import com.gympro.manager.utils.stripPhoneSeparators
import com.gympro.manager.utils.toCleanString
import com.gympro.manager.utils.toSafeDoubleOrNull
import com.gympro.manager.utils.toSafeIntOrNull
import com.gympro.manager.utils.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Calendar
import kotlin.coroutines.resume

private const val KEY_SELECTED_TYPE = "add_edit_member_selected_type"
private const val KEY_PAYMENT_STATUS = "add_edit_member_payment_status"
private const val KEY_PAY_METHOD = "add_edit_member_pay_method"
private const val KEY_START_DATE = "add_edit_member_start_date"
private const val KEY_HAS_UNSAVED_CHANGES = "add_edit_member_has_unsaved_changes"

/**
 * شاشة واحدة تخدم 3 سياقات بحسب الـ Intent extras المُمرَّرة:
 * 1) إضافة عضو جديد (بدون extras): تُنشئ عضواً واشتراكاً أول.
 * 2) تعديل عضو موجود (EXTRA_MEMBER_ID فقط): تعدّل بيانات العضو واشتراكه الحالي مباشرة.
 * 3) تجديد اشتراك (EXTRA_MEMBER_ID + EXTRA_RENEW=true): تضيف اشتراكاً جديداً
 *    لنفس العضو وتحتفظ بالاشتراك القديم في السجل التاريخي.
 *
 * التصميم (2026): Dark Premium Glassmorphism. محدِّد نوع الاشتراك وطريقة الدفع لم
 * يعودا ChipGroup/AutoCompleteTextView قياسيَين، بل قطاعات مخصَّصة (راجع
 * applyTypeSegmentSelection/applyPaymentMethodChipUI) حتى تُطابق شكل المرجع البصري
 * (أيقونة أعلى النص، توهّج عند الاختيار...) الذي لا تدعمه مكوّنات Material القياسية.
 * سويتش "تم الدفع؟" الثنائي استُبدل بميزة "الدفع الجزئي" الجديدة: حالة دفع ثلاثية
 * (مدفوع/جزئي/غير مدفوع) — راجع PaymentStatus.kt وGymRepository لتفاصيل حسابات
 * الإيراد/الدَّين المرتبطة بها.
 */
class AddEditMemberActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddEditMemberBinding
    private val repository by lazy { (application as GymApplication).repository }

    private var memberId: Long = -1L
    private var isRenewMode = false
    // نحتفظ بكامل سجل الاشتراك الأصلي (وليس فقط الـ id) حتى نحافظ على createdAt
    // الحقيقي عند التعديل. createdAt يُستخدم في كل حسابات الإيرادات المالية،
    // فإذا أُعيد توليده عند كل تعديل بسيط (مثل تصحيح رقم هاتف) فإن الإيراد
    // "ينتقل" زمنياً ويكسر تقارير الإيرادات المُقفلة سابقاً.
    private var loadedSubscription: SubscriptionEntity? = null
    private var loadedMember: MemberEntity? = null
    private var currentSettings: GymSettingsEntity = GymSettingsEntity()
    private var selectedStartDate: Long = DateUtils.startOfDay(DateUtils.now())
    private var suppressAutoFill = false
    // يُمنع أي Animation على ظهور/اختفاء قسم الدفع أو مجموعة بيانات المُرسِل أثناء
    // التحميل الأولي للبيانات (وضع التعديل/التجديد)، حتى لا تظهر حركة انتقال غير
    // مرغوبة فور فتح الشاشة — نفس مبدأ suppressAutoFill لكن لظهور/اختفاء الحقول بدل
    // تعبئة قيمها. يُضبط false فقط بعد اكتمال loadInitialData بالكامل.
    private var isLoadingInitialData = true

    // البند 1: يصبح true بمجرد أي تعديل فعلي من المستخدم على أي حقل بعد اكتمال
    // التحميل الأولي (isLoadingInitialData = false) — لا خلال تعبئة البيانات
    // المحمَّلة تلقائياً في وضعي التعديل/التجديد. يُستخدم فقط لتقرير هل نعرض حوار
    // "تجاهل التغييرات؟" عند محاولة الخروج (رجوع النظام أو زر الرجوع في الشريط
    // العلوي) بدل إغلاق الشاشة وفقدان البيانات المكتوبة فوراً وبصمت.
    private var hasUnsavedChanges = false

    /**
     * البند 1: حارس تزامني ضد ضغطة "حفظ" المزدوجة السريعة. لا يكفي وحده
     * setOnSingleClickListener (يعمل على مستوى الـ View فقط ولا يمنع سباقاً
     * حقيقياً)، لأن validateAndSave() تفحص التكرار بشكل غير متزامن
     * (findDuplicateWarningMessage داخل lifecycleScope.launch) ولا تُعطِّل الزر
     * فعلياً (setSaving(true)) إلا بعد انتهاء ذلك الفحص. فبين لحظة الضغطة الأولى
     * ولحظة تعطيل الزر تبقى نافذة زمنية حقيقية يمكن لضغطة ثانية خلالها أن تُطلق
     * نفس التحقق والحفظ مرة أخرى. هذا المتغير يُقفَل فوراً ومتزامناً عند أول
     * دخول صالح لعملية الحفظ (بعد اجتياز كل تحقق من صحة الحقول، قبل أي تعليق/
     * await)، ويُحرَّر في كل مخرج من عملية الحفظ (إلغاء المستخدم لحوار التكرار،
     * أو اكتمال الحفظ نجاحاً، أو فشله باستثناء) — لا فرصة لدخول ثانٍ بينهما مهما
     * بلغت سرعة الضغط.
     */
    private var isSaveInProgress = false

    /**
     * البند 2: صحيح فقط عندما أُعيد إنشاء هذه الشاشة من Bundle محفوظ فعلياً (تدوير
     * الشاشة، أو استعادة بعد قتل العملية في الخلفية) — لا عند بداية فعلية جديدة. تُستخدم
     * في loadInitialData لمنع إعادة تعبئة الحقول/الاختيارات (النوع، حالة الدفع، طريقة
     * الدفع، تاريخ البدء) بالقيم الافتراضية أو قيم القاعدة القديمة فوق ما استُعيد بالفعل
     * من onSaveInstanceState أدناه (أو فوق ما أبقاه المستخدم كما هو مؤقتاً في نص الحقول،
     * والذي يُستعاد تلقائياً بواسطة إطار العمل لأن كل EditText له معرِّف ثابت). تبقى بعض
     * الاستدعاءات (تحميل loadedMember/loadedSubscription/priorOutstandingBalance...)
     * ضرورية دائماً بلا استثناء لأنها بيانات يحتاجها الحفظ لاحقاً وليست مُخزَّنة في الـ Bundle.
     */
    private var isRestoringState = false

    private fun markDirty() {
        if (!isLoadingInitialData) hasUnsavedChanges = true
    }
    // يوم الفوترة المرجعي المُورَّث من آخر اشتراك شهري سابق لهذا العضو (وضع التجديد فقط،
    // يُحمَّل مرة واحدة في loadInitialData). ضروري حتى تطابق معاينة تاريخ الانتهاء المعروضة
    // هنا فعلياً ما سيحسبه ويحفظه GymRepository.addSubscription لاحقاً — بدونه، المعاينة
    // كانت تفترض خطأً أن يوم الفوترة = يوم selectedStartDate نفسه، فتعرض تاريخ انتهاء
    // مختلفاً (بفارق عدة أيام أحياناً) عن التاريخ الذي يُحفظ فعلياً عند الضغط "حفظ".
    private var inheritedMonthlyAnchor: Int? = null

    // نوع الاشتراك المختار حالياً في المحدِّد المخصَّص (بديل ChipGroup.checkedChipId).
    // الافتراضي MONTHLY مطابق تماماً للحالة المُختارة بصرياً في XML
    // (segMonthly بخلفية bg_segment_selected_type منذ البداية).
    private var selectedSubscriptionType: SubscriptionType = SubscriptionType.MONTHLY

    // حالة الدفع الثلاثية المختارة حالياً (ميزة الدفع الجزئي الجديدة). الافتراضي UNPAID
    // مطابق تماماً لسلوك سويتش "تم الدفع؟" الثنائي القديم (يبدأ دائماً غير مفعّل).
    private var selectedPaymentStatus: PaymentStatus = PaymentStatus.UNPAID

    // طريقة الدفع المختارة حالياً في صف الشرائح المخصَّص (بديل AutoCompleteTextView).
    // البند 28: null (لا افتراضي) عمداً عند إنشاء عضو جديد — كان PALPAY يُختار تلقائياً
    // بنفس الشكل البصري الكامل لاختيار حقيقي (لون + حركة "اختيار") بلا أي تمييز بينه
    // وبين اختيار فعلي من المستخدم، فقد يُحفَظ اشتراك بطريقة دفع خاطئة (مثلاً نقداً
    // فعلياً) بصمت لمجرد أن الموظف لم ينتبه لهذا الصف أثناء تعبئة نموذج سريع. الآن
    // يجب اختيار شريحة صراحةً قبل الحفظ (راجع التحقق في saveAndFinish) طالما قسم الدفع
    // ظاهر أصلاً (أي حالة الدفع ليست "غير مدفوع") — حالات التعديل/التجديد تستبدلها
    // فوراً في loadInitialData بالقيمة المحفوظة فعلياً فلا تمر بهذه الحالة الفارغة إطلاقاً.
    private var selectedPayMethod: PaymentMethod? = null

    // البند 29: الدَّين المتراكم من دورات سابقة لنفس العضو (قبل هذا التجديد) — يُحمَّل في
    // loadInitialData عند isRenewMode فقط ويُستخدم في updateOldDebtWarning أدناه، منفصلاً
    // تماماً عن حالة الدفع المختارة لهذا التجديد نفسه.
    private var priorOutstandingBalance: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddEditMemberBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupClearFocusOnOutsideTouch(binding.root)

        // البند 2: يُستعاد هنا — قبل أي من setupXxxSegments/Chips أدناه — لأن تلك الدوال
        // تقرأ القيمة الحالية لهذه المتغيرات فوراً عند الإعداد لرسم الاختيار الصحيح على
        // الشاشة (applyTypeSegmentSelection/applyPaymentMethodChipUI/selectPaymentStatus).
        // استعادتها بعد ذلك بدل قبله كانت ستعني رسم القطاعات مرة بالقيم الافتراضية ثم عدم
        // إعادة رسمها أبداً بالقيم المستعادة الفعلية.
        isRestoringState = savedInstanceState != null
        savedInstanceState?.let { state ->
            state.getString(KEY_SELECTED_TYPE)
                ?.let { raw -> runCatching { SubscriptionType.valueOf(raw) }.getOrNull() }
                ?.let { selectedSubscriptionType = it }
            state.getString(KEY_PAYMENT_STATUS)
                ?.let { raw -> runCatching { PaymentStatus.valueOf(raw) }.getOrNull() }
                ?.let { selectedPaymentStatus = it }
            selectedPayMethod = state.getString(KEY_PAY_METHOD)
                ?.let { raw -> runCatching { PaymentMethod.valueOf(raw) }.getOrNull() }
            if (state.containsKey(KEY_START_DATE)) {
                selectedStartDate = state.getLong(KEY_START_DATE)
            }
            hasUnsavedChanges = state.getBoolean(KEY_HAS_UNSAVED_CHANGES, hasUnsavedChanges)
        }

        memberId = intent.getLongExtra(EXTRA_MEMBER_ID, -1L)
        isRenewMode = intent.getBooleanExtra(EXTRA_RENEW, false)

        binding.tvToolbarTitle.text = when {
            isRenewMode -> getString(R.string.renew_member_title)
            memberId != -1L -> getString(R.string.edit_member_title)
            else -> getString(R.string.add_member_title)
        }
        binding.btnBack.setOnClickListener { handleExitRequest() }

        // البند 1: نفس حوار "تجاهل التغييرات؟" أيضاً لرجوع النظام (زر/إيماءة الرجوع)،
        // لا لزر الرجوع في الشريط العلوي فقط — كلاهما طريقتان شائعتان للخروج من
        // الشاشة ويجب أن يحميا بيانات المستخدم غير المحفوظة بنفس القدر.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleExitRequest()
        })

        setupTypeSegments()
        setupDatePicker()
        setupPaymentStatusSegments()
        setupPartialAmountListener()
        setupCustomDaysListener()
        setupPaymentMethodChips()
        setupSenderNameSync()
        setupSenderPhoneSync()
        setupPressScaleEffects()
        setupLiveValidationFeedback()
        setupDirtyTracking()
        setupDecimalPlaceLimits()
        loadInitialData()
        animatePriceHeroEntrance()

        binding.btnSaveMember.setOnSingleClickListener { validateAndSave() }
    }

    /**
     * البند 2: يحفظ فقط ما لا يُغطّيه استرجاع الحالة التلقائي لإطار العمل (نص حقول
     * EditText يُستعاد تلقائياً بحكم android:id ثابت — freezesText مفعَّل افتراضياً).
     * هذه الخمسة تحديداً متغيرات Kotlin عادية غير مرتبطة بأي View، فلا تُستعاد إطلاقاً
     * بدون هذا: نوع الاشتراك المختار، حالة الدفع، طريقة الدفع (قد تكون null فلا تُكتب
     * أصلاً في هذه الحالة، وonCreate يعاملها كـnull بلا فرق)، تاريخ البدء، وعلَم
     * "توجد تغييرات غير محفوظة" (حتى يبقى حوار "تجاهل التغييرات؟" يعمل بشكل صحيح بعد
     * الاستعادة أيضاً، بدل أن يُعامَل المستخدم كمن لم يُغيّر شيئاً رغم استعادة تعديلاته).
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_TYPE, selectedSubscriptionType.name)
        outState.putString(KEY_PAYMENT_STATUS, selectedPaymentStatus.name)
        selectedPayMethod?.let { outState.putString(KEY_PAY_METHOD, it.name) }
        outState.putLong(KEY_START_DATE, selectedStartDate)
        outState.putBoolean(KEY_HAS_UNSAVED_CHANGES, hasUnsavedChanges)
    }

    /**
     * البند 4: يقيّد etPrice وetPaidAmount بخانتين عشريتين كحد أقصى (مناسب لكل
     * العملات المدعومة حالياً)، بدل السماح بإدخال أي عدد من الخانات بعد الفاصلة
     * (مثل 12.123456) وحفظه كما هو، ما كان يكسر اتساق تنسيق العملة لاحقاً في كل
     * الشاشات الأخرى (CurrencyFormatter وغيرها).
     */
    private fun setupDecimalPlaceLimits() {
        binding.etPrice.limitDecimalPlaces(2)
        binding.etPaidAmount.limitDecimalPlaces(2)
    }

    /**
     * البند 1 (تكملة): يُسجِّل متابعة تغيّر كل حقل تفاعلي في الشاشة — نصي (مباشرة)
     * أو اختياري (نوع الاشتراك/حالة الدفع/طريقة الدفع/تاريخ البداية، كل منها عبر
     * استدعاء markDirty() من دالته الخاصة أدناه). لا تأثير على أي منطق حفظ أو
     * تحقق فعلي، فقط تتبّع "هل تغيّر شيء فعلاً؟" لأجل حوار الخروج.
     */
    private fun setupDirtyTracking() {
        val dirtyWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = markDirty()
        }
        listOf(
            binding.etName, binding.etPhone, binding.etPrice, binding.etCustomDays,
            binding.etNotes, binding.etPaidAmount, binding.etSenderName, binding.etSenderPhone
        ).forEach { it.addTextChangedListener(dirtyWatcher) }
    }

    /**
     * البند 1 (تكملة): نقطة خروج موحَّدة لكل من زر الرجوع في الشريط العلوي ورجوع
     * النظام. تُغلق الشاشة مباشرة إن لم يتغيّر شيء فعلياً منذ فتحها (hasUnsavedChanges
     * ما زالت false)، وإلا تعرض حوار تأكيد صريح أولاً — القرار يتعلّق ببيانات قد
     * تكون مالية (سعر/دفعة)، فلا يجوز فقدانها بضغطة واحدة غير مقصودة.
     */
    private fun handleExitRequest() {
        if (!hasUnsavedChanges) {
            finish()
            return
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.member_discard_changes_title)
            .setMessage(R.string.member_discard_changes_message)
            .setNegativeButton(R.string.member_discard_changes_keep_editing, null)
            .setPositiveButton(R.string.member_discard_changes_discard) { _, _ -> finish() }
            .show()
    }

    // ==================================================================== تفاعلات دقيقة (Micro Interactions)

    /**
     * تفاعل لمسي موحَّد (انكماش خفيف عند الضغط + عودة نابضة عند الرفع) لكل عنصر قابل
     * للاختيار في الشاشة، بدل الاعتماد على تغيّر اللون وحده عند النقر. نُحرِّك scale فقط
     * (بلا أي إعادة قياس/تخطيط)، فالتأثير رخيص تماماً على GPU (compositing فقط) ولا يُبطئ
     * التمرير أو يُثقل الرسم. isPressed تُضبط يدوياً لأننا نستهلك حدث اللمس بالكامل
     * (نُعيد true من onTouch)؛ بدونها كانت موجة اللمس (ripple) في foreground القطاعات
     * ستتوقف عن الاستجابة بصرياً رغم بقاء النقر نفسه يعمل.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.applyPressScale() {
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupPressScaleEffects() {
        listOf(
            binding.segDaily, binding.segWeekly, binding.segMonthly, binding.segCustom,
            binding.segPaid, binding.segPartial, binding.segUnpaid,
            binding.methodPalpay, binding.methodJawwal, binding.methodBank, binding.methodCash,
            binding.boxStartDate, binding.btnSaveMember
        ).forEach { it.applyPressScale() }
    }

    /**
     * دخول ناعم لمرة واحدة لبطاقة السعر عند فتح الشاشة (تلاشٍ + انزلاق خفيف من الأسفل)،
     * يُبرزها كنقطة التركيز البصرية الأولى دون تغيير أي شيء في تخطيطها. حركة لمرة واحدة
     * فقط (ليست حلقة مستمرة) — النبض المتكرر يُشتت الانتباه ولا يليق بتطبيق تجاري premium.
     */
    private fun animatePriceHeroEntrance() {
        binding.priceHeroCard.apply {
            alpha = 0f
            translationY = 24f
            animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(80)
                .setDuration(280)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }
    }

    /**
     * تغذية راجعة فورية أثناء الكتابة (بدل الانتظار حتى الضغط على "حفظ"): تُمسح رسالة
     * الخطأ فور أن يصبح الحقل صالحاً، فيشعر المستخدم أن الشاشة "تستمع" له لحظياً. لا يُغيّر
     * هذا أي قاعدة تحقق فعلية — نفس شروط validateAndSave تماماً، فقط أبكر زمنياً.
     */
    private fun setupLiveValidationFeedback() {
        binding.etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!s.isNullOrBlank()) binding.tilName.error = null
            }
        })
        binding.etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val digits = s?.toString()?.filter { it.isDigit() }.orEmpty()
                if (digits.length >= 9 && digits.length == s?.toString()?.trim()?.length) {
                    binding.tilPhone.error = null
                }
            }
        })
        binding.etPrice.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim()?.toSafeDoubleOrNull()
                if (value != null && value > 0.0) binding.tilPrice.error = null
            }
        })
    }

    // ==================================================================== نوع الاشتراك

    /**
     * محدِّد نوع الاشتراك: 4 قطاعات مخصَّصة (ليست ChipGroup/Chip قياسية، لأن Chip لا يدعم
     * أيقونة أعلى النص كما في المرجع البصري). كل قطاع LinearLayout بعنصرين فقط بالضبط
     * (ImageView ثم TextView) — نصل إليهما عبر getChildAt(0)/(1) بدل مُعرِّفات مستقلة
     * لكل عنصر، تفادياً لتضخّم عدد المُعرِّفات في XML.
     */
    private fun setupTypeSegments() {
        binding.segDaily.setOnClickListener { selectType(SubscriptionType.DAILY) }
        binding.segWeekly.setOnClickListener { selectType(SubscriptionType.WEEKLY) }
        binding.segMonthly.setOnClickListener { selectType(SubscriptionType.MONTHLY) }
        binding.segCustom.setOnClickListener { selectType(SubscriptionType.CUSTOM) }
        applyTypeSegmentSelection()
        updateCustomDaysVisibility()
    }

    /**
     * يُطابق تماماً سلوك setOnCheckedStateChangeListener القديم على ChipGroup: يُحدِّث
     * ظهور حقل "عدد الأيام" دائماً (حتى أثناء suppressAutoFill عند التحميل الأولي)، بخلاف
     * تعبئة السعر التلقائية أدناه التي يجب أن تبقى مُعطَّلة في تلك الحالة تحديداً — فهما
     * سؤالان مختلفان: "ما الحقول الظاهرة؟" مقابل "هل نُدرِّس المستخدم على سعر جديد بدل
     * قيمته المحمَّلة؟". تُستخدم أيضاً من loadInitialData بدل selectChip القديمة، بنفس
     * سلوك .check(id) الذي كان يُطلق هذا المنطق تلقائياً.
     */
    private fun selectType(type: SubscriptionType) {
        markDirty()
        selectedSubscriptionType = type
        applyTypeSegmentSelection()
        updateCustomDaysVisibility()
        if (suppressAutoFill) return
        val price = when (type) {
            SubscriptionType.DAILY -> currentSettings.dailyPrice
            SubscriptionType.WEEKLY -> currentSettings.weeklyPrice
            SubscriptionType.MONTHLY -> currentSettings.monthlyPrice
            // لا سعر ثابت مُعرَّف مسبقاً للمدة المخصصة (بخلاف الأنواع الثلاثة الأخرى):
            // يُحسَب دائماً كـ(عدد الأيام × السعر اليومي) عبر applyCustomDurationPrice، حتى
            // عند مجرد التبديل إلى هذا النوع (مثلاً بعد تبديل بعيد ثم عودة، وعدد الأيام
            // مُدخَل مسبقاً) — راجع توثيق applyCustomDurationPrice لتفاصيل السعر اليومي
            // كمصدر الحقيقة الوحيد لهذا النوع.
            SubscriptionType.CUSTOM -> null
        }
        if (price != null) {
            binding.etPrice.setText(price.toCleanString())
        } else {
            applyCustomDurationPrice()
        }
        updateEndDatePreview()
    }

    private fun selectedType(): SubscriptionType = selectedSubscriptionType

    /**
     * يُحسِّب سعر "المدة المخصصة" تلقائياً كـ(عدد الأيام × السعر اليومي من الإعدادات) ويكتبه
     * في حقل السعر مباشرة أثناء الكتابة — بلا حاجة لضغط "حفظ" (نفس تدفق updateEndDatePreview
     * أعلاه: كل حقل يُحدِّث المعتمِد عليه فور تغيّره). السعر اليومي (currentSettings.dailyPrice)
     * هو مصدر الحقيقة الوحيد هنا؛ لا يُخزَّن أي سعر ثابت افتراضي لـCUSTOM كما في
     * DAILY/WEEKLY/MONTHLY (راجع selectType أعلاه). كتابة السعر عبر setText تُشغِّل تلقائياً
     * مستمِع etPrice الموجود مسبقاً في setupPartialAmountListener، فيتبعه "الإجمالي/المتبقي"
     * فوراً بلا أي كود إضافي مكرَّر هنا.
     *
     * إدخال فارغ أو غير صالح (أثناء الكتابة، قبل أن ينتهي المستخدم) يُفرغ حقل السعر بدل عرض
     * "0" بجانب رمز العملة، حتى لا يُخيَّل لصاحب الجيم أن سعراً صفرياً مقصود ومُعتمَد فعلاً.
     */
    private fun applyCustomDurationPrice() {
        val days = binding.etCustomDays.text?.toString()?.trim()?.toIntOrNull()
        if (days != null && days > 0) {
            val price = days * currentSettings.dailyPrice
            binding.etPrice.setText(price.toCleanString())
            binding.tilPrice.error = null
        } else {
            binding.etPrice.text = null
        }
    }

    /** يُلوِّن القطاع المختار (خلفية متدرِّجة + أيقونة/نص أبيض) ويُعيد البقية لحالتها المحايدة. */
    private fun applyTypeSegmentSelection() {
        val segments = listOf(
            binding.segDaily to SubscriptionType.DAILY,
            binding.segWeekly to SubscriptionType.WEEKLY,
            binding.segMonthly to SubscriptionType.MONTHLY,
            binding.segCustom to SubscriptionType.CUSTOM
        )
        segments.forEach { (container, type) ->
            val icon = container.getChildAt(0) as ImageView
            val label = container.getChildAt(1) as TextView
            if (type == selectedSubscriptionType) {
                container.setBackgroundResource(R.drawable.bg_segment_selected_type)
                icon.imageTintList = ColorStateList.valueOf(getColor(R.color.white))
                label.setTextColor(getColor(R.color.white))
                label.setTypeface(null, Typeface.BOLD)
            } else {
                container.setBackgroundResource(0)
                icon.imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
                label.setTextColor(getColor(R.color.text_secondary))
                label.setTypeface(null, Typeface.NORMAL)
            }
        }
        // حركة اختيار بسيطة (Material Motion خفيف) على القطاع المختار حديثاً فقط.
        segments.firstOrNull { it.second == selectedSubscriptionType }?.first?.let { view ->
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.animate().scaleX(1f).scaleY(1f).setDuration(160)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        }
    }

    /** يُظهر حقل "عدد الأيام" فقط عند اختيار نوع الاشتراك "مدة مخصصة"، ويُخفيه لبقية الأنواع. */
    private fun updateCustomDaysVisibility() {
        binding.tilCustomDays.visibility =
            if (selectedType() == SubscriptionType.CUSTOM) View.VISIBLE else View.GONE
    }

    /**
     * يُحدِّث معاينة تاريخ الانتهاء وسعر "المدة المخصصة" معاً فور تغيير عدد الأيام المُدخَل
     * يدوياً (حرفاً بحرف)، ويمسح رسالة الخطأ السابقة (إن وُجدت) لأن المستخدم بصدد تصحيح
     * القيمة. نفس نمط setupPaymentStatusSegments وsetupDatePicker: كل حقل يُحدِّث المعتمِد
     * عليه مباشرة، بدل الانتظار حتى الحفظ.
     *
     * تحديث السعر مقيَّد بشرطين: النوع الحالي CUSTOM فعلاً (وإلا فحقل الأيام غير ظاهر أصلاً
     * ولا معنى لتحديث سعر نوع آخر)، و!suppressAutoFill — وهو الحارس الحقيقي هنا: أثناء
     * loadInitialData (وضعا التعديل/التجديد)، يُستدعى binding.etCustomDays.setText(...)
     * بينما suppressAutoFill = true، فيُمنع هذا المستمِع من الكتابة فوق السعر المحفوظ فعلياً
     * لهذا الاشتراك (sub.price) بسعر مُعاد حسابه من السعر اليومي الحالي في الإعدادات — وهما قد
     * يختلفان إن تغيّر السعر اليومي في الإعدادات بعد إنشاء ذلك الاشتراك.
     */
    private fun setupCustomDaysListener() {
        binding.etCustomDays.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.tilCustomDays.error = null
                updateEndDatePreview()
                if (selectedType() == SubscriptionType.CUSTOM && !suppressAutoFill) {
                    applyCustomDurationPrice()
                }
            }
        })
    }

    // ==================================================================== التواريخ

    private fun setupDatePicker() {
        binding.boxStartDate.setOnSingleClickListener { showDatePicker() }
    }

    /**
     * البند 6: حدّ أدنى وأقصى معقولان لتاريخ بداية الاشتراك، بدل ترك المنتقي بلا أي
     * قيد يسمح باختيار تاريخ بعيد جداً في الماضي أو المستقبل بالخطأ (تمرير سريع
     * بعجلة السنة في DatePickerDialog، مثلاً). 5 سنوات للخلف تكفي لأي إدخال بيانات
     * تاريخية فعلية لعضو قديم، وسنتان للأمام تكفي لأي جدولة مسبقة مقصودة — خارج
     * هذا النطاق يكون الاختيار شبه مؤكَّد أنه خطأ غير مقصود.
     */
    private fun showDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedStartDate }
        val dialog = DatePickerDialog(
            this,
            { _, year, month, day ->
                markDirty()
                val newCal = Calendar.getInstance()
                newCal.set(year, month, day, 0, 0, 0)
                selectedStartDate = DateUtils.startOfDay(newCal.timeInMillis)
                binding.tvStartDateValue.text = DateUtils.formatDate(selectedStartDate)
                binding.tvRenewalHint.visibility = View.GONE
                updateEndDatePreview()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        )
        val minCal = Calendar.getInstance().apply { add(Calendar.YEAR, -5) }
        val maxCal = Calendar.getInstance().apply { add(Calendar.YEAR, 2) }
        dialog.datePicker.minDate = minCal.timeInMillis
        dialog.datePicker.maxDate = maxCal.timeInMillis
        dialog.show()
    }

    /**
     * يوم الفوترة المرجعي المستخدم لمعاينة تاريخ الانتهاء، بنفس الأولوية المستخدمة فعلياً
     * عند الحفظ في GymRepository (addSubscription / updateSubscriptionDetails):
     * 1) وضع التجديد: يُورَّث من آخر اشتراك شهري سابق للعضو إن وُجد (inheritedMonthlyAnchor).
     * 2) وضع التعديل: يُورَّث من billingAnchorDay الخاص بالاشتراك المُعدَّل نفسه إن كان صالحاً.
     * 3) خلاف ذلك (عضو جديد، أو لا قيمة سابقة صالحة): يوم بداية الاشتراك المُختار حالياً.
     */
    private fun currentAnchorDayForPreview(): Int = when {
        isRenewMode -> inheritedMonthlyAnchor ?: DateUtils.dayOfMonth(selectedStartDate)
        memberId != -1L -> loadedSubscription?.billingAnchorDay?.takeIf { it in 1..31 }
            ?: DateUtils.dayOfMonth(selectedStartDate)
        else -> DateUtils.dayOfMonth(selectedStartDate)
    }

    /**
     * عدد الأيام المستخدم لمعاينة تاريخ الانتهاء عند اختيار "مدة مخصصة"، مقروءاً مباشرة من
     * حقل الإدخال. قيمة غير صالحة أو فارغة أثناء الكتابة (المستخدم لم ينتهِ بعد) تُعامَل
     * كـ1 مؤقتاً للمعاينة فقط، دون إظهار خطأ — التحقق الفعلي وإظهار رسالة الخطأ يحدثان فقط
     * عند الضغط على "حفظ" في validateAndSave، بنفس مبدأ باقي الحقول في هذه الشاشة.
     */
    private fun currentCustomDaysForPreview(): Int =
        binding.etCustomDays.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1

    private fun updateEndDatePreview() {
        val end = DateUtils.calculateEndDate(
            selectedStartDate,
            selectedType(),
            currentAnchorDayForPreview(),
            currentCustomDaysForPreview()
        )
        binding.tvEndDateValue.text = DateUtils.formatDate(end)
    }

    // ==================================================================== حالة الدفع (جديد)

    /**
     * حالة الدفع الثلاثية (مدفوع/جزئي/غير مدفوع، ميزة "الدفع الجزئي" الجديدة) تغذي مباشرة
     * حسابات الإيراد والديون في GymRepository/SubscriptionDao. لذلك يجب أن تكون حالتها
     * الحالية واضحة نصياً ولونياً دائماً، بنفس الحرص الذي كان على سويتش "تم الدفع؟"
     * الثنائي القديم — هذا الحقل تحديداً هو الأكثر عرضة لأن يُترك دون انتباه في التجديد
     * السريع أثناء الازدحام داخل الجيم.
     */
    private fun setupPaymentStatusSegments() {
        binding.segPaid.setOnClickListener {
            selectPaymentStatus(PaymentStatus.PAID, animate = !isLoadingInitialData)
        }
        binding.segPartial.setOnClickListener {
            selectPaymentStatus(PaymentStatus.PARTIAL, animate = !isLoadingInitialData)
        }
        binding.segUnpaid.setOnClickListener {
            selectPaymentStatus(PaymentStatus.UNPAID, animate = !isLoadingInitialData)
        }
        selectPaymentStatus(selectedPaymentStatus, animate = false)
    }

    private fun selectPaymentStatus(status: PaymentStatus, animate: Boolean) {
        markDirty()
        selectedPaymentStatus = status
        applyPaymentStatusSegmentUI()
        updatePaymentStatusHelperText()
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.scrollContent)
        }
        updatePartialAmountVisibility()
        updatePaymentSectionVisibility()
        if (status != PaymentStatus.UNPAID) {
            binding.tvRenewUnpaidWarning.visibility = View.GONE
        } else if (isRenewMode) {
            binding.tvRenewUnpaidWarning.visibility = View.VISIBLE
        }
    }

    /**
     * البند 29: تذكير منفصل تماماً عن tvRenewUnpaidWarning أعلاه. ذاك يحذّر فقط من أن
     * *هذا* التجديد نفسه سيُحفَظ "غير مدفوع" افتراضياً؛ هذا يذكّر بدَين قديم متراكم من
     * دورات سابقة (priorOutstandingBalance) بغض النظر عن حالة دفع التجديد الحالي —
     * وهو تحديداً ما كان مفقوداً: عضو عليه دَين سابق، يختار الموظف "مدفوع بالكامل"
     * لهذا التجديد فيظن أن كل شيء مُسدَّد الآن، بينما الدَّين القديم ما زال قائماً فعلياً
     * ولا علاقة له باختيار حالة الدفع الحالية. لذلك يظهر دائماً طالما الدَّين > صفر،
     * حتى لو كانت حالة التجديد الحالي "غير مدفوع" أيضاً (عندها يظهر الاثنان معاً).
     */
    private fun updateOldDebtWarning() {
        binding.tvOldDebtWarning.visibility = if (isRenewMode && priorOutstandingBalance > 0) {
            binding.tvOldDebtWarning.text = getString(
                R.string.renew_old_debt_warning,
                CurrencyFormatter.format(priorOutstandingBalance, currentSettings.currencySymbol)
            )
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    /** يُلوِّن القطاع المختار بلون حالته (أخضر/بنفسجي/أحمر) ويُعيد البقية لحالتها المحايدة. */
    private fun applyPaymentStatusSegmentUI() {
        data class SegmentStyle(val view: TextView, val status: PaymentStatus, val bgRes: Int, val colorRes: Int)
        val segments = listOf(
            SegmentStyle(binding.segPaid, PaymentStatus.PAID, R.drawable.bg_payment_status_paid_selected, R.color.status_success),
            SegmentStyle(binding.segPartial, PaymentStatus.PARTIAL, R.drawable.bg_payment_status_partial_selected, R.color.status_partial),
            SegmentStyle(binding.segUnpaid, PaymentStatus.UNPAID, R.drawable.bg_payment_status_unpaid_selected, R.color.status_error)
        )
        segments.forEach { seg ->
            if (seg.status == selectedPaymentStatus) {
                seg.view.setBackgroundResource(seg.bgRes)
                seg.view.setTextColor(getColor(seg.colorRes))
                seg.view.setTypeface(null, Typeface.BOLD)
            } else {
                seg.view.setBackgroundResource(0)
                seg.view.setTextColor(getColor(R.color.text_secondary))
                seg.view.setTypeface(null, Typeface.NORMAL)
            }
        }
        // نفس حركة الاختيار الخفيفة المستخدمة في applyTypeSegmentSelection، للاتساق.
        segments.firstOrNull { it.status == selectedPaymentStatus }?.view?.let { view ->
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.animate().scaleX(1f).scaleY(1f).setDuration(160)
                .setInterpolator(OvershootInterpolator(1.5f)).start()
        }
    }

    private fun updatePaymentStatusHelperText() {
        val (text, colorRes) = when (selectedPaymentStatus) {
            PaymentStatus.PAID -> R.string.member_payment_status_helper_paid to R.color.status_success
            PaymentStatus.PARTIAL -> R.string.member_payment_status_helper_partial to R.color.status_partial
            PaymentStatus.UNPAID -> R.string.member_payment_status_helper_unpaid to R.color.status_error
        }
        binding.tvPaymentStatusHelper.text = getString(text)
        binding.tvPaymentStatusHelper.setTextColor(getColor(colorRes))
    }

    /**
     * حقل "المبلغ المدفوع" ومعاينة "المتبقي" يظهران فقط عند اختيار "جزئي"، ويبقيان مخفيَين
     * تماماً لبقية الحالات — نفس مبدأ updatePaymentSectionVisibility أدناه، بحركة انتقال
     * سلسة إلا أثناء التحميل الأولي للبيانات.
     */
    private fun updatePartialAmountVisibility() {
        val show = selectedPaymentStatus == PaymentStatus.PARTIAL
        binding.llPartialAmountGroup.visibility = if (show) View.VISIBLE else View.GONE
        if (show) updateRemainingAmountPreview()
    }

    /**
     * يُحدِّث حقل etPaidAmount ومعاينة "المتبقي" مع كل تغيير في المبلغ المدفوع أو في السعر
     * نفسه (تغيير نوع الاشتراك مثلاً يُغيّر السعر تلقائياً، فيجب أن يتبعه المتبقي فوراً).
     */
    private fun setupPartialAmountListener() {
        binding.etPaidAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.tilPaidAmount.error = null
                updateRemainingAmountPreview()
            }
        })
        binding.etPrice.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (selectedPaymentStatus == PaymentStatus.PARTIAL) updateRemainingAmountPreview()
            }
        })
    }

    /**
     * يُحدِّث "الإجمالي" و"المتبقي" معاً مع كل تغيير في المبلغ المدفوع أو في السعر نفسه
     * (تغيير نوع الاشتراك مثلاً يُغيّر السعر تلقائياً، فيجب أن يتبعه المتبقي فوراً — بلا أي
     * وميض، لأن كلا القيمتين تُحسَبان وتُكتَبان في نفس اللحظة ضمن نفس الاستدعاء).
     */
    private fun updateRemainingAmountPreview() {
        val price = binding.etPrice.text?.toString()?.trim()?.toSafeDoubleOrNull() ?: 0.0
        val paid = binding.etPaidAmount.text?.toString()?.trim()?.toSafeDoubleOrNull() ?: 0.0
        val remaining = (price - paid).coerceAtLeast(0.0)
        binding.tvTotalAmount.text = CurrencyFormatter.format(price, currentSettings.currencySymbol)
        binding.tvRemainingAmount.text = CurrencyFormatter.format(remaining, currentSettings.currencySymbol)
    }

    /**
     * قسم طريقة الدفع + بيانات المحفظة/المرسل معاً: يظهران فقط عندما حالة الدفع ليست "غير
     * مدفوع" (مدفوع أو جزئي)، ويبقيان مخفيَين تماماً وهي "غير مدفوع" حتى تبقى الشاشة بسيطة
     * كما هو مطلوب. التبديل بحركة انتقال سلسة (TransitionManager) إلا أثناء التحميل
     * الأولي للبيانات، حيث يُضبط الظهور مباشرة بلا حركة تجنباً لوميض غير مرغوب فور فتح
     * الشاشة.
     */
    private fun updatePaymentSectionVisibility() {
        binding.llPaymentSection.visibility =
            if (selectedPaymentStatus != PaymentStatus.UNPAID) View.VISIBLE else View.GONE
    }

    // ==================================================================== طريقة الدفع

    /**
     * صف طريقة الدفع: 4 شرائح مخصَّصة (بديل AutoCompleteTextView القديم) بنفس ترتيب
     * تعريف PaymentMethod. لا اختيار افتراضي عند الإنشاء الأول لعضو جديد (راجع البند 28
     * أعلى تعريف selectedPayMethod) — كل الشرائح تبدأ بمظهر "غير مُختار" محايد إلى أن
     * يضغط المستخدم إحداها فعلياً. حالات التعديل/التجديد تستبدل هذا لاحقاً في
     * loadInitialData بالقيمة المحفوظة فعلياً لهذا الاشتراك.
     */
    private fun setupPaymentMethodChips() {
        binding.methodPalpay.setOnClickListener { selectPaymentMethod(PaymentMethod.PALPAY) }
        binding.methodJawwal.setOnClickListener { selectPaymentMethod(PaymentMethod.JAWWAL_PAY) }
        binding.methodBank.setOnClickListener { selectPaymentMethod(PaymentMethod.BANK_OF_PALESTINE) }
        binding.methodCash.setOnClickListener { selectPaymentMethod(PaymentMethod.CASH) }
        applyPaymentMethodChipUI()
        updateSenderGroupVisibility(animate = false)
    }

    private fun selectPaymentMethod(method: PaymentMethod) {
        markDirty()
        selectedPayMethod = method
        binding.tvPaymentMethodError.visibility = View.GONE
        applyPaymentMethodChipUI()
        updateSenderGroupVisibility(animate = !isLoadingInitialData)
    }

    /** يُطابق الشريحة المختارة حالياً مع قيمة PaymentMethod المقابلة لها — null قبل أي اختيار صريح. */
    private fun selectedPaymentMethod(): PaymentMethod? = selectedPayMethod

    private fun applyPaymentMethodChipUI() {
        val chips = listOf(
            binding.methodPalpay to PaymentMethod.PALPAY,
            binding.methodJawwal to PaymentMethod.JAWWAL_PAY,
            binding.methodBank to PaymentMethod.BANK_OF_PALESTINE,
            binding.methodCash to PaymentMethod.CASH
        )
        chips.forEach { (container, method) ->
            val selected = method == selectedPayMethod
            container.setBackgroundResource(
                if (selected) R.drawable.bg_payment_method_chip_selected
                else R.drawable.bg_payment_method_chip_unselected
            )
            val label = container.getChildAt(1) as TextView
            label.setTextColor(getColor(if (selected) R.color.text_primary else R.color.text_secondary))
            if (selected) {
                container.scaleX = 0.92f
                container.scaleY = 0.92f
                container.animate().scaleX(1f).scaleY(1f).setDuration(160)
                    .setInterpolator(OvershootInterpolator(1.5f)).start()
            }
        }
    }

    /**
     * مجموعة بيانات المُرسِل (checkbox + اسم المُرسِل + رقم جوال التحويل) تظهر فقط لطرق
     * الدفع الإلكترونية الثلاث (بال باي/جوال باي/بنك فلسطين)، وتُخفى بالكامل عند اختيار
     * "نقدًا" أو عندما لا توجد أي طريقة دفع مُختارة بعد أصلاً (راجع البند 28) — إذ لا
     * معنى لعرضها قبل أن يحدِّد المستخدم طريقة الدفع فعلياً.
     */
    private fun updateSenderGroupVisibility(animate: Boolean) {
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.llSenderGroup.parent as ViewGroup)
        }
        binding.llSenderGroup.visibility =
            if (selectedPayMethod != null && selectedPayMethod != PaymentMethod.CASH) View.VISIBLE else View.GONE
    }

    // ==================================================================== بيانات المُرسِل

    /**
     * checkbox "نفس اسم العضو" (مفعّلة افتراضياً): طالما مفعّلة، حقل اسم المُرسِل مقفل
     * للتعديل (بأيقونة قفل وخلفية معتمة، راجع applyLockedFieldStyle) ويتحدّث تلقائياً مع
     * كل تغيير في اسم العضو أعلى الشاشة. عند إلغاء تفعيلها يُفتح الحقل للتعديل الحر ويتوقف
     * التحديث التلقائي فوراً — القيمة المكتوبة يدوياً تبقى كما هي دون أي مطابقة أو تحقق
     * من الصيغة.
     */
    private fun setupSenderNameSync() {
        binding.cbSenderSameAsMember.setOnCheckedChangeListener { _, isChecked ->
            binding.etSenderName.isEnabled = !isChecked
            applyLockedFieldStyle(binding.tilSenderName, isChecked)
            if (isChecked) {
                binding.etSenderName.setText(binding.etName.text)
            }
        }
        binding.etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding.cbSenderSameAsMember.isChecked) {
                    binding.etSenderName.setText(s?.toString().orEmpty())
                }
            }
        })
        applyLockedFieldStyle(binding.tilSenderName, binding.cbSenderSameAsMember.isChecked)
    }

    /**
     * checkbox "نفس رقم جوال العضو" (مفعّلة افتراضياً): نفس نمط وسلوك setupSenderNameSync
     * تماماً، لكن مطبَّق على حقل رقم محفظة المُرسِل مقابل حقل رقم جوال العضو أعلى الشاشة
     * بدل اسمه.
     */
    private fun setupSenderPhoneSync() {
        binding.cbSenderPhoneSameAsMember.setOnCheckedChangeListener { _, isChecked ->
            binding.etSenderPhone.isEnabled = !isChecked
            applyLockedFieldStyle(binding.tilSenderPhone, isChecked)
            if (isChecked) {
                binding.etSenderPhone.setText(binding.etPhone.text)
            }
        }
        binding.etPhone.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (binding.cbSenderPhoneSameAsMember.isChecked) {
                    binding.etSenderPhone.setText(s?.toString().orEmpty())
                }
            }
        })
        applyLockedFieldStyle(binding.tilSenderPhone, binding.cbSenderPhoneSameAsMember.isChecked)
    }

    /**
     * "مقفل": أيقونة قفل صغيرة (endIconMode مخصَّص) + خلفية معتمة (alpha)، بنفس أسلوب
     * تطبيقات الفنتك الفاخرة المطلوب في المرجع البصري. "مفتوح": بلا أيقونة، شفافية كاملة.
     * لا علاقة لهذه الدالة بأي قيمة أو تحقق فعلي — عرض بصري بحت.
     */
    private fun applyLockedFieldStyle(til: TextInputLayout, locked: Boolean) {
        til.alpha = if (locked) 0.6f else 1f
        if (locked) {
            til.endIconMode = TextInputLayout.END_ICON_CUSTOM
            til.endIconDrawable = getDrawable(R.drawable.ic_lock_small)
        } else {
            til.endIconMode = TextInputLayout.END_ICON_NONE
        }
    }

    /**
     * يُحمِّل بيانات المُرسِل المحفوظة (senderName/senderPhone) عند فتح شاشة تعديل أو
     * تجديد اشتراك قائم. إن كان اسم المُرسِل المحفوظ مطابقاً لاسم العضو أو غير موجود
     * أصلاً، تبقى checkbox "نفس اسم العضو" مفعّلة (الحالة الافتراضية) ليستمر التحديث
     * التلقائي؛ خلاف ذلك (اسم مُرسِل مختلف صراحة — مثال: ولي أمر يدفع عن ابنه) تُلغى
     * تفعيلها لعرض الاسم المحفوظ فعلياً دون الكتابة فوقه. نفس المنطق تماماً يُطبَّق على
     * checkbox "نفس رقم جوال العضو" مقابل رقم هاتف العضو ورقم محفظة المُرسِل.
     */
    private fun applySenderData(sub: SubscriptionEntity) {
        val memberName = binding.etName.text?.toString().orEmpty()
        val sameNameAsMember = sub.senderName.isNullOrBlank() || sub.senderName == memberName
        binding.cbSenderSameAsMember.isChecked = sameNameAsMember
        binding.etSenderName.isEnabled = !sameNameAsMember
        applyLockedFieldStyle(binding.tilSenderName, sameNameAsMember)
        binding.etSenderName.setText(if (sameNameAsMember) memberName else sub.senderName)

        val memberPhone = binding.etPhone.text?.toString().orEmpty()
        val samePhoneAsMember = sub.senderPhone.isNullOrBlank() || sub.senderPhone == memberPhone
        binding.cbSenderPhoneSameAsMember.isChecked = samePhoneAsMember
        binding.etSenderPhone.isEnabled = !samePhoneAsMember
        applyLockedFieldStyle(binding.tilSenderPhone, samePhoneAsMember)
        binding.etSenderPhone.setText(if (samePhoneAsMember) memberPhone else sub.senderPhone)
    }

    // ==================================================================== تحميل البيانات

    private fun loadInitialData() {
        lifecycleScope.launch {
            currentSettings = repository.getSettingsOnce()
            binding.tilPrice.suffixText = currentSettings.currencySymbol

            if (memberId != -1L) {
                val member = repository.getMemberOnce(memberId)
                loadedMember = member
                // البند 2: نص هذه الحقول مُستعاد تلقائياً بواسطة إطار العمل عند إعادة
                // الإنشاء (كل EditText له معرِّف ثابت)؛ إعادة كتابته هنا من القاعدة فوق ذلك
                // كانت بالضبط ما يمحو تعديلات المستخدم غير المحفوظة بصمت عند استعادة الشاشة.
                if (!isRestoringState) {
                    member?.let {
                        binding.etName.setText(it.name)
                        binding.etPhone.setText(it.phone)
                        binding.etNotes.setText(it.notes)
                    }
                }

                if (isRenewMode) {
                    binding.etName.isEnabled = false
                    binding.etPhone.isEnabled = false
                    binding.tilNotes.visibility = View.GONE
                    // البند 29: يُحمَّل هنا (قبل أي تفاعل من المستخدم) حتى يظهر التذكير فوراً
                    // عند دخول شاشة التجديد إن كان على العضو دَين سابق، بدل انتظار أي حدث آخر.
                    // يُحمَّل دائماً بلا استثناء (حتى عند الاستعادة) لأنه ليس بيانات أدخلها
                    // المستخدم بل مُشتقّ من القاعدة ولازم لمنطق الحفظ/العرض بلا تخزينه في الـ Bundle.
                    priorOutstandingBalance = repository.getMemberStats(memberId).totalOutstanding
                    updateOldDebtWarning()
                    // يُحمَّل قبل أي استدعاء لـ updateEndDatePreview() في هذا الوضع، حتى تعرض
                    // المعاينة نفس تاريخ الانتهاء الذي سيُحسب ويُحفظ فعلياً عند "حفظ".
                    inheritedMonthlyAnchor = repository.getInheritedMonthlyAnchor(memberId)
                    val current = repository.getCurrentSubscription(memberId)
                    if (!isRestoringState) {
                        suppressAutoFill = true
                        current?.let { sub ->
                            selectType(sub.type)
                            binding.etPrice.setText(sub.price.toCleanString())
                            // لا يُخزَّن عدد الأيام كحقل مستقل (بخلاف billingAnchorDay للشهري)؛
                            // نشتقّه من فارق startDate/endDate المحفوظَين فعلياً، فيبقى التجديد
                            // مقترحاً نفس عدد الأيام السابق (قابل للتعديل) بدل حقل فارغ دائماً.
                            if (sub.type == SubscriptionType.CUSTOM) {
                                binding.etCustomDays.setText(
                                    (DateUtils.daysBetween(sub.startDate, sub.endDate) + 1).toString()
                                )
                            }
                            // نفس منطق تعبئة السعر/النوع تلقائياً: طريقة الدفع غالباً تتكرر لنفس
                            // العضو من دورة لأخرى، فنقترحها كنقطة بداية قابلة للتعديل بدل PalPay
                            // الافتراضية دائماً.
                            selectPaymentMethod(sub.paymentMethod)
                            applySenderData(sub)
                        }
                        suppressAutoFill = false

                        val remainingDays = current?.let { DateUtils.daysRemaining(it.endDate) } ?: 0
                        if (current != null && remainingDays > 0) {
                            // لا نُهدر الأيام المدفوعة المتبقية: التجديد يبدأ بعد انتهاء الاشتراك الحالي مباشرة
                            selectedStartDate = DateUtils.startOfDay(DateUtils.addDays(current.endDate, 1))
                            binding.tvRenewalHint.text = getString(
                                R.string.renewal_hint_remaining_days,
                                remainingDays,
                                DateUtils.formatDate(selectedStartDate)
                            )
                            binding.tvRenewalHint.visibility = View.VISIBLE
                        } else {
                            selectedStartDate = DateUtils.startOfDay(DateUtils.now())
                        }
                        // هذا الافتراضي (غير مدفوع) مقصود: لا نستطيع افتراض أن أي تجديد دُفع
                        // تلقائياً. لكنه أيضاً الحالة الأكثر شيوعاً لتفويت الانتباه في الاستخدام
                        // اليومي السريع، لذا يجب إظهاره كتنبيه صريح (راجع selectPaymentStatus).
                        selectPaymentStatus(PaymentStatus.UNPAID, animate = false)
                    }
                    // عند الاستعادة: selectedSubscriptionType/selectedPaymentStatus/selectedPayMethod/
                    // selectedStartDate كلها استُعيدت بالفعل من onSaveInstanceState في onCreate،
                    // ورُسمت واجهتها هناك أيضاً (setupTypeSegments/setupPaymentStatusSegments/
                    // setupPaymentMethodChips تقرأ القيمة الحالية عند الإعداد) — لا شيء إضافي
                    // مطلوب هنا لهذا الفرع.
                } else {
                    val current = repository.getCurrentSubscription(memberId)
                    current?.let { sub ->
                        // بيانات لازمة للحفظ (updateMemberAndSubscription) بغض النظر عن
                        // الاستعادة — ليست من الحقول القابلة للتحرير المرئية على الشاشة.
                        loadedSubscription = sub
                    }
                    if (!isRestoringState) {
                        current?.let { sub ->
                            suppressAutoFill = true
                            selectType(sub.type)
                            binding.etPrice.setText(sub.price.toCleanString())
                            // نفس الاشتقاق المستخدم في وضع التجديد أعلاه: عدد الأيام غير مُخزَّن
                            // كحقل مستقل، فنشتقّه من startDate/endDate المحفوظَين للاشتراك الحالي.
                            if (sub.type == SubscriptionType.CUSTOM) {
                                binding.etCustomDays.setText(
                                    (DateUtils.daysBetween(sub.startDate, sub.endDate) + 1).toString()
                                )
                            }
                            suppressAutoFill = false
                            selectedStartDate = sub.startDate
                            selectPaymentStatus(sub.paymentStatus(), animate = false)
                            if (sub.paymentStatus() == PaymentStatus.PARTIAL) {
                                binding.etPaidAmount.setText(sub.paidAmount?.toCleanString().orEmpty())
                            }
                            selectPaymentMethod(sub.paymentMethod)
                            applySenderData(sub)
                        }
                    }
                }
            } else if (!isRestoringState) {
                binding.etPrice.setText(currentSettings.monthlyPrice.toCleanString())
            }

            binding.tvStartDateValue.text = DateUtils.formatDate(selectedStartDate)
            updateEndDatePreview()
            isLoadingInitialData = false
        }
    }

    // ==================================================================== الحفظ

    /**
     * يبحث عن تكرار محتمل (نفس رقم الجوال أو نفس الاسم لعضو نشط آخر) قبل الحفظ — خطأ شائع
     * جداً في الاستخدام اليومي: كتابة رقم/اسم عضو موجود بالغلط بدل عضو جديد فيُفتح ملف مكرَّر
     * له بدل تحديث ملفه الحقيقي، أو تعديل بيانات عضو بحيث تصبح مطابقة لعضو آخر بالخطأ.
     * تحذير رقم الجوال له أولوية على تحذير الاسم لأنه إشارة أقوى وأندر حدوثاً بشكل طبيعي
     * (نادراً ما يشترك شخصان فعلياً في نفس رقم الجوال، بعكس تشابه الأسماء الشائع جداً).
     * excludeId = memberId (يبقى -1 في وضع الإضافة) حتى لا يُبلَّغ العضو أنه مكرَّر لنفسه
     * عند تعديل بياناته دون تغييرها فعلياً.
     */
    private suspend fun findDuplicateWarningMessage(name: String, phoneDigits: String): String? {
        val phoneMatch = repository.findMemberByPhone(phoneDigits, memberId)
        if (phoneMatch != null) return getString(R.string.member_duplicate_phone_message, phoneMatch.name)
        val nameMatch = repository.findMemberByName(name, memberId)
        if (nameMatch != null) return getString(R.string.member_duplicate_name_message, nameMatch.name)
        return null
    }

    /**
     * ينتظر قرار الموظف (متابعة الحفظ رغم التكرار / إلغاء) عبر حوار تحذير، ويُعلِّق تنفيذ
     * الحفظ حتى صدور القرار. suspendCancellableCoroutine لأن MaterialAlertDialogBuilder قائم
     * على Callback بينما تدفّق الحفظ المحيط به suspend بالكامل. setCancelable(false) عمداً:
     * الرجوع للخلف أو اللمس خارج الحوار لا يجوز أن يُفسَّر ضمنياً كـ"متابعة" أو "إلغاء" —
     * يجب على الموظف اختيار أحد الزرّين صراحة، فهذا قرار يتعلّق بسلامة بيانات ماليّة.
     */
    private suspend fun confirmProceedDespiteDuplicate(message: String): Boolean =
        suspendCancellableCoroutine { cont ->
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.member_duplicate_warning_title)
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton(R.string.action_cancel) { _, _ -> cont.resume(false) }
                .setPositiveButton(R.string.member_duplicate_continue) { _, _ -> cont.resume(true) }
                .show()
        }

    private fun validateAndSave() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            binding.tilName.error = getString(R.string.member_error_name_required)
            return
        }
        binding.tilName.error = null

        // القيمة الخام تُطبَّع أولاً (أرقام عربية-هندية → لاتينية) قبل أي مقارنة طول أو فلترة،
        // وإلا فإن Char.isDigit() القياسية تعتبر الأرقام العربية-الهندية (٠-٩) أرقاماً صحيحة
        // فتمرّ من هذا التحقق بنجاح، بينما تبقى غير مفهومة لاحقاً لمكوّن الاتصال (tel:) ولا
        // لرابط واتساب (wa.me) — راجع توثيق toSafePhoneDigits في Extensions.kt.
        // البند 3: تُزال فواصل التنسيق الشائعة (مسافات/شرطات/أقواس) أولاً — مثل رقم
        // ملصوق من جهات الاتصال بصيغة "059-999-9999" أو "(059) 999 9999" — قبل فحص
        // "هل تبقّى غير أرقام؟"، بدل رفض أي رقم يحتوي هذا التنسيق الشائع تماماً برسالة
        // "أرقام فقط" رغم أنه رقم صالح فعلياً بعد إزالة الفواصل فقط. أي حرف آخر غير
        // رقمي وغير هذه الفواصل (حروف مثلاً) يبقى مرفوضاً كما كان تماماً.
        val phoneRaw = binding.etPhone.text?.toString().orEmpty().trim().stripPhoneSeparators()
        val phoneNormalized = phoneRaw.normalizeDigitsToLatin()
        val phoneDigits = phoneNormalized.filter { it.isDigit() }
        if (phoneDigits.isEmpty() || phoneDigits.length != phoneNormalized.length) {
            binding.tilPhone.error = getString(R.string.member_phone_error_digits)
            return
        }
        if (phoneDigits.length < 9) {
            binding.tilPhone.error = getString(R.string.member_phone_error_length)
            return
        }
        binding.tilPhone.error = null

        val priceText = binding.etPrice.text?.toString()?.trim().orEmpty()
        val price = priceText.toSafeDoubleOrNull()
        if (price == null || price <= 0.0) {
            binding.tilPrice.error = getString(R.string.setup_error_price_field)
            binding.etPrice.requestFocus()
            return
        }
        binding.tilPrice.error = null

        val notes = binding.etNotes.text?.toString()?.trim().orEmpty()
        val type = selectedType()
        val paymentStatus = selectedPaymentStatus
        val isPaid = paymentStatus == PaymentStatus.PAID

        // عدد الأيام مطلوب فقط عند "مدة مخصصة"؛ لبقية الأنواع تبقى القيمة الافتراضية (1)
        // بلا أي أثر، لأن DateUtils.calculateEndDate يتجاهلها كلياً خارج فرع CUSTOM.
        var customDays = 1
        if (type == SubscriptionType.CUSTOM) {
            val customDaysText = binding.etCustomDays.text?.toString()?.trim().orEmpty()
            // toSafeIntOrNull (لا toIntOrNull القياسية) لقبول الأرقام العربية-الهندية هنا
            // أيضاً، بنفس معاملة حقول السعر تماماً — بدونها كان رقم "٧" مثلاً يُرفَض هنا رغم
            // قبوله في حقل السعر المجاور له في نفس الشاشة.
            val parsedCustomDays = customDaysText.toSafeIntOrNull()
            if (parsedCustomDays == null || parsedCustomDays <= 0) {
                binding.tilCustomDays.error = getString(R.string.member_custom_days_error)
                binding.etCustomDays.requestFocus()
                return
            }
            // البند 5: حد أقصى منطقي (≈5 سنوات) بدل قبول أي رقم مهما كبر (مثل 99999)،
            // الذي كان يُنتج تاريخ انتهاء بعيد جداً بلا أي تحذير أو منطق تجاري خلفه.
            if (parsedCustomDays > MAX_CUSTOM_DAYS) {
                binding.tilCustomDays.error =
                    getString(R.string.member_custom_days_error_too_large, MAX_CUSTOM_DAYS)
                binding.etCustomDays.requestFocus()
                return
            }
            binding.tilCustomDays.error = null
            customDays = parsedCustomDays
        }

        // مبلغ الدفع الجزئي (ميزة "الدفع الجزئي" الجديدة): مطلوب وصالح (0 < مبلغ ≤ السعر)
        // فقط عندما حالة الدفع = "جزئي" — راجع GymRepository.normalizePaidAmount للتطبيع
        // النهائي عند الحفظ (يُقيَّد مرة أخرى هناك أيضاً كحماية إضافية على مستوى الـ Repository).
        var paidAmount: Double? = null
        if (paymentStatus == PaymentStatus.PARTIAL) {
            val paidAmountText = binding.etPaidAmount.text?.toString()?.trim().orEmpty()
            val parsedPaidAmount = paidAmountText.toSafeDoubleOrNull()
            if (parsedPaidAmount == null || parsedPaidAmount <= 0.0) {
                binding.tilPaidAmount.error = getString(R.string.member_paid_amount_error_required)
                binding.etPaidAmount.requestFocus()
                return
            }
            if (parsedPaidAmount > price) {
                binding.tilPaidAmount.error = getString(R.string.member_paid_amount_error_exceeds)
                binding.etPaidAmount.requestFocus()
                return
            }
            binding.tilPaidAmount.error = null
            paidAmount = parsedPaidAmount
        }

        val paymentMethod = selectedPaymentMethod()
        // البند 28: لا افتراضي صامت — إن كان قسم الدفع ظاهراً أصلاً (مدفوع/جزئي) يجب
        // اختيار طريقة دفع صراحةً قبل الحفظ. عند "غير مدفوع" الصف غير ظاهر أصلاً
        // (updatePaymentSectionVisibility) فلا فرصة أمام المستخدم ليختار شيئاً، ويُستخدم
        // PALPAY كقيمة توثيقية بحتة (راجع تعليق SubscriptionEntity.paymentMethod) لا
        // تُعرض كحقيقة دفع في أي مكان طالما isPaid=false.
        if (paymentStatus != PaymentStatus.UNPAID && paymentMethod == null) {
            binding.tvPaymentMethodError.visibility = View.VISIBLE
            binding.rowPaymentMethods.requestFocus()
            return
        }
        binding.tvPaymentMethodError.visibility = View.GONE
        val resolvedPaymentMethod = paymentMethod ?: PaymentMethod.PALPAY
        // بيانات المُرسِل مقروءة فقط عندما قسم الدفع ظاهر فعلياً (مدفوع أو جزئي) وطريقة
        // الدفع ليست "نقدًا" (راجع updatePaymentSectionVisibility وupdateSenderGroupVisibility)؛
        // خلاف ذلك null، لأن الحقول أصلاً غير معروضة فلا معنى لأي قيمة قد تبقى فيها من
        // تحميل سابق. كلا الحقلين اختياري (يمكن حفظ الاشتراك بدونهما)، لكن إن كُتب أحدهما
        // فعلياً فيخضع لنفس مستوى التحقق تقريباً كحقول العضو المقابلة، بدل قبوله كما هو
        // بلا أي تحقق كما كان سابقاً.
        val collectSenderInfo = paymentStatus != PaymentStatus.UNPAID && resolvedPaymentMethod != PaymentMethod.CASH

        val senderNameRaw = if (collectSenderInfo) {
            binding.etSenderName.text?.toString()?.trim().orEmpty()
        } else ""
        if (senderNameRaw.isNotEmpty() && senderNameRaw.length < 2) {
            binding.tilSenderName.error = getString(R.string.member_sender_name_error_invalid)
            binding.etSenderName.requestFocus()
            return
        }
        binding.tilSenderName.error = null
        val senderName = senderNameRaw.takeIf { it.isNotEmpty() }

        // نفس تحقق رقم جوال العضو أعلاه بالضبط (تطبيع الأرقام العربية-الهندية ثم فحص أنها
        // أرقام فقط وطولها لا يقل عن 9)، بدل الاكتفاء بأخذ النص الخام كما كُتب دون أي
        // تطبيع أو تحقق — وإلا يُحفظ رقم تحويل بأرقام عربية لا يفهمها لاحقاً tel:/wa.me
        // بصمت، أو رقم ناقص الخانات بلا أي تنبيه للموظف وقت إدخاله.
        // البند 3: نفس معالجة فواصل التنسيق المطبَّقة على رقم جوال العضو أعلاه تماماً.
        val senderPhoneRaw = if (collectSenderInfo) {
            binding.etSenderPhone.text?.toString()?.trim()?.stripPhoneSeparators().orEmpty()
        } else ""
        var senderPhone: String? = null
        if (senderPhoneRaw.isNotEmpty()) {
            val senderPhoneNormalized = senderPhoneRaw.normalizeDigitsToLatin()
            val senderPhoneDigits = senderPhoneNormalized.filter { it.isDigit() }
            if (senderPhoneDigits.isEmpty() || senderPhoneDigits.length != senderPhoneNormalized.length) {
                binding.tilSenderPhone.error = getString(R.string.member_phone_error_digits)
                binding.etSenderPhone.requestFocus()
                return
            }
            if (senderPhoneDigits.length < 9) {
                binding.tilSenderPhone.error = getString(R.string.member_phone_error_length)
                binding.etSenderPhone.requestFocus()
                return
            }
            binding.tilSenderPhone.error = null
            senderPhone = senderPhoneDigits
        } else {
            binding.tilSenderPhone.error = null
        }
        // البند 1: يُقفَل هنا بالضبط — بعد اجتياز كل تحقق من صحة الحقول (أعلاه) وقبل أي
        // نقطة تعليق (suspend) داخل lifecycleScope.launch أدناه، متزامناً تماماً مع معالج
        // النقر. أي ضغطة ثانية تصل بينما لا تزال الأولى عالقة في فحص التكرار غير المتزامن
        // تُرفَض هنا فوراً قبل تكرار نفس الفحص والحفظ.
        if (isSaveInProgress) return
        isSaveInProgress = true

        lifecycleScope.launch {
            // فحص التكرار قبل أي حفظ فعلي — لا يُنفَّذ في وضع التجديد لأن الاسم ورقم الجوال
            // معطَّلان للتعديل هناك أصلاً (نفس العضو بالضرورة). لا setSaving(true) بعد حتى
            // يبقى بإمكان الموظف إلغاء الحوار والتراجع عن التعديل دون رؤية الزر معطَّلاً.
            try {
                if (!isRenewMode) {
                    val duplicateMessage = findDuplicateWarningMessage(name, phoneDigits)
                    if (duplicateMessage != null && !confirmProceedDespiteDuplicate(duplicateMessage)) {
                        return@launch
                    }
                }
                setSaving(true)
                saveInternal(
                    name = name,
                    phoneDigits = phoneDigits,
                    notes = notes,
                    type = type,
                    price = price,
                    isPaid = isPaid,
                    customDays = customDays,
                    resolvedPaymentMethod = resolvedPaymentMethod,
                    senderName = senderName,
                    senderPhone = senderPhone,
                    paidAmount = paidAmount
                )
            } finally {
                // يُحرَّر في كل الحالات: إلغاء حوار التكرار (return@launch أعلاه)، اكتمال
                // الحفظ نجاحاً، أو أي استثناء غير متوقع أثناءه — لا يبقى الحارس مقفلاً
                // بصمت لو فشل الحفظ لسبب ما، ما كان سيُبطِل زر "حفظ" نهائياً في تلك الشاشة.
                isSaveInProgress = false
            }
        }
    }

    private suspend fun saveInternal(
        name: String,
        phoneDigits: String,
        notes: String,
        type: SubscriptionType,
        price: Double,
        isPaid: Boolean,
        customDays: Int,
        resolvedPaymentMethod: PaymentMethod,
        senderName: String?,
        senderPhone: String?,
        paidAmount: Double?
    ) {
        when {
            isRenewMode -> {
                repository.addSubscription(
                    memberId = memberId,
                    type = type,
                    price = price,
                    startDate = selectedStartDate,
                    isPaid = isPaid,
                    customDays = customDays,
                    paymentMethod = resolvedPaymentMethod,
                    senderName = senderName,
                    senderPhone = senderPhone,
                    paidAmount = paidAmount
                )
            }
            memberId != -1L -> {
                // نُفوّض حساب يوم الفوترة المرجعي وتاريخ التحصيل الفعلي (paidAt) بالكامل
                // لـ GymRepository.updateSubscriptionDetails، بدل بنائهما يدوياً هنا.
                // هذا يضمن أن أي تفعيل لاحق لحالة "مدفوع"/"جزئي" من هذه الشاشة (تسوية
                // دَين قديم) يُسجَّل بتاريخ التحصيل الحقيقي (الآن)، لا بتاريخ إنشاء
                // الاشتراك الأصلي — وإلا لظهرت الدفعة في تقرير إيرادات شهر قديم
                // مُقفَل بدل شهر تحصيلها الفعلي.
                //
                // تحديث بيانات العضو واشتراكه يتمّان معاً ضمن معاملة واحدة (راجع
                // GymRepository.updateMemberAndSubscription) بدل استدعاءين منفصلين، حتى لا
                // يتوقّف الحفظ بينهما (تدمير الـ Activity، إنهاء العملية) تاركاً بيانات
                // العضو محدَّثة بينما يُفقَد تحديث الاشتراك صمتاً.
                val member = loadedMember
                val subscription = loadedSubscription
                if (member != null && subscription != null) {
                    repository.updateMemberAndSubscription(
                        member = member.copy(name = name, phone = phoneDigits, notes = notes),
                        subscriptionOriginal = subscription,
                        type = type,
                        price = price,
                        startDate = selectedStartDate,
                        isPaid = isPaid,
                        customDays = customDays,
                        paymentMethod = resolvedPaymentMethod,
                        senderName = senderName,
                        senderPhone = senderPhone,
                        paidAmount = paidAmount
                    )
                } else if (member != null) {
                    // لا يوجد اشتراك محمَّل لهذا العضو (حالة نادرة) — تحديث بيانات
                    // العضو فقط، بنفس السلوك السابق تماماً في هذه الحالة الاستثنائية.
                    repository.updateMemberProfile(member.copy(name = name, phone = phoneDigits, notes = notes))
                }
            }
            else -> {
                repository.addMemberWithSubscription(
                    name = name,
                    phone = phoneDigits,
                    notes = notes,
                    photoPath = null,
                    type = type,
                    price = price,
                    startDate = selectedStartDate,
                    isPaid = isPaid,
                    customDays = customDays,
                    paymentMethod = resolvedPaymentMethod,
                    senderName = senderName,
                    senderPhone = senderPhone,
                    paidAmount = paidAmount
                )
            }
        }
        playSaveSuccessPulse {
            toast(getString(R.string.member_save_success))
            finish()
        }
    }

    /**
     * نبضة نجاح قصيرة على زر الحفظ قبل إغلاق الشاشة: تكبير خفيف ثم عودة، لمرة واحدة فقط.
     * تُطمئن المستخدم أن الحفظ اكتمل فعلاً قبل أن تختفي الشاشة، بدل انتقال مفاجئ قد يُشعره
     * أن شيئاً ما "قُطِع" في المنتصف. onEnd تستدعي onFinished مرة واحدة بالضبط.
     */
    private fun playSaveSuccessPulse(onFinished: () -> Unit) {
        binding.btnSaveMember.animate()
            .scaleX(1.04f).scaleY(1.04f)
            .setDuration(110)
            .withEndAction {
                binding.btnSaveMember.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(140)
                    .withEndAction { onFinished() }
                    .start()
            }
            .start()
    }

    private fun setSaving(isSaving: Boolean) {
        binding.frameSaveGlow.isEnabled = !isSaving
        binding.btnSaveMember.isEnabled = !isSaving
        binding.btnSaveMember.text = if (isSaving) "" else getString(R.string.action_save)
        binding.progressSave.visibility = if (isSaving) View.VISIBLE else View.GONE
    }

    companion object {
        const val EXTRA_MEMBER_ID = "extra_member_id"
        const val EXTRA_RENEW = "extra_renew"

        /** البند 5: أقصى عدد أيام مقبول لاشتراك "مدة مخصصة" (≈5 سنوات). */
        const val MAX_CUSTOM_DAYS = 1825
    }
}
