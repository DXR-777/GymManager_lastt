package com.gympro.manager.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * منطق "التذكير الذكي" بتفعيل التنبيهات بعد أن يتجاهل المستخدم الورقة الأولى:
 *  - بطاقة دائمة في الرئيسية (تظهر فقط حين توجد أرقام حقيقية تهمّه، ويمكن إغلاقها وتعود بعد أسبوع).
 *  - تلميح على الجرس (حتى 3 مرات فقط).
 * isEnabled تغطي رفض الإذن (أندرويد 13+) وتعطيل الإشعارات يدوياً من إعدادات النظام (أي إصدار).
 */
object NotificationNudge {
    private const val PREFS = "notification_nudge"
    private const val KEY_BANNER_DISMISSED_AT = "banner_dismissed_at"
    private const val KEY_TIP_SHOWN_COUNT = "tip_shown_count"
    private const val BANNER_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
    private const val TIP_MAX = 3

    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun shouldShowBanner(context: Context, alertsCount: Int): Boolean {
        if (isEnabled(context) || alertsCount <= 0) return false
        val dismissedAt = prefs(context).getLong(KEY_BANNER_DISMISSED_AT, 0L)
        return System.currentTimeMillis() - dismissedAt > BANNER_COOLDOWN_MS
    }

    fun dismissBanner(context: Context) {
        prefs(context).edit().putLong(KEY_BANNER_DISMISSED_AT, System.currentTimeMillis()).apply()
    }

    fun shouldShowTip(context: Context): Boolean =
        !isEnabled(context) && prefs(context).getInt(KEY_TIP_SHOWN_COUNT, 0) < TIP_MAX

    fun markTipShown(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_TIP_SHOWN_COUNT, p.getInt(KEY_TIP_SHOWN_COUNT, 0) + 1).apply()
    }

    fun openSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        activity.startActivity(intent)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
