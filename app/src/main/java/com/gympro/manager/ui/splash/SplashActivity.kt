package com.gympro.manager.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gympro.manager.GymApplication
import com.gympro.manager.ui.main.MainActivity
import com.gympro.manager.ui.onboarding.OnboardingWelcomeActivity
import kotlinx.coroutines.launch

/**
 * تتحقق من حالة الإعداد الأولي: إذا لم يقم صاحب النادي بالإعداد بعد
 * (اسم النادي والأسعار) تُوجَّه إلى مسار الإعداد الأولي (Onboarding، يبدأ
 * بشاشة الترحيب وينتهي بشاشة إعداد النادي)، وإلا تذهب مباشرة للرئيسية.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as GymApplication).repository

        lifecycleScope.launch {
            val settings = repository.getSettingsOnce()
            val next = if (settings.isSetupComplete) {
                Intent(this@SplashActivity, MainActivity::class.java)
            } else {
                Intent(this@SplashActivity, OnboardingWelcomeActivity::class.java)
            }
            startActivity(next)
            finish()
        }
    }
}
