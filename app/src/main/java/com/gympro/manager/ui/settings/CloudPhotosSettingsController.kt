package com.gympro.manager.ui.settings

import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.gympro.manager.R
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.databinding.FragmentSettingsBinding
import com.gympro.manager.utils.CloudPhotoPrefs
import com.gympro.manager.utils.CloudPhotoSync
import com.gympro.manager.utils.DriveAuth
import com.gympro.manager.utils.GoogleDriveAuth
import com.gympro.manager.utils.setOnSingleClickListener
import com.gympro.manager.utils.toast
import com.gympro.manager.utils.visibleIf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * بطاقة "حساب Google" في الإعدادات: ربط الحساب، وتفعيل حفظ صور الأعضاء في Google Drive (مساحة
 * التطبيق الخاصة)، وعرض حالة المزامنة. الموافقة (drive.appdata) تُطلب مرة واحدة عند التفعيل، وبعدها
 * تعمل المزامنة بصمت في الخلفية (CloudPhotoSyncWorker).
 */
class CloudPhotosSettingsController(
    private val fragment: Fragment,
    private val binding: FragmentSettingsBinding,
    private val repository: GymRepository,
    private val launchSignIn: (Intent) -> Unit,
    private val launchConsent: (IntentSenderRequest) -> Unit
) {
    private val context get() = fragment.requireContext()
    private var email: String? = null
    private var rendering = false

    private val signInClient by lazy {
        GoogleSignIn.getClient(
            context,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().build()
        )
    }

    fun bind() {
        binding.btnCloudSignIn.setOnSingleClickListener { launchSignIn(signInClient.signInIntent) }
        binding.btnCloudSyncNow.setOnSingleClickListener { retrySync() }
        binding.switchCloudPhotos.setOnCheckedChangeListener { _, checked ->
            if (rendering) return@setOnCheckedChangeListener
            if (checked) startEnable() else disable()
        }
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeSettings().collect { settings ->
                    email = settings?.googleAccountEmail
                    render()
                }
            }
        }
        // تحديث الحالة عند انتهاء/تغيّر عمل المزامنة.
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(CloudPhotoSync.UNIQUE_ONE_TIME)
            .observe(owner) { render() }
    }

    fun onSignInResult(data: Intent?) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                repository.updateGoogleAccount(account.email, account.displayName)
            }
        } catch (e: ApiException) {
            if (e.statusCode != CommonStatusCodes.CANCELED) context.toast(context.getString(R.string.cloud_auth_failed))
        }
    }

    fun onConsentResult(data: Intent?) {
        try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
            if (result.accessToken != null) activate() else denied()
        } catch (e: ApiException) {
            denied()
        }
    }

    private fun startEnable() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            when (val auth = GoogleDriveAuth.authorize(context, email)) {
                is DriveAuth.Token -> activate()
                is DriveAuth.NeedsConsent -> launchConsent(IntentSenderRequest.Builder(auth.pendingIntent.intentSender).build())
                is DriveAuth.Failed -> {
                    context.toast(context.getString(R.string.cloud_auth_failed))
                    render()
                }
            }
        }
    }

    private fun activate() {
        CloudPhotoPrefs.setEnabled(context, true)
        CloudPhotoPrefs.markResult(context, CloudPhotoPrefs.STATUS_IDLE)
        CloudPhotoSync.ensurePeriodic(context)
        CloudPhotoSync.schedule(context)
        render()
    }

    private fun denied() {
        context.toast(context.getString(R.string.cloud_consent_denied))
        render()
    }

    private fun disable() {
        CloudPhotoPrefs.setEnabled(context, false)
        CloudPhotoSync.cancelAll(context)
        render()
    }

    /** "مزامنة الآن": إن كان الإذن مطلوباً من جديد يُعاد طلبه، وإلا تُجدوَل المزامنة. */
    private fun retrySync() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            when (val auth = GoogleDriveAuth.authorize(context, email)) {
                is DriveAuth.Token -> {
                    CloudPhotoPrefs.markResult(context, CloudPhotoPrefs.STATUS_IDLE)
                    CloudPhotoSync.schedule(context)
                    render()
                }
                is DriveAuth.NeedsConsent -> launchConsent(IntentSenderRequest.Builder(auth.pendingIntent.intentSender).build())
                is DriveAuth.Failed -> context.toast(context.getString(R.string.cloud_auth_failed))
            }
        }
    }

    private fun render() {
        if (!fragment.isAdded || fragment.view == null) return
        rendering = true
        val signedIn = !email.isNullOrBlank()
        val enabled = CloudPhotoPrefs.isEnabled(context)
        binding.tvCloudAccount.text = email ?: context.getString(R.string.cloud_account_none)
        binding.btnCloudSignIn.visibleIf(!signedIn)
        binding.containerCloudPhotos.visibleIf(signedIn)
        binding.switchCloudPhotos.isChecked = enabled && signedIn
        binding.btnCloudSyncNow.visibleIf(enabled && signedIn)
        binding.tvCloudPhotosStatus.visibleIf(enabled && signedIn)
        binding.tvCloudPhotosStatus.text = when (CloudPhotoPrefs.status(context)) {
            CloudPhotoPrefs.STATUS_OK -> context.getString(
                R.string.cloud_status_ok,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(CloudPhotoPrefs.lastSyncAt(context)))
            )
            CloudPhotoPrefs.STATUS_NEEDS_CONSENT -> context.getString(R.string.cloud_status_consent)
            CloudPhotoPrefs.STATUS_FAILED -> context.getString(R.string.cloud_status_failed)
            else -> context.getString(R.string.cloud_status_idle)
        }
        rendering = false
    }
}
