package com.gympro.manager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gympro.manager.GymApplication
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.utils.DateUtils
import com.gympro.manager.utils.NotificationHelper

/**
 * يحذف نهائياً كل عضو موجود في الأرشيف (isDeleted = true) منذ أكثر من
 * [GymRepository.ARCHIVE_RETENTION_DAYS] يوماً — تنفيذ فعلي للوعد الظاهر في رسالة تأكيد
 * الحذف (delete_dialog_message): "سيتم نقل %1$s إلى الأرشيف. يمكنك استعادته خلال 30 يوماً."
 *
 * قبل إضافة هذا الـ Worker كانت هذه الرسالة وعداً بلا أي تنفيذ برمجي فعلي: الأعضاء
 * المؤرشفون كانوا يبقون محفوظين للأبد حتى حذف يدوي صريح من شاشة الأرشيف — ما يخالف مباشرة
 * ما يُقال للمستخدم في شاشة الحذف.
 *
 * قبل إضافة خطوة التحذير هذه، كان الحذف النهائي يحدث بصمت تام كل 24 ساعة بلا أي تنبيه
 * مسبق: عضو يبقى مؤرشفاً 30 يوماً (مثلاً بعد سفر صاحب النادي أو انشغاله) كان يُحذف نهائياً
 * وبلا أي رجعة دون أن يعرف صاحب النادي أن الموعد اقترب أصلاً — القرار غير القابل للتراجع
 * الوحيد في التطبيق كان يُتَّخذ تلقائياً بلا تدخل بشري ممكن. الآن: كل دورة تُرسل أولاً تنبيهاً
 * لكل عضو سيُحذف خلال أيام قليلة قادمة (طالما لم يُحذف بعد)، ثم تُنفِّذ الحذف الفعلي لمن
 * انتهت مهلته بالفعل — بنفس ترتيب "أنذر ثم نفِّذ" المستخدم في ExpiryCheckWorker للاشتراكات.
 */
class ArchivePurgeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GymApplication
        val repository = app.repository

        val warningCandidates = repository.getArchivePurgeWarningCandidates()
        val now = DateUtils.now()
        warningCandidates.forEach { candidate ->
            val elapsed = DateUtils.daysBetween(candidate.deletedAt, now)
            val daysLeft = (GymRepository.ARCHIVE_RETENTION_DAYS - elapsed).coerceAtLeast(1)
            NotificationHelper.showArchivePurgeWarning(applicationContext, candidate.id, candidate.name, daysLeft)
        }

        repository.purgeArchivedOlderThan()
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "archive_purge_worker"
    }
}
