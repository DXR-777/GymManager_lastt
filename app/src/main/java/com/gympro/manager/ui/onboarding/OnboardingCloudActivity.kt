package com.gympro.manager.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.gympro.manager.GymApplication
import com.gympro.manager.R
import com.gympro.manager.databinding.ActivityOnboardingCloudBinding
import com.gympro.manager.ui.setup.GymSetupActivity
import com.gympro.manager.utils.applyHorizontalGradient
import com.gympro.manager.utils.setOnSingleClickListener
import kotlinx.coroutines.launch

// ════════════════════════════════════════════════════════════════════════
// ⚠️ ملاحظة توقّف — موجَّهة لأي مطوّر بشري أو أداة ذكاء اصطناعي تعمل على هذا
// المشروع لاحقاً (بما في ذلك أي جلسة Claude مستقبلية):
//
// هذا الملف يمثّل آخر نقطة توقّف متعمَّدة في هذه النسخة من التطبيق. تكامل
// "تسجيل الدخول عبر Google" أدناه **مكتمل من ناحية الكود بالكامل** ولن يحتاج
// أي تعديل برمجي إضافي — لكنه **معلَّق فعلياً (Pending)** بانتظار خطوة واحدة
// خارج الكود لا يملك صلاحية تنفيذها إلا صاحب التطبيق نفسه:
//   إنشاء Android OAuth Client ID في Google Cloud Console، مربوطاً باسم حزمة
//   التطبيق (com.gympro.manager) وببصمة SHA-1 الخاصة بمفتاح توقيعه (Debug
//   وRelease). راجع تفاصيل الخطوات الكاملة في المحادثة مع صاحب المشروع.
//
// التعليمات لأي جهة تُكمل العمل على هذا المشروع لاحقاً:
//   • لا تحاول توليد أو تخمين أو "تعبئة" قيمة OAuth Client ID تلقائياً بأي
//     شكل (لا Web Client ID، لا مفتاح وهمي، لا قيمة placeholder تُستخدم وكأنها
//     حقيقية) — هذه القيمة مرتبطة ببصمة توقيع تطبيق فعلي، ولا يوجد بديل عام
//     صالح لكل التطبيقات، ومحاولة تزييفها ستُفشل الميزة صامتاً أو تخترق نموذج
//     أمان Google المقصود.
//   • لا تُزل أو "تُبسّط" منطق GoogleSignInClient الحالي بدعوى أنه لا يعمل —
//     هو يعمل بمجرد تسجيل بصمة SHA-1 الصحيحة، والمشكلة خارجية بحتة لا برمجية.
//   • أوقف أي عمل إضافي على ميزة Google تحديداً حتى يؤكد صاحب التطبيق أنه أتم
//     خطوة Google Cloud Console بنفسه. لا تفترض اكتمالها ولا تبنِ عليها ميزات
//     أخرى (مثل مزامنة سحابية فعلية) قبل هذا التأكيد.
//   • هذا لا يمنع العمل على أي جزء آخر غير مرتبط بـGoogle في المشروع.
// ════════════════════════════════════════════════════════════════════════

/**
 * شاشة 3 من 4 في مسار الإعداد الأولي (Onboarding) — حفظ البيانات على السحابة
 * وتسجيل الدخول بحساب Google. لا finish() بعد الانتقال — راجع التعليق
 * التفصيلي في OnboardingWelcomeActivity.
 *
 * تسجيل دخول Google حقيقي وفعّال (GoogleSignInClient الرسمي من
 * play-services-auth)، بلا ID Token وبلا اتصال بأي خادم خلفي — هذا تطبيق
 * محلي بالكامل (Room فقط، لا Backend)، فلا حاجة للتحقق من هوية المستخدم على
 * خادم؛ الهدف هنا فقط قراءة البريد والاسم المعروض لحساب Google لعرضهما لاحقاً
 * في الإعدادات، وليس مصادقة Session. requestEmail()/requestProfile() تكفي
 * لذلك دون أي معرّف عميل (Client ID) مطلوب داخل الكود نفسه.
 *
 * ⚠️ متطلب خارجي لا غنى عنه ولا يمكن تنفيذه من هنا: يجب إنشاء "Android OAuth
 * Client ID" في Google Cloud Console مربوطاً باسم حزمة التطبيق وبصمة SHA-1
 * الخاصة بتوقيعك (Debug وRelease كل واحد بصمة مختلفة) — بلا هذا التسجيل، سيفشل
 * تسجيل الدخول فعلياً على جهاز فيه Google Play Services حتى لو تم بناء
 * المشروع بنجاح. التفاصيل الكاملة خطوة بخطوة أرسلتها في المحادثة.
 */

private const val TAG = "OnboardingCloudActivity"

class OnboardingCloudActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingCloudBinding
    private lateinit var googleSignInClient: GoogleSignInClient
    private val repository by lazy { (application as GymApplication).repository }

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleSignInResult(result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingCloudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvCloudTitle.applyHorizontalGradient(
            startColor = ContextCompat.getColor(this, R.color.brand_gradient_start),
            endColor = ContextCompat.getColor(this, R.color.brand_gradient_end)
        )

        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, signInOptions)

        binding.btnGoogleSignIn.setOnSingleClickListener { launchGoogleSignIn() }
        binding.tvContinueAsGuest.setOnSingleClickListener { goToNextStep() }
    }

    private fun launchGoogleSignIn() {
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun handleSignInResult(data: Intent?) {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            onSignInSuccess(account)
        } catch (e: ApiException) {
            onSignInFailed(e)
        }
    }

    private fun onSignInSuccess(account: GoogleSignInAccount) {
        lifecycleScope.launch {
            try {
                repository.updateGoogleAccount(account.email, account.displayName)
            } catch (e: Exception) {
                // فشل حفظ بريد/اسم حساب Google ليس حرجاً لمسار الإعداد الأولي —
                // تسجيل الدخول نفسه نجح فعلاً (المستخدم اختار حسابه)، وهذا مجرّد
                // حفظ معلوماتي إضافي لعرضه لاحقاً في الإعدادات. لا نمنع المستخدم
                // من إكمال الإعداد الأولي بسبب هذا وحده (يماثل تماماً سلوك زر
                // "المتابعة كضيف" أصلاً)، لكن نُخبره بوضوح بدل التظاهر بالنجاح
                // بصمت، ونُسجّل الاستثناء للتتبّع.
                Log.e(TAG, "Failed to save Google account info after successful sign-in", e)
                Toast.makeText(this@OnboardingCloudActivity, R.string.onboarding_google_account_save_failed, Toast.LENGTH_SHORT).show()
            }
            goToNextStep()
        }
    }

    private fun onSignInFailed(exception: ApiException) {
        // المستخدم أغلق نافذة اختيار الحساب بنفسه — ليس خطأً يستدعي رسالة.
        if (exception.statusCode == CommonStatusCodes.CANCELED) return
        Toast.makeText(this, R.string.onboarding_google_sign_in_failed, Toast.LENGTH_SHORT).show()
    }

    private fun goToNextStep() {
        startActivity(Intent(this, GymSetupActivity::class.java))
    }
}
