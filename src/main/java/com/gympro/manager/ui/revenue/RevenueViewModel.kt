package com.gympro.manager.ui.revenue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.utils.DateUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

data class RevenueUiState(
    /** اسم النادي من الإعدادات — يُستخدم في عنوان تقرير المشاركة (البند 24)
     *  بدل عنوان عام لا يوضّح لأي نادٍ يعود التقرير. */
    val gymName: String = "",
    val currency: String = "₪",
    val today: Double = 0.0,
    val week: Double = 0.0,
    val month: Double = 0.0,
    val year: Double = 0.0,
    val lastMonth: Double = 0.0,
    val last7Days: List<Double> = emptyList(),
    val byType: Map<SubscriptionType, Double> = emptyMap()
)

class RevenueViewModel(private val repository: GymRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(RevenueUiState())
    val uiState: StateFlow<RevenueUiState> = _uiState.asStateFlow()

    /**
     * ينبض مرة واحدة عند بدء التشغيل ثم عند كل تغيّر لليوم التقويمي الحالي (منتصف الليل)،
     * دون أي علاقة بتغيّر البيانات — نفس الآلية والتوثيق الكامل الموجودين في
     * DashboardViewModel.dayChangeTicker(). ضروري هنا لأن today/week/month/last7Days
     * كانت تُعاد حسابها فقط عند كتابة جديدة في قاعدة البيانات، فتبقى قيمة "إيرادات اليوم"
     * الخاصة بالأمس معروضة بعد منتصف الليل حتى أول عملية كتابة في اليوم الجديد.
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
        // إعادة الحساب تلقائياً عند أي تغيير في الأعضاء/الاشتراكات أو الإعدادات، وأيضاً
        // عند تغيّر اليوم التقويمي (راجع dayChangeTicker) حتى بلا أي تغيير في البيانات.
        viewModelScope.launch {
            combine(
                repository.observeActiveMembers(""),
                repository.observeSettings(),
                dayChangeTicker()
            ) { _, settings, _ -> settings }
                .collect { settings ->
                    val monthStart = DateUtils.startOfMonth()
                    val monthEnd = DateUtils.endOfDay(DateUtils.now())
                    _uiState.value = RevenueUiState(
                        gymName = settings?.gymName.orEmpty(),
                        currency = settings?.currencySymbol ?: "₪",
                        today = repository.revenueToday(),
                        week = repository.revenueThisWeek(),
                        month = repository.revenueThisMonth(),
                        year = repository.revenueThisYear(),
                        lastMonth = repository.revenueLastMonth(),
                        last7Days = repository.revenueLast7Days(),
                        byType = repository.revenueByType(monthStart, monthEnd)
                    )
                }
        }
    }

    /**
     * إيراد أي نطاق تاريخ حرّ يختاره صاحب النادي (شهر سابق أو فترة مخصصة) — راجع
     * البند 23. لا يُخزَّن في uiState لأنه استعلام لحظي عند الطلب، لا حالة دائمة
     * يجب إعادة حسابها تلقائياً عند كل تغيير في البيانات كباقي الفترات الثابتة.
     */
    suspend fun revenueForRange(start: Long, end: Long): Double = repository.revenueForRange(start, end)

    class Factory(private val repository: GymRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RevenueViewModel(repository) as T
    }
}
