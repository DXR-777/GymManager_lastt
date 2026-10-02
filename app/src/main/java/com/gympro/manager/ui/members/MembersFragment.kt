package com.gympro.manager.ui.members

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import android.content.res.Configuration
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.databinding.FragmentMembersBinding
import com.gympro.manager.model.MemberFilter
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.model.MemberSort
import com.gympro.manager.model.PaymentStatus
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.ui.archive.ArchiveActivity
import com.gympro.manager.ui.widgets.GridSpacingItemDecoration
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.WhatsAppHelper
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MembersFragment : Fragment() {

    companion object {
        private const val ARG_INITIAL_FILTER = "arg_initial_filter"

        /**
         * ينشئ الشاشة مع تطبيق فلتر ابتدائي (مثلاً عند الضغط على بطاقة من لوحة التحكم) —
         * انظر MainActivity.openMembersFiltered().
         */
        fun newInstance(initialFilter: MemberFilter? = null): MembersFragment {
            val fragment = MembersFragment()
            if (initialFilter != null) {
                fragment.arguments = Bundle().apply {
                    putString(ARG_INITIAL_FILTER, initialFilter.name)
                }
            }
            return fragment
        }
    }

    private var _binding: FragmentMembersBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MembersViewModel by viewModels {
        MembersViewModel.Factory((requireActivity().application as GymApplication).repository)
    }

    private lateinit var adapter: MembersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMembersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        setupRecyclerView()
        setupSearch()
        setupFilterChips()
        applyInitialFilterIfAny()
        observeMembers()
    }

    /**
     * يقرأ الفلتر الابتدائي (إن وُجد) من arguments ويطبّقه — يغطي فقط لحظة إنشاء أول
     * نسخة من هذا الفراغمنت. راجع applyFilter() أدناه للحالة الأخرى (نسخة موجودة أصلاً).
     */
    private fun applyInitialFilterIfAny() {
        val filterName = arguments?.getString(ARG_INITIAL_FILTER) ?: return
        val filter = runCatching { MemberFilter.valueOf(filterName) }.getOrNull() ?: return
        applyFilter(filter)
    }

    /**
     * يطبّق فلتراً على القائمة والـ Chip المطابق له. عامة (public) كي تستدعيها
     * MainActivity.openMembersFiltered() مباشرة على نسخة هذا الفراغمنت القائمة فعلاً
     * (منذ إصلاح فقدان الحالة عند تبديل التبويبات — راجع showTab في MainActivity)، بدل
     * الاعتماد فقط على arguments التي لا تُقرأ إلا مرة واحدة عند الإنشاء الأول.
     */
    fun applyFilter(filter: MemberFilter) {
        val chipId = when (filter) {
            MemberFilter.ALL -> R.id.chipAll
            MemberFilter.ACTIVE -> R.id.chipActive
            MemberFilter.EXPIRING_SOON -> R.id.chipExpiring
            MemberFilter.EXPIRED -> R.id.chipExpired
            MemberFilter.UNPAID -> R.id.chipUnpaid
        }
        binding.chipGroupFilter.check(chipId)
        viewModel.setFilter(filter)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.member_list_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_archive) {
            startActivity(Intent(requireContext(), ArchiveActivity::class.java))
            return true
        }
        if (item.itemId == R.id.action_sort) {
            showSortDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * حوار فرز قائمة الأعضاء — راجع البند 14. القائمة السابقة كانت تدعم فقط بحث نصي
     * وفلاتر حالة (نشط/منتهي/...) بلا أي طريقة لإعادة ترتيب النتائج نفسها.
     */
    private fun showSortDialog() {
        val sorts = arrayOf(MemberSort.NAME, MemberSort.EXPIRY_SOONEST, MemberSort.NEWEST)
        val labels = arrayOf(
            getString(R.string.members_sort_name),
            getString(R.string.members_sort_expiry_soonest),
            getString(R.string.members_sort_newest)
        )
        val checkedIndex = sorts.indexOf(viewModel.currentSort.value).coerceAtLeast(0)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.members_sort_action)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                viewModel.setSort(sorts[which])
                dialog.dismiss()
            }
            .show()
    }

    /**
     * البند 8: على الشاشات الكبيرة (عرض متاح > 600dp) أو في الوضع الأفقي، تتمدد بطاقة
     * العضو بعرض الشاشة كاملاً وتفقد اتجاهها البصري (رقم/اسم قصيرين داخل بطاقة عريضة
     * جداً). نستبدل العمود الواحد بشبكة 4 أعمدة في هذه الحالة بدل تخطيطات منفصلة
     * (layout-sw600dp / layout-land)، فتُعاد حسابها تلقائياً عند الدوران لأن Fragment
     * تُعاد إنشاؤه افتراضياً مع تغيّر الإعدادات.
     */
    private fun useGridLayout(): Boolean {
        val config = resources.configuration
        return config.screenWidthDp > 600 || config.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    private fun setupRecyclerView() {
        adapter = MembersAdapter(
            onClick = { member -> openDetail(member.id) },
            onWhatsappClick = { member -> sendWhatsapp(member) }
        )
        val spanCount = if (useGridLayout()) 4 else 1
        binding.rvMembers.layoutManager = GridLayoutManager(context, spanCount)
        if (spanCount > 1) {
            val spacingPx = resources.getDimensionPixelSize(R.dimen.spacing_card_gap)
            binding.rvMembers.addItemDecoration(GridSpacingItemDecoration(spanCount, spacingPx))
        }
        binding.rvMembers.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setQuery(s?.toString().orEmpty())
                binding.btnClearSearch.visibleIf(!s.isNullOrEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.text?.clear() }
    }

    private fun setupFilterChips() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newFilter = when (id) {
                R.id.chipActive -> MemberFilter.ACTIVE
                R.id.chipExpiring -> MemberFilter.EXPIRING_SOON
                R.id.chipExpired -> MemberFilter.EXPIRED
                R.id.chipUnpaid -> MemberFilter.UNPAID
                else -> MemberFilter.ALL
            }
            viewModel.setFilter(newFilter)
        }
    }

    private fun openDetail(memberId: Long) {
        startActivity(
            Intent(requireContext(), MemberDetailActivity::class.java)
                .putExtra(MemberDetailActivity.EXTRA_MEMBER_ID, memberId)
        )
    }

    /**
     * راجع البند 24: تُغذّى القائمة الآن عبر adapter.submitData() (Paging 3) بدل
     * adapter.submitList() على قائمة كاملة جاهزة مسبقاً. حالتا التحميل الأول والقائمة
     * الفارغة (البند 15) تُستمَدّان من adapter.loadStateFlow بدل فحص list.isEmpty()
     * مباشرة — وهو النمط القياسي الموصى به مع PagingDataAdapter، إذ لا توجد قائمة
     * كاملة بعد الآن للفحص المباشر عليها.
     */
    private fun observeMembers() {
        binding.progressMembersLoading.visibleIf(true)
        binding.rvMembers.visibleIf(false)
        binding.emptyMembersContainer.visibleIf(false)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pagedMembers.collectLatest { pagingData ->
                        adapter.submitData(pagingData)
                    }
                }
                launch {
                    adapter.loadStateFlow.collectLatest { loadStates ->
                        val isLoading = loadStates.refresh is LoadState.Loading
                        val isEmpty = loadStates.refresh is LoadState.NotLoading && adapter.itemCount == 0
                        binding.progressMembersLoading.visibleIf(isLoading)
                        binding.rvMembers.visibleIf(!isLoading && !isEmpty)
                        binding.emptyMembersContainer.visibleIf(!isLoading && isEmpty)
                        // البند 16: رسالة "لا يوجد أعضاء بعد / اضغط + لإضافة أول عضو" كانت تظهر
                        // أيضاً عند عدم وجود نتائج بسبب بحث أو فلتر (مثلاً "غير مدفوع")، وهي
                        // رسالة مضلِّلة في تلك الحالة لأن المشكلة ليست غياب الأعضاء فعلياً.
                        if (!isLoading && isEmpty) {
                            val isFiltered = viewModel.currentQuery.value.isNotBlank() ||
                                viewModel.currentFilter.value != MemberFilter.ALL
                            binding.tvMembersEmptyMessage.setText(
                                if (isFiltered) R.string.members_no_results else R.string.members_empty
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sendWhatsapp(member: MemberListItem, forcedPrefix: String? = null) {
        // البند 18: تحقّق أخير من صيغة الرقم قبل محاولة الإرسال — يمنع بناء رابط واتساب
        // من رقم غير صالح (بيانات قديمة سابقة لتحقق AddEditMemberActivity) وإرساله لجهة
        // خاطئة أو لا أحد بصمت.
        if (!WhatsAppHelper.isSendableNumber(member.phone)) {
            Snackbar.make(binding.root, getString(R.string.whatsapp_invalid_phone), Snackbar.LENGTH_LONG).show()
            return
        }
        val settings = viewModel.settings.value
        val candidates = forcedPrefix?.let { listOf(it) } ?: WhatsAppHelper.candidatePrefixes(settings.whatsappPrefix)
        val prefix = candidates.first()
        val number = WhatsAppHelper.toInternationalNumber(member.phone, prefix)

        val typeLabel = when (member.type) {
            SubscriptionType.DAILY -> getString(R.string.subscription_daily)
            SubscriptionType.WEEKLY -> getString(R.string.subscription_weekly)
            SubscriptionType.MONTHLY -> getString(R.string.subscription_monthly)
            SubscriptionType.CUSTOM -> getString(R.string.subscription_custom)
            null -> "—"
        }
        val startLabel = member.startDate?.let { DateUtils.formatDate(it) } ?: "—"
        val endLabel = member.endDate?.let { DateUtils.formatDate(it) } ?: "—"
        val priceLabel = CurrencyFormatter.format(member.price ?: 0.0, settings.currencySymbol)
        // حالة الدفع: آخر اشتراك فقط (للمرجع السريع) — 3 حالات الآن بحسب ميزة "الدفع
        // الجزئي" الجديدة (راجع MemberListItem.lastSubscriptionStatus()).
        val paidLabel = when (member.lastSubscriptionStatus()) {
            PaymentStatus.PAID -> getString(R.string.status_paid)
            PaymentStatus.PARTIAL -> getString(R.string.payment_status_partial)
            PaymentStatus.UNPAID -> getString(R.string.status_unpaid)
            null -> "—"
        }
        val daysLabel = member.endDate?.let {
            val days = DateUtils.daysRemaining(it)
            if (days < 0) getString(R.string.days_remaining_expired, -days) else days.toString()
        } ?: "—"

        // سطر الدَّين: يظهر فقط إذا كان على العضو ديون قديمة غير مسدَّدة
        // هذا مستقل عن isPaid (الذي يصف آخر اشتراك فقط) —
        // عضو دفع آخر شهر لكن لا يزال يدين بشهر سابق سيحصل على تحذير واضح
        val debtLine = if (member.outstandingBalance > 0.0)
            getString(R.string.whatsapp_debt_line,
                CurrencyFormatter.format(member.outstandingBalance, settings.currencySymbol))
        else ""

        val message = WhatsAppHelper.buildInvoiceMessage(
            requireContext(), settings.gymName, member.name, typeLabel, startLabel, endLabel,
            priceLabel, paidLabel, daysLabel, debtLine
        )

        val result = WhatsAppHelper.openChat(requireContext(), number, message)

        // البند 17 و20: نفس منطق MemberDetailActivity — رسالة واثقة فقط عند فتح
        // تطبيق واتساب فعلياً؛ رسالة أقل ثقة صريحة عند اللجوء لحل المتصفح الاحتياطي
        // (لا ضمان إرسال فعلي)؛ ولا رسالة إطلاقاً عند الفشل الكامل (Toast من
        // openChat() يكفي).
        when (result) {
            WhatsAppHelper.SendResult.AppOpened -> {
                Snackbar.make(binding.root, getString(R.string.whatsapp_sending_to, number), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.whatsapp_try_other_prefix)) {
                        sendWhatsapp(member, forcedPrefix = WhatsAppHelper.otherPrefix(prefix))
                    }
                    .show()
            }
            WhatsAppHelper.SendResult.BrowserFallback -> {
                Snackbar.make(binding.root, getString(R.string.whatsapp_sending_browser_fallback, number), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.whatsapp_try_other_prefix)) {
                        sendWhatsapp(member, forcedPrefix = WhatsAppHelper.otherPrefix(prefix))
                    }
                    .show()
            }
            WhatsAppHelper.SendResult.Failed -> Unit
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
