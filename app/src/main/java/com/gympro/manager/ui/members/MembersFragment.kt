package com.gympro.manager.ui.members

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.view.children
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
import com.gympro.manager.utils.applySystemBarInsetsPadding
import com.google.android.material.chip.Chip

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
        // الرأس جزء من المحتوى (لا AppBar في هذه الشاشة) فنضيف حشوة شريط الحالة هنا.
        binding.membersContent.applySystemBarInsetsPadding(top = true, bottom = false)
        setupHeaderActions()
        setupRecyclerView()
        setupSearch()
        setupFilterChips()
        applyInitialFilterIfAny()
        observeMembers()
        observeCounts()
    }

    /** الأرشيف في الرأس، والترتيب داخل شريط البحث (كانا سابقاً في قائمة الـ AppBar). */
    private fun setupHeaderActions() {
        binding.btnArchive.setOnSingleClickListener {
            startActivity(Intent(requireContext(), ArchiveActivity::class.java))
        }
        binding.btnSort.setOnSingleClickListener { showSortDialog() }
    }

    /** أعداد الفلاتر تظهر داخل الرقائق، وعدد النشطين تحت عنوان الشاشة. */
    private fun observeCounts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.counts.collectLatest { counts ->
                    if (counts != null && _binding != null) renderCounts(counts)
                }
            }
        }
    }

    private fun renderCounts(c: MemberCounts) {
        binding.tvMembersCount.text =
            if (c.all == 0) getString(R.string.members_count_none)
            else getString(R.string.members_count_active, c.active)
        setChipLabel(binding.chipAll, R.string.members_filter_all, c.all)
        setChipLabel(binding.chipActive, R.string.members_filter_active, c.active)
        setChipLabel(binding.chipExpiring, R.string.members_filter_expiring, c.expiring)
        setChipLabel(binding.chipExpired, R.string.members_filter_expired, c.expired)
        setChipLabel(binding.chipUnpaid, R.string.members_filter_unpaid, c.unpaid)
    }

    private fun setChipLabel(chip: Chip, labelRes: Int, count: Int) {
        val label = getString(labelRes)
        val text = SpannableString("$label  $count")
        text.setSpan(RelativeSizeSpan(0.85f), label.length + 2, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        chip.text = text
    }

    /** "لا يوجد أي عضو": تُخفى أدوات البحث والفلترة والقائمة وتظهر شاشة البداية. */
    private fun setFirstRun(show: Boolean) {
        val wasShown = binding.firstRunScroll.visibility == View.VISIBLE
        binding.firstRunScroll.visibleIf(show)
        if (show && !wasShown) playEmptyEntrance()
        if (show) startEmptyAnimations() else stopEmptyAnimations()
        binding.listFrame.visibleIf(!show)
        binding.searchBar.visibleIf(!show)
        binding.chipsScroll.visibleIf(!show)
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
        binding.chipGroupFilter.post { moveChipIndicator(animate = true) }
        viewModel.setFilter(filter)
    }

    /**
     * حوار فرز قائمة الأعضاء — راجع البند 14. القائمة السابقة كانت تدعم فقط بحث نصي
     * وفلاتر حالة (نشط/منتهي/...) بلا أي طريقة لإعادة ترتيب النتائج نفسها.
     */
    private fun showSortDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheet = com.gympro.manager.databinding.BottomSheetMemberSortBinding.inflate(layoutInflater)
        dialog.setContentView(sheet.root)
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val current = viewModel.currentSort.value
        listOf(
            Triple(sheet.rowSortName, sheet.radioSortName, MemberSort.NAME),
            Triple(sheet.rowSortExpiry, sheet.radioSortExpiry, MemberSort.EXPIRY_SOONEST),
            Triple(sheet.rowSortNewest, sheet.radioSortNewest, MemberSort.NEWEST)
        ).forEach { (row, radio, sort) ->
            val selected = sort == current
            row.setBackgroundResource(if (selected) R.drawable.bg_home_sort_row_selected else R.drawable.bg_home_sort_row)
            radio.setBackgroundResource(if (selected) R.drawable.bg_home_radio_on else R.drawable.bg_home_radio_off)
            radio.setImageResource(if (selected) R.drawable.ic_ds_check else 0)
            row.isSelected = selected
            row.setOnSingleClickListener {
                viewModel.setSort(sort)
                dialog.dismiss()
            }
        }
        dialog.show()
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

    private var chipIndicatorAnimator: ValueAnimator? = null

    /** ينقل المؤشّر المتدرّج خلف الرقاقة المختارة (انزلاق 280ms)، أو يضعه فوراً بلا حركة عند أول عرض. */
    private fun moveChipIndicator(animate: Boolean) {
        val b = _binding ?: return
        val chip = b.chipGroupFilter.findViewById<View>(b.chipGroupFilter.checkedChipId)
        val ind = b.chipIndicator
        if (chip == null || chip.width == 0) {
            ind.visibility = View.INVISIBLE
            return
        }
        val toX = chip.left.toFloat()
        val toY = chip.top.toFloat()
        val toW = chip.width
        val toH = chip.height
        chipIndicatorAnimator?.cancel()
        fun apply(x: Float, w: Int) {
            val lp = ind.layoutParams
            if (lp.width != w || lp.height != toH) {
                lp.width = w
                lp.height = toH
                ind.layoutParams = lp
            }
            ind.translationX = x
            ind.translationY = toY
        }
        if (!animate || ind.visibility != View.VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
            apply(toX, toW)
            ind.visibility = View.VISIBLE
            return
        }
        val fromX = ind.translationX
        val fromW = ind.layoutParams.width.coerceAtLeast(1)
        chipIndicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                apply(fromX + (toX - fromX) * t, (fromW + (toW - fromW) * t).toInt())
            }
            start()
        }
    }

    private fun setupFilterChips() {
        // الوضع الأولي + إعادة الضبط عند أي تغيّر في قياس الرقائق (تغيّر العدّادات يغيّر العرض).
        binding.chipGroupFilter.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) moveChipIndicator(animate = false)
        }
        binding.chipGroupFilter.post { moveChipIndicator(animate = false) }
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            binding.chipGroupFilter.post { moveChipIndicator(animate = true) }
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
     * يربط قائمة الأعضاء بالواجهة: تُحدَّث القائمة عند كل تغيير في قاعدة البيانات، وتُعرض
     * حالة التحميل الأول (الهيكل العظمي) ثم القائمة أو رسالة الفراغ/لا نتائج.
     */
    private fun observeMembers() {
        setSkeleton(true)
        binding.rvMembers.visibleIf(true)
        binding.emptyMembersContainer.visibleIf(false)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.members.collectLatest { list ->
                    adapter.submitList(list) {
                        if (_binding != null) {
                            binding.rvMembers.visibility = View.VISIBLE
                            binding.rvMembers.alpha = 1f
                            binding.rvMembers.requestLayout()
                            highlightPendingMember()
                        }
                    }
                    setSkeleton(false)
                    val isEmpty = list.isEmpty()
                    val isFiltered = viewModel.currentQuery.value.isNotBlank() ||
                        viewModel.currentFilter.value != MemberFilter.ALL
                    // فراغ بلا بحث/فلتر = لا يوجد أي عضو أصلاً → شاشة البداية؛ فراغ مع بحث/فلتر → "لا نتائج".
                    setFirstRun(isEmpty && !isFiltered)
                    binding.emptyMembersContainer.visibleIf(isEmpty && isFiltered)
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
        }
        if (_binding != null) {
            if (hidden) stopEmptyAnimations()
            else if (binding.firstRunScroll.visibility == View.VISIBLE) startEmptyAnimations()
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

    // ───── حركة شاشة "لا يوجد أعضاء": خفيفة، بطيئة، وتتوقف مع إخفاء الشاشة ─────
    private val emptyAnimators = mutableListOf<Animator>()

    private fun loop(
        anim: ObjectAnimator,
        durationMs: Long,
        interpolator: TimeInterpolator,
        reverse: Boolean = true,
        delayMs: Long = 0L
    ): ObjectAnimator = anim.apply {
        duration = durationMs
        this.interpolator = interpolator
        startDelay = delayMs
        repeatCount = ValueAnimator.INFINITE
        repeatMode = if (reverse) ValueAnimator.REVERSE else ValueAnimator.RESTART
    }

    private fun startEmptyAnimations() {
        if (_binding == null || emptyAnimators.isNotEmpty() || !ValueAnimator.areAnimatorsEnabled()) return
        val b = binding
        val d = resources.displayMetrics.density
        val ease = AccelerateDecelerateInterpolator()
        val list = listOf(
            // الحلقة الخارجية ونقاطها: دورة كاملة كل 30 ثانية
            loop(ObjectAnimator.ofFloat(b.orbitOuter, View.ROTATION, 0f, 360f), 30_000, LinearInterpolator(), reverse = false),
            // الحلقة الداخلية "تتنفّس"
            loop(
                ObjectAnimator.ofPropertyValuesHolder(
                    b.orbitInner,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 1f)
                ), 2_800, ease
            ),
            // النواة تطفو وتتنفّس بخفة
            loop(
                ObjectAnimator.ofPropertyValuesHolder(
                    b.orbitCore,
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -4f * d),
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f)
                ), 3_200, ease
            ),
            // موجتان تنتشران من النواة بالتناوب
            loop(pingAnimator(b.pingA), 3_000, DecelerateInterpolator(), reverse = false),
            loop(pingAnimator(b.pingB), 3_000, DecelerateInterpolator(), reverse = false, delayMs = 1_500),
            // وميض النقاط بإيقاعات مختلفة كي لا تبدو آلية
            loop(ObjectAnimator.ofFloat(b.dotPink, View.ALPHA, 0.45f, 1f), 1_700, ease),
            loop(ObjectAnimator.ofFloat(b.dotBlue, View.ALPHA, 0.45f, 1f), 2_300, ease),
            loop(ObjectAnimator.ofFloat(b.dotLav, View.ALPHA, 0.45f, 1f), 2_900, ease)
        )
        emptyAnimators += list
        list.forEach { it.start() }
    }

    private fun pingAnimator(view: View): ObjectAnimator = ObjectAnimator.ofPropertyValuesHolder(
        view,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.9f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.9f),
        PropertyValuesHolder.ofFloat(View.ALPHA, 0.5f, 0f)
    )

    private fun stopEmptyAnimations() {
        emptyAnimators.forEach { it.cancel() }
        emptyAnimators.clear()
        val b = _binding ?: return
        b.orbitOuter.rotation = 0f
        listOf(b.orbitInner, b.orbitCore).forEach { it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f; it.translationY = 0f }
        listOf(b.pingA, b.pingB).forEach { it.alpha = 0f }
        listOf(b.dotPink, b.dotBlue, b.dotLav).forEach { it.alpha = 1f }
    }

    /** ظهور متدرّج عند فتح الشاشة: الرسم يكبر من 85% ثم تصعد العناصر التالية واحداً بعد الآخر. */
    private fun playEmptyEntrance() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val d = resources.displayMetrics.density
        binding.emptyContent.children.forEachIndexed { i, child ->
            child.alpha = 0f
            child.translationY = 18 * d
            if (i == 0) { child.scaleX = 0.85f; child.scaleY = 0.85f }
            child.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(i * 80L).setDuration(450)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    override fun onStart() {
        super.onStart()
        if (_binding != null && !isHidden && binding.firstRunScroll.visibility == View.VISIBLE) startEmptyAnimations()
    }

    override fun onStop() {
        stopEmptyAnimations()
        super.onStop()
    }

    override fun onDestroyView() {
        chipIndicatorAnimator?.cancel()
        stopEmptyAnimations()
        skeletonPulse?.cancel()
        skeletonPulse = null
        super.onDestroyView()
        _binding = null
    }
}
