package com.gympro.manager.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import com.gympro.manager.R
import com.gympro.manager.ui.splash.SplashActivity
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * قفل التطبيق (رمز PIN من 6 أرقام + بصمة اختيارية + استرداد).
 *
 * قرارات التصميم:
 *  - الرمز لا يُخزَّن أبداً كنص ولا كـ hash عادي: يُخزَّن HMAC-SHA256 بملح عشوائي، والمفتاح داخل
 *    Android Keystore (غير قابل للاستخراج من الجهاز) — فحتى من يسحب ملف التفضيلات من جهاز
 *    مروَّت لا يستطيع تجربة الملايين من التخمينات خارج الجهاز (6 أرقام = مليون احتمال فقط).
 *  - ملف التفضيلات app_lock.xml مُستبعَد من النسخ الاحتياطي التلقائي (راجع xml/backup_rules.xml)
 *    لأن المفتاح لا ينتقل مع النسخة، فلو انتقل الملف وحده لصار الرمز "خاطئاً" دائماً.
 *  - إيقاف مؤقت متصاعد بعد كل 5 محاولات خاطئة (راجع PinPolicy)، محفوظ على القرص فلا يُصفَّر
 *    بإغلاق التطبيق.
 *  - القفل التلقائي يعتمد elapsedRealtime (لا يتأثر بتغيير ساعة الجهاز).
 *  - الاسترداد: (1) قفل الهاتف نفسه، أو (2) رمز استرداد من 16 خانة يُعرض مرة واحدة عند التفعيل.
 */
object AppLockManager {

    val AUTO_LOCK_OPTIONS_MS = longArrayOf(0L, 30_000L, 60_000L, 300_000L)
    private const val DEFAULT_AUTO_LOCK_MS = 60_000L

    private const val PREFS_NAME = "app_lock"   // ← يطابق الاستبعاد في backup_rules.xml / data_extraction_rules.xml
    private const val K_ENABLED = "enabled"
    private const val K_PIN_HASH = "pin_hash"
    private const val K_PIN_SALT = "pin_salt"
    private const val K_RECOVERY_HASH = "recovery_hash"
    private const val K_RECOVERY_SALT = "recovery_salt"
    private const val K_BIOMETRIC = "biometric"
    private const val K_AUTO_LOCK = "auto_lock_ms"
    private const val K_SECURE_SCREEN = "secure_screen"
    private const val K_FAILS = "fail_count"
    private const val K_LOCK_START = "lockout_start"
    private const val K_LOCK_DURATION = "lockout_duration"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gympro_app_lock_hmac"
    private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // بلا I/O/0/1 لتفادي الالتباس
    private const val RECOVERY_LENGTH = 16
    private const val COVER_TAG = "app_lock_cover"

    enum class VerifyResult { SUCCESS, WRONG, LOCKED_OUT, UNAVAILABLE }

    private lateinit var appContext: Context

    @Volatile private var unlocked = false
    @Volatile private var lockRequested = false
    private var startedCount = 0
    private var backgroundedAt = 0L   // 0 = التطبيق في الواجهة حالياً

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun install(app: Application) {
        appContext = app.applicationContext
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    // ───────────────────────────── الحالة ─────────────────────────────

    fun isEnabled(): Boolean =
        prefs.getBoolean(K_ENABLED, false) && prefs.getString(K_PIN_HASH, null) != null

    fun shouldLock(): Boolean = isEnabled() && !unlocked
    fun markUnlocked() { unlocked = true }
    fun onLockScreenClosed() { lockRequested = false }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(K_BIOMETRIC, false)
    fun setBiometricEnabled(v: Boolean) { prefs.edit().putBoolean(K_BIOMETRIC, v).commit() }

    fun autoLockDelayMs(): Long = prefs.getLong(K_AUTO_LOCK, DEFAULT_AUTO_LOCK_MS)
    fun setAutoLockDelayMs(v: Long) { prefs.edit().putLong(K_AUTO_LOCK, v).commit() }

    fun isSecureScreenEnabled(): Boolean = prefs.getBoolean(K_SECURE_SCREEN, true)
    fun setSecureScreen(v: Boolean) { prefs.edit().putBoolean(K_SECURE_SCREEN, v).commit() }

    fun biometricAvailable(context: Context): Boolean =
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    // ───────────────────────────── الرمز ─────────────────────────────

    /** يضبط/يغيّر الرمز ويفعّل القفل. false عند فشل Keystore (نادر جداً). */
    fun setPin(pin: String): Boolean = try {
        val key = existingKey() ?: createKey()
        val salt = randomBytes(16)
        prefs.edit()
            .putString(K_PIN_SALT, b64(salt))
            .putString(K_PIN_HASH, b64(hmac(pin, salt, key)))
            .putBoolean(K_ENABLED, true)
            .commit()
        resetFailures()
        true
    } catch (e: Exception) {
        false
    }

    fun verifyPin(pin: String): VerifyResult {
        if (remainingLockoutMs() > 0L) return VerifyResult.LOCKED_OUT
        return when (matches(K_PIN_HASH, K_PIN_SALT, pin)) {
            null -> VerifyResult.UNAVAILABLE
            true -> { resetFailures(); VerifyResult.SUCCESS }
            false -> { registerFailure(); VerifyResult.WRONG }
        }
    }

    /** يوقف القفل ويمسح كل ما يخصه (بما فيه مفتاح Keystore). */
    fun disable() {
        prefs.edit().clear().commit()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    // ───────────────────────────── رمز الاسترداد ─────────────────────────────

    /** يولّد رمز استرداد جديداً (يُبطل السابق) ويعيده للعرض مرة واحدة. لا يُخزَّن إلا HMAC له. */
    fun generateRecoveryCode(): String? = try {
        val rnd = SecureRandom()
        val raw = String(CharArray(RECOVERY_LENGTH) { RECOVERY_ALPHABET[rnd.nextInt(RECOVERY_ALPHABET.length)] })
        val key = existingKey() ?: createKey()
        val salt = randomBytes(16)
        prefs.edit()
            .putString(K_RECOVERY_SALT, b64(salt))
            .putString(K_RECOVERY_HASH, b64(hmac(raw, salt, key)))
            .commit()
        raw.chunked(4).joinToString("-")
    } catch (e: Exception) {
        null
    }

    fun verifyRecoveryCode(input: String): Boolean {
        val normalized = input.uppercase(Locale.US).filter { it in RECOVERY_ALPHABET }
        if (normalized.length != RECOVERY_LENGTH) return false
        return matches(K_RECOVERY_HASH, K_RECOVERY_SALT, normalized) == true
    }

    // ───────────────────────────── الإيقاف المؤقت ─────────────────────────────

    fun remainingLockoutMs(): Long = PinPolicy.remainingLockoutMs(
        prefs.getLong(K_LOCK_START, 0L),
        prefs.getLong(K_LOCK_DURATION, 0L),
        System.currentTimeMillis()
    )

    fun attemptsLeftInRound(): Int = PinPolicy.attemptsLeft(prefs.getInt(K_FAILS, 0))

    fun resetFailures() {
        prefs.edit().remove(K_FAILS).remove(K_LOCK_START).remove(K_LOCK_DURATION).commit()
    }

    private fun registerFailure() {
        val fails = prefs.getInt(K_FAILS, 0) + 1
        val editor = prefs.edit().putInt(K_FAILS, fails)
        val lockMs = PinPolicy.lockoutAfterFailure(fails)
        if (lockMs > 0L) {
            editor.putLong(K_LOCK_START, System.currentTimeMillis()).putLong(K_LOCK_DURATION, lockMs)
        }
        editor.commit()
    }

    // ───────────────────────────── التشفير ─────────────────────────────

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = try {
        keyStore().getKey(KEY_ALIAS, null) as? SecretKey
    } catch (e: Exception) {
        null
    }

    private fun createKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    private fun hmac(secret: String, salt: ByteArray, key: SecretKey): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        mac.update(salt)
        return mac.doFinal(secret.toByteArray(Charsets.UTF_8))
    }

    /**
     * true/false = مطابق/غير مطابق. null = لا يمكن التحقق أصلاً (لا بيانات، أو مفتاح Keystore ضاع —
     * مثلاً بعد تصفير الجهاز/تحديث معطوب). لا نُنشئ مفتاحاً جديداً هنا عمداً: مفتاح جديد يعني أن
     * الرمز الصحيح يظهر "خاطئاً" للأبد؛ الأفضل إبلاغ المستخدم ليستعيد الوصول بالطرق البديلة.
     */
    private fun matches(hashKey: String, saltKey: String, secret: String): Boolean? {
        val hash = prefs.getString(hashKey, null) ?: return null
        val salt = prefs.getString(saltKey, null) ?: return null
        val key = existingKey() ?: return null
        return try {
            MessageDigest.isEqual(hmac(secret, Base64.decode(salt, Base64.NO_WRAP), key), Base64.decode(hash, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    // ───────────────────────────── حماية الشاشة ─────────────────────────────

    /** FLAG_SECURE: يخفي المحتوى من قائمة التطبيقات الأخيرة ويمنع لقطات/تسجيل الشاشة. */
    fun applySecureFlag(activity: Activity) {
        val secure = isEnabled() && (isSecureScreenEnabled() || activity is LockActivity)
        if (secure) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    // ───────────────────────────── دورة الحياة والقفل التلقائي ─────────────────────────────

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {

        override fun onActivityStarted(activity: Activity) {
            if (startedCount == 0) onEnterForeground()
            startedCount++
            applySecureFlag(activity)
            if (activity is LockActivity || activity is SplashActivity) return
            if (shouldLock()) {
                cover(activity)
                launchLockScreen(activity)
            }
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity is LockActivity || activity is SplashActivity) return
            if (shouldLock()) {
                cover(activity)
                if (!lockRequested) launchLockScreen(activity)
            } else {
                uncover(activity)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            startedCount = (startedCount - 1).coerceAtLeast(0)
            // تدوير/تغيير إعدادات ليس خروجاً من التطبيق
            if (startedCount == 0 && !activity.isChangingConfigurations) {
                backgroundedAt = SystemClock.elapsedRealtime()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private fun onEnterForeground() {
        if (backgroundedAt != 0L) {
            val away = SystemClock.elapsedRealtime() - backgroundedAt
            if (isEnabled() && away >= autoLockDelayMs()) unlocked = false
            backgroundedAt = 0L
        }
    }

    private fun launchLockScreen(from: Activity) {
        lockRequested = true
        from.startActivity(
            Intent(from, LockActivity::class.java)
                .putExtra(LockActivity.EXTRA_MODE, LockActivity.MODE_UNLOCK)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    /** غطاء معتم فوق الشاشة الحساسة لحين ظهور شاشة القفل — يمنع وميض المحتوى لإطار واحد. */
    private fun cover(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (decor.findViewWithTag<View>(COVER_TAG) != null) return
        val cover = View(activity).apply {
            tag = COVER_TAG
            isClickable = true
            isFocusable = true
            setBackgroundColor(ContextCompat.getColor(activity, R.color.background))
        }
        decor.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun uncover(activity: Activity) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(COVER_TAG)?.let { decor.removeView(it) }
    }
}
