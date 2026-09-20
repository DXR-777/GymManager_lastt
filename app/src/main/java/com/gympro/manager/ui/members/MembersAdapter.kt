package com.gympro.manager.ui.members

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.gympro.manager.R
import com.gympro.manager.databinding.ItemMemberBinding
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.setOnSingleClickListener

/**
 * راجع البند 24: PagingDataAdapter بدل ListAdapter العادي — يعرض العناصر تدريجياً
 * حسب ما يحمّله Paging 3 من MembersViewModel.pagedMembers بدل قائمة كاملة جاهزة مسبقاً.
 * DiffUtil.ItemCallback نفسها بالضبط كما كانت (لا تغيير في منطق المقارنة).
 */
class MembersAdapter(
    private val onClick: (MemberListItem) -> Unit,
    private val onWhatsappClick: (MemberListItem) -> Unit
) : PagingDataAdapter<MemberListItem, MembersAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    // getItem() من PagingDataAdapter قد يُعيد null مؤقتاً لعنصر لم يُحمَّل بعد (مواضع
    // Placeholder) — لكن enablePlaceholders=false في GymRepository.getActiveMembersPaged
    // يمنع ظهور مواضع فارغة أصلاً، فهذا الحارس احترازي بحت (تفادي NPE نظري لا حالة
    // متوقعة عملياً) بدل الاعتماد على !! غير آمن.
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        getItem(position)?.let { holder.bind(it) }
    }

    inner class ViewHolder(private val binding: ItemMemberBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MemberListItem) {
            val context = binding.root.context
            binding.tvInitial.text = item.name.trim().firstOrNull()?.uppercase() ?: "?"
            binding.tvName.text = item.name
            binding.tvPhone.text = item.phone

            binding.tvTypeBadge.text = when (item.type) {
                SubscriptionType.DAILY -> context.getString(R.string.subscription_daily)
                SubscriptionType.WEEKLY -> context.getString(R.string.subscription_weekly)
                SubscriptionType.MONTHLY -> context.getString(R.string.subscription_monthly)
                SubscriptionType.CUSTOM -> context.getString(R.string.subscription_custom)
                null -> "—"
            }

            // الشارة يجب أن تعكس الدَّين الحقيقي المتراكم (outstandingBalance عبر isUnpaid())،
            // وليس فقط isPaid الذي يصف حالة آخر دورة اشتراك فقط. عضو دفع آخر شهر لكن لا يزال
            // يدين بشهر سابق كان يظهر بشارة "مدفوع" خضراء مضلِّلة في هذه القائمة تحديداً،
            // رغم أن كل شاشة أخرى بالتطبيق (لوحة التحكم، فلتر "غير مدفوع"، رسالة واتساب،
            // بانر الدَّين بالملف الشخصي) تعتمد بالفعل على outstandingBalance/isUnpaid().
            when {
                item.type == null -> {
                    binding.tvPaymentBadge.text = "—"
                    binding.tvPaymentBadge.setBackgroundResource(R.drawable.bg_pill_neutral)
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.text_secondary))
                }
                item.isUnpaid() -> {
                    binding.tvPaymentBadge.text = context.getString(R.string.status_unpaid)
                    binding.tvPaymentBadge.setBackgroundResource(R.drawable.bg_pill_error)
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.status_error))
                }
                else -> {
                    binding.tvPaymentBadge.text = context.getString(R.string.status_paid)
                    binding.tvPaymentBadge.setBackgroundResource(R.drawable.bg_pill_success)
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.status_success))
                }
            }

            val endDate = item.endDate
            if (endDate == null) {
                binding.tvDaysRemaining.text = "—"
                binding.tvDaysRemaining.setTextColor(context.getColor(R.color.text_secondary))
            } else if (item.isFuture()) {
                binding.tvDaysRemaining.text = context.getString(R.string.days_remaining_not_started)
                binding.tvDaysRemaining.setTextColor(context.getColor(R.color.text_secondary))
            } else {
                val days = DateUtils.daysRemaining(endDate)
                when {
                    days < 0 -> {
                        binding.tvDaysRemaining.text = context.getString(R.string.days_remaining_expired, -days)
                        binding.tvDaysRemaining.setTextColor(context.getColor(R.color.status_error))
                    }
                    days == 0 -> {
                        binding.tvDaysRemaining.text = context.getString(R.string.days_remaining_today)
                        binding.tvDaysRemaining.setTextColor(context.getColor(R.color.status_warning))
                    }
                    days <= 3 -> {
                        binding.tvDaysRemaining.text = context.getString(R.string.days_remaining_format, days)
                        binding.tvDaysRemaining.setTextColor(context.getColor(R.color.status_warning))
                    }
                    else -> {
                        binding.tvDaysRemaining.text = context.getString(R.string.days_remaining_format, days)
                        binding.tvDaysRemaining.setTextColor(context.getColor(R.color.status_success))
                    }
                }
            }

            binding.cardRoot.setOnSingleClickListener { onClick(item) }
            binding.btnWhatsapp.setOnSingleClickListener { onWhatsappClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MemberListItem>() {
            override fun areItemsTheSame(oldItem: MemberListItem, newItem: MemberListItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MemberListItem, newItem: MemberListItem) = oldItem == newItem
        }
    }
}
