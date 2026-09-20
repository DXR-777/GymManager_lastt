package com.gympro.manager.ui.revenue

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.databinding.FragmentRevenueBinding
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.utils.BackupManager
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.XlsxWriter
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.toast
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

class RevenueFragment : Fragment() {

    private var _binding: FragmentRevenueBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RevenueViewModel by viewModels {
        RevenueViewModel.Factory((requireActivity().application as GymApplication).repository)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRevenueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChartStyle()
        setupDots()
        binding.btnShareReport.setOnSingleClickListener { shareReport() }
        binding.rowCustomRange.setOnSingleClickListener { showCustomRangePicker() }
        binding.btnClearCustomRange.setOnClickListener {
            binding.containerCustomRangeResult.visibleIf(false)
        }
        observeState()
    }

    private fun setupDots() {
        binding.dotDaily.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.chart_daily))
        binding.dotWeekly.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.chart_weekly))
        binding.dotMonthly.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.chart_monthly))
        binding.dotCustom.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.chart_custom))
    }

    private fun setupChartStyle() {
        val secondary = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        // QA (Dark Glassmorphism 2026): شبكة المحور Y كانت بلون @color/divider الصلب
        // (رمادي عادي)، استُبدل بـ glass_border_strong (أبيض شفاف 25%) مع خط متقطع
        // رفيع ليتماشى مع سطوح الزجاج المستخدمة في بطاقة الرسم نفسها (bg_card_glass)
        // بدل خط شبكة صلب غير متّسق مع الهوية الجديدة.
        val gridColor = ContextCompat.getColor(requireContext(), R.color.glass_border_strong)
        binding.barChartLast7Days.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setTouchEnabled(false)
            setDrawGridBackground(false)
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.textColor = secondary
            axisLeft.textSize = 10f
            axisLeft.setLabelCount(4, true)
            axisLeft.gridColor = gridColor
            axisLeft.gridLineWidth = 0.6f
            axisLeft.enableGridDashedLine(4f, 4f, 0f)
            axisLeft.setDrawAxisLine(false)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.setDrawAxisLine(false)
            xAxis.granularity = 1f
            // مكتبة الرسم البياني (MPAndroidChart) لا تعرض النص العربي بشكل صحيح على محورها أصلاً
            // (تظهر رموزاً غير مفهومة)، لذا نُعطّل تسمياتها هنا ونعرض أيام الأسبوع بعناصر
            // TextView عربية طبيعية أسفل الرسم مباشرة (في lastSevenDayLabels/renderDayLabels).
            xAxis.setDrawLabels(false)
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

    /** يبني صفّ تسميات الأيام بعناصر TextView عربية طبيعية (بدل تسميات المخطط المعطوبة) */
    private fun renderDayLabels(labels: List<String>) {
        binding.llDayLabels.removeAllViews()
        labels.forEachIndexed { index, day ->
            val isToday = index == labels.lastIndex
            val textView = android.widget.TextView(requireContext()).apply {
                text = day
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (isToday) R.color.brand_accent else R.color.text_secondary
                    )
                )
                setTypeface(typeface, if (isToday) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            binding.llDayLabels.addView(textView)
        }
    }

    private fun updateChart(values: List<Double>, currency: String) {
        val entries = values.mapIndexed { index, value -> BarEntry(index.toFloat(), value.toFloat()) }
        // QA (Dark Glassmorphism 2026): الأعمدة كانت بلون brand_accent الأحمر المصمت
        // الواحد. MPAndroidChart v3.1.0 يدعم BarDataSet.setGradientColor(start, end)
        // (تدرّج عمودي جاهز على مستوى الـ dataSet بالكامل) بلا أي تبعية إضافية، فاستُبدل
        // اللون المصمت بتدرّج brand_gradient (brand_gradient_start → brand_gradient_end)
        // ليطابق هوية التدرّج المعتمدة في بقية الشاشة. ملاحظة: هذه النسخة من المكتبة لا
        // تدعم حدوداً دائرية (rounded corners) للأعمدة أصلاً بدون Renderer مخصّص، فلم تُطبَّق
        // تجنباً لتوسيع النطاق خارج تنسيق المخطط.
        val gradientStart = ContextCompat.getColor(requireContext(), R.color.brand_gradient_start)
        val gradientEnd = ContextCompat.getColor(requireContext(), R.color.brand_gradient_end)
        val valueTextColor = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val dataSet = BarDataSet(entries, "").apply {
            setGradientColor(gradientStart, gradientEnd)
            // البند 22: كانت القيم مخفية تماماً (setDrawValues(false)) مع touch معطّل
            // (setTouchEnabled(false))، فلا توجد أي طريقة لمعرفة القيمة الدقيقة ليوم
            // معيّن من الأيام السبعة — فقط ارتفاع نسبي بالعين. الآن تُعرض القيمة
            // المُنسَّقة بالعملة فوق كل عمود مباشرة، فتُقرأ القيمة الدقيقة لأي يوم
            // بنظرة واحدة دون الحاجة لأي تفاعل باللمس.
            setDrawValues(true)
            this.valueTextColor = valueTextColor
            valueTextSize = 9f
            valueFormatter = object : ValueFormatter() {
                override fun getBarLabel(barEntry: BarEntry?): String =
                    CurrencyFormatter.format((barEntry?.y ?: 0f).toDouble(), currency)
            }
        }
        binding.barChartLast7Days.data = BarData(dataSet).apply { barWidth = 0.55f }
        // مساحة إضافية أعلى المخطط كي لا تُقصّ تسمية القيمة فوق أطول عمود؛ لو كانت كل
        // القيم صفراً نترك المحور بلا حدّ أقصى مفروض (auto) بدل ضبطه على صفر.
        val maxValue = values.maxOrNull() ?: 0.0
        if (maxValue > 0.0) {
            binding.barChartLast7Days.axisLeft.axisMaximum = maxValue.toFloat() * 1.2f
        } else {
            binding.barChartLast7Days.axisLeft.resetAxisMaximum()
        }
        binding.barChartLast7Days.invalidate()
        renderDayLabels(lastSevenDayLabels())
        binding.tvNoChartData.visibleIf(values.all { it == 0.0 })
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvRevenueToday.text = CurrencyFormatter.format(state.today, state.currency)
                    binding.tvRevenueWeek.text = CurrencyFormatter.format(state.week, state.currency)
                    binding.tvRevenueMonth.text = CurrencyFormatter.format(state.month, state.currency)
                    binding.tvRevenueYear.text = CurrencyFormatter.format(state.year, state.currency)

                    binding.tvCompareLastMonth.text = buildCompareText(state.month, state.lastMonth)

                    updateChart(state.last7Days, state.currency)

                    binding.tvRevenueDaily.text = CurrencyFormatter.format(state.byType[SubscriptionType.DAILY] ?: 0.0, state.currency)
                    binding.tvRevenueWeekly.text = CurrencyFormatter.format(state.byType[SubscriptionType.WEEKLY] ?: 0.0, state.currency)
                    binding.tvRevenueMonthly.text = CurrencyFormatter.format(state.byType[SubscriptionType.MONTHLY] ?: 0.0, state.currency)
                    binding.tvRevenueCustom.text = CurrencyFormatter.format(state.byType[SubscriptionType.CUSTOM] ?: 0.0, state.currency)
                }
            }
        }
    }

    private fun buildCompareText(month: Double, lastMonth: Double): String {
        if (lastMonth <= 0.0) return getString(R.string.revenue_compare_no_data)
        val diffPercent = (((month - lastMonth) / lastMonth) * 100).roundToInt()
        return when {
            diffPercent > 0 -> "📈 ${getString(R.string.revenue_compare_last_month)}: +$diffPercent%"
            diffPercent < 0 -> "📉 ${getString(R.string.revenue_compare_last_month)}: $diffPercent%"
            else -> "➖ ${getString(R.string.revenue_compare_last_month)}: 0%"
        }
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
            .setTitleText(R.string.revenue_custom_range_action)
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
            binding.tvCustomRangeTotal.text = CurrencyFormatter.format(total, currency)
            binding.containerCustomRangeResult.visibleIf(true)
        }
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
        val view = layoutInflater.inflate(R.layout.dialog_report_preview, null)
        view.findViewById<android.widget.TextView>(R.id.tvReportPreviewHint).setText(hintRes)
        view.findViewById<android.widget.TextView>(R.id.tvReportPreviewContent).text = content
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.revenue_report_preview_title)
            .setView(view)
            .setPositiveButton(R.string.revenue_report_preview_action_share) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.revenue_report_preview_action_cancel, null)
            .show()
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
        val amount = { value: Double -> XlsxWriter.Cell(CurrencyFormatter.format(value, state.currency), numericValue = value) }

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
                    requireContext().toast(getString(R.string.revenue_excel_export_error))
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
        binding.btnShareReport.isEnabled = !isBusy
        binding.btnShareReport.text = if (isBusy) "" else getString(R.string.revenue_share_report)
        binding.progressShareReport.visibility = if (isBusy) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
