package com.gympro.manager.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gympro.manager.GymApplication
import com.gympro.manager.utils.CloudPhotoPrefs
import com.gympro.manager.utils.CloudPhotoSync
import com.gympro.manager.utils.CrashReporter
import com.gympro.manager.utils.DriveAuth
import com.gympro.manager.utils.GoogleDriveAuth
import java.io.IOException

/** يرفع/ينزّل صور الأعضاء مع Google Drive بصمت في الخلفية (يحتاج شبكة وموافقة سابقة من المستخدم). */
class CloudPhotoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!CloudPhotoPrefs.isEnabled(app)) return Result.success()
        val repository = (app as GymApplication).repository
        return try {
            val email = repository.getSettingsOnce().googleAccountEmail
            when (val auth = GoogleDriveAuth.authorize(app, email)) {
                is DriveAuth.Token -> {
                    CloudPhotoSync.sync(app, repository, auth.value)
                    CloudPhotoPrefs.markResult(app, CloudPhotoPrefs.STATUS_OK)
                    Result.success()
                }
                is DriveAuth.NeedsConsent -> {
                    // الإذن سُحب أو لم يُمنح بعد: لا فائدة من إعادة المحاولة بصمت، يُطلب من الإعدادات.
                    CloudPhotoPrefs.markResult(app, CloudPhotoPrefs.STATUS_NEEDS_CONSENT)
                    Result.success()
                }
                is DriveAuth.Failed -> retryOrFail(auth.error)
            }
        } catch (e: IOException) {
            retryOrFail(e)
        } catch (e: Exception) {
            CrashReporter.record(e)
            CloudPhotoPrefs.markResult(app, CloudPhotoPrefs.STATUS_FAILED)
            Result.failure()
        }
    }

    private fun retryOrFail(error: Throwable): Result {
        CrashReporter.record(error)
        return if (runAttemptCount < 4) {
            Result.retry()
        } else {
            CloudPhotoPrefs.markResult(applicationContext, CloudPhotoPrefs.STATUS_FAILED)
            Result.failure()
        }
    }
}
