package com.example.jarvis.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Symmetric cipher abstraction so secrets logic can be unit-tested without AndroidKeyStore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore (TEE/StrongBox backed where
 * available). Output layout: 12-byte IV followed by ciphertext+tag.
 */
class KeystoreSecretCipher(private val alias: String) : SecretCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_SIZE) { "Ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
        return cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/**
 * Small encrypted key/value store for API keys, OAuth state and the database passphrase.
 * Values are encrypted individually; the preferences file is excluded from Android backup.
 */
class SecureStore(
    private val prefs: SharedPreferences,
    private val cipher: SecretCipher
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        KeystoreSecretCipher(KEY_ALIAS)
    )

    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            String(cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key invalidated (e.g. device credential reset) — the value is unrecoverable.
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun putString(key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = Base64.encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(key, encrypted).commit()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    companion object {
        const val PREFS_NAME = "jarvis_secure"
        private const val KEY_ALIAS = "jarvis_secure_store_v1"

        const val GEMINI_API_KEY = "gemini_api_key"
        const val PICOVOICE_ACCESS_KEY = "picovoice_access_key"
        const val OPENAI_API_KEY = "openai_api_key"
        const val DB_PASSPHRASE = "db_passphrase"
    }
}
