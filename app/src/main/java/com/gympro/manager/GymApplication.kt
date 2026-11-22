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
import com.gympro.manager.worker.AutoBackupWorker
import com.gympro.manager.utils.ThemePrefs
import com.gympro.manager.utils.CrashReporter
import com.gympro.manager.worker.ExpiryCheckWorker
import java.util.concurrent.TimeUnit
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import com.gympro.manager.R

class GymApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val repository: GymRepository by lazy { GymRepository.getInstance(database) }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init()
        installLocalCrashLog()
        AppLockManager.install(this)
        NotificationHelper.createChannel(this)
        scheduleExpiryWorker()
        scheduleArchivePurgeWorker()
        AutoBackupWorker.schedule(this)
        com.gympro.manager.utils.CloudPhotoSync.ensurePeriodic(this)
        ThemePrefs.apply(this)
        installOrientationPolicy()
    }

    /** يحفظ آخر خطأ قاتل في ملف، ثم يعرضه (للنسخ) عند فتح التطبيق مجدداً لتشخيص الإغلاق المفاجئ. */
    private fun installLocalCrashLog() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "last_crash.txt").writeText(android.util.Log.getStackTraceString(e))
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun showPendingCrash(activity: Activity) {
        val file = java.io.File(filesDir, "last_crash.txt")
        if (!file.exists() || activity !is androidx.appcompat.app.AppCompatActivity) return
        val text = try { file.readText() } catch (_: Throwable) { return }
        file.delete()
        val tv = android.widget.TextView(activity).apply {
            this.text = text.take(3500)
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(40, 20, 40, 20)
        }
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("تقرير الخطأ (أرسله للمطوّر)")
            .setView(android.widget.ScrollView(activity).apply { addView(tv) })
            .setPositiveButton("نسخ") { _, _ ->
                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    /**
     * بدل قفل portrait في الـManifest (يُجبر الأجهزة اللوحية على عمودي ضيق ويخالف إرشادات
     * Google للشاشات الكبيرة): الهواتف تبقى عمودية، والأجهزة بأصغر عرض ≥ 600dp تدور بحرية.
     * مقصور على أنشطة التطبيق نفسه حتى لا نمسّ أنشطة مكتبات (تسجيل Google مثلاً).
     */
    private fun installOrientationPolicy() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity.javaClass.name.startsWith(packageName) &&
                    activity.resources.getBoolean(R.bool.lock_portrait)
                ) {
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                showPendingCrash(activity)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
