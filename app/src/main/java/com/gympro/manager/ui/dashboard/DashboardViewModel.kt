package com.gympro.manager.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.utils.DateUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** فترة بطاقة الإيرادات في تصميم الشاشة الرئيسية (مفتاح "اليوم/الأسبوع/الشهر"). */
enum class RevenuePeriod { DAY, WEEK, MONTH }

data class DashboardUiState(
    val gymName: String = "",
    val currency: String = "₪",
    val activeCount: Int = 0,
    val todayRevenue: Double = 0.0,
    val period: RevenuePeriod = RevenuePeriod.DAY,
    /** إيراد الفترة المختارة (اليوم/الأسبوع حتى الآن/الشهر حتى الآن). */
    val periodRevenue: Double = 0.0,
    /** نسبة التغيّر عن الفترة المماثلة السابقة؛ null إن كانت السابقة صفراً (لا مقارنة ذات معنى). */
    val deltaPercent: Int? = null,
    /** إيراد آخر 7 أيام، الأقدم أولاً (أعمدة الرسم المصغّر). */
    val last7Days: List<Double> = List(7) { 0.0 },
    val unpaidCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val expiringSoon: List<MemberListItem> = emptyList(),
    val unpaidMembers: List<MemberListItem> = emptyList()
)

class DashboardViewModel(private val repository: GymRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _period = MutableStateFlow(RevenuePeriod.DAY)

    fun setPeriod(period: RevenuePeriod) {
        _period.value = period
    }

    /**
     * إيراد الفترة + المقارنة بالفترة المماثلة السابقة (نفس عدد الأيام المنقضية):
     * اليوم ← أمس كاملاً، الأسبوع حتى اليوم ← الأسبوع الماضي حتى نفس اليوم،
     * الشهر حتى اليوم ← الشهر الماضي حتى نفس اليوم.
     */
    private suspend fun periodRevenueWithDelta(period: RevenuePeriod, now: Long): Pair<Double, Int?> {
        val current: Double
        val previous: Double
        when (period) {
            RevenuePeriod.DAY -> {
                current = repository.revenueToday()
                val yesterday = DateUtils.addDays(now, -1)
                previous = repository.revenueForRange(DateUtils.startOfDay(yesterday), DateUtils.endOfDay(yesterday))
            }
            RevenuePeriod.WEEK -> {
                current = repository.revenueThisWeek()
                val weekAgo = DateUtils.addDays(now, -7)
                previous = repository.revenueForRange(DateUtils.startOfWeek(weekAgo), DateUtils.endOfDay(weekAgo))
            }
            RevenuePeriod.MONTH -> {
                current = repository.revenueThisMonth()
                val monthAgo = DateUtils.addMonths(now, -1)
                previous = repository.revenueForRange(DateUtils.startOfMonth(monthAgo), DateUtils.endOfDay(monthAgo))
            }
        }
        val delta = if (previous > 0.0) Math.round((current - previous) / previous * 100.0).toInt() else null
        return current to delta
    }

    /**
     * ينبض مرة واحدة عند بدء التشغيل ثم عند كل تغيّر لليوم التقويمي الحالي (منتصف الليل)،
     * دون أي علاقة بتغيّر البيانات. القيمة المُصدَرة (رقم اليوم) غير مستخدمة في الحساب،
     * الغرض الوحيد منها هو إجبار [combine] أدناه على إعادة التنفيذ عند مرور الوقت فقط.
     *
     * لماذا هذا ضروري: قبل هذا التغيير كان uiState يُعاد حسابه فقط عند تغيّر جدول
     * الأعضاء/الاشتراكات/الإعدادات في قاعدة البيانات (عبر Room Flow)، وطالما التطبيق
     * حيّاً في الخلفية (وهو السلوك الطبيعي لأندرويد عند الرجوع للشاشة الرئيسية بدل
     * إغلاق التطبيق فعلياً) بلا أي عملية كتابة جديدة، تبقى القيمة المخزَّنة في
     * StateFlow كما هي — فيستمر عرض "إيرادات اليوم" الخاصة بأمس بعد منتصف الليل حتى
     * أول عملية كتابة في اليوم الجديد. هذا النبض يضمن إعادة الحساب فوراً عند تغيّر اليوم
     * بغض النظر عن أي كتابة لقاعدة البيانات.
     */
    private fun dayChangeTicker() = flow {
        var lastEmittedDayStart = -1L
        while (true) {
            val todayStart = DateUtils.startOfDay(DateUtils.now())
            if (todayStart != lastEmittedDayStart) {
                lastEmittedDayStart = todayStart
                emit(todayStart)
            }
            delay(60_000L)
        }
    }

    init {
        viewModelScope.launch {
            combine(
                repository.observeActiveMembers(""),
                repository.observeSettings(),
                dayChangeTicker(),
                _period
            ) { members, settings, _, period -> Triple(members, settings, period) }
                .collect { (members, settings, period) ->
                    val now = DateUtils.now()
                    // "نشط" = لديه اشتراك بدأ فعلاً ولم ينتهِ بعد (startDate <= اليوم <= endDate).
                    // members.size يعدّ الجميع حتى المنتهين منذ أشهر أو الذين لم يبدأ اشتراكهم بعد — خاطئ.
                    val activeMembers = members.filter { it.isActive(now) }
                    val expiring = activeMembers
                        .filter { DateUtils.isExpiringSoon(it.endDate!!, 3, now) }
                        .sortedBy { it.endDate }
                    val unpaid = members.filter { it.isUnpaid() }
                    val todayRevenue = repository.revenueToday()
                    val (periodRevenue, delta) = periodRevenueWithDelta(period, now)
                    val last7 = repository.revenueLast7Days()

                    _uiState.value = DashboardUiState(
                        gymName = settings?.gymName.orEmpty(),
                        currency = settings?.currencySymbol ?: "₪",
                        activeCount = activeMembers.size,
                        todayRevenue = todayRevenue,
                        period = period,
                        periodRevenue = periodRevenue,
                        deltaPercent = delta,
                        last7Days = last7,
                        unpaidCount = unpaid.size,
                        expiringSoonCount = expiring.size,
                        expiringSoon = expiring.take(8),
                        unpaidMembers = unpaid.take(8)
                    )
                }
        }
    }

    class Factory(private val repository: GymRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DashboardViewModel(repository) as T
    }
}
