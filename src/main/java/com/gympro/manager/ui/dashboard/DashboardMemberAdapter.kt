package com.gympro.manager.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gympro.manager.databinding.ItemDashboardMemberBinding
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.utils.setOnSingleClickListener

/** قائمة أفقية صغيرة قابلة لإعادة الاستخدام (تُستخدم لقسمي "ينتهي قريباً" و"غير مدفوع") */
class DashboardMemberAdapter(
    private val subtitleProvider: (MemberListItem) -> String,
    private val onClick: (MemberListItem) -> Unit
) : RecyclerView.Adapter<DashboardMemberAdapter.ViewHolder>() {

    private var items: List<MemberListItem> = emptyList()

    fun submitList(newItems: List<MemberListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemDashboardMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDashboardMemberBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MemberListItem) {
            binding.tvInitial.text = item.name.trim().firstOrNull()?.uppercase() ?: "?"
            binding.tvName.text = item.name
            binding.tvSubtitle.text = subtitleProvider(item)
            binding.root.setOnSingleClickListener { onClick(item) }
        }
    }
}
