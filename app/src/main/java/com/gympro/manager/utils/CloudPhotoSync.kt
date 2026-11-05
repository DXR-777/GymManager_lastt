package com.gympro.manager.utils

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.worker.CloudPhotoSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * مزامنة صور الأعضاء مع Google Drive (appDataFolder) عندما يفعّل المستخدم الخيار في الإعدادات:
 *  - رفع أي صورة محلية غير موجودة في Drive.
 *  - تنزيل أي صورة مرتبطة بعضو وغير موجودة محلياً (هاتف جديد/إعادة تثبيت).
 *  - حذف نسخة Drive لصورة استُبدلت/أُزيلت على هذا الجهاز فقط (لا نحذف "اليتيمة" تلقائياً حتى لا
 *    تُمسح صور قبل استعادة قاعدة البيانات على جهاز جديد).
 */
object CloudPhotoSync {
    const val UNIQUE_ONE_TIME = "cloud_photo_sync"
    private const val UNIQUE_PERIODIC = "cloud_photo_sync_periodic"

    data class Result(val uploaded: Int, val downloaded: Int, val deleted: Int)

    suspend fun sync(context: Context, repository: GymRepository, token: String): Result =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val remote = GoogleDriveAppData.list(token).toMutableMap()
            val paths = repository.getAllPhotoPaths().distinct()
            var up = 0
            var down = 0
            var del = 0

            val done = HashSet<String>()
            CloudPhotoPrefs.pendingDeletes(app).forEach { name ->
                remote[name]?.let { id ->
                    GoogleDriveAppData.delete(token, id)
                    remote.remove(name)
                    del++
                }
                done.add(name)
            }
            if (done.isNotEmpty()) CloudPhotoPrefs.clearPendingDeletes(app, done)

            paths.forEach { path ->
                val name = File(path).name
                val local = MemberPhotoStore.file(app, path)
                when {
                    local.exists() && name !in remote -> {
                        remote[name] = GoogleDriveAppData.upload(token, name, local)
                        up++
                    }
                    !local.exists() && name in remote -> {
                        GoogleDriveAppData.download(token, remote.getValue(name), local)
                        down++
                    }
                }
            }
            Result(up, down, del)
        }

    fun scheduleIfEnabled(context: Context) {
        if (CloudPhotoPrefs.isEnabled(context)) schedule(context)
    }

    /** مزامنة فورية (عند توفر الشبكة) — تُستبدل بأحدث طلب. */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudPhotoSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_ONE_TIME, ExistingWorkPolicy.REPLACE, request)
    }

    /** مزامنة يومية خفيفة تعوّض أي رفع فاته. تُستدعى عند بدء التطبيق. */
    fun ensurePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!CloudPhotoPrefs.isEnabled(context)) {
            wm.cancelUniqueWork(UNIQUE_PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<CloudPhotoSyncWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(UNIQUE_ONE_TIME)
        wm.cancelUniqueWork(UNIQUE_PERIODIC)
    }

    /** يسجّل حذف نسخة Drive لصورة لم تعد مستخدمة (تُنفَّذ في المزامنة التالية). */
    fun queueRemoteDelete(context: Context, relativePath: String?) {
        if (relativePath.isNullOrBlank() || !CloudPhotoPrefs.isEnabled(context)) return
        CloudPhotoPrefs.addPendingDelete(context, File(relativePath).name)
    }
}
