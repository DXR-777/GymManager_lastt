package com.gympro.manager.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gympro.manager.R
import com.gympro.manager.databinding.ActivityOnboardingWelcomeBinding
import com.gympro.manager.utils.applyHorizontalGradient
import com.gympro.manager.utils.setOnSingleClickListener

/**
 * شاشة 1 من 4 في مسار الإعداد الأولي (Onboarding) — الترحيب. هذه أول شاشة
 * يراها المستخدم عند أول تشغيل (يصلها فقط عبر SplashActivity عند
 * isSetupComplete == false)، لذا الضغط على "نظام الرجوع" هنا يُنهي هذا الـ
 * Activity الوحيد الموجود على المهمّة (Task) فيُغلق التطبيق — وهذا هو سلوك
 * أندرويد القياسي والمتوقَّع لأول شاشة في أي تطبيق (تماماً كالشاشة الرئيسية
 * لأي تطبيق آخر)، وليس إغلاقاً غير متوقَّع يستدعي معالجة خاصة.
 *
 * لا يُستدعى finish() بعد الانتقال للشاشة التالية عمداً: تبقى هذه الشاشة على
 * Back Stack حتى يعمل زر الرجوع النظامي من الشاشة 2 بشكل طبيعي ويعيد المستخدم
 * إلى هنا (سلوك أندرويد القياسي لأي مسار متعدد الخطوات). يُنظَّف الـ Back
 * Stack بالكامل مرة واحدة فقط عند اكتمال المسار فعلياً في نهاية
 * GymSetupActivity، وليس في كل خطوة وسيطة.
 */
class OnboardingWelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvOnboardingTitle.applyHorizontalGradient(
            startColor = ContextCompat.getColor(this, R.color.brand_gradient_start),
            endColor = ContextCompat.getColor(this, R.color.brand_gradient_end)
        )

        binding.btnOnboardingNext.setOnSingleClickListener { goToNextStep() }
    }

    private fun goToNextStep() {
        startActivity(Intent(this, OnboardingConceptActivity::class.java))
    }
}
