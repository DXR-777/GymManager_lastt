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
            members.forEach { member ->
                val endDate = member.endDate ?: return@forEach
                val startDate = member.startDate ?: return@forEach
                if (DateUtils.isFuture(startDate, now)) return@forEach
                val daysLeft = DateUtils.daysRemaining(endDate, now)
                when {
                    daysLeft == 0 -> NotificationHelper.showExpired(applicationContext, member.id, member.name)
                    daysLeft in 1..3 -> NotificationHelper.showExpiring(applicationContext, member.id, member.name, daysLeft)
                }
            }
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
