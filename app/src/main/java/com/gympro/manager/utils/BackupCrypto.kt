package com.gympro.manager.utils

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** الملف مشفّر ولم تُمرَّر كلمة مرور. */
class BackupPasswordRequiredException : Exception("النسخة الاحتياطية مشفّرة وتحتاج كلمة مرور")

/** كلمة المرور خاطئة أو الملف تالف/معدَّل (AES-GCM لا يميّز بين الحالتين عمداً). */
class BackupWrongPasswordException : Exception("كلمة المرور غير صحيحة أو الملف تالف")

/**
 * تشفير النسخ الاحتياطية بكلمة مرور: AES-256-GCM مع اشتقاق المفتاح PBKDF2-HMAC-SHA256.
 *
 * تنسيق الملف (كل الأعداد big-endian):
 *   "GYMBK" (5) | version (1) | iterations (4) | salt (16) | iv (12) | ciphertext+tag
 *
 * - الترويسة حتى نهاية الـsalt تُمرَّر كـAAD، فأي تعديل فيها (مثل خفض عدد التكرارات) يُفشل
 *   التحقق من الوسم.
 * - عدد التكرارات مخزّن في الملف فيمكن رفعه مستقبلاً دون كسر النسخ القديمة.
 * - salt وiv عشوائيان لكل نسخة، فنسختان من البيانات نفسها بالكلمة نفسها تختلفان.
 * - لا يمكن استرجاع كلمة المرور إن ضاعت: هذا مقصود، ويُحذَّر منه المستخدم في الواجهة.
 */
object BackupCrypto {

    private val MAGIC = "GYMBK".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 5_000_000 // يمنع ملفاً خبيثاً من تجميد التطبيق
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val AAD_LEN = MAGIC.size + 1 + 4 + SALT_LEN
    private val HEADER_LEN = AAD_LEN + IV_LEN

    private val random = SecureRandom()

    fun isEncrypted(data: ByteArray): Boolean =
        data.size >= MAGIC.size && MAGIC.indices.all { data[it] == MAGIC[it] }

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        val salt = ByteArray(SALT_LEN)
        val iv = ByteArray(IV_LEN)
        random.nextBytes(salt)
        random.nextBytes(iv)
        val aad = ByteBuffer.allocate(AAD_LEN)
            .put(MAGIC).put(VERSION).putInt(iterations).put(salt).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return aad + iv + cipher.doFinal(plain)
    }

    /**
     * @throws BackupPasswordRequiredException إن كان الملف غير مشفّر بهذا التنسيق (لا ينبغي استدعاؤها له)
     * @throws BackupWrongPasswordException كلمة مرور خاطئة أو ملف معدَّل/تالف
     */
    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        if (!isEncrypted(data) || data.size < HEADER_LEN + TAG_BITS / 8) throw BackupWrongPasswordException()
        val buf = ByteBuffer.wrap(data)
        buf.position(MAGIC.size)
        val version = buf.get()
        if (version != VERSION) throw IllegalStateException("إصدار نسخة احتياطية غير مدعوم: $version")
        val iterations = buf.int
        if (iterations !in 1..MAX_ITERATIONS) throw BackupWrongPasswordException()
        val salt = ByteArray(SALT_LEN)
        buf.get(salt)
        val iv = ByteArray(IV_LEN)
        buf.get(iv)
        val aad = data.copyOfRange(0, AAD_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(data, HEADER_LEN, data.size - HEADER_LEN)
        } catch (e: AEADBadTagException) {
            throw BackupWrongPasswordException()
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
