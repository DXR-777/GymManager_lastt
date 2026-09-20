package com.gympro.manager.ui.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.databinding.FragmentDashboardBinding
import com.gympro.manager.model.MemberFilter
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.ui.main.MainActivity
import com.gympro.manager.ui.members.MemberDetailActivity
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.launch
import java.util.Calendar

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory((requireActivity().application as GymApplication).repository)
    }

    private lateinit var expiringAdapter: DashboardMemberAdapter
    private lateinit var unpaidAdapter: DashboardMemberAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupGreeting()
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

    private fun setupLists() {
        expiringAdapter = DashboardMemberAdapter(
            subtitleProvider = { member ->
                val days = DateUtils.daysRemaining(member.endDate ?: 0L)
                if (days <= 0) getString(R.string.days_remaining_today)
                else getString(R.string.days_remaining_format, days)
            },
            onClick = { openMember(it.id) }
        )
        unpaidAdapter = DashboardMemberAdapter(
            subtitleProvider = { member ->
                // outstandingBalance = الدَّين الحقيقي المتراكم (كل الاشتراكات غير المدفوعة عبر تاريخ العضو)،
                // وليس price (سعر آخر اشتراك فقط، الذي قد يُظهر مبلغاً أقل بكثير من الدَّين الفعلي).
                CurrencyFormatter.format(member.outstandingBalance, viewModel.uiState.value.currency)
            },
            onClick = { openMember(it.id) }
        )
        binding.rvExpiringSoon.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvExpiringSoon.adapter = expiringAdapter
        binding.rvUnpaid.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.rvUnpaid.adapter = unpaidAdapter
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

    private fun observeState() {
        // البند 15: نُخفي محتوى الشاشة ونعرض مؤشر التحميل إلى حين وصول أول انبعاث
        // فعلي من الحالة، بدل ظهور لوحة بأرقام صفرية وقوائم فارغة للحظة قبل التعبئة.
        var isFirstLoad = true
        binding.scrollDashboardContent.visibleIf(false)
        binding.progressDashboardLoading.visibleIf(true)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (isFirstLoad) {
                        isFirstLoad = false
                        binding.progressDashboardLoading.visibleIf(false)
                        binding.scrollDashboardContent.visibleIf(true)
                    }
                    binding.tvGymName.text = state.gymName
                    binding.tvActiveMembersCount.text = state.activeCount.toString()
                    binding.tvTodayRevenue.text = CurrencyFormatter.format(state.todayRevenue, state.currency)
                    binding.tvUnpaidCount.text = state.unpaidCount.toString()
                    binding.tvExpiringSoonCount.text = state.expiringSoonCount.toString()
                    updateStatCardAccessibilityLabels(state)

                    expiringAdapter.submitList(state.expiringSoon)
                    unpaidAdapter.submitList(state.unpaidMembers)

                    binding.rvExpiringSoon.visibleIf(state.expiringSoon.isNotEmpty())
                    binding.tvEmptyExpiring.visibleIf(state.expiringSoon.isEmpty())
                    binding.rvUnpaid.visibleIf(state.unpaidMembers.isNotEmpty())
                    binding.tvEmptyUnpaid.visibleIf(state.unpaidMembers.isEmpty())
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
