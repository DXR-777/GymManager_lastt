package com.gympro.manager.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "expiry_check_after_time_change",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ExpiryCheckWorker>().build()
                )
            }
        }
    }
}
