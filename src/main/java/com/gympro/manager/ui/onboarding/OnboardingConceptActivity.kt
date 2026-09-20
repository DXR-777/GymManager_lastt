package com.gympro.manager.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gympro.manager.R
import com.gympro.manager.databinding.ActivityOnboardingConceptBinding
import com.gympro.manager.utils.applyHorizontalGradient
import com.gympro.manager.utils.setOnSingleClickListener

/**
 * شاشة 2 من 4 في مسار الإعداد الأولي (Onboarding) — فكرة التطبيق.
 * لا finish() بعد الانتقال — راجع التعليق التفصيلي في OnboardingWelcomeActivity.
 */
class OnboardingConceptActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingConceptBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingConceptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvConceptTitle.applyHorizontalGradient(
            startColor = ContextCompat.getColor(this, R.color.brand_gradient_start),
            endColor = ContextCompat.getColor(this, R.color.brand_gradient_end)
        )

        binding.btnOnboardingNext.setOnSingleClickListener { goToNextStep() }
    }

    private fun goToNextStep() {
        startActivity(Intent(this, OnboardingCloudActivity::class.java))
    }
}
