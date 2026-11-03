package com.gympro.manager.ui.dashboard

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.databinding.FragmentDashboardBinding
import com.gympro.manager.model.MemberFilter
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.model.PaymentStatus
import com.gympro.manager.ui.main.MainActivity
import com.gympro.manager.ui.members.MemberDetailActivity
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.Haptic
import com.gympro.manager.utils.applySystemBarInsetsPadding
import com.gympro.manager.utils.haptic
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory((requireActivity().application as GymApplication).repository)
    }

    private lateinit var expiringAdapter: DashboardMemberAdapter
    private lateinit var unpaidAdapter: DashboardUnpaidAdapter
    private var skeletonPulse: ObjectAnimator? = null
    private val bars = mutableListOf<View>()

    // حركات خفيفة: عدّ تصاعدي للمبلغ، نمو الأعمدة، ظهور متدرّج للأقسام، ونبض نقطة الجرس.
    private var shownAmount = 0.0
    private var amountAnimator: ValueAnimator? = null
    private var dotPulse: ObjectAnimator? = null
    private var entrancePending = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.homeContent.applySystemBarInsetsPadding(top = true, bottom = false)
        setupGreeting()
        setupBars()
        setupPeriodSwitch()
        setupBell()
        setupLists()
        setupStatCardClicks()
        observeState()
    }

    /**
     * البطاقات الثلاث (الأعضاء/غير المدفوعين/القريبين من الانتهاء) كانت تبدو تفاعلية
     * (ارتفاع + أيقونة) لكنها بلا أي إجراء عند النقر. الآن تنقل لقائمة الأعضاء مفلترة
     * مسبقاً بدل إجبار المستخدم على الذهاب لتبويب الأعضاء وتطبيق الفلتر يدوياً.
     */
    private fun setupStatCardClicks() {
        binding.cardActiveMembers.setOnSingleClickListener {
            (activity as? MainActivity)?.openMembersFiltered(MemberFilter.ACTIVE)
        }
        binding.cardUnpaid.setOnSingleClickListener {
            (activity as? MainActivity)?.openMembersFiltered(MemberFilter.UNPAID)
        }
        binding.cardExpiringSoon.setOnSingleClickListener {
            (activity as? MainActivity)?.openMembersFiltered(MemberFilter.EXPIRING_SOON)
        }
    }

    /**
     * تحية مبنية على وقت اليوم الحالي (ساعة الجهاز). سابقاً كانت أي ساعة خارج نطاق
     * "الصباح" (5-16) تُصنَّف "مساء الخير" — بما في ذلك 0 إلى 4 فجراً، وهي ليست مساءً
     * منطقياً بل ساعات متأخرة من الليل/فجر مبكر. الآن تُفرَد كنطاق ثالث بتحية محايدة
     * لا ترتبط بوقت مساء أو صباح محدَّد، بدل قول "مساء الخير" لصاحب نادٍ يفتح التطبيق
     * الساعة 3 فجراً مثلاً.
     */
    private fun setupGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        binding.tvGreeting.text = when (hour) {
            in 5..16 -> getString(R.string.dashboard_greeting_morning)
            in 17..23 -> getString(R.string.dashboard_greeting_evening)
            else -> getString(R.string.dashboard_greeting_night)
        }
    }

    /** أعمدة الرسم المصغّر السبعة (آخر 7 أيام): الأقدم أولاً، واليوم (الأخير) أبيض ناصع. */
    private fun setupBars() {
        val density = resources.displayMetrics.density
        val gap = (5 * density).toInt()
        binding.barsContainer.removeAllViews()
        bars.clear()
        repeat(BAR_COUNT) { index ->
            val bar = View(requireContext()).apply {
                setBackgroundResource(if (index == BAR_COUNT - 1) R.drawable.bg_home_bar_current else R.drawable.bg_home_bar)
            }
            val lp = LinearLayout.LayoutParams(0, (BAR_MIN_FRACTION * BAR_AREA_DP * density).toInt(), 1f)
            if (index > 0) lp.marginStart = gap
            binding.barsContainer.addView(bar, lp)
            bars += bar
        }
    }

    private fun bindBars(values: List<Double>) {
        val density = resources.displayMetrics.density
        val max = values.maxOrNull()?.takeIf { it > 0.0 } ?: 0.0
        bars.forEachIndexed { index, bar ->
            val value = values.getOrElse(index) { 0.0 }
            val fraction = if (max > 0.0) (value / max).toFloat().coerceAtLeast(BAR_MIN_FRACTION) else BAR_MIN_FRACTION
            val lp = bar.layoutParams
            val newHeight = (fraction * BAR_AREA_DP * density).toInt()
            if (lp.height != newHeight) {
                lp.height = newHeight
                bar.layoutParams = lp
            }
            bar.pivotY = newHeight.toFloat()
        }
    }

    /** مفتاح "الشهر | الأسبوع | اليوم" داخل بطاقة الإيرادات. */
    private fun setupPeriodSwitch() {
        mapOf(
            binding.segDay to RevenuePeriod.DAY,
            binding.segWeek to RevenuePeriod.WEEK,
            binding.segMonth to RevenuePeriod.MONTH
        ).forEach { (view, period) ->
            view.setOnClickListener {
                if (viewModel.uiState.value.period != period) {
                    it.haptic(Haptic.TICK)
                    viewModel.setPeriod(period)
                }
            }
        }
    }

    /**
     * الجرس: النقطة الحمراء تظهر عند وجود ما يستدعي الانتباه (غير مدفوعين أو اشتراكات توشك على
     * الانتهاء)، والنقر يفتح قائمة الأعضاء مفلترة على الأهم (غير المدفوعين أولاً).
     */
    private fun setupBell() {
        binding.btnBell.setOnSingleClickListener {
            val state = viewModel.uiState.value
            val filter = when {
                state.unpaidCount > 0 -> MemberFilter.UNPAID
                state.expiringSoonCount > 0 -> MemberFilter.EXPIRING_SOON
                else -> null
            }
            if (filter != null) (activity as? MainActivity)?.openMembersFiltered(filter)
        }
    }

    private fun formatAmount(value: Double): String {
        val rounded = Math.round(value * 100) / 100.0
        return if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
            String.format(Locale.US, "%,d", rounded.toLong())
        } else {
            String.format(Locale.US, "%,.2f", rounded)
        }
    }

    private fun deltaText(period: RevenuePeriod, delta: Int): String {
        val arrow = when {
            delta > 0 -> "↗"
            delta < 0 -> "↘"
            else -> "→"
        }
        val res = when (period) {
            RevenuePeriod.DAY -> R.string.home_delta_day
            RevenuePeriod.WEEK -> R.string.home_delta_week
            RevenuePeriod.MONTH -> R.string.home_delta_month
        }
        return getString(res, arrow, Math.abs(delta))
    }

    /** "بعد يومين" / "بعد 3 أيام" … بدل "باقي N يوم" القديمة (نصوص تصميم HTML). */
    private fun expiryText(days: Int): String = when {
        days <= 0 -> getString(R.string.home_in_today)
        days == 1 -> getString(R.string.home_in_one_day)
        days == 2 -> getString(R.string.home_in_two_days)
        days in 3..10 -> getString(R.string.home_in_days, days)
        else -> getString(R.string.home_in_days_many, days)
    }

    /**
     * "مستحق منذ أسبوع" … محسوبة من بداية آخر اشتراك غير مسدَّد. إن كان آخر اشتراك مسدَّداً
     * والدَّين من اشتراكات أقدم (لا تاريخ دقيق لها في الصف المجمَّع) نعرض "رصيد متأخر" بدل
     * تاريخ قد يكون مضللاً.
     */
    private fun dueText(member: MemberListItem): String {
        val start = member.startDate
        if (start == null || member.lastSubscriptionStatus() == PaymentStatus.PAID) {
            return getString(R.string.home_due_balance)
        }
        val d = DateUtils.daysBetween(start, DateUtils.now())
        return when {
            d <= 0 -> getString(R.string.home_due_today)
            d == 1 -> getString(R.string.home_due_one_day)
            d == 2 -> getString(R.string.home_due_two_days)
            d < 7 -> getString(R.string.home_due_days, d)
            d < 14 -> getString(R.string.home_due_one_week)
            d < 21 -> getString(R.string.home_due_two_weeks)
            d < 30 -> getString(R.string.home_due_weeks, d / 7)
            d < 60 -> getString(R.string.home_due_one_month)
            d < 90 -> getString(R.string.home_due_two_months)
            d < 365 -> getString(R.string.home_due_months, minOf(d / 30, 11))
            else -> getString(R.string.home_due_over_year)
        }
    }

    private fun setupLists() {
        expiringAdapter = DashboardMemberAdapter(
            subtitleProvider = { member ->
                // درجة الاستعجال: اليوم أو غداً بلون coral، وما بعده بالكهرماني (amber) كما في التصميم.
                val days = DateUtils.daysRemaining(member.endDate ?: 0L)
                val text = expiryText(days)
                if (days <= 1) SubtitlePill(text, R.drawable.bg_home_pill_coral, R.color.home_coral)
                else SubtitlePill(text, R.drawable.bg_home_pill_amber, R.color.home_amber)
            },
            onClick = { openMember(it.id) }
        )
        unpaidAdapter = DashboardUnpaidAdapter(
            // outstandingBalance = الدَّين الحقيقي المتراكم (كل الاشتراكات غير المدفوعة عبر تاريخ العضو)،
            // وليس price (سعر آخر اشتراك فقط، الذي قد يُظهر مبلغاً أقل بكثير من الدَّين الفعلي).
            amountProvider = { member ->
                CurrencyFormatter.format(member.outstandingBalance, viewModel.uiState.value.currency)
            },
            dueProvider = { member -> dueText(member) },
            onClick = { openMember(it.id) }
        )
        binding.rvExpiringSoon.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvExpiringSoon.adapter = expiringAdapter
        binding.rvUnpaid.layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        binding.rvUnpaid.adapter = unpaidAdapter

        // 6.2: snap لضبط توقف التمرير على بطاقة كاملة بدل منتصف بطاقة، مع الإطلالة الجزئية
        // من حواف الشاشة (المهيّأة في XML عبر margin/padding سالبَين) كإيحاء بوجود المزيد.
        LinearSnapHelper().attachToRecyclerView(binding.rvExpiringSoon)

        // "عرض الكل" (6.2): يفتح فلتر الأعضاء الموجود أصلاً بدل تكرار منطق العرض هنا.
        binding.btnViewAllExpiring.setOnSingleClickListener {
            (activity as? MainActivity)?.openMembersFiltered(MemberFilter.EXPIRING_SOON)
        }
        binding.btnViewAllUnpaid.setOnSingleClickListener {
            (activity as? MainActivity)?.openMembersFiltered(MemberFilter.UNPAID)
        }
    }

    private fun openMember(id: Long) {
        startActivity(Intent(requireContext(), MemberDetailActivity::class.java).putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, id))
    }

    /**
     * البند 7: يضبط وصف Accessibility مُجمَّع (تسمية + قيمة + تلميح الإجراء) على كل
     * بطاقة إحصائية قابلة للنقر، بدل ترك قارئ الشاشة يقرأ عناصرها الفرعية بترتيب
     * الشجرة الافتراضي (الرقم قبل التسمية، وبلا أي إشارة أن البطاقة قابلة للنقر
     * أصلاً). الأيقونات الزخرفية داخل كل بطاقة تبقى مستبعَدة من شجرة الوصول
     * (importantForAccessibility="no" في XML) لأن هذا الوصف الموحَّد يغنيها تماماً.
     * بطاقة "إيرادات اليوم" غير قابلة للنقر أصلاً (راجع setupStatCardClicks)، فلا
     * تحتاج وصفاً موحَّداً مماثلاً — قراءة عنصريها الفرعيين تكفي كما هي.
     */
    private fun updateStatCardAccessibilityLabels(state: DashboardUiState) {
        binding.cardActiveMembers.contentDescription = getString(
            R.string.dashboard_card_content_description,
            getString(R.string.dashboard_active_members),
            state.activeCount.toString()
        )
        binding.cardUnpaid.contentDescription = getString(
            R.string.dashboard_card_content_description,
            getString(R.string.dashboard_unpaid_count),
            state.unpaidCount.toString()
        )
        binding.cardExpiringSoon.contentDescription = getString(
            R.string.dashboard_card_content_description,
            getString(R.string.dashboard_expiring_soon),
            state.expiringSoonCount.toString()
        )
    }

    /** عدّاد بجانب عنوان القسم مثل "(5)" بلون الحالة؛ يختفي عند الصفر. */
    private fun bindSectionCount(view: android.widget.TextView, count: Int) {
        view.text = count.toString()
        view.visibleIf(count > 0)
    }

    /**
     * 7.2: وميض خفيف مستمر على هيكل الـ Skeleton (تذبذب alpha بدل شكل ثابت جامد)
     * لإيصال إحساس "جارٍ التحميل" فعلياً، لا مجرد بطاقات رمادية ساكنة.
     */
    private fun startSkeletonPulse() {
        skeletonPulse = ObjectAnimator.ofFloat(binding.skeletonDashboard, "alpha", 1f, 0.55f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopSkeletonPulse() {
        skeletonPulse?.cancel()
        skeletonPulse = null
        binding.skeletonDashboard.alpha = 1f
    }

    private fun applyAmountSize(text: String) {
        binding.tvTodayRevenue.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            when {
                text.length > 10 -> 26f
                text.length > 8 -> 32f
                text.length > 6 -> 38f
                else -> 42f
            }
        )
    }

    /** يعدّ المبلغ تصاعدياً من القيمة المعروضة إلى الجديدة (700ms) — يعمل عند أول ظهور وعند تبديل الفترة. */
    private fun showAmount(target: Double) {
        amountAnimator?.cancel()
        val finalText = formatAmount(target)
        applyAmountSize(finalText)
        val from = shownAmount
        shownAmount = target
        if (from == target) {
            binding.tvTodayRevenue.text = finalText
            return
        }
        val whole = target == Math.rint(target)
        amountAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                if (_binding == null) return@addUpdateListener
                val v = from + (target - from) * (a.animatedValue as Float)
                binding.tvTodayRevenue.text = formatAmount(if (whole) Math.rint(v) else v)
            }
            start()
        }
    }

    /** ظهور متدرّج (تلاشٍ + صعود 20dp) لكل قسم من الشاشة عند أول تحميل، ثم نمو الأعمدة من أسفلها. */
    private fun playEntrance() {
        val density = resources.displayMetrics.density
        binding.homeContent.children.forEachIndexed { i, child ->
            child.alpha = 0f
            child.translationY = 20 * density
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(i * 70L).setDuration(420)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        bars.forEachIndexed { i, bar ->
            bar.scaleY = 0f
            bar.animate().scaleY(1f)
                .setStartDelay(350L + i * 60L).setDuration(450)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** نبض هادئ جداً لنقطة الجرس الحمراء (تكبير 1 → 1.35). */
    private fun updateDotPulse(visible: Boolean) {
        if (!visible) {
            dotPulse?.cancel()
            dotPulse = null
            return
        }
        if (dotPulse != null) return
        dotPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.bellDot,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f)
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    /** بطاقة Hero: العنوان/المبلغ/شريحة المقارنة/الأعمدة/المفتاح حسب الفترة المختارة. */
    private fun bindRevenueCard(state: DashboardUiState) {
        binding.tvRevenueTitle.setText(
            when (state.period) {
                RevenuePeriod.DAY -> R.string.home_revenue_day
                RevenuePeriod.WEEK -> R.string.home_revenue_week
                RevenuePeriod.MONTH -> R.string.home_revenue_month
            }
        )
        binding.tvCurrencySymbol.text = state.currency
        showAmount(state.periodRevenue)
        val delta = state.deltaPercent
        if (delta == null) {
            binding.tvRevenueDelta.visibility = View.INVISIBLE
        } else {
            binding.tvRevenueDelta.text = deltaText(state.period, delta)
            binding.tvRevenueDelta.visibility = View.VISIBLE
        }
        bindBars(state.last7Days)
        binding.segDay.isSelected = state.period == RevenuePeriod.DAY
        binding.segWeek.isSelected = state.period == RevenuePeriod.WEEK
        binding.segMonth.isSelected = state.period == RevenuePeriod.MONTH
    }

    private fun observeState() {
        // البند 15 + 7.2: نُخفي محتوى الشاشة ونعرض هيكل الـ Skeleton إلى حين وصول أول
        // انبعاث فعلي من الحالة، بدل ظهور لوحة بأرقام صفرية وقوائم فارغة للحظة قبل التعبئة.
        var isFirstLoad = true
        binding.scrollDashboardContent.visibleIf(false)
        binding.skeletonDashboard.visibleIf(true)
        startSkeletonPulse()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (isFirstLoad) {
                        isFirstLoad = false
                        entrancePending = true
                        stopSkeletonPulse()
                        binding.skeletonDashboard.visibleIf(false)
                        binding.scrollDashboardContent.visibleIf(true)
                    }
                    binding.tvGymName.text = state.gymName
                    binding.tvAvatar.text = state.gymName.trim().firstOrNull()?.uppercase() ?: "؟"
                    binding.tvActiveMembersCount.text = state.activeCount.toString()
                    binding.tvUnpaidCount.text = state.unpaidCount.toString()
                    binding.tvExpiringSoonCount.text = state.expiringSoonCount.toString()
                    val hasAlerts = state.unpaidCount + state.expiringSoonCount > 0
                    binding.bellDot.visibleIf(hasAlerts)
                    updateDotPulse(hasAlerts)
                    bindRevenueCard(state)
                    updateStatCardAccessibilityLabels(state)
                    bindSectionCount(binding.tvExpiringSectionCount, state.expiringSoonCount)
                    bindSectionCount(binding.tvUnpaidSectionCount, state.unpaidCount)

                    expiringAdapter.submitList(state.expiringSoon)
                    unpaidAdapter.submitList(state.unpaidMembers)

                    binding.rvExpiringSoon.visibleIf(state.expiringSoon.isNotEmpty())
                    binding.tvEmptyExpiring.visibleIf(state.expiringSoon.isEmpty())
                    binding.cardUnpaidList.visibleIf(state.unpaidMembers.isNotEmpty())
                    binding.tvEmptyUnpaid.visibleIf(state.unpaidMembers.isEmpty())

                    if (entrancePending) {
                        entrancePending = false
                        playEntrance()
                    }
                }
            }
        }
    }

    private companion object {
        const val BAR_COUNT = 7
        /** ارتفاع منطقة الأعمدة داخل الحاوية: 58dp ناقص حشوة علوية 12dp (كما في تصميم HTML). */
        const val BAR_AREA_DP = 46f
        const val BAR_MIN_FRACTION = 0.12f
    }

    override fun onDestroyView() {
        amountAnimator?.cancel()
        amountAnimator = null
        dotPulse?.cancel()
        dotPulse = null
        shownAmount = 0.0
        stopSkeletonPulse()
        super.onDestroyView()
        _binding = null
    }
}
