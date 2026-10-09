package com.gympro.manager.ui.members

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.gympro.manager.R
import com.gympro.manager.databinding.ItemMemberBinding
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.setOnSingleClickListener
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * راجع البند 24: PagingDataAdapter بدل ListAdapter العادي — يعرض العناصر تدريجياً
 * حسب ما يحمّله Paging 3 من MembersViewModel.pagedMembers بدل قائمة كاملة جاهزة مسبقاً.
 * DiffUtil.ItemCallback نفسها بالضبط كما كانت (لا تغيير في منطق المقارنة).
 */
class MembersAdapter(
    private val onClick: (MemberListItem) -> Unit,
    private val onWhatsappClick: (MemberListItem) -> Unit
) : ListAdapter<MemberListItem, MembersAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    // getItem() من PagingDataAdapter قد يُعيد null مؤقتاً لعنصر لم يُحمَّل بعد (مواضع
    // Placeholder) — لكن enablePlaceholders=false في GymRepository.getActiveMembersPaged
    // يمنع ظهور مواضع فارغة أصلاً، فهذا الحارس احترازي بحت (تفادي NPE نظري لا حالة
    // متوقعة عملياً) بدل الاعتماد على !! غير آمن.
    /** عضو جديد يُميَّز بوميض حلقة عند ظهوره (يُصفَّر بعد العرض). */
    var highlightId: Long? = null

    /** نافذة دخول القائمة: العناصر المرتبطة خلالها تظهر بتلاشٍ وانزلاق متدرّج. تتحكم بها الشاشة. */
    var entranceEnabled = false

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
        val v = holder.itemView
        // إعادة تعيين آثار التدوير: ViewHolder يُعاد استخدامه وقد يحمل حالة حركة سابقة.
        v.animate().cancel()
        v.alpha = 1f
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
        v.foreground = null
        when {
            item.id == highlightId -> {
                highlightId = null
                playHighlight(v)
            }
            entranceEnabled -> playEntrance(v, position)
        }
    }

    private fun playEntrance(v: View, position: Int) {
        val density = v.resources.displayMetrics.density
        v.alpha = 0f
        v.translationY = 20f * density
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(minOf(position, 6) * 35L)
            .setDuration(260L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun playHighlight(v: View) {
        val density = v.resources.displayMetrics.density
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = v.resources.getDimension(R.dimen.radius_lg)
            setColor(Color.TRANSPARENT)
            setStroke((2 * density).toInt(), ContextCompat.getColor(v.context, R.color.brand_accent))
        }
        v.foreground = ring
        ObjectAnimator.ofInt(ring, "alpha", 255, 0).apply {
            startDelay = 700L
            duration = 900L
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (v.foreground === ring) v.foreground = null
                }
            })
            start()
        }
        v.animate()
            .scaleX(1.03f).scaleY(1.03f)
            .setDuration(180L)
            .withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(220L).start() }
            .start()
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
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.status_error_text))
                }
                else -> {
                    binding.tvPaymentBadge.text = context.getString(R.string.status_paid)
                    binding.tvPaymentBadge.setBackgroundResource(R.drawable.bg_pill_success)
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.status_success_text))
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
                        binding.tvDaysRemaining.setTextColor(context.getColor(R.color.status_error_text))
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
                        binding.tvDaysRemaining.setTextColor(context.getColor(R.color.status_success_text))
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
