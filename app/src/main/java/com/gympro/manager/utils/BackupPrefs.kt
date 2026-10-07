package com.gympro.manager.utils

import android.content.Context
import android.net.Uri
import java.util.concurrent.TimeUnit

object BackupPrefs {

    private const val FILE = "auto_backup_prefs"
    private const val K_ENABLED = "enabled"
    private const val K_TREE = "tree_uri"
    private const val K_PASSWORD = "password_blob"
    private const val K_LAST_BACKUP = "last_backup_at"
    private const val K_LAST_AUTO_OK = "last_auto_ok"
    private const val K_LAST_ERROR = "last_error"
    private const val K_FIRST_SEEN = "first_seen_at"
    private const val K_HAS_ENCRYPTED = "has_encrypted_backup"

    val REMINDER_INTERVAL_MS: Long = TimeUnit.DAYS.toMillis(7)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isAutoEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, false)

    fun treeUri(c: Context): Uri? = prefs(c).getString(K_TREE, null)?.let(Uri::parse)

    fun setTree(c: Context, uri: Uri?) {
        prefs(c).edit().apply {
            if (uri == null) remove(K_TREE) else putString(K_TREE, uri.toString())
        }.apply()
    }

    fun setPassword(c: Context, password: CharArray?) {
        prefs(c).edit().apply {
            if (password == null) remove(K_PASSWORD) else putString(K_PASSWORD, SecretVault.encrypt(password))
        }.apply()
    }

    fun hasPassword(c: Context): Boolean = prefs(c).getString(K_PASSWORD, null) != null

    fun password(c: Context): CharArray? = prefs(c).getString(K_PASSWORD, null)?.let(SecretVault::decrypt)

    fun setAutoEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(K_ENABLED, enabled).apply()
    }

    fun disableAuto(c: Context) {
        prefs(c).edit().putBoolean(K_ENABLED, false).remove(K_PASSWORD).remove(K_LAST_ERROR).apply()
    }

    fun markBackupDone(c: Context, encrypted: Boolean) {
        prefs(c).edit().putLong(K_LAST_BACKUP, System.currentTimeMillis()).apply {
            if (encrypted) putBoolean(K_HAS_ENCRYPTED, true)
        }.apply()
    }

    fun markAutoResult(c: Context, ok: Boolean, error: String? = null) {
        prefs(c).edit().apply {
            if (ok) {
                putLong(K_LAST_AUTO_OK, System.currentTimeMillis()).remove(K_LAST_ERROR)
            } else {
                putString(K_LAST_ERROR, error ?: "")
            }
        }.apply()
    }

    fun lastBackupAt(c: Context): Long = prefs(c).getLong(K_LAST_BACKUP, 0L)

    fun lastError(c: Context): String? = prefs(c).getString(K_LAST_ERROR, null)

    fun hasEncryptedBackup(c: Context): Boolean = prefs(c).getBoolean(K_HAS_ENCRYPTED, false)

    fun isReminderDue(c: Context): Boolean {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val first = p.getLong(K_FIRST_SEEN, 0L).takeIf { it > 0 } ?: now.also {
            p.edit().putLong(K_FIRST_SEEN, it).apply()
        }
        val reference = maxOf(lastBackupAt(c), first)
        return now - reference >= REMINDER_INTERVAL_MS
    }
}
