package com.gympro.manager.ui.dashboard

import com.gympro.manager.utils.bindMemberAvatar
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gympro.manager.R
import com.gympro.manager.databinding.ItemDashboardMemberBinding
import com.gympro.manager.databinding.ItemDashboardUnpaidBinding
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.utils.setOnSingleClickListener

/** شارة العنصر: النص + خلفية pill دلالية + لون النص (5.2). */
data class SubtitlePill(
    val text: String,
    @DrawableRes val background: Int,
    @ColorRes val textColor: Int
)

/** اسم نوع الاشتراك كما يظهر تحت اسم العضو في بطاقات الشريط ("اشتراك شهري"…). */
internal fun planLabel(context: Context, type: SubscriptionType?): String = when (type) {
    SubscriptionType.MONTHLY -> context.getString(R.string.onboarding_price_monthly_desc)
    SubscriptionType.WEEKLY -> context.getString(R.string.onboarding_price_weekly_desc)
    SubscriptionType.DAILY -> context.getString(R.string.onboarding_price_daily_desc)
    SubscriptionType.CUSTOM -> context.getString(R.string.home_plan_custom)
    null -> ""
}

private fun MemberListItem.initial(): String = name.trim().firstOrNull()?.uppercase() ?: "?"

/** قائمة أفقية لبطاقات "ينتهي اشتراكهم قريباً" (تصميم HTML: .m). */
class DashboardMemberAdapter(
    private val subtitleProvider: (MemberListItem) -> SubtitlePill,
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
            val context = binding.root.context
            binding.tvInitial.bindMemberAvatar(item.name, item.photoPath)
            binding.tvName.text = item.name
            binding.tvPlan.text = planLabel(context, item.type)
            val pill = subtitleProvider(item)
            binding.tvSubtitle.text = pill.text
            binding.tvSubtitle.setBackgroundResource(pill.background)
            binding.tvSubtitle.setTextColor(ContextCompat.getColor(context, pill.textColor))
            binding.root.setOnSingleClickListener { onClick(item) }
        }
    }
}

/**
 * قائمة "لم يدفعوا بعد" (تصميم HTML: .u) — صفوف رأسية داخل بطاقة زجاجية واحدة.
 * زر "تسديد" يفتح تفاصيل العضو حيث تُسجَّل الدفعة (نفس مسار النقر على الصف).
 */
class DashboardUnpaidAdapter(
    private val amountProvider: (MemberListItem) -> String,
    private val dueProvider: (MemberListItem) -> String,
    private val onClick: (MemberListItem) -> Unit
) : RecyclerView.Adapter<DashboardUnpaidAdapter.ViewHolder>() {

    private var items: List<MemberListItem> = emptyList()

    fun submitList(newItems: List<MemberListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDashboardUnpaidBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], position)

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDashboardUnpaidBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MemberListItem, position: Int) {
            binding.divider.visibility = if (position == 0) android.view.View.GONE else android.view.View.VISIBLE
            binding.tvInitial.bindMemberAvatar(item.name, item.photoPath)
            binding.tvName.text = item.name
            binding.tvSubtitle.text = dueProvider(item)
            binding.tvAmount.text = amountProvider(item)
            binding.rowRoot.setOnSingleClickListener { onClick(item) }
            binding.btnPay.setOnSingleClickListener { onClick(item) }
        }
    }
}
