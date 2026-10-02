package com.gympro.manager.ui.splash

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.data.repository.GymRepository
import com.gympro.manager.ui.main.MainActivity
import com.gympro.manager.ui.onboarding.OnboardingWelcomeActivity
import kotlinx.coroutines.launch

private const val TAG = "SplashActivity"

/**
 * تتحقق من حالة الإعداد الأولي: إذا لم يقم صاحب النادي بالإعداد بعد
 * (اسم النادي والأسعار) تُوجَّه إلى مسار الإعداد الأولي (Onboarding، يبدأ
 * بشاشة الترحيب وينتهي بشاشة إعداد النادي)، وإلا تذهب مباشرة للرئيسية.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var repository: GymRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = (application as GymApplication).repository
        loadSettingsAndNavigate()
    }

    /**
     * البند 5: مغلَّفة الآن بـ try/catch — أي فشل في قراءة قاعدة البيانات (تلف الملف،
     * مساحة تخزين ممتلئة أثناء ترحيل معلّق...) لم يعد يُحطِّم التطبيق فوراً بلا تفسير؛
     * بدل ذلك يُسجَّل الخطأ (نفس نمط Log.e المستخدم في GymSetupActivity/
     * OnboardingCloudActivity) وتُعرَض رسالة واضحة للمستخدم مع خيار إعادة المحاولة —
     * تغطي أسباباً مؤقتة (قفل قاعدة بيانات لحظي، مساحة تخزين ممتلئة تُفرَّغ يدوياً) دون
     * إجبار المستخدم على إغلاق التطبيق وإعادة فتحه من الصفر لتجربة نفس الشيء يدوياً.
     */
    private fun loadSettingsAndNavigate() {
        lifecycleScope.launch {
            try {
                val settings = repository.getSettingsOnce()
                val next = if (settings.isSetupComplete) {
                    Intent(this@SplashActivity, MainActivity::class.java)
                } else {
                    Intent(this@SplashActivity, OnboardingWelcomeActivity::class.java)
                }
                startActivity(next)
                finish()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read gym settings on startup", e)
                showLoadErrorDialog()
            }
        }
    }

    private fun showLoadErrorDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.splash_load_error_title)
            .setMessage(R.string.splash_load_error_message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_retry) { _, _ -> loadSettingsAndNavigate() }
            .setNegativeButton(R.string.action_exit_app) { _, _ -> finish() }
            .show()
    }
}
