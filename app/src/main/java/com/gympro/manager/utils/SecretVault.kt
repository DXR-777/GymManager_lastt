package com.gympro.manager.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecretVault {

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "gym_auto_backup_secret_v1"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(secret: CharArray): String {
        val bytes = Charsets.UTF_8.encode(CharBuffer.wrap(secret)).let { bb ->
            ByteArray(bb.remaining()).also { bb.get(it) }
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.iv + cipher.doFinal(bytes)
            return Base64.encodeToString(out, Base64.NO_WRAP)
        } finally {
            bytes.fill(0)
        }
    }

    fun decrypt(blob: String): CharArray? = try {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        if (data.size <= IV_LEN) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_LEN))
            val plain = cipher.doFinal(data, IV_LEN, data.size - IV_LEN)
            val cb = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
            val chars = CharArray(cb.remaining()).also { cb.get(it) }
            plain.fill(0)
            chars
        }
    } catch (e: Exception) {
        null
    }

    fun deleteKey() {
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }
}
