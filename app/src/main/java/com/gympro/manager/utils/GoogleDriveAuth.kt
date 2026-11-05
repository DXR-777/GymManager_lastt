package com.gympro.manager.utils

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class DriveAuth {
    data class Token(val value: String) : DriveAuth()
    /** أول مرة (أو سُحب الإذن): يلزم عرض شاشة موافقة Google للمستخدم من Activity. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAuth()
    data class Failed(val error: Throwable) : DriveAuth()
}

/**
 * طلب إذن drive.appdata عبر AuthorizationClient الرسمي (Google Identity). بعد موافقة المستخدم مرة واحدة
 * يعيد التوكن بصمت في الخلفية (WorkManager) دون أي واجهة.
 */
object GoogleDriveAuth {
    const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

    suspend fun authorize(context: Context, email: String?): DriveAuth = suspendCancellableCoroutine { cont ->
        try {
            val builder = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_APPDATA)))
            if (!email.isNullOrBlank()) builder.setAccount(Account(email, "com.google"))
            Identity.getAuthorizationClient(context.applicationContext)
                .authorize(builder.build())
                .addOnSuccessListener { result ->
                    val pending = result.pendingIntent
                    val token = result.accessToken
                    val out: DriveAuth = when {
                        result.hasResolution() && pending != null -> DriveAuth.NeedsConsent(pending)
                        token != null -> DriveAuth.Token(token)
                        else -> DriveAuth.Failed(IllegalStateException("no_token"))
                    }
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(DriveAuth.Failed(it)) }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(DriveAuth.Failed(e))
        }
    }
}
