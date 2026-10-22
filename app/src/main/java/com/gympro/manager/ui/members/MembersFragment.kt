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
import android.content.res.Configuration
import androidx.recyclerview.widget.GridLayoutManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.gympro.manager.utils.SnackType
import com.gympro.manager.utils.showSnack
import com.gympro.manager.utils.Haptic
import com.gympro.manager.utils.haptic
import com.gympro.manager.utils.MemberHighlight
import com.gympro.manager.utils.setOnSingleClickListener

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
        if (true) {
            binding.chipAll.setOnLongClickListener { showLayoutDiag(); true }
        }
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
    /** يمنع اهتزاز التبديل عندما يُحدَّد الفلتر برمجياً (من لوحة التحكم) لا بلمسة المستخدم. */
    private var programmaticFilterChange = false

    private var pendingHighlightId: Long? = null
    private val closeEntranceRunnable = Runnable { if (_binding != null) adapter.entranceEnabled = false }

    /** يفتح نافذة حركة دخول العناصر لمدة محدودة ثم يغلقها (لا حركة أثناء التمرير العادي). */
    private fun openEntranceWindow(durationMs: Long) {
        val root = _binding?.root ?: return
        adapter.entranceEnabled = true
        root.removeCallbacks(closeEntranceRunnable)
        root.postDelayed(closeEntranceRunnable, durationMs)
    }

    /** يلتقط معرّف العضو المُضاف حديثاً (إن وُجد) قبل إعادة تحميل القائمة. */
    private var highlightAttempts = 0
    private var debugStrip: android.widget.TextView? = null

    private fun ensureDebugStrip() {
        if (!true || debugStrip != null) return
        val host = binding.root as? android.widget.LinearLayout ?: return
        debugStrip = android.widget.TextView(requireContext()).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.YELLOW)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(12, 6, 12, 6)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        host.addView(
            debugStrip,
            2,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun verifyListRendered() {
        val b = _binding ?: return
        val rv = b.rvMembers
        if (adapter.itemCount > 0 && (rv.childCount == 0 || rv.height == 0)) {
            (rv.parent as? View)?.requestLayout()
            rv.adapter = adapter
            rv.requestLayout()
        }
        debugStrip?.text = "flow=$lastEmittedCount adapter=${adapter.itemCount} children=${rv.childCount} " +
            "rv=${rv.width}x${rv.height} vis=${rv.visibility} hidden=$isHidden " +
            "filter=${viewModel.currentFilter.value} q='${viewModel.currentQuery.value}' " +
            "skel=${b.skeletonMembers.visibility} empty=${b.emptyMembersContainer.visibility} " +
            "c0=${rv.getChildAt(0)?.let { "${it.width}x${it.height} a=${it.alpha}" }} " +
            "first=${adapter.currentList.firstOrNull()?.name}"
    }
    private var lastEmittedCount = -1

    private var diagTicks = 0
    private var diagShown = false

    private fun describeView(v: View?, name: String): String {
        if (v == null) return "$name=null"
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return "$name: ${v.width}x${v.height} y=${loc[1]} vis=${v.visibility} a=${v.alpha} " +
            "ty=${v.translationY} sy=${v.scaleY} shown=${v.isShown}"
    }

    private fun showLayoutDiag() {
        val b = _binding ?: return
        val root = b.root
        val rootParent = root.parent as? View
        val lines = listOf(
            describeView(root, "root"),
            "rootParent=${root.parent?.javaClass?.simpleName} ${rootParent?.width}x${rootParent?.height} " +
                "clip=${(root.parent as? ViewGroup)?.clipChildren}",
            "rootLP=${root.layoutParams?.width}x${root.layoutParams?.height} pad=${root.paddingTop}/${root.paddingBottom}",
            describeView(root.getChildAt(0), "search"),
            describeView(root.getChildAt(1), "chips"),
            describeView(debugStrip, "strip"),
            describeView(b.rvMembers.parent as? View, "rvFrame"),
            describeView(b.rvMembers, "rv"),
            describeView(b.emptyMembersContainer, "empty"),
            describeView(b.skeletonMembers, "skel"),
            "children=${root.childCount} flow=$lastEmittedCount adapter=${adapter.itemCount} " +
                "rvChildren=${b.rvMembers.childCount} rvLM=${b.rvMembers.layoutManager?.javaClass?.simpleName}"
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("layout diag v6")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showDebugInfo() {
        val rv = binding.rvMembers
        val repo = (requireActivity().application as GymApplication).repository
        viewLifecycleOwner.lifecycleScope.launch {
            val dbCount = try { repo.observeActiveMembersCount().first() } catch (e: Exception) { -1 }
            val msg = "db=$dbCount\nflow=$lastEmittedCount\nadapter=${adapter.itemCount}\n" +
                "children=${rv.childCount}\nrvVisible=${rv.visibility == View.VISIBLE}\n" +
                "rv=${rv.width}x${rv.height}\nrvAlpha=${rv.alpha}\n" +
                "filter=${viewModel.currentFilter.value}\nquery='${viewModel.currentQuery.value}'\n" +
                "sort=${viewModel.currentSort.value}\nhidden=$isHidden"
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("debug").setMessage(msg).setPositiveButton("OK", null).show()
        }
    }

    private fun prepareHighlight() {
        MemberHighlight.consume()?.let {
            pendingHighlightId = it
            highlightAttempts = 0
            _binding?.rvMembers?.post { if (_binding != null) highlightPendingMember() }
        }
    }

    private fun highlightPendingMember() {
        val id = pendingHighlightId ?: return
        val pos = adapter.currentList.indexOfFirst { it.id == id }
        if (pos < 0) {
            if (++highlightAttempts >= 4) pendingHighlightId = null
            return
        }
        pendingHighlightId = null
        adapter.highlightId = id
        binding.rvMembers.scrollToPosition(pos)
        adapter.notifyItemChanged(pos)
    }

    fun applyFilter(filter: MemberFilter) {
        val chipId = when (filter) {
            MemberFilter.ALL -> R.id.chipAll
            MemberFilter.ACTIVE -> R.id.chipActive
            MemberFilter.EXPIRING_SOON -> R.id.chipExpiring
            MemberFilter.EXPIRED -> R.id.chipExpired
            MemberFilter.UNPAID -> R.id.chipUnpaid
        }
        programmaticFilterChange = true
        binding.chipGroupFilter.check(chipId)
        programmaticFilterChange = false
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
        openEntranceWindow(1500L)
        setupEmptyStateActions()
    }

    private fun setupEmptyStateActions() {
        binding.btnEmptyAdd.setOnSingleClickListener {
            startActivity(Intent(requireContext(), AddEditMemberActivity::class.java))
        }
        binding.btnEmptyClear.setOnSingleClickListener {
            binding.etSearch.setText("")
            applyFilter(MemberFilter.ALL)
        }
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
            if (!programmaticFilterChange) {
                binding.chipGroupFilter.haptic(Haptic.TICK)
                openEntranceWindow(700L)
            }
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
    private fun startDebugTicker() {
        if (!true) return
        ensureDebugStrip()
        val rv = binding.rvMembers
        rv.postDelayed(object : Runnable {
            override fun run() {
                if (_binding == null) return
                verifyListRendered()
                diagTicks++
                if (diagTicks == 4 && !diagShown && !isHidden) { diagShown = true; showLayoutDiag() }
                rv.postDelayed(this, 1000)
            }
        }, 300)
    }

    private fun observeMembers() {
        startDebugTicker()
        setSkeleton(true)
        binding.rvMembers.visibleIf(true)
        binding.emptyMembersContainer.visibleIf(false)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.members.collectLatest { list ->
                    lastEmittedCount = list.size
                    android.widget.Toast.makeText(requireContext(), "DIAG v5: members=${list.size}", android.widget.Toast.LENGTH_LONG).show()
                    adapter.submitList(list) {
                        if (_binding != null) {
                            binding.rvMembers.visibility = View.VISIBLE
                            binding.rvMembers.alpha = 1f
                            binding.rvMembers.requestLayout()
                            highlightPendingMember()
                            ensureDebugStrip()
                            binding.rvMembers.post { verifyListRendered() }
                            binding.rvMembers.postDelayed({ verifyListRendered() }, 500)
                        }
                    }
                    setSkeleton(false)
                    val isEmpty = list.isEmpty()
                    binding.emptyMembersContainer.visibleIf(isEmpty)
                    if (isEmpty) {
                        val isFiltered = viewModel.currentQuery.value.isNotBlank() ||
                            viewModel.currentFilter.value != MemberFilter.ALL
                        binding.tvMembersEmptyMessage.setText(
                            if (isFiltered) R.string.members_no_results else R.string.members_empty_title
                        )
                        binding.tvMembersEmptyHint.visibleIf(!isFiltered)
                        binding.btnEmptyAdd.visibleIf(!isFiltered)
                        binding.btnEmptyClear.visibleIf(isFiltered)
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
            binding.root.showSnack(getString(R.string.whatsapp_invalid_phone), SnackType.ERROR)
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
                binding.root.showSnack(
                    getString(R.string.whatsapp_sending_to, number),
                    SnackType.SUCCESS,
                    getString(R.string.whatsapp_try_other_prefix)
                ) { sendWhatsapp(member, forcedPrefix = WhatsAppHelper.otherPrefix(prefix)) }
            }
            WhatsAppHelper.SendResult.BrowserFallback -> {
                binding.root.showSnack(
                    getString(R.string.whatsapp_sending_browser_fallback, number),
                    SnackType.INFO,
                    getString(R.string.whatsapp_try_other_prefix)
                ) { sendWhatsapp(member, forcedPrefix = WhatsAppHelper.otherPrefix(prefix)) }
            }
            WhatsAppHelper.SendResult.Failed -> Unit
        }
    }

    /**
     * إصلاح: بعد إضافة عضو من AddEditMemberActivity كانت القائمة لا تعكس العضو الجديد
     * (رغم ظهوره في الإحصائيات). نُجبر Paging على إعادة الاستعلام عند العودة للشاشة
     * وعند إظهار التبويب (hide/show لا يستدعي onResume).
     */
    override fun onResume() {
        super.onResume()
        if (_binding != null && !isHidden) {
            prepareHighlight()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            prepareHighlight()
            binding.rvMembers.post { verifyListRendered() }
        }
    }

    private var skeletonPulse: android.animation.ObjectAnimator? = null

    private fun setSkeleton(show: Boolean) {
        val sk = binding.skeletonMembers
        if (show == (sk.visibility == View.VISIBLE)) return
        sk.visibleIf(show)
        skeletonPulse?.cancel()
        skeletonPulse = null
        sk.alpha = 1f
        if (show) {
            skeletonPulse = android.animation.ObjectAnimator.ofFloat(sk, "alpha", 1f, 0.55f).apply {
                duration = 800
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
    }

    override fun onDestroyView() {
        skeletonPulse?.cancel()
        skeletonPulse = null
        super.onDestroyView()
        _binding = null
    }
}
