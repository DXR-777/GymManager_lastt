package com.gympro.manager.ui.members

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.local.entities.MemberEntity
import com.gympro.manager.data.local.entities.SubscriptionEntity
import com.gympro.manager.databinding.ActivityMemberDetailBinding
import com.gympro.manager.model.MemberStats
import com.gympro.manager.model.PaymentStatus
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.model.paidPaymentMethodLabel
import com.gympro.manager.model.paymentStatus
import com.gympro.manager.model.remainingAmount
import com.gympro.manager.model.senderInfoDetailLabel
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.WhatsAppHelper
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.toSafePhoneDigits
import com.gympro.manager.utils.toast
import androidx.fragment.app.setFragmentResultListener
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MemberDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMemberDetailBinding
    private val repository by lazy { (application as GymApplication).repository }
    private val historyAdapter = SubscriptionHistoryAdapter(
        onSettleDebt = { sub -> confirmSettleDebt(sub) }
    )

    private var memberId: Long = -1L
    private var currentSettings: GymSettingsEntity = GymSettingsEntity()
    private var currentMember: MemberEntity? = null
    private var currentSubscription: SubscriptionEntity? = null
    private var currentStats: MemberStats = MemberStats(0, 0.0, 0.0)

    // البند 30: سجل الاشتراكات الكامل كما وصل من قاعدة البيانات (غير مُصفّى وغير مُعاد
    // فرزه) — يُحفَظ منفصلاً عن القائمة المعروضة فعلياً حتى يمكن تطبيق فلتر/فرز جديد
    // فوراً دون انتظار انبعاث جديد من observeSubscriptionHistory. راجع refreshHistoryList().
    private var fullHistory: List<SubscriptionEntity> = emptyList()
    private var currentHistoryFilter: PaymentStatus? = null
    private var currentHistorySortNewestFirst: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemberDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        memberId = intent.getLongExtra(EXTRA_MEMBER_ID, -1L)
        if (memberId == -1L) { finish(); return }

        binding.toolbar.setNavigationOnClickListener { finish() }
        // LinearLayoutManager داخل ScrollView يقيس فقط العناصر المرئية على الشاشة
        // عند أول تحميل، مما يُحدّد الارتفاع بعدد العناصر الأولى فقط.
        // الحل: LayoutManager مخصص يُجبر على قياس كل العناصر دفعة واحدة.
        binding.rvHistory.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically() = false
            override fun isAutoMeasureEnabled() = true
        }
        binding.rvHistory.adapter = historyAdapter

        setupActions()
        setupHistoryControls()
        setupFragmentResultListeners()
        observeData()
    }

    /**
     * تُسجَّل هنا بدلاً من lambda callback داخل DeleteConfirmBottomSheet، لأن
     * الـ lambda لا يمكن استعادتها إذا أعاد FragmentManager إنشاء الحوار
     * بعد قتل العملية في الخلفية. Fragment Result API آمن للاستعادة لأنه
     * لا يعتمد على أي مرجع تم تمريره وقت الإنشاء.
     */
    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(
            DeleteConfirmBottomSheet.REQUEST_KEY_DELETE_CONFIRM,
            this
        ) { _, bundle ->
            if (bundle.getBoolean(DeleteConfirmBottomSheet.RESULT_CONFIRMED)) {
                lifecycleScope.launch {
                    repository.softDeleteMember(memberId)
                    showDeleteUndoSnackbar()
                }
            }
        }
    }

    /**
     * البند 27: الحذف هنا Soft-delete فعلياً (نقل للأرشيف قابل للاستعادة خلال 30 يوماً —
     * راجع ArchivePurgeWorker)، لكن الشاشة كانت تُغلَق فوراً (finish()) بلا أي طريقة
     * للتراجع، فيبدو القرار نهائياً بصرياً للمستخدم رغم أنه ليس كذلك فعلياً. الآن:
     * Snackbar بزر "تراجع" يُبقي هذه الشاشة مفتوحة (finish() يُؤجَّل إلى onDismissed
     * بدل استدعائه فوراً)؛ إن ضغط المستخدم "تراجع" يُستعاد العضو (restoreMember) وتبقى
     * الشاشة كما هي؛ إن انتهت مدة الـ Snackbar (أو أُغلقت بأي طريقة أخرى) دون تراجع،
     * تُغلَق الشاشة كالسابق تماماً. لا حاجة لأي تعديل في render()/observeData(): استعلام
     * العضو (getById) لا يُصفّي حسب isDeleted أصلاً فتستمر الشاشة بعرضه بشكل طبيعي
     * طوال دورة الحذف/التراجع هذه.
     */
    private fun showDeleteUndoSnackbar() {
        var undone = false
        Snackbar.make(binding.root, R.string.delete_success, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                undone = true
                lifecycleScope.launch { repository.restoreMember(memberId) }
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    if (!undone) finish()
                }
            })
            .show()
    }

    private fun setupActions() {
        binding.btnWhatsapp.setOnSingleClickListener { sendWhatsapp() }
        binding.btnCall.setOnSingleClickListener { callMember() }
        binding.btnRenew.setOnClickListener {
            it.isEnabled = false
            startActivity(
                Intent(this, AddEditMemberActivity::class.java)
                    .putExtra(AddEditMemberActivity.EXTRA_MEMBER_ID, memberId)
                    .putExtra(AddEditMemberActivity.EXTRA_RENEW, true)
            )
        }
        binding.btnEdit.setOnClickListener {
            it.isEnabled = false
            startActivity(
                Intent(this, AddEditMemberActivity::class.java)
                    .putExtra(AddEditMemberActivity.EXTRA_MEMBER_ID, memberId)
            )
        }
        binding.btnDelete.setOnSingleClickListener { showDeleteConfirmation() }
        binding.cardOutstandingBanner.setOnClickListener {
            binding.scrollView.post {
                binding.scrollView.smoothScrollTo(0, binding.rvHistory.top)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // يُعاد تفعيلهما هنا (وليس فقط عند الإنشاء) لأن العودة من AddEditMemberActivity
        // عبر زر الرجوع تُعيد استخدام نفس نسخة هذه الشاشة دون المرور بـ onCreate مجدداً.
        binding.btnRenew.isEnabled = true
        binding.btnEdit.isEnabled = true
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    repository.observeMember(memberId),
                    repository.observeSubscriptionHistory(memberId),
                    repository.observeSettings()
                ) { member, history, settings -> Triple(member, history, settings) }
                    .collect { (member, history, settings) ->
                        if (member == null) {
                            finish()
                            return@collect
                        }
                        currentMember = member
                        currentSettings = settings ?: GymSettingsEntity()
                        currentSubscription = history.maxByOrNull { it.endDate }
                        currentStats = repository.getMemberStats(memberId)
                        render(member, history, currentSubscription, currentStats, currentSettings)
                    }
            }
        }
    }

    private fun render(
        member: MemberEntity,
        history: List<SubscriptionEntity>,
        current: SubscriptionEntity?,
        stats: MemberStats,
        settings: GymSettingsEntity
    ) {
        binding.tvInitial.text = member.name.trim().firstOrNull()?.uppercase() ?: "?"
        binding.tvName.text = member.name
        binding.tvPhone.text = member.phone
        binding.tvMemberSince.text = getString(R.string.detail_member_since, DateUtils.formatDate(member.createdAt))

        binding.tvTotalMonths.text = stats.totalMonthsSubscribed.toString()
        binding.tvTotalPaid.text = CurrencyFormatter.format(stats.totalPaid, settings.currencySymbol)
        binding.tvTotalOutstanding.text = CurrencyFormatter.format(stats.totalOutstanding, settings.currencySymbol)

        // بنر الدَّين المتراكم: مستقل تماماً عن current.isPaid (الذي يصف الدورة الحالية فقط).
        // يظهر فقط عند وجود دَين تاريخي حقيقي > 0، ويُخفى تماماً غير ذلك حتى لا يتحول
        // إلى "ضوضاء بصرية" دائمة تفقد الموظفين الحساسية تجاهه مع الوقت.
        // عدد الاشتراكات (وليس "الأشهر"): النظام يدعم اشتراكات يومية/أسبوعية/شهرية،
        // فاستخدام "أشهر" هنا قد يضلل صاحب الجيم لو كان العضو على باقة يومية.
        if (stats.totalOutstanding > 0.0) {
            binding.cardOutstandingBanner.visibility = android.view.View.VISIBLE
            val amountLabel = CurrencyFormatter.format(stats.totalOutstanding, settings.currencySymbol)
            val countLabel = resources.getQuantityString(
                R.plurals.detail_outstanding_banner_count,
                stats.unpaidSubscriptionsCount,
                stats.unpaidSubscriptionsCount
            )
            binding.tvOutstandingBannerSentence.text =
                getString(R.string.detail_outstanding_banner_sentence, amountLabel, countLabel)
        } else {
            binding.cardOutstandingBanner.visibility = android.view.View.GONE
        }

        if (current != null) {
            binding.tvCurrentTypeBadge.text = typeLabel(current.type)
            // شارة حالة الدفع: ثلاث حالات الآن (مدفوع/جزئي/غير مدفوع) بدل حالتين، بحسب
            // ميزة "الدفع الجزئي" الجديدة — راجع PaymentStatus.kt.
            when (current.paymentStatus()) {
                PaymentStatus.PAID -> {
                    binding.tvCurrentPaidBadge.text = getString(R.string.status_paid)
                    binding.tvCurrentPaidBadge.setBackgroundResource(R.drawable.bg_pill_success)
                    binding.tvCurrentPaidBadge.setTextColor(getColor(R.color.status_success))
                }
                PaymentStatus.PARTIAL -> {
                    binding.tvCurrentPaidBadge.text = getString(R.string.payment_status_partial)
                    binding.tvCurrentPaidBadge.setBackgroundResource(R.drawable.bg_pill_partial)
                    binding.tvCurrentPaidBadge.setTextColor(getColor(R.color.status_partial))
                }
                PaymentStatus.UNPAID -> {
                    binding.tvCurrentPaidBadge.text = getString(R.string.status_unpaid)
                    binding.tvCurrentPaidBadge.setBackgroundResource(R.drawable.bg_pill_error)
                    binding.tvCurrentPaidBadge.setTextColor(getColor(R.color.status_error))
                }
            }

            val days = DateUtils.daysRemaining(current.endDate)
            val isFutureSubscription = DateUtils.isFuture(current.startDate)
            binding.tvDaysRemainingBig.text = when {
                isFutureSubscription -> getString(R.string.days_remaining_not_started)
                days < 0 -> getString(R.string.days_remaining_expired, -days)
                days == 0 -> getString(R.string.days_remaining_today)
                else -> getString(R.string.days_remaining_format, days)
            }
            binding.tvDaysRemainingBig.setTextColor(
                getColor(
                    when {
                        isFutureSubscription -> R.color.text_secondary
                        days < 0 -> R.color.status_error
                        days <= 3 -> R.color.status_warning
                        else -> R.color.status_success
                    }
                )
            )
            binding.tvCurrentDates.text = "${DateUtils.formatDate(current.startDate)} ← ${DateUtils.formatDate(current.endDate)}"

            // طريقة الدفع: لا تُعرض إطلاقاً إلا بعد أن يُدفع الاشتراك فعلياً (راجع
            // paidPaymentMethodLabel في PaymentMethod.kt). قبل ذلك، القيمة المحفوظة في
            // paymentMethod مجرد اختيار افتراضي من الدروب-داون عند إنشاء العضو ولا تعني
            // أن أي تحصيل حدث — عرضها كـ"سيتم الدفع عبر: ..." كان يُوهم صاحب الجيم بأن
            // طريقة دفع معيّنة "محجوزة" أو مؤكدة، بينما لا شيء تأكّد بعد.
            val currentMethodLabel = current.paidPaymentMethodLabel(this)
            if (currentMethodLabel != null) {
                binding.tvCurrentPaymentMethod.text =
                    getString(R.string.detail_payment_method_paid, currentMethodLabel)
                binding.tvCurrentPaymentMethod.visibility = android.view.View.VISIBLE
            } else {
                binding.tvCurrentPaymentMethod.visibility = android.view.View.GONE
            }

            // معلومات المُرسِل (اسم صاحب المحفظة/رقم المحفظة): لنفس السبب أعلاه، لا تُعرض
            // إلا لاشتراك دخل جزء حقيقي منه في الإيراد فعلياً (مدفوع بالكامل أو جزئياً) —
            // بيانات مُرسِل لدفعة لم تحدث بعد ليست معلومة حقيقية بل توقّع، فتُخفى تماماً
            // وهي UNPAID (راجع SubscriptionDisplay.kt وPaymentStatus.kt).
            val senderInfo = if (current.paymentStatus() != PaymentStatus.UNPAID) {
                current.senderInfoDetailLabel(this)
            } else null
            if (senderInfo != null) {
                binding.tvCurrentSenderInfo.text = senderInfo
                binding.tvCurrentSenderInfo.visibility = android.view.View.VISIBLE
            } else {
                binding.tvCurrentSenderInfo.visibility = android.view.View.GONE
            }

            val total = (current.endDate - current.startDate).coerceAtLeast(1L)
            val elapsed = (DateUtils.now() - current.startDate).coerceIn(0L, total)
            binding.progressSubscription.progress = ((elapsed * 100) / total).toInt()
        }

        fullHistory = history
        refreshHistoryList()
    }

    /**
     * البند 30: فلتر حالة الدفع (شرائح) + زر فرز لسجل الاشتراكات — لعضو قديم بعشرات
     * الاشتراكات كان يظهر كل شيء دفعة واحدة بلا أي طريقة لتضييق أو إعادة ترتيب القائمة.
     * كلاهما محلي بحت فوق fullHistory (لا استعلام قاعدة بيانات إضافي)، لأن سجل عضو واحد
     * محدود الحجم عملياً بخلاف قائمة كل الأعضاء (راجع البند 24 لسبب استخدام Paging هناك
     * تحديداً ولماذا لا حاجة له هنا).
     */
    private fun setupHistoryControls() {
        binding.chipGroupHistoryFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            currentHistoryFilter = when (id) {
                R.id.chipHistoryPaid -> PaymentStatus.PAID
                R.id.chipHistoryPartial -> PaymentStatus.PARTIAL
                R.id.chipHistoryUnpaid -> PaymentStatus.UNPAID
                else -> null
            }
            refreshHistoryList()
        }
        binding.btnHistorySort.setOnSingleClickListener { showHistorySortDialog() }
    }

    /** نفس حوار الفرز المستخدم في شاشة قائمة الأعضاء (MembersFragment.showSortDialog) لكن بخيارين فقط. */
    private fun showHistorySortDialog() {
        val labels = arrayOf(
            getString(R.string.detail_history_sort_newest),
            getString(R.string.detail_history_sort_oldest)
        )
        val checkedIndex = if (currentHistorySortNewestFirst) 0 else 1
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.members_sort_action)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                currentHistorySortNewestFirst = which == 0
                refreshHistoryList()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * يطبّق currentHistoryFilter/currentHistorySortNewestFirst على fullHistory ويُحدِّث
     * القائمة المعروضة فعلياً — نفس منطق تحديث الظهور/الفراغ الذي كان في نهاية render()
     * سابقاً، بالإضافة لتمييز حالة "لا نتائج بسبب الفلتر" عن "لا اشتراكات إطلاقاً"
     * (نفس مبدأ البند 16 في شاشة قائمة الأعضاء).
     */
    private fun refreshHistoryList() {
        var list = fullHistory
        currentHistoryFilter?.let { status -> list = list.filter { it.paymentStatus() == status } }
        list = if (currentHistorySortNewestFirst) {
            list.sortedByDescending { it.startDate }
        } else {
            list.sortedBy { it.startDate }
        }

        historyAdapter.submitList(list, currentSettings.currencySymbol)
        // بعد تحديث القائمة نُجبر ScrollView الأب على إعادة قياس كامل الشاشة
        // هذا يحل مشكلة RecyclerView الذي يحسب ارتفاعه مرة واحدة فقط
        // عند أول تحميل ثم لا يُعيد الحساب عند تغيّر عدد الـ items أو أحجامها
        binding.rvHistory.post {
            binding.rvHistory.requestLayout()
            (binding.rvHistory.parent as? android.view.View)?.requestLayout()
        }
        val isEmpty = list.isEmpty()
        binding.rvHistory.visibility = if (isEmpty) android.view.View.GONE else android.view.View.VISIBLE
        binding.tvEmptyHistory.setText(
            if (fullHistory.isNotEmpty() && isEmpty) R.string.detail_history_no_results
            else R.string.detail_history_empty
        )
        binding.tvEmptyHistory.visibility = if (isEmpty) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun typeLabel(type: SubscriptionType): String = when (type) {
        SubscriptionType.DAILY -> getString(R.string.subscription_daily)
        SubscriptionType.WEEKLY -> getString(R.string.subscription_weekly)
        SubscriptionType.MONTHLY -> getString(R.string.subscription_monthly)
        SubscriptionType.CUSTOM -> getString(R.string.subscription_custom)
    }

    private fun callMember() {
        val member = currentMember ?: return
        // toSafePhoneDigits حماية لبيانات قديمة قد تحتوي أرقاماً عربية-هندية (راجع
        // AddEditMemberActivity وWhatsAppHelper.toInternationalNumber لنفس المعالجة) —
        // بدونها قد يفتح تطبيق الاتصال برقم لا يتعرّف عليه صمتاً.
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${member.phone.toSafePhoneDigits()}"))
        startActivity(intent)
    }

    private fun sendWhatsapp(forcedPrefix: String? = null) {
        val member = currentMember ?: return
        // البند 18: نفس تحقّق صيغة الرقم قبل الإرسال المُضاف في MembersFragment.
        if (!WhatsAppHelper.isSendableNumber(member.phone)) {
            Snackbar.make(binding.root, getString(R.string.whatsapp_invalid_phone), Snackbar.LENGTH_LONG).show()
            return
        }
        val sub = currentSubscription
        val candidates = forcedPrefix?.let { listOf(it) } ?: WhatsAppHelper.candidatePrefixes(currentSettings.whatsappPrefix)
        val prefix = candidates.first()
        val number = WhatsAppHelper.toInternationalNumber(member.phone, prefix)

        val typeLabel = sub?.let { typeLabel(it.type) } ?: "—"
        val startLabel = sub?.let { DateUtils.formatDate(it.startDate) } ?: "—"
        val endLabel = sub?.let { DateUtils.formatDate(it.endDate) } ?: "—"
        val priceLabel = CurrencyFormatter.format(sub?.price ?: 0.0, currentSettings.currencySymbol)
        val paidLabel = when (sub?.paymentStatus()) {
            PaymentStatus.PAID -> getString(R.string.status_paid)
            PaymentStatus.PARTIAL -> getString(R.string.payment_status_partial)
            PaymentStatus.UNPAID -> getString(R.string.status_unpaid)
            null -> "—"
        }
        val daysLabel = sub?.let {
            val d = DateUtils.daysRemaining(it.endDate)
            if (d < 0) getString(R.string.days_remaining_expired, -d) else d.toString()
        } ?: "—"

        val debtLine = if (currentStats.totalOutstanding > 0.0)
            getString(R.string.whatsapp_debt_line,
                CurrencyFormatter.format(currentStats.totalOutstanding, currentSettings.currencySymbol))
        else ""

        val message = WhatsAppHelper.buildInvoiceMessage(
            this, currentSettings.gymName, member.name, typeLabel, startLabel, endLabel,
            priceLabel, paidLabel, daysLabel, debtLine
        )
        val result = WhatsAppHelper.openChat(this, number, message)

        // البند 17 و20: نعرض رسالة نجاح واثقة فقط إذا فُتح تطبيق واتساب فعلياً. إذا
        // فُتح المتصفح كحل أخير فقط (واتساب غير مثبت)، نعرض رسالة أقل ثقة توضّح أن
        // الإرسال غير مضمون بدل الإيحاء بنجاح مؤكَّد. الفشل الكامل لا يعرض شيئاً هنا
        // لأن openChat() يعرض Toast "واتساب غير مثبت" بنفسه في تلك الحالة.
        when (result) {
            WhatsAppHelper.SendResult.AppOpened -> {
                Snackbar.make(binding.root, getString(R.string.whatsapp_sending_to, number), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.whatsapp_try_other_prefix)) {
                        sendWhatsapp(forcedPrefix = WhatsAppHelper.otherPrefix(prefix))
                    }
                    .show()
            }
            WhatsAppHelper.SendResult.BrowserFallback -> {
                Snackbar.make(binding.root, getString(R.string.whatsapp_sending_browser_fallback, number), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.whatsapp_try_other_prefix)) {
                        sendWhatsapp(forcedPrefix = WhatsAppHelper.otherPrefix(prefix))
                    }
                    .show()
            }
            WhatsAppHelper.SendResult.Failed -> Unit
        }
    }

    /**
     * نافذة تأكيد تسديد الدَّين المتبقي (سواء كان الاشتراك غير مدفوع بالكامل أو مدفوعاً
     * جزئياً — راجع GymRepository.togglePaidStatus). المبلغ المعروض هنا هو المتبقي
     * الفعلي فقط (remainingAmount)، وليس سعر الاشتراك كاملاً: لاشتراك بدفعة جزئية، جزء من
     * السعر دخل الإيراد فعلاً من قبل، فعرض السعر الكامل هنا كان سيُضلِّل صاحب الجيم بأنه
     * سيُحصِّل مبلغاً أكبر مما تبقّى فعلياً.
     * تستدعي togglePaidStatus() (الموجودة أصلاً في Repository) فقط بعد موافقة صاحب الجيم
     * — لمنع التسديد العرضي بضغطة واحدة.
     */
    private fun confirmSettleDebt(subscription: SubscriptionEntity) {
        val member = currentMember ?: return
        val amount = CurrencyFormatter.format(subscription.remainingAmount(), currentSettings.currencySymbol)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settle_debt_confirm_title))
            .setMessage(getString(R.string.settle_debt_confirm_message, amount, member.name))
            .setNegativeButton(R.string.action_cancel, null)
            // البند 5: بلا مُعرِّف نصّي (null) هنا عمداً — منطق التأكيد الفعلي يُربَط أدناه
            // عبر setOnShowListener على زر الحوار الحقيقي (View)، لا عبر مستمع الزر القياسي
            // لـ AlertDialog، لأن ذاك الأخير (DialogInterface.OnClickListener) ليس View ولا
            // يمكن حمايته بـsetOnSingleClickListener الموجودة أصلاً في Extensions.kt. ضغطة
            // مزدوجة سريعة على "تأكيد" كانت قد تُطلق togglePaidStatus() مرتين لنفس الاشتراك
            // قبل أن يُغلق الحوار فعلياً.
            .setPositiveButton(R.string.action_confirm, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnSingleClickListener {
                lifecycleScope.launch {
                    repository.togglePaidStatus(subscription)
                    toast(getString(R.string.settle_debt_success))
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirmation() {
        val member = currentMember ?: return
        val daysLeft = currentSubscription?.let { DateUtils.daysRemaining(it.endDate).coerceAtLeast(0) } ?: 0
        DeleteConfirmBottomSheet.newInstance(
            memberName = member.name,
            outstandingBalance = currentStats.totalOutstanding,
            currencySymbol = currentSettings.currencySymbol,
            daysRemaining = daysLeft
        ).show(supportFragmentManager, "delete_confirm")
    }

    companion object {
        const val EXTRA_MEMBER_ID = "extra_member_id"
    }
}
