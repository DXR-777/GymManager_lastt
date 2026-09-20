package com.gympro.manager.ui.archive

import android.os.Bundle
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

class ArchiveActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchiveBinding
    private val repository by lazy { (application as GymApplication).repository }
    private lateinit var adapter: ArchiveAdapter

    /** استعلام بحث الأرشيف — راجع البند 15: سابقاً لم يوجد بحث إطلاقاً في هذه الشاشة. */
    private val searchQuery = MutableStateFlow("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ArchiveAdapter(
            onRestore = { member ->
                lifecycleScope.launch {
                    repository.restoreMember(member.id)
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

    private fun confirmPermanentDelete(memberId: Long, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.archive_permanent_delete_title)
            .setMessage(getString(R.string.archive_permanent_delete_message, name))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch { repository.permanentDeleteMember(memberId) }
            }
            .show()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeArchived() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                searchQuery.flatMapLatest { q -> repository.observeDeletedMembers(q) }
                    .collect { list ->
                        adapter.submitList(list)
                        binding.rvArchived.visibleIf(list.isNotEmpty())
                        binding.emptyArchiveContainer.visibleIf(list.isEmpty())
                        // نفس فجوة "رسالة فارغة مضلِّلة" الموثّقة في شاشة الأعضاء (البند 16)،
                        // نتفاداها هنا منذ البداية بدل تكرارها: رسالة مختلفة حين يكون
                        // البحث هو سبب عدم وجود نتائج بدل الأرشيف الفارغ فعلياً.
                        binding.tvArchiveEmptyMessage.setText(
                            if (searchQuery.value.isBlank()) R.string.archive_empty
                            else R.string.archive_no_search_results
                        )
                    }
            }
        }
    }
}
