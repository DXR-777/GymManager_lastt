package com.gympro.manager.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gympro.manager.GymApplication
import com.gympro.manager.utils.AutoBackupRunner
import com.gympro.manager.utils.BackupManager
import com.gympro.manager.utils.BackupPrefs
import com.gympro.manager.utils.NotificationHelper
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val app = ctx as GymApplication
        if (BackupPrefs.hasEncryptedBackup(ctx)) {
            BackupManager.purgePlainBackups(ctx, olderThanMs = TimeUnit.DAYS.toMillis(1))
        }
        if (BackupPrefs.isAutoEnabled(ctx)) {
            val ok = AutoBackupRunner.run(ctx, app.repository)
            if (!ok) NotificationHelper.showBackupReminder(ctx, failed = true)
        } else if (BackupPrefs.isReminderDue(ctx)) {
            NotificationHelper.showBackupReminder(ctx, failed = false)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "auto_backup_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
