package com.gympro.manager.ui.members

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gympro.manager.R
import com.gympro.manager.data.local.entities.SubscriptionEntity
import com.gympro.manager.databinding.ItemSubscriptionBinding
import com.gympro.manager.model.PaymentStatus
import com.gympro.manager.model.SubscriptionType
import com.gympro.manager.model.paidPaymentMethodLabel
import com.gympro.manager.model.paymentStatus
import com.gympro.manager.model.remainingAmount
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.areAnimationsEnabled
import com.gympro.manager.utils.setOnSingleClickListener

/**
 * سجل الاشتراكات كخط زمني (تصميم 2026): كل عنصر = نقطة متوهجة بلون الحالة + بطاقة زجاجية.
 *
 * ListAdapter بـ DiffUtil (بدل notifyDataSetChanged) لأن القائمة داخل ScrollView: إعادة
 * البناء الكاملة كانت تُسبّب حساب ارتفاع خاطئ لـ RecyclerView بعد التسديد. DiffUtil يحدّث
 * العنصر المتغيّر فقط فتبقى بقية السجلات ظاهرة.
 *
 * الظهور التدريجي أثناء التمرير: العناصر التي لم تُكشف بعد تبدأ شفافة (alpha=0) وتُكشف
 * من MemberDetailActivity.revealVisibleHistory عند دخولها الشاشة؛ [markRevealed] يمنع تكرار
 * الحركة عند إعادة الربط. عند تعطيل الحركة (تقليل الحركة) تُعرض كلها فوراً.
 */
class SubscriptionHistoryAdapter(
    private val onSettleDebt: (SubscriptionEntity) -> Unit
) : ListAdapter<SubscriptionEntity, SubscriptionHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var currencySymbol: String = "₪"
    private val revealed = HashSet<Long>()

    fun submitList(newItems: List<SubscriptionEntity>, currency: String, onCommitted: Runnable? = null) {
        currencySymbol = currency
        submitList(newItems, onCommitted)
    }

    fun isRevealed(id: Long) = id in revealed
    fun markRevealed(id: Long) { revealed.add(id) }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemSubscriptionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(sub: SubscriptionEntity) {
            val context = binding.root.context
            binding.root.setTag(R.id.tag_sub_id, sub.id)

            // جاهز للظهور التدريجي: شفاف ومُزاح قليلاً حتى يُكشف، وإلا فكما هو.
            if (areAnimationsEnabled() && sub.id !in revealed) {
                binding.root.alpha = 0f
                binding.root.translationY = 12f * context.resources.displayMetrics.density
            } else {
                binding.root.alpha = 1f
                binding.root.translationY = 0f
            }

            binding.tvSubType.text = when (sub.type) {
                SubscriptionType.DAILY -> context.getString(R.string.subscription_daily)
                SubscriptionType.WEEKLY -> context.getString(R.string.subscription_weekly)
                SubscriptionType.MONTHLY -> context.getString(R.string.subscription_monthly)
                SubscriptionType.CUSTOM -> context.getString(R.string.subscription_custom)
            }
            binding.tvSubDates.text =
                "${DateUtils.formatDate(sub.startDate)} ← ${DateUtils.formatDate(sub.endDate)}"
            binding.tvSubPrice.text = CurrencyFormatter.format(sub.price, currencySymbol)

            // طريقة الدفع لا تُعرض إطلاقاً إن لم يُدفَع الاشتراك بعد (paidPaymentMethodLabel):
            // القيمة المحفوظة حينها مجرد "توقّع" من الدروب-داون وعرضها يُضلل صاحب الجيم.
            val paidMethodLabel = sub.paidPaymentMethodLabel(context)
            binding.tvSubPaymentMethod.visibility = if (paidMethodLabel != null) View.VISIBLE else View.GONE
            binding.tvSubPaymentMethod.text = paidMethodLabel.orEmpty()

            // بيانات المُرسِل تُعرض في شاشة تفاصيل العضو فقط، لا في السجل المختصر.
            binding.tvSubSenderInfo.visibility = View.GONE

            val status = sub.paymentStatus()
            val (label, colorRes, pillRes) = when (status) {
                PaymentStatus.PAID -> Triple(R.string.status_paid, R.color.home_mint, R.drawable.bg_detail_pill_mint)
                PaymentStatus.PARTIAL -> Triple(R.string.payment_status_partial, R.color.home_lav, R.drawable.bg_detail_pill_lav)
                PaymentStatus.UNPAID -> Triple(R.string.status_unpaid, R.color.home_coral, R.drawable.bg_detail_pill_coral)
            }
            val color = context.getColor(colorRes)
            binding.tvSubPaid.text = context.getString(label)
            binding.tvSubPaid.setBackgroundResource(pillRes)
            binding.tvSubPaid.setTextColor(color)
            binding.dotTimeline.backgroundTintList = ColorStateList.valueOf(color)

            when (status) {
                PaymentStatus.PAID -> {
                    binding.pbSubPaid.visibility = View.GONE
                    binding.rowSubAction.visibility = View.GONE
                }
                PaymentStatus.PARTIAL -> {
                    binding.tvSubRemaining.text = context.getString(
                        R.string.member_remaining_amount_format,
                        CurrencyFormatter.format(sub.remainingAmount(), currencySymbol)
                    )
                    binding.tvSubRemaining.visibility = View.VISIBLE
                    val paidShare = if (sub.price > 0.0) ((sub.paidAmount ?: 0.0) / sub.price * 100).toInt() else 0
                    binding.pbSubPaid.progress = paidShare.coerceIn(0, 100)
                    binding.pbSubPaid.visibility = View.VISIBLE
                    // زر "تسديد" متاح للجزئي أيضاً: يُحصّل كامل المتبقي ويحوّله إلى "مدفوع"
                    // (راجع GymRepository.togglePaidStatus).
                    binding.rowSubAction.visibility = View.VISIBLE
                    binding.btnSettleDebt.setOnSingleClickListener { onSettleDebt(sub) }
                }
                PaymentStatus.UNPAID -> {
                    binding.tvSubRemaining.visibility = View.INVISIBLE
                    binding.tvSubRemaining.text = ""
                    binding.pbSubPaid.visibility = View.GONE
                    binding.rowSubAction.visibility = View.VISIBLE
                    binding.btnSettleDebt.setOnSingleClickListener { onSettleDebt(sub) }
                }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SubscriptionEntity>() {
            override fun areItemsTheSame(old: SubscriptionEntity, new: SubscriptionEntity) =
                old.id == new.id

            // SubscriptionEntity data class: == يقارن كل الحقول المعروضة (price, type, تواريخ،
            // isPaid, paidAmount, ...) فلا يبقى صف قديم بعد تعديل لا يغيّر حالة الدفع.
            override fun areContentsTheSame(old: SubscriptionEntity, new: SubscriptionEntity) =
                old == new
        }
    }
}
