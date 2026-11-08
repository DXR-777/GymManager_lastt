package com.gympro.manager.ui.archive

import android.os.Bundle
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.databinding.ActivityArchiveBinding
import com.gympro.manager.utils.toast
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import com.gympro.manager.utils.applySystemBarInsetsPadding
import com.gympro.manager.utils.Haptic
import com.gympro.manager.utils.haptic
import com.gympro.manager.utils.capContentWidth

class ArchiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchiveBinding
    private val repository by lazy { (application as GymApplication).repository }
    private lateinit var adapter: ArchiveAdapter

    /** استعلام بحث الأرشيف — راجع البند 15: سابقاً لم يوجد بحث إطلاقاً في هذه الشاشة. */
    private val searchQuery = MutableStateFlow("")

    /** آخر قائمة معروضة (بعد البحث) — تُستخدم لـ "استعادة الكل". */
    private lateinit var boxAnimator: com.gympro.manager.utils.ArchiveBoxAnimator

    private var currentList: List<com.gympro.manager.model.MemberListItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // شاشة داكنة دائماً (نفس الرئيسية والأعضاء): شريطا الحالة والتنقّل بلون الخلفية وأيقونات فاتحة.
        val barColor = androidx.core.content.ContextCompat.getColor(this, R.color.home_bg)
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        binding.archiveContent.applySystemBarInsetsPadding()
        capContentWidth(binding.archiveContent)

        boxAnimator = com.gympro.manager.utils.ArchiveBoxAnimator(
            root = binding.orbitRoot,
            floater = binding.orbitCore,
            group = binding.archiveBoxGroup,
            lid = binding.archiveLid,
            dust = listOf(binding.archiveDust1, binding.archiveDust2, binding.archiveDust3),
            shadow = binding.archiveShadow,
            glow = binding.orbitGlow,
            title = binding.tvArchiveEmptyMessage
        )
        // نقر على الصندوق = يفتح ويهتز من جديد
        binding.orbitCore.setOnClickListener {
            it.haptic(Haptic.TICK)
            boxAnimator.replay()
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRestoreAll.setOnClickListener { confirmRestoreAll() }

        adapter = ArchiveAdapter(
            onRestore = { member ->
                lifecycleScope.launch {
                    repository.restoreMember(member.id)
                    binding.root.haptic(Haptic.CONFIRM)
                    toast(getString(R.string.archive_restore_success))
                }
            },
            onPermanentDelete = { member -> confirmPermanentDelete(member.id, member.name) }
        )
        binding.rvArchived.layoutManager = LinearLayoutManager(this)
        binding.rvArchived.adapter = adapter

        setupSearch()
        observeArchived()
        observeCurrency()
    }

    private fun setupSearch() {
        binding.etArchiveSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery.value = s?.toString().orEmpty()
                binding.btnClearArchiveSearch.visibleIf(!s.isNullOrEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnClearArchiveSearch.setOnClickListener { binding.etArchiveSearch.text?.clear() }
    }

    /** يزوّد بادج الدَّين برمز العملة الصحيح (نفس مصدر العملة المستخدم في لوحة التحكم وقائمة الأعضاء) */
    private fun observeCurrency() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeSettings().collect { settings ->
                    adapter.setCurrencySymbol(settings?.currencySymbol ?: "₪")
                }
            }
        }
    }

    /** استعادة كل الأعضاء المعروضين دفعة واحدة، بعد تأكيد (لا يظهر الزر أثناء البحث). */
    private fun startEmptyMotion() {
        boxAnimator.start()
    }

    private fun stopEmptyMotion() {
        boxAnimator.stop()
    }

    override fun onStart() {
        super.onStart()
        if (::boxAnimator.isInitialized && binding.emptyArchiveContainer.visibility == View.VISIBLE) startEmptyMotion()
    }

    override fun onStop() {
        if (::boxAnimator.isInitialized) stopEmptyMotion()
        super.onStop()
    }

    private fun confirmRestoreAll() {
        val items = currentList
        if (items.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.archive_restore_all_title)
            .setMessage(getString(R.string.archive_restore_all_message, items.size))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.archive_restore_all_confirm) { _, _ ->
                lifecycleScope.launch {
                    items.forEach { repository.restoreMember(it.id) }
                    binding.root.haptic(Haptic.CONFIRM)
                    toast(getString(R.string.archive_restore_all_success, items.size))
                }
            }
            .show()
    }

    private fun confirmPermanentDelete(memberId: Long, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.archive_permanent_delete_title)
            .setMessage(getString(R.string.archive_permanent_delete_message, name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    repository.permanentDeleteMember(memberId)
                    binding.root.haptic(Haptic.CONFIRM)
                }
            }
            .show()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeArchived() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchQuery.flatMapLatest { q -> repository.observeDeletedMembers(q) }
                    .collect { list ->
                        val wasEmpty = adapter.itemCount == 0
                        adapter.submitList(list)
                        if (wasEmpty && list.isNotEmpty()) binding.rvArchived.scheduleLayoutAnimation()
                        binding.rvArchived.visibleIf(list.isNotEmpty())
                        binding.emptyArchiveContainer.visibleIf(list.isEmpty())
                        // نفس فجوة "رسالة فارغة مضلِّلة" الموثّقة في شاشة الأعضاء (البند 16)،
                        // نتفاداها هنا منذ البداية بدل تكرارها: رسالة مختلفة حين يكون
                        // البحث هو سبب عدم وجود نتائج بدل الأرشيف الفارغ فعلياً.
                        val searching = searchQuery.value.isNotBlank()
                        binding.tvArchiveEmptyMessage.setText(
                            if (searching) R.string.archive_no_search_results else R.string.archive_empty
                        )
                        // أرشيف فارغ فعلاً: لا معنى للبحث ولا لملاحظة المهلة، فتُخفيان لشاشة هادئة.
                        val archiveReallyEmpty = list.isEmpty() && !searching
                        binding.archiveSearchBar.visibleIf(!archiveReallyEmpty)
                        binding.archiveNote.visibleIf(!archiveReallyEmpty)
                        currentList = list
                        // الحركة تعمل فقط حين يكون رسم "الأرشيف فارغ" ظاهراً (توفيراً للبطارية).
                        if (list.isEmpty()) startEmptyMotion() else stopEmptyMotion()
                        binding.btnRestoreAll.visibleIf(list.isNotEmpty() && !searching)
                        binding.tvArchiveCount.visibleIf(list.isNotEmpty())
                        binding.tvArchiveCount.text = getString(R.string.archive_count_format, list.size)
                    }
            }
        }
    }
}
