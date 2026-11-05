package com.gympro.manager.utils

import android.content.Context

/** حالة مزامنة صور الأعضاء مع Google Drive (SharedPreferences؛ لا تغيير على قاعدة البيانات). */
object CloudPhotoPrefs {
    private const val FILE = "cloud_photo_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PENDING_DELETES = "pending_deletes"
    private const val KEY_LAST_SYNC = "last_sync_at"
    private const val KEY_STATUS = "status"

    const val STATUS_IDLE = "idle"
    const val STATUS_OK = "ok"
    const val STATUS_NEEDS_CONSENT = "needs_consent"
    const val STATUS_FAILED = "failed"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun status(c: Context): String = prefs(c).getString(KEY_STATUS, STATUS_IDLE) ?: STATUS_IDLE
    fun lastSyncAt(c: Context): Long = prefs(c).getLong(KEY_LAST_SYNC, 0L)

    fun markResult(c: Context, status: String) {
        val e = prefs(c).edit().putString(KEY_STATUS, status)
        if (status == STATUS_OK) e.putLong(KEY_LAST_SYNC, System.currentTimeMillis())
        e.apply()
    }

    /** ملفات (اسم الملف فقط) تنتظر الحذف من Drive بعد استبدال/إزالة صورة على هذا الجهاز. */
    fun pendingDeletes(c: Context): Set<String> =
        prefs(c).getStringSet(KEY_PENDING_DELETES, emptySet()) ?: emptySet()

    fun addPendingDelete(c: Context, name: String) {
        prefs(c).edit().putStringSet(KEY_PENDING_DELETES, pendingDeletes(c) + name).apply()
    }

    fun clearPendingDeletes(c: Context, done: Set<String>) {
        prefs(c).edit().putStringSet(KEY_PENDING_DELETES, pendingDeletes(c) - done).apply()
    }
}
