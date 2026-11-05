package com.gympro.manager.ui.archive

import com.gympro.manager.utils.bindMemberAvatar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.gympro.manager.R
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.databinding.ItemArchivedBinding
import com.gympro.manager.model.MemberListItem
import com.gympro.manager.utils.CurrencyFormatter
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.visibleIf

class ArchiveAdapter(
    private val onRestore: (MemberListItem) -> Unit,
    private val onPermanentDelete: (MemberListItem) -> Unit
) : ListAdapter<MemberListItem, ArchiveAdapter.ViewHolder>(DIFF) {

    /**
     * رمز العملة المستخدم لعرض بادج "دَين مستحق" لكل عضو مؤرشف عليه outstandingBalance > 0.
     * outstandingBalance يصل مسبقاً محسوباً ضمن MemberListItem عبر MemberDao.getDeletedMembers()
     * (نفس subquery المستخدم للأعضاء النشطين) — كان محسوباً بالفعل في طبقة البيانات لكنه لم يكن
     * يُعرض هنا، فيختفي الدَّين تماماً عن صاحب النادي بمجرد أرشفة العضو دون أي وسيلة لرؤيته
     * إلا بالاستعادة. راجع بادج مماثل في MembersAdapter وبانر التحذير في DeleteConfirmBottomSheet.
     */
    private var currencySymbol: String = "₪"

    /** يُستدعى عند تغيّر إعدادات العملة (GymSettingsEntity) لتحديث كل البادجات المعروضة */
    fun setCurrencySymbol(symbol: String) {
        if (symbol == currencySymbol) return
        currencySymbol = symbol
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): ViewHolder {
        val binding = ItemArchivedBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemArchivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MemberListItem) {
            val context = binding.root.context
            binding.tvInitial.bindMemberAvatar(item.name, item.photoPath)
            binding.tvName.text = item.name
            // مؤقّت الحذف النهائي التلقائي (راجع ArchivePurgeWorker): نصّ + شريط تقدّم بلون الإلحاح
            // (أخضر > 10 أيام، كهرماني 4–10، مرجاني ≤ 3) حتى يرى الموظف فوراً من يوشك أن يُحذف.
            val deletedAt = item.deletedAt
            if (deletedAt == null) {
                binding.tvDeletedOn.text = "—"
                binding.tvPurgeCountdown.visibility = View.GONE
                binding.progressPurge.visibility = View.GONE
            } else {
                binding.tvDeletedOn.text = context.getString(R.string.archive_deleted_on, DateUtils.formatDate(deletedAt))
                val elapsed = DateUtils.daysBetween(deletedAt, DateUtils.now())
                val daysLeft = (GymRepository.ARCHIVE_RETENTION_DAYS - elapsed).coerceAtLeast(0)
                val color = context.getColor(
                    when {
                        daysLeft <= 3 -> R.color.home_coral
                        daysLeft <= 10 -> R.color.home_amber
                        else -> R.color.home_mint
                    }
                )
                binding.tvPurgeCountdown.visibility = View.VISIBLE
                binding.tvPurgeCountdown.text = if (daysLeft > 0) {
                    context.getString(R.string.archive_purge_countdown, daysLeft)
                } else {
                    context.getString(R.string.archive_purge_countdown_today)
                }
                binding.tvPurgeCountdown.setTextColor(color)
                binding.progressPurge.visibility = View.VISIBLE
                binding.progressPurge.setIndicatorColor(color)
                val pct = (daysLeft * 100 / GymRepository.ARCHIVE_RETENTION_DAYS).coerceIn(4, 100)
                binding.progressPurge.setProgressCompat(pct, false)
            }

            // الدَّين الحقيقي المتراكم (outstandingBalance عبر isUnpaid()) يجب أن يبقى مرئياً
            // حتى بعد الأرشفة، بنفس المبدأ المطبَّق في بقية الشاشات (لوحة التحكم، فلتر "غير
            // مدفوع"، بانر تأكيد الحذف) — الأرشفة تُخفي العضو عن القائمة النشطة فقط، ولا يجوز
            // أن تُخفي ديناً حقيقياً لم يُسدَّد بعد.
            if (item.isUnpaid()) {
                binding.tvDebtBadge.text = context.getString(
                    R.string.archive_debt_badge,
                    CurrencyFormatter.format(item.outstandingBalance, currencySymbol)
                )
            }
            binding.tvDebtBadge.visibleIf(item.isUnpaid())

            binding.btnRestore.setOnSingleClickListener { onRestore(item) }
            binding.btnPermanentDelete.setOnSingleClickListener { onPermanentDelete(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MemberListItem>() {
            override fun areItemsTheSame(oldItem: MemberListItem, newItem: MemberListItem) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MemberListItem, newItem: MemberListItem) = oldItem == newItem
        }
    }
}
