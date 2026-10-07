package com.gympro.manager.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {

    // تكرارات قليلة لتسريع الاختبارات فقط؛ الإنتاج يستخدم DEFAULT_ITERATIONS.
    private val fast = 1_000
    private val plain = """{"members":[{"name":"أحمد"}]}""".toByteArray(Charsets.UTF_8)

    @Test fun roundTrip_restoresOriginalBytes() {
        val enc = BackupCrypto.encrypt(plain, "كلمة-سر-123".toCharArray(), fast)
        assertArrayEquals(plain, BackupCrypto.decrypt(enc, "كلمة-سر-123".toCharArray()))
    }

    @Test fun encrypted_isDetected_andPlainJsonIsNot() {
        assertTrue(BackupCrypto.isEncrypted(BackupCrypto.encrypt(plain, "secret1".toCharArray(), fast)))
        assertFalse(BackupCrypto.isEncrypted(plain))
        assertFalse(BackupCrypto.isEncrypted(ByteArray(0)))
    }

    @Test fun wrongPassword_isRejected() {
        val enc = BackupCrypto.encrypt(plain, "right-pass".toCharArray(), fast)
        try {
            BackupCrypto.decrypt(enc, "wrong-pass".toCharArray())
            fail("expected BackupWrongPasswordException")
        } catch (_: BackupWrongPasswordException) {
        }
    }

    @Test fun tamperedCiphertext_isRejected() {
        val enc = BackupCrypto.encrypt(plain, "secret1".toCharArray(), fast)
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 1).toByte()
        try {
            BackupCrypto.decrypt(enc, "secret1".toCharArray())
            fail("expected BackupWrongPasswordException")
        } catch (_: BackupWrongPasswordException) {
        }
    }

    @Test fun tamperedHeaderIterations_isRejected() {
        val enc = BackupCrypto.encrypt(plain, "secret1".toCharArray(), fast)
        enc[9] = (enc[9].toInt() xor 1).toByte() // آخر بايت من عدد التكرارات
        try {
            BackupCrypto.decrypt(enc, "secret1".toCharArray())
            fail("expected BackupWrongPasswordException")
        } catch (_: BackupWrongPasswordException) {
        }
    }

    @Test fun sameInput_producesDifferentFiles() {
        val a = BackupCrypto.encrypt(plain, "secret1".toCharArray(), fast)
        val b = BackupCrypto.encrypt(plain, "secret1".toCharArray(), fast)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun truncatedFile_isRejected() {
        val enc = BackupCrypto.encrypt(plain, "secret1".toCharArray(), fast)
        try {
            BackupCrypto.decrypt(enc.copyOf(20), "secret1".toCharArray())
            fail("expected BackupWrongPasswordException")
        } catch (_: BackupWrongPasswordException) {
        }
    }
}
