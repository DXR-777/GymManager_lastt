package com.gympro.manager

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gympro.manager.data.local.AppDatabase
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.security.AppLockManager
import com.gympro.manager.utils.NotificationHelper
import com.gympro.manager.worker.ArchivePurgeWorker
import com.gympro.manager.worker.ExpiryCheckWorker
import java.util.concurrent.TimeUnit

class GymApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: GymRepository by lazy { GymRepository.getInstance(database) }

    override fun onCreate() {
        super.onCreate()
        AppLockManager.install(this)
        NotificationHelper.createChannel(this)
        scheduleExpiryWorker()
        scheduleArchivePurgeWorker()
    }

    /** يفحص الاشتراكات المنتهية/القريبة من الانتهاء والمدفوعات المتأخرة مرة كل 24 ساعة */
    private fun scheduleExpiryWorker() {
        val request = PeriodicWorkRequestBuilder<ExpiryCheckWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ExpiryCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** يحذف نهائياً الأعضاء المؤرشفين منذ أكثر من مهلة الاستعادة، مرة كل 24 ساعة — راجع ArchivePurgeWorker. */
    private fun scheduleArchivePurgeWorker() {
        val request = PeriodicWorkRequestBuilder<ArchivePurgeWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ArchivePurgeWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
