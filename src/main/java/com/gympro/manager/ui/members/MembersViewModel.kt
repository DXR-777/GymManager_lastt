package com.gympro.manager.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.gympro.manager.data.local.entities.GymSettingsEntity
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.model.MemberFilter
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.model.MemberSort
import com.gympro.manager.utils.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MembersViewModel(private val repository: GymRepository) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(MemberFilter.ALL)
    private val sort = MutableStateFlow(MemberSort.NAME)

    val settings: StateFlow<GymSettingsEntity> = repository.observeSettings()
        .map { it ?: GymSettingsEntity() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GymSettingsEntity())

    /**
     * راجع البند 24: قائمة الأعضاء مُقسَّمة على صفحات (Paging 3) بدل تحميل النتيجة
     * كاملة في StateFlow<List<...>> دفعة واحدة كما كانت سابقاً — مع نادٍ كبير (600+ عضو)
     * كان كل تغيير بسيط (بحث/فلتر/تعديل عضو واحد) يُعيد تحميل وحساب كل الصفوف من جديد.
     * الفرز (الاسم/الأقرب انتهاءً/الأحدث) يُنفَّذ في SQL مباشرة عبر
     * GymRepository.getActiveMembersPaged (راجع MemberDao لثلاث دوال الفرز المنفصلة).
     * فلاتر الحالة (نشط/منتهي/غير مدفوع/قريب الانتهاء) تبقى في Kotlin عبر PagingData.filter
     * باستخدام نفس دوال MemberListItem.isActive/isExpired/isUnpaid بالضبط — بلا أي تغيير
     * في منطق التصفية نفسه، فقط في مكان تنفيذه، حتى لا تُفقد الدقة الموثَّقة في DateUtils
     * (حساب يوم يولياني مصمَّم خصيصاً لتفادي أخطاء التوقيت الصيفي) بمحاولة إعادة كتابتها
     * كشرط SQL مختلف قد يتصرف بشكل مختلف عند حواف التوقيت الصيفي.
     * debounce/distinctUntilChanged على البحث بنفس القيم والسبب المستخدمين سابقاً
     * (راجع التوثيق الأصلي أدناه على currentQuery) — لم يتغيّر شيء هنا.
     * cachedIn(viewModelScope): يحتفظ بالصفحات المُحمَّلة عبر دورة حياة الشاشة (تدوير
     * الجهاز مثلاً) بدل إعادة تحميلها من الصفر في كل اشتراك جديد.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val pagedMembers: Flow<PagingData<MemberListItem>> =
        combine(
            query.debounce(300).distinctUntilChanged(),
            filter,
            sort
        ) { q, currentFilter, currentSort -> Triple(q, currentFilter, currentSort) }
            .flatMapLatest { (q, currentFilter, currentSort) ->
                repository.getActiveMembersPaged(q, currentSort).map { pagingData ->
                    pagingData.filter { member -> matchesFilter(member, currentFilter) }
                }
            }
            .cachedIn(viewModelScope)

    val currentFilter: StateFlow<MemberFilter> = filter.asStateFlow()
    val currentSort: StateFlow<MemberSort> = sort.asStateFlow()
    /** يبقى غير مؤخَّر عمداً (لا debounce) كي يعكس نص حقل البحث الفعلي فوراً لأي
     *  استخدام واجهة آخر مثل رسالة "لا نتائج لبحثك" في MembersFragment. */
    val currentQuery: StateFlow<String> = query.asStateFlow()

    fun setQuery(text: String) {
        query.value = text
    }

    fun setFilter(newFilter: MemberFilter) {
        filter.value = newFilter
    }

    fun setSort(newSort: MemberSort) {
        sort.value = newSort
    }

    private fun matchesFilter(member: MemberListItem, filterValue: MemberFilter): Boolean {
        val now = DateUtils.now()
        return when (filterValue) {
            MemberFilter.ALL -> true
            MemberFilter.ACTIVE -> member.isActive(now)
            MemberFilter.EXPIRED -> member.isExpired(now)
            MemberFilter.UNPAID -> member.isUnpaid()
            MemberFilter.EXPIRING_SOON ->
                member.isActive(now) && DateUtils.isExpiringSoon(member.endDate!!, 3, now)
        }
    }

    class Factory(private val repository: GymRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MembersViewModel(repository) as T
    }
}
