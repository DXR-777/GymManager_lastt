package com.gympro.manager.ui.members

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
import com.gympro.manager.utils.setOnSingleClickListener

/**
 * التغيير الرئيسي: تحوّل من RecyclerView.Adapter بـ notifyDataSetChanged() إلى ListAdapter بـ DiffUtil.
 *
 * لماذا هذا يحل المشكلة؟
 * notifyDataSetChanged() يُخبر RecyclerView بأن "كل شيء تغيّر" — فيُلغي كل الـ views
 * ويُعيد بناءها من الصفر. داخل ScrollView هذا يُسبب مشكلة:
 * الـ RecyclerView يُحسب ارتفاعه الجديد قبل أن تُبنى الـ views الجديدة،
 * فيظن أن الارتفاع صفر أو ارتفاع item واحد فقط.
 *
 * DiffUtil يُخبر RecyclerView بالتغييرات الدقيقة فقط (item واحد تغيّرت حالته):
 * - الـ views الأخرى تبقى كما هي (لم يُعد بناؤها).
 * - لا يحدث إعادة حساب خاطئة للارتفاع.
 * - النتيجة: كل الاشتراكات السابقة تبقى ظاهرة بعد التسديد.
 */
class SubscriptionHistoryAdapter(
    private val onSettleDebt: (SubscriptionEntity) -> Unit
) : ListAdapter<SubscriptionEntity, SubscriptionHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var currencySymbol: String = "₪"

    fun submitList(newItems: List<SubscriptionEntity>, currency: String) {
        currencySymbol = currency
        submitList(newItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemSubscriptionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemSubscriptionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(sub: SubscriptionEntity) {
            val context = binding.root.context

            binding.tvSubType.text = when (sub.type) {
                SubscriptionType.DAILY -> context.getString(R.string.subscription_daily)
                SubscriptionType.WEEKLY -> context.getString(R.string.subscription_weekly)
                SubscriptionType.MONTHLY -> context.getString(R.string.subscription_monthly)
                SubscriptionType.CUSTOM -> context.getString(R.string.subscription_custom)
            }
            binding.tvSubDates.text =
                "${DateUtils.formatDate(sub.startDate)} ← ${DateUtils.formatDate(sub.endDate)}"
            binding.tvSubPrice.text = CurrencyFormatter.format(sub.price, currencySymbol)
            // صيغة مختصرة توفّر مساحة رأسية بدل سطر "طريقة الدفع: ..." الطويل: "الحالة •
            // الطريقة" (مثال: "مدفوع • بال باي"). شارة الدفع (tvSubPaid) أدناه تبقى كما
            // هي دون تغيير — هذا السطر إضافي مختصر وليس بديلاً عنها.
            //
            // طريقة الدفع لا تُعرض إطلاقاً إن لم يُدفَع الاشتراك بعد (راجع
            // paidPaymentMethodLabel في PaymentMethod.kt) — فالقيمة المحفوظة في هذه
            // الحالة مجرد "توقّع" من الدروب-داون وليست دفعاً فعلياً، وعرضها يُضلل صاحب
            // الجيم. لذا يُعرض "غير مدفوع" فقط دون أي طريقة دفع بجانبها.
            val paidMethodLabel = sub.paidPaymentMethodLabel(context)
            val statusLabel = when (sub.paymentStatus()) {
                PaymentStatus.PAID -> context.getString(R.string.status_paid)
                PaymentStatus.PARTIAL -> context.getString(R.string.payment_status_partial)
                PaymentStatus.UNPAID -> context.getString(R.string.status_unpaid)
            }
            binding.tvSubPaymentMethod.text = if (paidMethodLabel != null) {
                context.getString(R.string.subscription_payment_method_compact, statusLabel, paidMethodLabel)
            } else {
                statusLabel
            }

            // بيانات المُرسِل (اسم صاحب المحفظة/رقم المحفظة) تُعرَض فقط في شاشة تفاصيل
            // العضو (راجع MemberDetailActivity)، وليس هنا داخل سجل الاشتراكات المختصر،
            // حتى لا تصبح البطاقة مزدحمة — يبقى هذا السطر "الحالة • طريقة الدفع" فقط.
            binding.tvSubSenderInfo.visibility = View.GONE

            when (sub.paymentStatus()) {
                PaymentStatus.PAID -> {
                    binding.tvSubPaid.text = context.getString(R.string.status_paid)
                    binding.tvSubPaid.setBackgroundResource(R.drawable.bg_pill_success)
                    binding.tvSubPaid.setTextColor(context.getColor(R.color.status_success))
                    binding.tvSubRemaining.visibility = View.GONE
                    binding.btnSettleDebt.visibility = View.GONE
                }
                PaymentStatus.PARTIAL -> {
                    binding.tvSubPaid.text = context.getString(R.string.payment_status_partial)
                    binding.tvSubPaid.setBackgroundResource(R.drawable.bg_pill_partial)
                    binding.tvSubPaid.setTextColor(context.getColor(R.color.status_partial))
                    binding.tvSubRemaining.text = context.getString(
                        R.string.member_remaining_amount_format,
                        CurrencyFormatter.format(sub.remainingAmount(), currencySymbol)
                    )
                    binding.tvSubRemaining.visibility = View.VISIBLE
                    // زر "تسديد" متاح أيضاً للدفعات الجزئية الآن — يُحصِّل كامل المتبقي
                    // الآن ويحوّل الاشتراك إلى "مدفوع" بالكامل (راجع GymRepository.togglePaidStatus).
                    binding.btnSettleDebt.visibility = View.VISIBLE
                    binding.btnSettleDebt.setOnSingleClickListener { onSettleDebt(sub) }
                }
                PaymentStatus.UNPAID -> {
                    binding.tvSubPaid.text = context.getString(R.string.status_unpaid)
                    binding.tvSubPaid.setBackgroundResource(R.drawable.bg_pill_error)
                    binding.tvSubPaid.setTextColor(context.getColor(R.color.status_error))
                    binding.tvSubRemaining.visibility = View.GONE
                    binding.btnSettleDebt.visibility = View.VISIBLE
                    binding.btnSettleDebt.setOnSingleClickListener { onSettleDebt(sub) }
                }
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<SubscriptionEntity>() {
            override fun areItemsTheSame(old: SubscriptionEntity, new: SubscriptionEntity) =
                old.id == new.id
            // يُعيد رسم item واحد فقط عند تغيّر أي حقل معروض فعلياً في الصف (بما فيها
            // isPaid/paidAmount/paidAt). SubscriptionEntity هي data class، لذا == تقارن كل
            // الحقول بنيوياً (price, type, startDate, endDate, isPaid, paidAmount, paidAt,
            // billingAnchorDay, ...) — هذا يمنع بقاء صف بسعر/نوع/تاريخ قديم على الشاشة بعد
            // تعديل الاشتراك دون تبديل حالة الدفع.
            override fun areContentsTheSame(old: SubscriptionEntity, new: SubscriptionEntity) =
                old == new
        }
    }
}
