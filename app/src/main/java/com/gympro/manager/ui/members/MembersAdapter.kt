package com.gympro.manager.ui.members

import com.gympro.manager.utils.bindMemberAvatar
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
        v.foreground = restingForeground(v)
        when {
            item.id == highlightId -> {
                highlightId = null
                playHighlight(v)
            }
        }
    }

    /** تموّج اللمس الافتراضي للبطاقة (يُستعاد بعد كل ربط/وميض بدل إلغائه كلياً). */
    private fun restingForeground(v: View) =
        ContextCompat.getDrawable(v.context, R.drawable.ripple_home_card)

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
                    if (v.foreground === ring) v.foreground = restingForeground(v)
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
            binding.tvInitial.bindMemberAvatar(item.name, item.photoPath)
            binding.tvName.text = item.name
            binding.tvPhone.text = item.phone

            binding.tvTypeBadge.text = when (item.type) {
                SubscriptionType.DAILY -> context.getString(R.string.subscription_daily)
                SubscriptionType.WEEKLY -> context.getString(R.string.subscription_weekly)
                SubscriptionType.MONTHLY -> context.getString(R.string.subscription_monthly)
                SubscriptionType.CUSTOM -> context.getString(R.string.subscription_custom)
                null -> "—"
            }

            // الشارة تعكس الدَّين الحقيقي المتراكم (outstandingBalance عبر isUnpaid()) لا حالة آخر
            // اشتراك فقط. الألوان هنا ثابتة داكنة (home_*) لأن الشاشة داكنة دائماً حتى في الوضع النهاري.
            when {
                item.type == null -> {
                    binding.tvPaymentBadge.text = "—"
                    binding.tvPaymentBadge.setBackgroundResource(R.drawable.bg_home_chip)
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.home_mute))
                }
                item.isUnpaid() -> {
                    binding.tvPaymentBadge.text = context.getString(R.string.status_unpaid)
                    binding.tvPaymentBadge.setBackgroundResource(R.drawable.bg_home_pill_coral)
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.home_coral))
                }
                else -> {
                    binding.tvPaymentBadge.text = context.getString(R.string.status_paid)
                    binding.tvPaymentBadge.setBackgroundResource(R.drawable.bg_home_pill_mint)
                    binding.tvPaymentBadge.setTextColor(context.getColor(R.color.home_mint))
                }
            }

            val mint = context.getColor(R.color.home_mint)
            val amber = context.getColor(R.color.home_amber)
            val coral = context.getColor(R.color.home_coral)
            val mute = context.getColor(R.color.home_mute)

            val endDate = item.endDate
            val progress = binding.progressDays
            if (endDate == null) {
                binding.tvDaysRemaining.text = "—"
                binding.tvDaysRemaining.setTextColor(mute)
                progress.visibility = View.GONE
            } else {
                progress.visibility = View.VISIBLE
                // طول دورة الاشتراك الحالية (≥1 يوم) لحساب نسبة المتبقي منها.
                val totalDays = DateUtils.daysBetween(item.startDate ?: endDate, endDate).coerceAtLeast(1)
                if (item.isFuture()) {
                    binding.tvDaysRemaining.text = context.getString(R.string.days_remaining_not_started)
                    binding.tvDaysRemaining.setTextColor(mute)
                    progress.setIndicatorColor(mute)
                    progress.setProgressCompat(100, false)
                } else {
                    val days = DateUtils.daysRemaining(endDate)
                    val (label, color) = when {
                        days < 0 -> context.getString(R.string.days_remaining_expired, -days) to coral
                        days == 0 -> context.getString(R.string.days_remaining_today) to amber
                        days <= 3 -> context.getString(R.string.days_remaining_format, days) to amber
                        else -> context.getString(R.string.days_remaining_format, days) to mint
                    }
                    binding.tvDaysRemaining.text = label
                    binding.tvDaysRemaining.setTextColor(color)
                    progress.setIndicatorColor(color)
                    // المنتهي: شريط فارغ؛ غير ذلك: نسبة المتبقي (بحدّ أدنى صغير يبقيه مرئياً).
                    val pct = if (days < 0) 0 else (days * 100f / totalDays).toInt().coerceIn(3, 100)
                    progress.setProgressCompat(pct, false)
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
