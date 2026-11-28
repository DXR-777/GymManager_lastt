package com.gympro.manager.ui.revenue

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.databinding.FragmentRevenueBinding
import com.gympro.manager.model.MemberFilter
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.ui.main.MainActivity
import com.gympro.manager.utils.BackupManager
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.XlsxWriter
import com.gympro.manager.utils.areAnimationsEnabled
import com.gympro.manager.utils.attachPressScale
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.toast
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt
import com.gympro.manager.utils.SnackType
import com.gympro.manager.utils.showSnack

class RevenueFragment : Fragment() {

    private var _binding: FragmentRevenueBinding? = null
    private val binding get() = _binding!!

    /** فترة بطاقة Hero المعروضة (الافتراضي: الشهر — الأكثر فائدة لصاحب النادي). */
    private enum class RevPeriod { DAY, WEEK, MONTH, YEAR }

    private var period = RevPeriod.MONTH
    private var shownAmount = 0.0
    private var amountAnimator: ValueAnimator? = null
    private var entrancePlayed = false
    private var lastState: RevenueUiState? = null

    /** false حتى وصول أول حالة محمَّلة: الهيكل ظاهر والمحتوى مخفي. يُعاد ضبطه مع كل View جديد. */
    private var revealed = false
    private var previewDialog: BottomSheetDialog? = null

    /** الرسوم تبدأ حركتها عند ظهور قسمها فعلاً (لا والمحتوى مخفي)؛ true بعد أول تشغيل. */
    private var barsShown = false
    private var donutShown = false

    /** مهام الحركة المؤجَّلة (بداية العدّ/نمو الأعمدة/sweep). تُلغى عند onStop وonDestroyView. */
    private val motionJobs = mutableListOf<Job>()

    private val viewModel: RevenueViewModel by viewModels {
        RevenueViewModel.Factory((requireActivity().application as GymApplication).repository)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRevenueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shownAmount = 0.0
        periodByType = null
        selectedType = null
        revealed = false
        barsShown = false
        donutShown = false
        setupDots()
        setupDonutInteraction()
        setupPeriodSwitch()
        binding.revBtnShare.setOnSingleClickListener { shareReport() }
        binding.rowCustomRange.setOnSingleClickListener { showRangeSheet() }
        binding.revUnpaidCard.setOnSingleClickListener {
            (requireActivity() as? MainActivity)?.openMembersFiltered(MemberFilter.UNPAID)
        }
        binding.revUnpaidCard.attachPressScale()
        binding.btnClearCustomRange.setOnClickListener { hideCustomRangeResult() }
        setupPressFeedback()
        observeState()
    }

    /** استجابة اللمس 0.97 على كل عنصر قابل للنقر في الشاشة (الحلقة والمخطط لهما إبرازهما الخاص). */
    private fun setupPressFeedback() {
        listOf(
            binding.revBtnShare, binding.revSegDay, binding.revSegWeek, binding.revSegMonth, binding.revSegYear,
            binding.rowCustomRange, binding.btnClearCustomRange
        ).forEach { it.attachPressScale() }
        typeRows.forEach { it.row().attachPressScale() }
    }

    private fun setupDots() {
        // نفس ألوان شرائح الحلقة (بدائل rev_type_* لـ chart_*) لتتطابق النقطة مع شريحتها.
        typeRows.forEach { row ->
            row.dot().backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), row.colorRes))
        }
    }

    private fun lastSevenDayLabels(): List<String> {
        val labels = resources.getStringArray(R.array.weekday_labels_short)
        val result = mutableListOf<String>()
        val cal = Calendar.getInstance()
        for (offset in 6 downTo 0) {
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_MONTH, -offset)
            result.add(labels[c.get(Calendar.DAY_OF_WEEK) - 1])
        }
        return result
    }

    // ------------------------------------------------------------------ الجزء 2: مخطط 7 أيام + حلقة التوزيع

    /** مخطط الأعمدة (Canvas): تسميات الأيام والفقاعة والمتوسط تُرسم داخل الـ View نفسه. */
    private fun updateBars(state: RevenueUiState, animate: Boolean = true) {
        val values = state.last7Days
        val days = lastSevenDayLabels()
        val last = days.lastIndex
        val axis = days.mapIndexed { i, d -> if (i == last) getString(R.string.rev_today) else d }
        val titles = days.mapIndexed { i, d -> if (i == last) getString(R.string.rev_today_named, d) else d }
        val amounts = values.map { CurrencyFormatter.formatGrouped(it, state.currency) }
        val avg = if (values.isEmpty()) 0.0 else values.sum() / values.size
        binding.revBars.setData(
            values = values,
            axisLabels = axis,
            tooltipTitles = titles,
            tooltipAmounts = amounts,
            averageText = getString(R.string.rev_avg_chip, CurrencyFormatter.formatGrouped(avg, state.currency)),
            emptyMessage = getString(R.string.rev_chart_empty),
            animate = animate
        )
        // وصف للوصول: المخطط مرسوم على Canvas لا يقرؤه TalkBack، فنلخّص قيمه نصاً (يوم: مبلغ).
        binding.revBars.contentDescription =
            if (values.all { it == 0.0 }) {
                getString(R.string.revenue_chart_content_description_empty)
            } else {
                getString(
                    R.string.revenue_chart_content_description,
                    values.mapIndexed { i, v -> "${days.getOrElse(i) { "" }} ${CurrencyFormatter.formatGrouped(v, state.currency)}" }
                        .joinToString("، ")
                )
            }
    }

    private class TypeRow(
        val type: SubscriptionType,
        val colorRes: Int,
        val nameRes: Int,
        val row: () -> View,
        val dot: () -> View,
        val amount: () -> android.widget.TextView,
        val pct: () -> android.widget.TextView
    )

    /** الأنواع الأربعة بترتيب الحلقة والأسطورة (نفس ترتيب SubscriptionType). */
    private val typeRows: List<TypeRow> by lazy {
        listOf(
            TypeRow(SubscriptionType.DAILY, R.color.rev_type_daily, R.string.subscription_daily,
                { binding.revRowDaily }, { binding.dotDaily }, { binding.tvRevenueDaily }, { binding.revTvPctDaily }),
            TypeRow(SubscriptionType.WEEKLY, R.color.rev_type_weekly, R.string.subscription_weekly,
                { binding.revRowWeekly }, { binding.dotWeekly }, { binding.tvRevenueWeekly }, { binding.revTvPctWeekly }),
            TypeRow(SubscriptionType.MONTHLY, R.color.rev_type_monthly, R.string.subscription_monthly,
                { binding.revRowMonthly }, { binding.dotMonthly }, { binding.tvRevenueMonthly }, { binding.revTvPctMonthly }),
            TypeRow(SubscriptionType.CUSTOM, R.color.rev_type_custom, R.string.subscription_custom,
                { binding.revRowCustom }, { binding.dotCustom }, { binding.tvRevenueCustom }, { binding.revTvPctCustom })
        )
    }

    /** الشريحة المُبرَزة حالياً (null = لا شيء). تتزامن بين الحلقة وصفوف الأسطورة. */
    private var selectedType: SubscriptionType? = null

    private fun setupDonutInteraction() {
        typeRows.forEach { row ->
            row.row().setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                toggleType(row.type)
            }
        }
        binding.revDonut.onSliceSelected = { index ->
            val row = typeRows.getOrNull(index)
            if (row != null) {
                binding.revDonut.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                toggleType(row.type)
            } else {
                selectType(null)
            }
        }
    }

    private fun toggleType(type: SubscriptionType) = selectType(if (selectedType == type) null else type)

    private fun selectType(type: SubscriptionType?) {
        selectedType = type
        val index = typeRows.indexOfFirst { it.type == type }
        binding.revDonut.setSelected(index)
        typeRows.forEach { it.row().isSelected = it.type == type }
        lastState?.let { renderDonutCenter(it) }
    }

    /**
     * توزيع حلقة الأنواع يتبع الفترة المختارة في مفتاح الفترة (يوم/أسبوع/شهر/سنة) فلا يتعارض مع بطاقة Hero.
     * قبل وصول نتيجة الاستعلام (أو للشهر الافتراضي) نستعمل توزيع الشهر الجاهز في الحالة.
     */
    private var periodByType: Map<SubscriptionType, Double>? = null
    private var splitJob: Job? = null

    private fun splitOf(state: RevenueUiState): Map<SubscriptionType, Double> =
        if (period == RevPeriod.MONTH) state.byType else (periodByType ?: state.byType)

    private fun periodRange(): Pair<Long, Long> {
        val now = DateUtils.now()
        val start = when (period) {
            RevPeriod.DAY -> DateUtils.startOfDay(now)
            RevPeriod.WEEK -> DateUtils.startOfWeek()
            RevPeriod.MONTH -> DateUtils.startOfMonth()
            RevPeriod.YEAR -> DateUtils.startOfYear()
        }
        return start to DateUtils.endOfDay(now)
    }

    private fun splitScopeRes(): Int = when (period) {
        RevPeriod.DAY -> R.string.rev_scope_day
        RevPeriod.WEEK -> R.string.rev_scope_week
        RevPeriod.MONTH -> R.string.rev_scope_month
        RevPeriod.YEAR -> R.string.rev_scope_year
    }

    private fun donutTotalLabelRes(): Int = when (period) {
        RevPeriod.DAY -> R.string.rev_donut_total_day
        RevPeriod.WEEK -> R.string.rev_donut_total_week
        RevPeriod.MONTH -> R.string.rev_donut_total
        RevPeriod.YEAR -> R.string.rev_donut_total_year
    }

    /** يجلب توزيع الفترة المختارة ويحدّث الحلقة وشارة النطاق؛ يُلغى أي طلب سابق لم ينتهِ. */
    private fun refreshSplit() {
        splitJob?.cancel()
        val requested = period
        splitJob = viewLifecycleOwner.lifecycleScope.launch {
            val (start, end) = periodRange()
            val result = if (requested == RevPeriod.MONTH) null else viewModel.byTypeForRange(start, end)
            if (_binding == null || requested != period) return@launch
            periodByType = result
            binding.revTvSplitScope.setText(splitScopeRes())
            lastState?.let { if (donutShown) updateDonut(it) }
        }
    }

    private fun typeAmount(state: RevenueUiState, type: SubscriptionType): Double = splitOf(state)[type] ?: 0.0

    private fun percentText(value: Double, total: Double): String {
        if (total <= 0.0 || value <= 0.0) return getString(R.string.rev_pct, 0)
        val pct = ((value / total) * 100).roundToInt()
        return if (pct == 0) getString(R.string.rev_pct_lt1) else getString(R.string.rev_pct, pct)
    }

    /** وسط الحلقة: إجمالي الشهر، أو مبلغ ونسبة النوع المُبرَز. */
    private fun renderDonutCenter(state: RevenueUiState) {
        val total = splitOf(state).values.sum()
        val sel = typeRows.firstOrNull { it.type == selectedType }
        if (sel == null) {
            binding.revDonut.setCenter(CurrencyFormatter.formatGrouped(total, state.currency), getString(donutTotalLabelRes()))
        } else {
            val v = typeAmount(state, sel.type)
            binding.revDonut.setCenter(
                CurrencyFormatter.formatGrouped(v, state.currency),
                getString(R.string.rev_donut_selected, getString(sel.nameRes), percentText(v, total))
            )
        }
    }

    private fun updateDonut(state: RevenueUiState, animate: Boolean = true) {
        val ctx = requireContext()
        val total = splitOf(state).values.sum()
        val values = DoubleArray(typeRows.size) { typeAmount(state, typeRows[it].type) }
        val colors = IntArray(typeRows.size) { ContextCompat.getColor(ctx, typeRows[it].colorRes) }
        binding.revDonut.setData(values, colors, animate)
        renderDonutCenter(state)

        val parts = ArrayList<String>(typeRows.size)
        typeRows.forEachIndexed { i, row ->
            val v = values[i]
            val amountText = CurrencyFormatter.formatGrouped(v, state.currency)
            val pctText = percentText(v, total)
            row.amount().text = amountText
            row.pct().text = pctText
            // الأنواع الصفرية تُخفَّت فيتركّز النظر على ما فيه إيراد فعلاً
            row.row().alpha = if (v > 0.0) 1f else 0.45f
            row.row().contentDescription = getString(R.string.rev_row_cd, getString(row.nameRes), amountText, pctText)
            parts.add("${getString(row.nameRes)} $amountText ($pctText)")
        }
        binding.revDonut.contentDescription =
            if (total <= 0.0) getString(R.string.rev_donut_cd_empty)
            else getString(R.string.rev_donut_cd, parts.joinToString("، "))
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    lastState = state
                    // قبل أول حساب حقيقي: يبقى الهيكل ظاهراً (لا وميض أصفار ولا «لا إيرادات» خاطئ).
                    if (!state.loaded) return@collect
                    renderScreenState(state)
                    renderHero(state)
                    renderIndicators(state)
                    renderUnpaid(state)
                    refreshSplit()
                    // الرسوم تُملأ بحركتها عند ظهور قسمها (scheduleReveal)؛ بعدها تتحدّث مباشرة.
                    if (barsShown) updateBars(state)
                    if (donutShown) updateDonut(state)
                    revealContent()
                }
            }
        }
    }

    // ------------------------------------------------------------------ الجزء 3أ: حالات الشاشة

    /** true إن وُجد أي إيراد في أي فترة نعرضها أو نقارن بها؛ غير ذلك = «لا إيرادات بعد». */
    private fun hasAnyRevenue(s: RevenueUiState): Boolean =
        s.today > 0.0 || s.week > 0.0 || s.month > 0.0 || s.year > 0.0 || s.lastMonth > 0.0 ||
            s.prevDay > 0.0 || s.prevWeek > 0.0 || s.prevMonth > 0.0 || s.prevYear > 0.0 ||
            s.last7Days.any { it > 0.0 } || s.byType.values.any { it > 0.0 }

    /**
     * عادي ↔ فارغ. في الحالة الفارغة يبقى الرأس ومفتاح الفترة وبطاقة Hero (بصفر نظيف ومقارنة محايدة)،
     * وتُستبدل بقية الأقسام ببطاقة طمأنة واحدة؛ ويُخفى زرّا المشاركة لأن لا شيء يستحق المشاركة بعد.
     */
    private fun renderScreenState(state: RevenueUiState) {
        val empty = !hasAnyRevenue(state)
        binding.revEmptyCard.visibleIf(empty)
        binding.revIndicators.visibleIf(!empty)
        binding.revSecTrendGroup.visibleIf(!empty)
        binding.revSecSplitGroup.visibleIf(!empty)
        binding.revSecDetailsGroup.visibleIf(!empty)
        binding.revBtnShare.visibleIf(!empty)
    }

    /** ينقل من الهيكل إلى المحتوى مرة واحدة لكل View: المحتوى يبدأ بالدخول المتتابع والهيكل يتلاشى. */
    private fun revealContent() {
        if (revealed) return
        revealed = true
        binding.revScroll.visibility = View.VISIBLE
        val delays = if (!entrancePlayed) {
            entrancePlayed = true
            playEntrance()
        } else emptyMap()
        scheduleReveal(delays)
        val skeleton = binding.revSkeleton
        skeleton.animate().cancel()
        skeleton.animate().alpha(0f).setDuration(200L).withEndAction {
            _binding?.let {
                it.revSkeleton.visibility = View.GONE
                it.revSkeleton.alpha = 1f
            }
        }.start()
    }

    // ------------------------------------------------------------------ الجزء 1: Hero + المؤشرات

    private fun setupPeriodSwitch() {
        val items = listOf(
            binding.revSegDay to RevPeriod.DAY,
            binding.revSegWeek to RevPeriod.WEEK,
            binding.revSegMonth to RevPeriod.MONTH,
            binding.revSegYear to RevPeriod.YEAR
        )
        fun markSelected() = items.forEach { (view, p) -> view.isSelected = p == period }
        markSelected()
        items.forEach { (view, p) ->
            view.setOnClickListener {
                if (period == p) return@setOnClickListener
                period = p
                markSelected()
                lastState?.let { renderHero(it) }
                refreshSplit()
            }
        }
    }

    private fun heroAmount(state: RevenueUiState): Double = when (period) {
        RevPeriod.DAY -> state.today
        RevPeriod.WEEK -> state.week
        RevPeriod.MONTH -> state.month
        RevPeriod.YEAR -> state.year
    }

    /** يملأ بطاقة Hero بالفترة المختارة: الرقم (بعدّ تصاعدي)، التسمية، النطاق، وشارة المقارنة. */
    private fun renderHero(state: RevenueUiState) {
        val now = DateUtils.now()
        val amount: Double
        val prev: Double
        val labelRes: Int
        val vsRes: Int
        val range: String
        when (period) {
            RevPeriod.DAY -> {
                amount = state.today; prev = state.prevDay
                labelRes = R.string.rev_hero_day; vsRes = R.string.rev_vs_day
                range = DateUtils.formatDateRangeShort(now, now)
            }
            RevPeriod.WEEK -> {
                amount = state.week; prev = state.prevWeek
                labelRes = R.string.rev_hero_week; vsRes = R.string.rev_vs_week
                range = DateUtils.formatDateRangeShort(DateUtils.startOfWeek(), now)
            }
            RevPeriod.MONTH -> {
                amount = state.month; prev = state.prevMonth
                labelRes = R.string.rev_hero_month; vsRes = R.string.rev_vs_month
                range = DateUtils.formatDateRangeShort(DateUtils.startOfMonth(), now)
            }
            RevPeriod.YEAR -> {
                amount = state.year; prev = state.prevYear
                labelRes = R.string.rev_hero_year; vsRes = R.string.rev_vs_year
                range = DateUtils.formatDateRangeShort(DateUtils.startOfYear(), now)
            }
        }
        binding.revTvGym.text = state.gymName.ifBlank { getString(R.string.app_name) }
        binding.revTvSymbol.text = state.currency
        binding.revTvHeroLabel.setText(labelRes)
        binding.revTvHeroRange.text = range
        if (revealed) {
            showAmount(amount, PERIOD_COUNT_MS)
        } else {
            // قبل الظهور: الرقم يبدأ من صفر ويبدأ عدّه مع دخول البطاقة (scheduleReveal).
            applyAmountSize(formatAmount(amount))
            binding.revTvAmount.text = formatAmount(shownAmount)
        }

        val vs = getString(vsRes)
        val ctx = requireContext()
        if (prev <= 0.0) {
            // لا بيانات سابقة: محايد (لا سهم ولا نسبة مضلِّلة).
            binding.revTvDelta.text = getString(R.string.rev_delta_none)
            binding.revTvDelta.setTextColor(ContextCompat.getColor(ctx, R.color.home_hero_title))
            binding.revTvPrev.text = ""
        } else {
            val pct = (((amount - prev) / prev) * 100).roundToInt()
            when {
                pct > 0 -> {
                    binding.revTvDelta.text = getString(R.string.rev_delta_up, pct, vs)
                    binding.revTvDelta.setTextColor(ContextCompat.getColor(ctx, R.color.home_mint))
                }
                pct < 0 -> {
                    binding.revTvDelta.text = getString(R.string.rev_delta_down, -pct, vs)
                    binding.revTvDelta.setTextColor(ContextCompat.getColor(ctx, R.color.home_coral))
                }
                else -> {
                    binding.revTvDelta.text = getString(R.string.rev_delta_flat, vs)
                    binding.revTvDelta.setTextColor(ContextCompat.getColor(ctx, R.color.home_hero_title))
                }
            }
            binding.revTvPrev.text = getString(R.string.rev_prev_amount, CurrencyFormatter.formatGrouped(prev, state.currency))
        }
        // وصف واحد مجمّع للبطاقة بالقيم النهائية (لا يتأثر بمرحلة العدّ التصاعدي).
        binding.revHeroCard.contentDescription = getString(
            R.string.rev_hero_cd, getString(labelRes), CurrencyFormatter.formatGrouped(amount, state.currency),
            binding.revTvDelta.text, binding.revTvPrev.text
        ).trim()
    }

    /** بطاقة المستحقات: تظهر فقط عند وجود متأخرين، وتفتح قائمة الأعضاء مفلترة على "غير المدفوعين". */
    private fun renderUnpaid(state: RevenueUiState) {
        val show = state.unpaidCount > 0 && state.unpaidTotal > 0.0
        binding.revUnpaidCard.visibleIf(show)
        if (!show) return
        val total = CurrencyFormatter.formatGrouped(state.unpaidTotal, state.currency)
        binding.revTvUnpaidTotal.text = total
        binding.revTvUnpaidSub.text = getString(R.string.rev_unpaid_sub, state.unpaidCount)
        binding.revUnpaidCard.contentDescription =
            "${getString(R.string.rev_unpaid_title)}: $total، ${binding.revTvUnpaidSub.text}"
    }

    private fun formatAmount(value: Double): String {
        val rounded = Math.round(value * 100) / 100.0
        return if (rounded == Math.rint(rounded) && Math.abs(rounded) < 1e15) {
            String.format(Locale.US, "%,d", rounded.toLong())
        } else {
            String.format(Locale.US, "%,.2f", rounded)
        }
    }

    private fun applyAmountSize(text: String) {
        // مع خط 1.3× يتسع الرقم أكثر: نحتسب الطول الفعلي بمعامل تكبير الخط حتى لا يُقصّ.
        val len = text.length * resources.configuration.fontScale.coerceAtLeast(1f)
        binding.revTvAmount.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            when {
                len > 10f -> 30f
                len > 8f -> 38f
                len > 6f -> 46f
                else -> 54f
            }
        )
    }

    /**
     * يعدّ الرقم البطل تصاعدياً من القيمة المعروضة إلى الجديدة: [durationMs] (650ms عند أول ظهور،
     * 450ms عند تبديل الفترة حتى لا يبطؤ التبديل). بلا عدّ مع [animate]=false أو تقليل الحركة.
     */
    private fun showAmount(target: Double, durationMs: Long = PERIOD_COUNT_MS, animate: Boolean = true) {
        amountAnimator?.cancel()
        amountAnimator = null
        val finalText = formatAmount(target)
        applyAmountSize(finalText)
        val from = shownAmount
        shownAmount = target
        if (!animate || !areAnimationsEnabled() || from == target) {
            binding.revTvAmount.text = finalText
            return
        }
        val whole = target == Math.rint(target)
        amountAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                if (_binding == null) return@addUpdateListener
                val v = from + (target - from) * (a.animatedValue as Float)
                binding.revTvAmount.text = formatAmount(if (whole) Math.rint(v) else v)
            }
            start()
        }
    }

    /**
     * المؤشرات الثلاثة تُحسب من البيانات الموجودة فقط:
     *  - متوسط الإيراد اليومي = إيراد الشهر ÷ عدد أيامه المنقضية (حتى اليوم).
     *  - أفضل يوم في آخر 7 أيام (إن تساوت القيم فالأحدث).
     */
    private fun renderIndicators(state: RevenueUiState) {
        val none = getString(R.string.rev_none_value)

        // الصفر يُعرض هادئاً (لون muted) لا كقيمة ملوّنة لامعة — فلا تبدو الشاشة معطّلة.
        val ctx = requireContext()
        val mute = ContextCompat.getColor(ctx, R.color.home_hero_title)

        val daysElapsed = DateUtils.dayOfMonth(DateUtils.now()).coerceAtLeast(1)
        binding.revTvAvgValue.text = CurrencyFormatter.formatGrouped(state.month / daysElapsed, state.currency)
        binding.revTvAvgValue.setTextColor(if (state.month > 0.0) ContextCompat.getColor(ctx, R.color.home_ink) else mute)

        val last7 = state.last7Days
        val best = last7.maxOrNull() ?: 0.0
        if (best <= 0.0) {
            binding.revTvBestValue.text = none
            binding.revTvBestValue.setTextColor(mute)
            binding.revTvBestLabel.setText(R.string.rev_ind_best_none)
        } else {
            binding.revTvBestValue.setTextColor(ContextCompat.getColor(ctx, R.color.home_ink))
            val labels = lastSevenDayLabels()
            val day = labels.getOrElse(last7.indexOfLast { it == best }) { "" }
            binding.revTvBestValue.text = CurrencyFormatter.formatGrouped(best, state.currency)
            binding.revTvBestLabel.text = getString(R.string.rev_ind_best_label, day)
        }

        // كل بطاقة مؤشر تُقرأ كعنصر وصول واحد: «التسمية: القيمة، النطاق».
        fun cd(label: CharSequence, value: CharSequence, scopeRes: Int): String = getString(
            R.string.rev_ind_cd, label, if (value.toString() == none) getString(R.string.rev_none_cd) else value, getString(scopeRes)
        )
        binding.revIndAvg.contentDescription = cd(getString(R.string.rev_ind_avg_label), binding.revTvAvgValue.text, R.string.rev_scope_month)
        binding.revIndBest.contentDescription = cd(binding.revTvBestLabel.text, binding.revTvBestValue.text, R.string.rev_scope_7d)
    }

    /**
     * دخول متتابع: كل ابن مرئي من rev_content يتلاشى ويصعد 20dp بفارق [STAGGER_MS] عن سابقه.
     * يُرجع تأخير بدء كل ابن ليبدأ عدّ البطل ونمو الرسوم مع ظهور قسمها بالضبط.
     * مع تقليل الحركة: لا إزاحة ولا تلاشٍ (المحتوى يظهر نهائياً فوراً).
     */
    private fun playEntrance(): Map<View, Long> {
        val targets = binding.revContent.children.filter { it.visibility == View.VISIBLE }.toList()
        val delays = HashMap<View, Long>(targets.size)
        val animate = areAnimationsEnabled()
        val density = resources.displayMetrics.density
        targets.forEachIndexed { i, child ->
            val startDelay = if (animate) i * STAGGER_MS else 0L
            delays[child] = startDelay
            if (!animate) return@forEachIndexed
            child.alpha = 0f
            child.translationY = 20 * density
            child.animate().alpha(1f).translationY(0f)
                .setStartDelay(startDelay).setDuration(ENTER_DUR_MS)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        return delays
    }

    /** يجدول بداية عدّ البطل ونمو أعمدة الاتجاه وsweep الحلقة عند ظهور كل منها (وليس والمحتوى مخفي). */
    private fun scheduleReveal(delays: Map<View, Long>) {
        fun lead(v: View) = (delays[v] ?: 0L) + CHART_LEAD_MS
        schedule(lead(binding.revHeroCard)) {
            lastState?.let { showAmount(heroAmount(it), HERO_COUNT_MS) }
        }
        schedule(lead(binding.revSecTrendGroup)) {
            barsShown = true
            lastState?.let { updateBars(it) }
        }
        schedule(lead(binding.revSecSplitGroup)) {
            donutShown = true
            lastState?.let { updateDonut(it) }
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        motionJobs += viewLifecycleOwner.lifecycleScope.launch {
            if (delayMs > 0L && areAnimationsEnabled()) delay(delayMs)
            if (_binding != null) block()
        }
    }

    /** يثبّت كل الأبناء على حالتهم النهائية (يُستعمل عند الإيقاف حتى لا يبقى نصف مرسوم). */
    private fun finishEntrance() {
        val b = _binding ?: return
        b.revContent.children.forEach {
            it.animate().cancel()
            it.alpha = 1f
            it.translationY = 0f
        }
    }

    /**
     * onStop: إلغاء كل حركة جارية أو مؤجَّلة والانتقال فوراً إلى القيم النهائية
     * (لا مؤقتات ولا animators تعمل والشاشة غير مرئية).
     */
    override fun onStop() {
        super.onStop()
        val b = _binding ?: return
        motionJobs.forEach { it.cancel() }
        motionJobs.clear()
        finishEntrance()
        if (!revealed) return
        b.revSkeleton.animate().cancel()
        b.revSkeleton.visibility = View.GONE
        b.revSkeleton.alpha = 1f
        lastState?.takeIf { it.loaded }?.let { s ->
            showAmount(heroAmount(s), animate = false)
            barsShown = true
            donutShown = true
            updateBars(s, animate = false)
            updateDonut(s, animate = false)
        }
    }

    /**
     * ورقة اختيار الفترة: اختصارات جاهزة بتواريخها الظاهرة (فيفهم المستخدم ما سيُعرض قبل الضغط)،
     * وخيار أخير يفتح التقويم الحرّ. النتيجة تظهر في نفس بطاقة نطاق التاريخ (loadCustomRange).
     */
    private var rangeSheet: BottomSheetDialog? = null

    private fun showRangeSheet() {
        rangeSheet?.dismiss()
        val now = DateUtils.now()
        val today = DateUtils.startOfDay(now)
        val thisMonthStart = DateUtils.startOfMonth(now)
        val thisYearStart = DateUtils.startOfYear(now)
        // (معرّف الصف، معرّف سطر التواريخ، البداية، النهاية)
        data class Preset(val rowId: Int, val subId: Int, val start: Long, val end: Long)
        val presets = listOf(
            Preset(R.id.rev_sheet_row_last_month, R.id.rev_sheet_sub_last_month,
                DateUtils.startOfMonth(DateUtils.addMonths(thisMonthStart, -1)),
                DateUtils.endOfDay(DateUtils.addDays(thisMonthStart, -1))),
            Preset(R.id.rev_sheet_row_30d, R.id.rev_sheet_sub_30d, DateUtils.addDays(today, -29), DateUtils.endOfDay(now)),
            Preset(R.id.rev_sheet_row_90d, R.id.rev_sheet_sub_90d, DateUtils.addDays(today, -89), DateUtils.endOfDay(now)),
            Preset(R.id.rev_sheet_row_last_year, R.id.rev_sheet_sub_last_year,
                DateUtils.startOfYear(DateUtils.addMonths(thisYearStart, -12)),
                DateUtils.endOfDay(DateUtils.addDays(thisYearStart, -1)))
        )

        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_revenue_range, null)
        presets.forEach { preset ->
            view.findViewById<android.widget.TextView>(preset.subId).text =
                DateUtils.formatDateRangeShort(preset.start, preset.end)
            view.findViewById<View>(preset.rowId).apply {
                attachPressScale()
                setOnSingleClickListener {
                    dialog.dismiss()
                    loadCustomRange(preset.start, preset.end)
                }
            }
        }
        view.findViewById<View>(R.id.rev_sheet_row_pick).apply {
            attachPressScale()
            setOnSingleClickListener {
                dialog.dismiss()
                showCustomRangePicker()
            }
        }

        dialog.setContentView(view)
        // نفس معالجة بقية الأوراق: حاوية Material شفافة، والزجاج مرسوم من خلفية الجذر (bg_home_sheet).
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.setOnDismissListener { if (rangeSheet === dialog) rangeSheet = null }
        rangeSheet = dialog
        dialog.show()
    }

    /**
     * منتقي نطاق تاريخ حرّ — راجع البند 23. يُقيَّد بـ"اليوم أو قبله" (لا معنى لإيراد
     * مستقبلي)، ولا حدّ أدنى: صاحب النادي قد يريد إيراد شهر بعيد أو نطاق قصير داخل
     * الشهر الحالي على حدّ سواء.
     */
    private fun showCustomRangePicker() {
        val constraints = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointBackward.now())
            .build()
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.rev_range_picker_title)
            .setTheme(R.style.ThemeOverlay_GymManager_DatePicker)
            .setPositiveButtonText(R.string.rev_range_apply)
            .setNegativeButtonText(R.string.rev_range_cancel)
            .setCalendarConstraints(constraints)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val start = localStartOfPickedUtcDay(selection.first)
            val end = DateUtils.endOfDay(localStartOfPickedUtcDay(selection.second))
            loadCustomRange(start, end)
        }
        picker.show(childFragmentManager, "revenue_custom_range_picker")
    }

    /**
     * MaterialDatePicker يُعيد الاختيار كملي ثانية UTC عند منتصف ليل ذلك التاريخ
     * بتقويم UTC (توثيق المكتبة نفسها)، وليس بالتوقيت المحلي للجهاز. تمريرها مباشرة
     * إلى DateUtils.startOfDay/endOfDay (اللتين تستخدمان التقويم المحلي) قد يزيح
     * التاريخ يوماً كاملاً بحسب فارق التوقيت المحلي عن UTC (فلسطين/إسرائيل +2 أو +3).
     * لذا نستخرج السنة/الشهر/اليوم من تقويم UTC أولاً، ثم نبني بها منتصف ليل محلي
     * صحيحاً — لا نستخدم قيمة الملي ثانية الأصلية كما هي إطلاقاً.
     */
    private fun localStartOfPickedUtcDay(utcMillis: Long): Long {
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        utcCal.timeInMillis = utcMillis
        val localCal = Calendar.getInstance()
        localCal.clear()
        localCal.set(
            utcCal.get(Calendar.YEAR),
            utcCal.get(Calendar.MONTH),
            utcCal.get(Calendar.DAY_OF_MONTH)
        )
        return localCal.timeInMillis
    }

    private fun loadCustomRange(start: Long, end: Long) {
        viewLifecycleOwner.lifecycleScope.launch {
            val total = viewModel.revenueForRange(start, end)
            val currency = viewModel.uiState.value.currency
            binding.tvCustomRangeLabel.text = getString(
                R.string.revenue_custom_range_label,
                DateUtils.formatDate(start),
                DateUtils.formatDate(end)
            )
            binding.tvCustomRangeTotal.text = CurrencyFormatter.formatGrouped(total, currency)
            showCustomRangeResult()
        }
    }

    /** ظهور بطاقة نتيجة النطاق بتلاشٍ + صعود خفيف (نفس إيقاع بقية الشاشة). */
    private fun showCustomRangeResult() {
        val card = binding.containerCustomRangeResult
        card.animate().cancel()
        if (card.visibility != View.VISIBLE) {
            card.alpha = 0f
            card.translationY = 12 * resources.displayMetrics.density
            card.visibleIf(true)
        }
        card.animate().alpha(1f).translationY(0f).setDuration(320)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun hideCustomRangeResult() {
        val card = binding.containerCustomRangeResult
        card.animate().cancel()
        card.animate().alpha(0f).translationY(8 * resources.displayMetrics.density).setDuration(180)
            .withEndAction {
                if (_binding != null) {
                    card.visibleIf(false)
                    card.alpha = 1f
                    card.translationY = 0f
                }
            }.start()
    }

    /**
     * البند 24: كانت المشاركة نصاً عادياً فقط دائماً — بلا خيار Excel/PDF، وبلا اسم
     * النادي أو تاريخ واضح في رأس الرسالة (فقط "الإيرادات والأرباح" عام). الآن نعرض
     * خيارين صريحين (نص أو ملف Excel)، وكلاهما يحمل اسم النادي وتاريخ إصدار التقرير
     * بوضوح في الرأس.
     */
    private fun shareReport() {
        val items = arrayOf(getString(R.string.revenue_share_as_text), getString(R.string.revenue_share_as_excel))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.revenue_share_choose_format)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> shareReportAsText()
                    1 -> shareReportAsExcel()
                }
            }
            .show()
    }

    /** رأس مشترك بين نسخة النص ونسخة Excel: اسم النادي (أو اسم التطبيق إن لم يُضبط
     *  بعد) + تاريخ إصدار التقرير بوضوح — يسدّ فجوة "لا اسم نادٍ ولا تاريخ" (البند 24). */
    private fun reportGymLabel(state: RevenueUiState): String =
        state.gymName.ifBlank { getString(R.string.app_name) }

    private fun shareReportAsText() {
        val state = viewModel.uiState.value
        val text = buildString {
            append("${reportGymLabel(state)} — ${getString(R.string.revenue_title)}\n")
            append("${getString(R.string.revenue_report_generated_on)}: ${DateUtils.formatDate(DateUtils.now())}\n\n")
            append("${getString(R.string.revenue_today)}: ${CurrencyFormatter.format(state.today, state.currency)}\n")
            append("${getString(R.string.revenue_week)}: ${CurrencyFormatter.format(state.week, state.currency)}\n")
            append("${getString(R.string.revenue_month)}: ${CurrencyFormatter.format(state.month, state.currency)}\n")
            append("${getString(R.string.revenue_year)}: ${CurrencyFormatter.format(state.year, state.currency)}\n\n")
            append("${getString(R.string.revenue_report_section_by_type)}\n")
            append("${getString(R.string.subscription_daily)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.DAILY] ?: 0.0, state.currency)}\n")
            append("${getString(R.string.subscription_weekly)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.WEEKLY] ?: 0.0, state.currency)}\n")
            append("${getString(R.string.subscription_monthly)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.MONTHLY] ?: 0.0, state.currency)}\n")
            append("${getString(R.string.subscription_custom)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.CUSTOM] ?: 0.0, state.currency)}\n")
        }
        // البند 31: قبل كانت هذه الدالة تفتح شيت المشاركة مباشرة فور بنائها للنص. الآن
        // نعرض المحتوى أولاً في حوار معاينة (showReportPreview) ولا يُطلق intent
        // المشاركة الفعلي إلا بعد ضغط المستخدم على "مشاركة" صراحة.
        showReportPreview(text) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, null))
        }
    }

    /**
     * البند 31: يبني نص معاينة مقروء لمحتوى ملف الـ Excel (نفس بيانات جدولَي الملخّص
     * والتوزيع حسب النوع، بالإضافة إلى آخر 7 أيام) قبل إنشاء الملف الفعلي فعلياً —
     * حتى يرى صاحب النادي الأرقام كاملة دون الحاجة لفتح تطبيق خارجي أولاً.
     */
    private fun buildExcelPreviewText(state: RevenueUiState, dayLabels: List<String>): String = buildString {
        append("${reportGymLabel(state)} — ${getString(R.string.revenue_report_sheet_title)}\n")
        append("${getString(R.string.revenue_report_generated_on)}: ${DateUtils.formatDate(DateUtils.now())}\n\n")
        append("${getString(R.string.revenue_report_section_summary)}\n")
        append("${getString(R.string.revenue_today)}: ${CurrencyFormatter.format(state.today, state.currency)}\n")
        append("${getString(R.string.revenue_week)}: ${CurrencyFormatter.format(state.week, state.currency)}\n")
        append("${getString(R.string.revenue_month)}: ${CurrencyFormatter.format(state.month, state.currency)}\n")
        append("${getString(R.string.revenue_year)}: ${CurrencyFormatter.format(state.year, state.currency)}\n\n")
        append("${getString(R.string.revenue_report_section_by_type)}\n")
        append("${getString(R.string.subscription_daily)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.DAILY] ?: 0.0, state.currency)}\n")
        append("${getString(R.string.subscription_weekly)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.WEEKLY] ?: 0.0, state.currency)}\n")
        append("${getString(R.string.subscription_monthly)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.MONTHLY] ?: 0.0, state.currency)}\n")
        append("${getString(R.string.subscription_custom)}: ${CurrencyFormatter.format(state.byType[SubscriptionType.CUSTOM] ?: 0.0, state.currency)}\n\n")
        append("${getString(R.string.revenue_report_section_last7days)}\n")
        state.last7Days.forEachIndexed { index, value ->
            val label = dayLabels.getOrNull(index) ?: (index + 1).toString()
            append("$label: ${CurrencyFormatter.format(value, state.currency)}\n")
        }
    }

    /**
     * البند 31: حوار معاينة مشترك بين مسار النص ومسار Excel — يعرض المحتوى داخل بطاقة
     * قابلة للتمرير والتحديد (textIsSelectable) ولا ينفّذ [onConfirmed] (إطلاق intent
     * المشاركة الفعلي أو بدء تصدير الملف) إلا عند ضغط "مشاركة" صراحة؛ ضغط "إلغاء" أو
     * الرجوع للخلف يُغلق الحوار بلا أي إرسال.
     */
    private fun showReportPreview(content: String, hintRes: Int = R.string.revenue_report_preview_hint, onConfirmed: () -> Unit) {
        previewDialog?.dismiss()
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_report_preview, null)
        view.findViewById<android.widget.TextView>(R.id.tvReportPreviewHint).setText(hintRes)
        view.findViewById<android.widget.TextView>(R.id.tvReportPreviewContent).text = content
        view.findViewById<View>(R.id.btnReportPreviewShare).setOnSingleClickListener {
            dialog.dismiss()
            onConfirmed()
        }
        view.findViewById<View>(R.id.btnReportPreviewCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnReportPreviewShare).attachPressScale()
        view.findViewById<View>(R.id.btnReportPreviewCancel).attachPressScale()

        dialog.setContentView(view)
        // نفس معالجة ورقة «ترتيب القائمة»: حاوية Material شفافة، والزجاج مرسوم من خلفية الجذر (bg_home_sheet).
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        // android:maxHeight لا يعمل على ScrollView: نقيّد ارتفاع الورقة بنحو 42% من الشاشة ويتمرّر الباقي.
        val maxHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        view.findViewById<View>(R.id.scrollReportPreview).doOnLayout {
            if (it.height > maxHeight) it.updateLayoutParams { height = maxHeight }
        }

        dialog.setOnDismissListener { if (previewDialog === dialog) previewDialog = null }
        previewDialog = dialog
        dialog.show()
    }

    /**
     * تصدير تقرير الإيرادات كملف .xlsx حقيقي قابل للفتح في Excel/Google Sheets ومشاركته
     * مباشرة — يسدّ جزءاً من البند 24 (كانت المشاركة نصاً فقط بلا أي ملف جدولي).
     * يبني جدولين: ملخص الفترات الثابتة، والتوزيع حسب نوع الاشتراك، مع أرقام فعلية
     * (لا نصوص) في عمود المبلغ ليتمكّن صاحب النادي من عمل مجاميع/رسوم عليها لاحقاً.
     */
    private fun shareReportAsExcel() {
        val state = viewModel.uiState.value
        val bold = { text: String -> XlsxWriter.Cell(text, bold = true) }
        val plain = { text: String -> XlsxWriter.Cell(text) }
        val amount = { value: Double -> XlsxWriter.Cell(CurrencyFormatter.formatGrouped(value, state.currency), numericValue = value) }

        val rows = mutableListOf<List<XlsxWriter.Cell>>()
        rows.add(listOf(bold("${reportGymLabel(state)} — ${getString(R.string.revenue_report_sheet_title)}")))
        rows.add(listOf(plain("${getString(R.string.revenue_report_generated_on)}: ${DateUtils.formatDate(DateUtils.now())}")))
        rows.add(emptyList())

        rows.add(listOf(bold(getString(R.string.revenue_report_section_summary))))
        rows.add(listOf(bold(getString(R.string.revenue_report_column_period)), bold(getString(R.string.revenue_report_column_amount))))
        rows.add(listOf(plain(getString(R.string.revenue_today)), amount(state.today)))
        rows.add(listOf(plain(getString(R.string.revenue_week)), amount(state.week)))
        rows.add(listOf(plain(getString(R.string.revenue_month)), amount(state.month)))
        rows.add(listOf(plain(getString(R.string.revenue_year)), amount(state.year)))
        rows.add(emptyList())

        rows.add(listOf(bold(getString(R.string.revenue_report_section_by_type))))
        rows.add(listOf(bold(getString(R.string.revenue_report_column_period)), bold(getString(R.string.revenue_report_column_amount))))
        rows.add(listOf(plain(getString(R.string.subscription_daily)), amount(state.byType[SubscriptionType.DAILY] ?: 0.0)))
        rows.add(listOf(plain(getString(R.string.subscription_weekly)), amount(state.byType[SubscriptionType.WEEKLY] ?: 0.0)))
        rows.add(listOf(plain(getString(R.string.subscription_monthly)), amount(state.byType[SubscriptionType.MONTHLY] ?: 0.0)))
        rows.add(listOf(plain(getString(R.string.subscription_custom)), amount(state.byType[SubscriptionType.CUSTOM] ?: 0.0)))
        rows.add(emptyList())

        rows.add(listOf(bold(getString(R.string.revenue_report_section_last7days))))
        val dayLabels = lastSevenDayLabels()
        rows.add(listOf(bold(getString(R.string.revenue_report_column_period)), bold(getString(R.string.revenue_report_column_amount))))
        state.last7Days.forEachIndexed { index, value ->
            val label = dayLabels.getOrNull(index) ?: (index + 1).toString()
            rows.add(listOf(plain(label), amount(value)))
        }

        // البند 31: قبل كان بناء rows يتبعه مباشرة إنشاء الملف وفتح شيت المشاركة بلا أي
        // معاينة. الآن نعرض محتوى الجدولين أولاً (buildExcelPreviewText) في حوار معاينة،
        // ولا يبدأ إنشاء ملف .xlsx الفعلي (setShareReportBusy + exportXlsx) إلا بعد
        // موافقة صريحة من المستخدم على "مشاركة".
        showReportPreview(buildExcelPreviewText(state, dayLabels), R.string.revenue_report_preview_hint_excel) {
            setShareReportBusy(true)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val file = BackupManager.exportXlsx(
                        context = requireContext(),
                        fileNamePrefix = "revenue_report",
                        sheetName = getString(R.string.revenue_report_sheet_title),
                        rows = rows,
                        columnWidths = listOf(28, 16)
                    )
                    BackupManager.shareFile(
                        requireContext(),
                        file,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    )
                } catch (e: Exception) {
                    view?.showSnack(getString(R.string.revenue_excel_export_error), SnackType.ERROR)
                } finally {
                    setShareReportBusy(false)
                }
            }
        }
    }

    /**
     * البند 13: كان الزر يُعطَّل فقط بلا أي مؤشر تحميل مرئي أثناء بناء ملف
     * Excel، فتبدو الشاشة متجمّدة للحظات مع عدد أعضاء/اشتراكات كبير — بنفس
     * نمط setSaving() في AddEditMemberActivity تماماً (نص الزر يختفي، Progress-
     * Bar دوّار يظهر مكانه).
     */
    private fun setShareReportBusy(isBusy: Boolean) {
        // زر المشاركة الوحيد صار في الرأس (الدائرة الزجاجية): نعطّله ونخفّف شفافيته أثناء بناء الملف.
        binding.revBtnShare.isEnabled = !isBusy
        binding.revBtnShare.alpha = if (isBusy) 0.5f else 1f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // المعاينة قد تحمل onConfirmed يستخدم viewLifecycleOwner: نغلقها قبل تدمير الـ View.
        previewDialog?.dismiss()
        previewDialog = null
        rangeSheet?.dismiss()
        rangeSheet = null
        motionJobs.forEach { it.cancel() }
        motionJobs.clear()
        _binding?.revSkeleton?.animate()?.cancel()
        amountAnimator?.cancel()
        amountAnimator = null
        _binding = null
    }

    private companion object {
        /** فارق الدخول المتتابع بين الأقسام. */
        const val STAGGER_MS = 60L
        const val ENTER_DUR_MS = 420L
        /** عدّ البطل عند أول ظهور / عند تبديل الفترة. */
        const val HERO_COUNT_MS = 650L
        const val PERIOD_COUNT_MS = 450L
        /** يبدأ العدّ والنمو بعد بدء تلاشي قسمهما بقليل ليُرى من أوله. */
        const val CHART_LEAD_MS = 140L
    }
}
