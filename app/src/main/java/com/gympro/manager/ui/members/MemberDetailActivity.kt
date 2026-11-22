package com.gympro.manager.ui.members

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
import com.gympro.manager.utils.Haptic
import com.gympro.manager.utils.WhatsAppHelper
import com.gympro.manager.utils.applySystemBarInsetsPadding
import com.gympro.manager.utils.areAnimationsEnabled
import com.gympro.manager.utils.bindMemberAvatar
import com.gympro.manager.utils.capContentWidth
import com.gympro.manager.utils.haptic
import com.gympro.manager.utils.moneyText
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.toSafePhoneDigits
import com.gympro.manager.utils.toast
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * شاشة تفاصيل العضو (إعادة تصميم 2026). المنطق (الاشتراكات، الدفع، الحذف الناعم، واتساب…)
 * لم يتغيّر؛ تغيّرت طبقة العرض: حلقة تقدّم حول الصورة، بطاقة هيرو بزر إجراء سياقي واحد،
 * إجراءات سريعة، ملخص مالي، ملاحظات، سجل كخط زمني بفلتر منزلق، ومنطقة حذف هادئة.
 *
 * الحركة (تحترم "تقليل الحركة" عبر areAnimationsEnabled): دخول البطاقات بفارق 60ms، رسم الحلقة
 * خلال 800ms، عدّ الأرقام 700ms، ظهور مدخلات الخط الزمني عند دخولها الشاشة، ومؤشر الفلتر
 * المنزلق بتجاوز خفيف.
 */
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

    // سجل الاشتراكات الكامل كما وصل من قاعدة البيانات (غير مُصفّى وغير مُفرز) — منفصل عن
    // القائمة المعروضة حتى يُطبَّق فلتر/فرز جديد فوراً دون انتظار انبعاث جديد.
    private var fullHistory: List<SubscriptionEntity> = emptyList()
    private var currentHistoryFilter: PaymentStatus? = null
    private var currentHistorySortNewestFirst: Boolean = true

    /**
     * يحدّ عدد السجلات المعروضة فعلياً في rvHistory (وليس المُحمَّلة في الذاكرة): عضو بباقة
     * يومية قد يتراكم له مئات السجلات، وrvHistory هنا بلا تدوير حقيقي (داخل ScrollView)،
     * فرسمها كلها دفعة واحدة يسبب تجمّداً. يُعاد للقيمة الابتدائية عند تغيّر الفلتر/الفرز.
     */
    private var historyVisibleLimit: Int = HISTORY_PAGE_SIZE

    private var firstRenderDone = false
    private var skeletonAnimator: ObjectAnimator? = null

    private class FilterChipRef(
        val container: View,
        val label: TextView,
        val count: TextView,
        val status: PaymentStatus?
    )
    private lateinit var filterChips: List<FilterChipRef>
    private lateinit var selectedChip: FilterChipRef
    private var indicatorAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initScreen()
        } catch (t: Throwable) {
            showFatal(t)
        }
    }

    /** بدل أن يُغلق التطبيق: يعرض نص الخطأ على الشاشة ليُنسخ ويُرسل للمطوّر. */
    private fun showFatal(t: Throwable) {
        val tv = android.widget.TextView(this).apply {
            text = "خطأ في شاشة العضو (صوّر هذه الشاشة أو انسخ النص وأرسله):\n\n" +
                android.util.Log.getStackTraceString(t).take(4000)
            setTextIsSelectable(true)
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(32, 96, 32, 32)
        }
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(tv)
        })
    }

    private fun initScreen() {
        binding = ActivityMemberDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // شاشة داكنة دائماً (نفس الرئيسية والأعضاء): شريطا الحالة والتنقّل بلون الخلفية وأيقونات فاتحة.
        val barColor = ContextCompat.getColor(this, R.color.home_bg)
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        binding.detailContent.applySystemBarInsetsPadding()
        capContentWidth(binding.detailContent)

        memberId = intent.getLongExtra(EXTRA_MEMBER_ID, -1L)
        if (memberId == -1L) { finish(); return }

        binding.btnBack.setOnClickListener { finish() }
        // LinearLayoutManager داخل ScrollView يقيس فقط العناصر المرئية عند أول تحميل؛
        // الحل: LayoutManager مخصص يُجبر على قياس كل العناصر دفعة واحدة.
        binding.rvHistory.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically() = false
            override fun isAutoMeasureEnabled() = true
        }
        binding.rvHistory.adapter = historyAdapter
        // بلا ItemAnimator: الظهور التدريجي تتحكّم به revealVisibleHistory وحدها، وإلا ظهرت
        // العناصر خارج الشاشة بحركة الإضافة الافتراضية قبل أن تُكشف أثناء التمرير.
        binding.rvHistory.itemAnimator = null
        binding.scrollView.setOnScrollChangeListener { _, _, _, _, _ -> revealVisibleHistory() }

        prepareEntrance()
        startSkeletonPulse()
        setupActions()
        setupHistoryControls()
        setupTabs()
        setupFragmentResultListeners()
        observeData()
    }

    /**
     * تُسجَّل هنا بدلاً من lambda داخل DeleteConfirmBottomSheet، لأن الـ lambda لا تُستعاد إذا
     * أعاد FragmentManager إنشاء الحوار بعد قتل العملية. Fragment Result API آمن للاستعادة.
     */
    private fun setupFragmentResultListeners() {
        supportFragmentManager.setFragmentResultListener(
            DeleteConfirmBottomSheet.REQUEST_KEY_DELETE_CONFIRM,
            this
        ) { _, bundle ->
            if (bundle.getBoolean(DeleteConfirmBottomSheet.RESULT_CONFIRMED)) {
                lifecycleScope.launch {
                    repository.softDeleteMember(memberId)
                    binding.root.haptic(Haptic.CONFIRM)
                    showDeleteUndoSnackbar()
                }
            }
        }
    }

    /**
     * الحذف Soft-delete (نقل للأرشيف قابل للاستعادة خلال 30 يوماً — ArchivePurgeWorker).
     * Snackbar بزر "تراجع" يُبقي الشاشة مفتوحة؛ إن انتهت المدة دون تراجع تُغلَق الشاشة.
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
        binding.btnSms.setOnSingleClickListener { sendSms() }
        binding.btnShare.setOnSingleClickListener { shareMember() }
        binding.btnDelete.setOnSingleClickListener { showDeleteConfirmation() }
        binding.btnNotesEdit.setOnSingleClickListener { openEdit() }
        binding.btnMore.setOnClickListener { showMoreMenu() }
    }

    /** قائمة "المزيد": تعديل / حذف. */
    private fun showMoreMenu() {
        PopupMenu(this, binding.btnMore).apply {
            menu.add(Menu.NONE, MENU_EDIT, 0, R.string.detail_action_edit)
            menu.add(Menu.NONE, MENU_DELETE, 1, R.string.detail_action_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> openEdit()
                    MENU_DELETE -> showDeleteConfirmation()
                }
                true
            }
            show()
        }
    }

    private fun openEdit() {
        startActivity(
            Intent(this, AddEditMemberActivity::class.java)
                .putExtra(AddEditMemberActivity.EXTRA_MEMBER_ID, memberId)
        )
    }

    private fun openRenew() {
        binding.btnPrimaryAction.isEnabled = false
        startActivity(
            Intent(this, AddEditMemberActivity::class.java)
                .putExtra(AddEditMemberActivity.EXTRA_MEMBER_ID, memberId)
                .putExtra(AddEditMemberActivity.EXTRA_RENEW, true)
        )
    }

    override fun onResume() {
        super.onResume()
        // يُعاد تفعيله هنا لأن العودة من AddEditMemberActivity بزر الرجوع تُعيد استخدام نفس
        // النسخة دون المرور بـ onCreate.
        binding.btnPrimaryAction.isEnabled = true
    }

    override fun onDestroy() {
        moneyAnimator?.cancel()
        skeletonAnimator?.cancel()
        indicatorAnimator?.cancel()
        super.onDestroy()
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
                        try {
                            render(member, history, currentSubscription, currentStats, currentSettings)
                        } catch (t: Throwable) {
                            showFatal(t)
                        }
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
        val first = !firstRenderDone
        val state = stateOf(current)
        bindPass(member, current, state, animate = first)
        bindStrip(current, state, stats, settings, animate = first)
        bindNotes(member)

        fullHistory = history
        binding.tvHistorySummary.text = getString(
            R.string.detail_history_summary,
            history.size.toString(),
            CurrencyFormatter.format(stats.totalPaid, settings.currencySymbol)
        )
        updateChipCounts()
        refreshHistoryList()

        if (first) {
            firstRenderDone = true
            stopSkeleton()
            playEntrance()
        }
    }

    // ───────────────────────── بطاقة العضوية ─────────────────────────

    /** حالة الاشتراك الحالي: تحدّد تدرّج البطاقة ونص الأيام والشريط السياقي. */
    private enum class PassState { ACTIVE, SOON, EXPIRED, FUTURE, NONE }

    private fun stateOf(current: SubscriptionEntity?): PassState {
        if (current == null) return PassState.NONE
        if (DateUtils.isFuture(current.startDate)) return PassState.FUTURE
        val days = DateUtils.daysRemaining(current.endDate)
        return when {
            days < 0 -> PassState.EXPIRED
            days <= 3 -> PassState.SOON // يشمل "ينتهي اليوم" (days == 0)
            else -> PassState.ACTIVE
        }
    }

    private fun bindPass(member: MemberEntity, current: SubscriptionEntity?, state: PassState, animate: Boolean) {
        binding.tvInitial.bindMemberAvatar(member.name, member.photoPath, 312)
        binding.tvName.text = member.name
        binding.tvPhone.text = member.phone
        binding.tvMemberSince.text = getString(R.string.detail_member_since, DateUtils.formatDate(member.createdAt))

        // تدرّج البطاقة يتبع الحالة: هوية التطبيق (ساري) · دفء كهرماني (قريب الانتهاء) · خافت (منتهي) · لافندر (لم يبدأ).
        binding.passCard.setBackgroundResource(
            when (state) {
                PassState.ACTIVE -> R.drawable.bg_detail_pass_active
                PassState.SOON -> R.drawable.bg_detail_pass_soon
                PassState.EXPIRED -> R.drawable.bg_detail_pass_expired
                PassState.FUTURE, PassState.NONE -> R.drawable.bg_detail_pass_future
            }
        )

        if (current == null) {
            // عضو بلا أي اشتراك.
            binding.tvPlanLabel.text = getString(R.string.detail_no_subscription)
            binding.chipPayment.visibility = View.GONE
            binding.blockExpires.visibility = View.INVISIBLE
            setDaysText(number = null, unit = getString(R.string.detail_no_subscription))
            binding.progressSubscription.progress = 0
            binding.tvCurrentSenderInfo.visibility = View.GONE
            return
        }

        // سطر الخطة: "عضوية شهرية · بال باي" (طريقة الدفع لا تظهر إلا بعد الدفع الفعلي).
        val planBase = getString(
            when (current.type) {
                SubscriptionType.DAILY -> R.string.detail_pass_daily
                SubscriptionType.WEEKLY -> R.string.detail_pass_weekly
                SubscriptionType.MONTHLY -> R.string.detail_pass_monthly
                SubscriptionType.CUSTOM -> R.string.detail_pass_custom
            }
        )
        val method = current.paidPaymentMethodLabel(this)
        binding.tvPlanLabel.text =
            if (method != null) getString(R.string.detail_pass_with_method, planBase, method) else planBase

        // شارة حالة الدفع: مدفوع (نعناعي) / جزئي (لافندر) / غير مدفوع (مرجاني).
        val status = current.paymentStatus()
        val (label, colorRes) = when (status) {
            PaymentStatus.PAID -> R.string.status_paid to R.color.home_mint
            PaymentStatus.PARTIAL -> R.string.payment_status_partial to R.color.home_lav
            PaymentStatus.UNPAID -> R.string.status_unpaid to R.color.home_coral
        }
        binding.chipPayment.visibility = View.VISIBLE
        binding.tvCurrentPaidBadge.text = getString(label)
        // نص محايد + نقطة ملوّنة (مدفوع/جزئي/غير مدفوع): يتجنّب تنافر النعناعي مع تدرّج البطاقة.
        binding.tvCurrentPaidBadge.compoundDrawableTintList = ColorStateList.valueOf(getColor(colorRes))

        binding.blockExpires.visibility = View.VISIBLE
        binding.tvCurrentDates.text = DateUtils.formatDate(current.endDate)

        val days = DateUtils.daysRemaining(current.endDate)
        when {
            state == PassState.FUTURE -> setDaysText(null, getString(R.string.detail_pass_not_started))
            days < 0 -> setDaysText((-days).toString(), resources.getQuantityString(R.plurals.detail_unit_expired, -days))
            days == 0 -> setDaysText(null, getString(R.string.days_remaining_today))
            else -> setDaysText(days.toString(), resources.getQuantityString(R.plurals.detail_unit_left, days))
        }

        // الشريط = المنقضي من مدة الاشتراك.
        val total = (current.endDate - current.startDate).coerceAtLeast(1L)
        val elapsed = (DateUtils.now() - current.startDate).coerceIn(0L, total)
        val share = if (state == PassState.EXPIRED) 100 else ((elapsed.toFloat() / total) * 100).toInt()
        progressTo(share, animate)

        // معلومات المُرسِل: فقط لاشتراك دخل جزء حقيقي منه في الإيراد (ليس UNPAID).
        val senderInfo = if (status != PaymentStatus.UNPAID) current.senderInfoDetailLabel(this) else null
        if (senderInfo != null) {
            binding.tvCurrentSenderInfo.text = senderInfo
            binding.tvCurrentSenderInfo.visibility = View.VISIBLE
        } else {
            binding.tvCurrentSenderInfo.visibility = View.GONE
        }
    }

    /** رقم الأيام الضخم + الوحدة؛ عند غياب الرقم تكبر الوحدة لتصبح هي العنوان. */
    private fun setDaysText(number: String?, unit: String) {
        binding.tvDaysNumber.visibility = if (number != null) View.VISIBLE else View.GONE
        binding.tvDaysNumber.text = number.orEmpty()
        binding.tvDaysUnit.text = unit
        binding.tvDaysUnit.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP, if (number != null) 14f else 22f
        )
        binding.tvDaysUnit.setTextColor(getColor(R.color.home_ink))
        val lp = binding.tvDaysUnit.layoutParams as android.widget.LinearLayout.LayoutParams
        lp.bottomMargin = if (number != null) (8 * resources.displayMetrics.density).toInt() else 0
        binding.tvDaysUnit.layoutParams = lp
    }

    private fun progressTo(target: Int, animate: Boolean) {
        val bar = binding.progressSubscription
        if (animate && areAnimationsEnabled()) {
            bar.progress = 0
            ObjectAnimator.ofInt(bar, "progress", 0, target).apply {
                duration = 800L
                interpolator = DecelerateInterpolator(1.6f)
                start()
            }
        } else {
            bar.progress = target
        }
    }

    // ───────────────────────── الشريط السياقي: تسديد / تجديد ─────────────────────────

    private var moneyAnimator: ValueAnimator? = null

    private fun bindStrip(
        current: SubscriptionEntity?,
        state: PassState,
        stats: MemberStats,
        settings: GymSettingsEntity,
        animate: Boolean
    ) {
        val strip = binding.cardStrip
        val btn = binding.btnPrimaryAction
        val debt = stats.totalOutstanding > 0.0
        moneyAnimator?.cancel()

        when {
            // 1) يوجد مبلغ غير مدفوع: "المتبقي غير المدفوع" فوق المبلغ المرجاني + زر تسديد.
            debt -> {
                strip.setBackgroundResource(R.drawable.bg_detail_strip_coral)
                styleStripIcon(R.drawable.ic_pd_alert, R.color.home_coral)
                binding.tvStripCaption.visibility = View.VISIBLE
                binding.tvStripCaption.setText(R.string.detail_strip_outstanding)
                styleStripValue(R.color.home_coral, 26f, extraBold = true)
                val coralSoft = ColorUtils.setAlphaComponent(getColor(R.color.home_coral), 0xB3)
                val symbol = settings.currencySymbol
                if (animate && areAnimationsEnabled()) {
                    moneyAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 700L
                        interpolator = DecelerateInterpolator(1.8f)
                        addUpdateListener {
                            binding.tvStripValue.text = moneyText(stats.totalOutstanding * (it.animatedValue as Float), symbol, coralSoft)
                        }
                        doOnEnd { binding.tvStripValue.text = moneyText(stats.totalOutstanding, symbol, coralSoft) }
                        start()
                    }
                } else {
                    binding.tvStripValue.text = moneyText(stats.totalOutstanding, symbol, coralSoft)
                }
                btn.setBackgroundResource(R.drawable.bg_home_cta)
                btn.setText(R.string.action_settle_debt)
                btn.setOnSingleClickListener {
                    if (current != null && current.paymentStatus() != PaymentStatus.PAID) confirmSettleDebt(current)
                    else showUnpaidHistory() // الدَّين على اشتراك أقدم: نعرضه في السجل لتسديده.
                }
            }
            // 2) بقية الحالات: أيقونة + جملة واحدة في سطر واحد (بلا عنوان فرعي مكرّر) + زر.
            else -> {
                binding.tvStripCaption.visibility = View.GONE
                val urgent = state == PassState.SOON || state == PassState.EXPIRED || state == PassState.NONE
                when (state) {
                    PassState.SOON -> {
                        val days = DateUtils.daysRemaining(current!!.endDate)
                        strip.setBackgroundResource(R.drawable.bg_detail_strip_amber)
                        styleStripIcon(R.drawable.ic_pd_alert, R.color.home_amber)
                        styleStripValue(R.color.home_amber, 16f)
                        binding.tvStripValue.text = if (days == 0) getString(R.string.days_remaining_today)
                        else resources.getQuantityString(R.plurals.detail_ends_in, days, days)
                    }
                    PassState.EXPIRED -> {
                        val ago = -DateUtils.daysRemaining(current!!.endDate)
                        strip.setBackgroundResource(R.drawable.bg_detail_strip_coral)
                        styleStripIcon(R.drawable.ic_pd_alert, R.color.home_coral)
                        styleStripValue(R.color.home_coral, 16f)
                        binding.tvStripValue.text = resources.getQuantityString(R.plurals.detail_expired_since, ago, ago)
                    }
                    PassState.NONE -> {
                        strip.setBackgroundResource(R.drawable.bg_detail_strip_coral)
                        styleStripIcon(R.drawable.ic_pd_calendar, R.color.home_lav)
                        styleStripValue(R.color.home_ink, 16f)
                        binding.tvStripValue.setText(R.string.detail_strip_none)
                    }
                    PassState.FUTURE -> {
                        strip.setBackgroundResource(R.drawable.bg_detail_strip_neutral)
                        styleStripIcon(R.drawable.ic_pd_calendar, R.color.home_lav)
                        styleStripValue(R.color.home_ink, 16f)
                        binding.tvStripValue.setText(R.string.detail_pass_not_started)
                    }
                    PassState.ACTIVE -> {
                        // ساري ومدفوع: الأخضر محصور في أيقونة صغيرة، والنص محايد هادئ.
                        strip.setBackgroundResource(R.drawable.bg_detail_strip_neutral)
                        styleStripIcon(R.drawable.ic_pd_check, R.color.home_mint)
                        styleStripValue(R.color.home_ink, 16f)
                        binding.tvStripValue.setText(R.string.detail_strip_ok_paid)
                    }
                }
                // تجديد بتدرّج عند الإلحاح، وبزر زجاجي هادئ عندما يكون كل شيء سليماً.
                btn.setBackgroundResource(if (urgent) R.drawable.bg_home_cta else R.drawable.bg_detail_btn_glass)
                btn.setText(R.string.detail_action_renew_short)
                btn.setOnSingleClickListener { openRenew() }
            }
        }
    }

    private fun styleStripIcon(iconRes: Int, colorRes: Int) {
        val color = getColor(colorRes)
        binding.ivStripIcon.setImageResource(iconRes)
        binding.ivStripIcon.imageTintList = ColorStateList.valueOf(color)
        binding.ivStripIcon.backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x29))
    }

    private fun styleStripValue(colorRes: Int, sizeSp: Float, extraBold: Boolean = false) {
        binding.tvStripValue.setTextColor(getColor(colorRes))
        binding.tvStripValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp)
        binding.tvStripValue.typeface = androidx.core.content.res.ResourcesCompat.getFont(
            this, if (extraBold) R.font.cairo_extrabold else R.font.cairo_bold
        )
    }

    /** ينتقل إلى تبويب السجل ويفلتر على "غير مدفوع" ثم يمرّر إليه. */
    private fun showUnpaidHistory() {
        selectTab(notes = false, animate = false)
        val ref = filterChips.first { it.status == PaymentStatus.UNPAID }
        if (ref !== selectedChip) selectHistoryFilter(ref)
        binding.scrollView.post {
            binding.scrollView.smoothScrollTo(0, binding.tabs.top)
        }
    }

    // ───────────────────────── التبويبان ─────────────────────────

    private var notesTabSelected = false

    private fun setupTabs() {
        binding.tabHistory.setOnClickListener { if (notesTabSelected) selectTab(notes = false, animate = true) }
        binding.tabNotes.setOnClickListener { if (!notesTabSelected) selectTab(notes = true, animate = true) }
    }

    private fun selectTab(notes: Boolean, animate: Boolean) {
        notesTabSelected = notes
        val ink = getColor(R.color.home_ink)
        val mute = getColor(R.color.home_mute)
        binding.tabHistory.setBackgroundResource(if (notes) R.drawable.bg_detail_tab else R.drawable.bg_home_cta)
        binding.tabNotes.setBackgroundResource(if (notes) R.drawable.bg_home_cta else R.drawable.bg_detail_tab)
        binding.tabHistory.setTextColor(if (notes) mute else ink)
        binding.tabNotes.setTextColor(if (notes) ink else mute)

        val show = if (notes) binding.sectionNotes else binding.sectionHistory
        val hide = if (notes) binding.sectionHistory else binding.sectionNotes
        if (!animate || !areAnimationsEnabled()) {
            hide.visibility = View.GONE
            show.alpha = 1f
            show.visibility = View.VISIBLE
        } else {
            hide.animate().alpha(0f).setDuration(120L).withEndAction {
                hide.visibility = View.GONE
                hide.alpha = 1f
                show.alpha = 0f
                show.visibility = View.VISIBLE
                show.animate().alpha(1f).setDuration(160L).start()
            }.start()
        }
        if (!notes) binding.rvHistory.postDelayed({ revealVisibleHistory() }, 200L)
    }

    // ───────────────────────── الملاحظات ─────────────────────────

    private fun bindNotes(member: MemberEntity) {
        val notes = member.notes.trim()
        val empty = notes.isEmpty()
        binding.tvNotes.text = if (empty) getString(R.string.detail_notes_empty) else notes
        binding.tvNotes.setTextColor(getColor(if (empty) R.color.home_mute else R.color.home_ink))
        // الحالة الفارغة: إطار متقطع هادئ بلا تعبئة.
        binding.cardNotes.setBackgroundResource(if (empty) R.drawable.bg_detail_empty else R.drawable.bg_detail_card)
    }


    // ───────────────────────── فلتر السجل + الخط الزمني ─────────────────────────

    private fun setupHistoryControls() {
        filterChips = listOf(
            FilterChipRef(binding.chipHistoryAll, binding.tvChipAllLabel, binding.tvChipAllCount, null),
            FilterChipRef(binding.chipHistoryPaid, binding.tvChipPaidLabel, binding.tvChipPaidCount, PaymentStatus.PAID),
            FilterChipRef(binding.chipHistoryPartial, binding.tvChipPartialLabel, binding.tvChipPartialCount, PaymentStatus.PARTIAL),
            FilterChipRef(binding.chipHistoryUnpaid, binding.tvChipUnpaidLabel, binding.tvChipUnpaidCount, PaymentStatus.UNPAID)
        )
        selectedChip = filterChips.first()
        filterChips.forEach { ref -> ref.container.setOnClickListener { selectHistoryFilter(ref) } }
        applyChipVisuals()
        // الشريحة قد تظهر لاحقاً (شرائح الفلتر مخفية حتى 3 اشتراكات) أو تتغيّر أرقامها فيتغيّر عرضها:
        // أعد وضع المؤشر عند كل تغيّر في تخطيط الصف.
        binding.chipRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (indicatorAnimator?.isRunning != true) moveIndicator(selectedChip, animate = false)
        }

        binding.btnHistorySort.setOnSingleClickListener { showHistorySortDialog() }
        binding.btnHistoryLoadMore.setOnSingleClickListener {
            historyVisibleLimit += HISTORY_PAGE_SIZE
            refreshHistoryList()
        }
    }

    private fun selectHistoryFilter(ref: FilterChipRef) {
        if (ref === selectedChip) return
        binding.chipRow.haptic(Haptic.TICK)
        selectedChip = ref
        currentHistoryFilter = ref.status
        // فلتر جديد = عرض مبدئي جديد بنفس الحد الافتراضي.
        historyVisibleLimit = HISTORY_PAGE_SIZE
        applyChipVisuals()
        moveIndicator(ref, animate = true)
        refreshHistoryList()
    }

    private fun applyChipVisuals() {
        val ink = getColor(R.color.home_ink)
        val mute = getColor(R.color.home_mute)
        filterChips.forEach { ref ->
            val selected = ref === selectedChip
            ref.label.setTextColor(if (selected) ink else mute)
            ref.count.setTextColor(if (selected) ink else mute)
            // المختارة بلا خلفية: يظهر خلفها المؤشر المتدرّج المنزلق.
            ref.container.background =
                if (selected) null else ContextCompat.getDrawable(this, R.drawable.bg_detail_chip)
            ref.container.isSelected = selected
        }
    }

    /** يُحرّك المؤشر المتدرّج إلى الشريحة المختارة: انزلاق 350ms بتجاوز خفيف. */
    private fun moveIndicator(ref: FilterChipRef, animate: Boolean) {
        val indicator = binding.chipIndicator
        val lp = indicator.layoutParams
        val targetW = ref.container.width
        val targetX = ref.container.left.toFloat()
        indicatorAnimator?.cancel()
        if (!animate || !areAnimationsEnabled() || lp.width <= 0) {
            // الحماية من إعادة التخطيط اللانهائية: لا نعيد ضبط العرض إن لم يتغيّر.
            if (lp.width != targetW) {
                lp.width = targetW
                indicator.layoutParams = lp
            }
            indicator.translationX = targetX
            return
        }
        val startW = lp.width
        val startX = indicator.translationX
        indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350L
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.width = (startW + (targetW - startW) * f).toInt().coerceAtLeast(0)
                indicator.layoutParams = lp
                indicator.translationX = startX + (targetX - startX) * f
            }
            start()
        }
    }

    private fun updateChipCounts() {
        binding.tvChipAllCount.text = fullHistory.size.toString()
        binding.tvChipPaidCount.text = fullHistory.count { it.paymentStatus() == PaymentStatus.PAID }.toString()
        binding.tvChipPartialCount.text = fullHistory.count { it.paymentStatus() == PaymentStatus.PARTIAL }.toString()
        binding.tvChipUnpaidCount.text = fullHistory.count { it.paymentStatus() == PaymentStatus.UNPAID }.toString()
        // تغيّر عرض الشرائح بتغيّر الأرقام → أعد وضع المؤشر دون حركة.
        binding.chipRow.post { moveIndicator(selectedChip, animate = false) }
    }

    /** حوار الفرز (الأحدث/الأقدم أولاً) بنفس الهوية الداكنة. */
    private fun showHistorySortDialog() {
        val labels = arrayOf(
            getString(R.string.detail_history_sort_newest),
            getString(R.string.detail_history_sort_oldest)
        )
        val checkedIndex = if (currentHistorySortNewestFirst) 0 else 1
        MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_GymManager_DialogDark)
            .setTitle(R.string.members_sort_action)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                currentHistorySortNewestFirst = which == 0
                historyVisibleLimit = HISTORY_PAGE_SIZE
                refreshHistoryList()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * يطبّق الفلتر والفرز الحاليين على fullHistory ويُحدِّث القائمة المعروضة، ويميّز حالة
     * "لا نتائج بسبب الفلتر" عن "لا اشتراكات إطلاقاً".
     */
    private fun refreshHistoryList() {
        var list = fullHistory
        currentHistoryFilter?.let { status -> list = list.filter { it.paymentStatus() == status } }
        list = if (currentHistorySortNewestFirst) list.sortedByDescending { it.startDate }
        else list.sortedBy { it.startDate }

        // نرسم فقط أول historyVisibleLimit من القائمة المُفلترة/المُفرزة (راجع تعليقه أعلاه).
        val visibleCount = list.size.coerceAtMost(historyVisibleLimit)
        val visibleList = list.take(visibleCount)
        val remaining = list.size - visibleCount

        historyAdapter.submitList(visibleList, currentSettings.currencySymbol, Runnable {
            // بعد التحديث نُجبر ScrollView الأب على إعادة قياس كامل الشاشة (RecyclerView داخل
            // ScrollView يحسب ارتفاعه مرة واحدة)، ثم نكشف العناصر الظاهرة تدريجياً.
            binding.rvHistory.post {
                binding.rvHistory.requestLayout()
                (binding.rvHistory.parent as? View)?.requestLayout()
                revealVisibleHistory()
                binding.rvHistory.postDelayed({ revealVisibleHistory() }, 350L)
            }
        })

        val isEmpty = visibleList.isEmpty()
        binding.rvHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.tvEmptyHistory.setText(
            if (fullHistory.isNotEmpty() && isEmpty) R.string.detail_history_no_results
            else R.string.detail_history_empty
        )
        binding.tvEmptyHistoryHint.visibility =
            if (fullHistory.isEmpty()) View.VISIBLE else View.GONE
        binding.cardEmptyHistory.visibility = if (isEmpty) View.VISIBLE else View.GONE

        // شرائح الفلتر لا تظهر إلا عند وجود 3 اشتراكات فأكثر (أو فلتر مفعّل) كي يبقى السجل القصير نظيفاً.
        binding.chipRowScroll.visibility =
            if (fullHistory.size >= 3 || currentHistoryFilter != null) View.VISIBLE else View.GONE

        binding.btnHistoryLoadMore.visibility = if (remaining > 0) View.VISIBLE else View.GONE
        if (remaining > 0) {
            binding.btnHistoryLoadMore.text =
                getString(R.string.detail_history_load_more, remaining.coerceAtMost(HISTORY_PAGE_SIZE))
        }
    }

    // ───────────────────────── الحركة ─────────────────────────

    private fun contentBlocks(): List<View> = listOf(
        binding.passCard, binding.cardStrip, binding.rowActions,
        binding.tabs, binding.tabContent, binding.dangerZone
    )

    /** يُخفي البلوكات قبل أول عرض حتى لا يظهر وميض قبل حركة الدخول. */
    private fun prepareEntrance() {
        if (!areAnimationsEnabled()) return
        contentBlocks().forEach { it.alpha = 0f }
    }

    /** دخول البطاقات: ترتفع 16dp مع ظهور تدريجي، بفارق 60ms بين كل بطاقة وأخرى. */
    private fun playEntrance() {
        val blocks = contentBlocks()
        if (!areAnimationsEnabled()) {
            blocks.forEach { it.alpha = 1f; it.translationY = 0f }
            return
        }
        val rise = 16f * resources.displayMetrics.density
        blocks.forEachIndexed { i, v ->
            v.translationY = rise
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 60L).setDuration(400L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
    }

    /** مدخلات الخط الزمني: تظهر بتلاشي وإزاحة عند دخولها الشاشة (مرة واحدة لكل مدخل). */
    private fun revealVisibleHistory() {
        if (!areAnimationsEnabled()) return
        val rv = binding.rvHistory
        val rect = Rect()
        var order = 0
        for (i in 0 until rv.childCount) {
            val v = rv.getChildAt(i)
            val id = v.getTag(R.id.tag_sub_id) as? Long ?: continue
            if (historyAdapter.isRevealed(id)) continue
            if (!v.getGlobalVisibleRect(rect) || rect.height() < v.height * 0.2f) continue
            historyAdapter.markRevealed(id)
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(order * 60L).setDuration(380L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
            order++
        }
    }

    private fun startSkeletonPulse() {
        if (!areAnimationsEnabled()) return
        skeletonAnimator = ObjectAnimator.ofFloat(binding.layoutHistorySkeleton, View.ALPHA, 0.5f, 1f).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopSkeleton() {
        skeletonAnimator?.cancel()
        binding.layoutHistorySkeleton.visibility = View.GONE
    }

    // ───────────────────────── إجراءات ─────────────────────────

    private fun typeLabel(type: SubscriptionType): String = when (type) {
        SubscriptionType.DAILY -> getString(R.string.subscription_daily)
        SubscriptionType.WEEKLY -> getString(R.string.subscription_weekly)
        SubscriptionType.MONTHLY -> getString(R.string.subscription_monthly)
        SubscriptionType.CUSTOM -> getString(R.string.subscription_custom)
    }

    private fun callMember() {
        val member = currentMember ?: return
        // toSafePhoneDigits حماية لبيانات قديمة قد تحتوي أرقاماً عربية-هندية.
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${member.phone.toSafePhoneDigits()}")))
    }

    /** نص الفاتورة/الملخص المشترك بين واتساب والرسائل والمشاركة. */
    private fun invoiceMessage(): String? {
        val member = currentMember ?: return null
        val sub = currentSubscription
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
        return WhatsAppHelper.buildInvoiceMessage(
            this, currentSettings.gymName, member.name, typeLabel, startLabel, endLabel,
            priceLabel, paidLabel, daysLabel, debtLine
        )
    }

    private fun sendSms() {
        val member = currentMember ?: return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${member.phone.toSafePhoneDigits()}"))
            .putExtra("sms_body", invoiceMessage().orEmpty())
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.detail_no_app, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun shareMember() {
        val message = invoiceMessage() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        startActivity(Intent.createChooser(send, null))
    }

    private fun sendWhatsapp(forcedPrefix: String? = null) {
        val member = currentMember ?: return
        // البند 18: تحقّق صيغة الرقم قبل الإرسال.
        if (!WhatsAppHelper.isSendableNumber(member.phone)) {
            Snackbar.make(binding.root, getString(R.string.whatsapp_invalid_phone), Snackbar.LENGTH_LONG).show()
            return
        }
        val candidates = forcedPrefix?.let { listOf(it) } ?: WhatsAppHelper.candidatePrefixes(currentSettings.whatsappPrefix)
        val prefix = candidates.first()
        val number = WhatsAppHelper.toInternationalNumber(member.phone, prefix)
        val message = invoiceMessage().orEmpty()
        val result = WhatsAppHelper.openChat(this, number, message)

        // البند 17 و20: رسالة نجاح واثقة فقط إذا فُتح واتساب فعلياً؛ المتصفح كحل أخير يعرض
        // رسالة أقل ثقة. الفشل الكامل يعرض openChat() Toast بنفسه.
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
     * نافذة تأكيد تسديد الدَّين المتبقي (غير مدفوع بالكامل أو جزئي — GymRepository.togglePaidStatus).
     * المبلغ المعروض هو المتبقي الفعلي (remainingAmount) لا السعر الكامل، ولا يُستدعى
     * togglePaidStatus() إلا بعد موافقة صاحب الجيم لمنع التسديد العرضي.
     */
    private fun confirmSettleDebt(subscription: SubscriptionEntity) {
        val member = currentMember ?: return
        val amount = CurrencyFormatter.format(subscription.remainingAmount(), currentSettings.currencySymbol)
        val dialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_GymManager_DialogDark)
            .setTitle(getString(R.string.settle_debt_confirm_title))
            .setMessage(getString(R.string.settle_debt_confirm_message, amount, member.name))
            .setNegativeButton(R.string.action_cancel, null)
            // بلا مُعرِّف نصّي هنا عمداً: التأكيد الفعلي يُربط في setOnShowListener على زر الحوار
            // (View) ليُحمى بـ setOnSingleClickListener ضد الضغط المزدوج (البند 5).
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

        private const val MENU_EDIT = 1
        private const val MENU_DELETE = 2

        /** نفس pageSize لقائمة الأعضاء (30) في GymRepository — للاتساق فقط. */
        private const val HISTORY_PAGE_SIZE = 30
    }
}
