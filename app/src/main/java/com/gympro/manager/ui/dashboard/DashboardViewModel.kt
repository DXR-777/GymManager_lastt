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

data class DashboardUiState(
    val gymName: String = "",
    val currency: String = "₪",
    val activeCount: Int = 0,
    val todayRevenue: Double = 0.0,
    val unpaidCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val expiringSoon: List<MemberListItem> = emptyList(),
    val unpaidMembers: List<MemberListItem> = emptyList()
)

class DashboardViewModel(private val repository: GymRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

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
                dayChangeTicker()
            ) { members, settings, _ -> members to settings }
                .collect { (members, settings) ->
                    val now = DateUtils.now()
                    // "نشط" = لديه اشتراك بدأ فعلاً ولم ينتهِ بعد (startDate <= اليوم <= endDate).
                    // members.size يعدّ الجميع حتى المنتهين منذ أشهر أو الذين لم يبدأ اشتراكهم بعد — خاطئ.
                    val activeMembers = members.filter { it.isActive(now) }
                    val expiring = activeMembers
                        .filter { DateUtils.isExpiringSoon(it.endDate!!, 3, now) }
                        .sortedBy { it.endDate }
                    val unpaid = members.filter { it.isUnpaid() }
                    val todayRevenue = repository.revenueToday()

                    _uiState.value = DashboardUiState(
                        gymName = settings?.gymName.orEmpty(),
                        currency = settings?.currencySymbol ?: "₪",
                        activeCount = activeMembers.size,
                        todayRevenue = todayRevenue,
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
