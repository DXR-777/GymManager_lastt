package com.gympro.manager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gympro.manager.GymApplication
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.NotificationHelper
import kotlinx.coroutines.flow.first

/**
 * يعمل مرة كل 24 ساعة تقريباً (تجدوله [GymApplication]) ويفحص:
 * 1) الأعضاء الذين سينتهي اشتراكهم قريباً → تنبيه تذكيري.
 * 2) الأعضاء الذين انتهى اشتراكهم اليوم تماماً → تنبيه.
 * 3) إجمالي عدد الاشتراكات غير المدفوعة → تنبيه تجميعي واحد.
 * يحترم مفاتيح التفعيل/التعطيل في إعدادات النادي (notifyExpiry / notifyUnpaid).
 */
class ExpiryCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GymApplication
        val repository = app.repository
        val settings = repository.getSettingsOnce()

        val members = repository.observeActiveMembers("").first()
        val now = DateUtils.now()

        if (settings.notifyExpiry) {
            // فرع else أدناه جديد: يُلغي صراحة أي إشعار "ينتهي قريباً/منتهٍ اليوم" سابق
            // لعضو لم يعد ضمن نطاق 0..3 أيام اليوم (جدّد اشتراكه، أو تجاوزت مدة انتهائه
            // 3 أيام بلا تجديد فلم يعد "اليوم" بالذات). بدونه كان الإشعار يبقى معلَّقاً
            // في الشريط إلى الأبد بمعلومة قديمة خاطئة، لأن الحلقة كانت ببساطة لا تفعل
            // شيئاً لهذا العضو في الأيام التالية بدل إلغاء إشعاره صراحة.
            var notifiedCount = 0
            members.forEach { member ->
                val endDate = member.endDate
                val startDate = member.startDate
                val daysLeft = if (endDate != null && startDate != null && !DateUtils.isFuture(startDate, now)) {
                    DateUtils.daysRemaining(endDate, now)
                } else {
                    null
                }
                when {
                    daysLeft == 0 -> {
                        NotificationHelper.showExpired(applicationContext, member.id, member.name)
                        notifiedCount++
                    }
                    daysLeft != null && daysLeft in 1..3 -> {
                        NotificationHelper.showExpiring(applicationContext, member.id, member.name, daysLeft)
                        notifiedCount++
                    }
                    else -> NotificationHelper.cancelExpiryReminder(applicationContext, member.id)
                }
            }
            // ملخّص مجمَّع واحد فوق الإشعارات الفردية أعلاه — راجع تعليق
            // notif_expiry_summary_title في strings.xml. يُستدعى دائماً (حتى بعدد 0 أو 1)
            // ليُلغي أي ملخّص قديم عالق من دورة سابقة بعدد لم يعد صحيحاً.
            NotificationHelper.showExpirySummary(applicationContext, notifiedCount)
        }

        if (settings.notifyUnpaid) {
            val unpaidCount = members.count { it.isUnpaid() }
            NotificationHelper.showUnpaidSummary(applicationContext, unpaidCount)
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "expiry_check_worker"
    }
}
