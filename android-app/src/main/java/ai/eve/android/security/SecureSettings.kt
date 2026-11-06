package ai.eve.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys are encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available) and never leaves it. Ciphertext sits in private prefs.
 * Keys are never logged, never sent to the model and never exposed as a tool.
 */
class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("eve_settings", Context.MODE_PRIVATE)

    fun apiKey(): String? = prefs.getString(K_API, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    fun setApiKey(value: String?) {
        val v = value?.trim()
        prefs.edit().apply { if (v.isNullOrEmpty()) remove(K_API) else putString(K_API, encrypt(v)) }.apply()
    }

    fun providerKey(provider: String): String? {
        if (provider == "anthropic") return apiKey()
        return prefs.getString("key_$provider", null)?.let { runCatching { decrypt(it) }.getOrNull() }
    }
    fun setProviderKey(provider: String, value: String?) {
        if (provider == "anthropic") { setApiKey(value); return }
        require(provider in setOf("openai", "google"))
        val cleaned = value?.trim()
        prefs.edit().apply {
            if (cleaned.isNullOrBlank()) remove("key_$provider")
            else putString("key_$provider", encrypt(cleaned))
        }.apply()
    }
    var collaboration: Boolean
        get() = prefs.getBoolean("collaboration", true)
        set(v) = prefs.edit().putBoolean("collaboration", v).apply()

    fun model(): String = prefs.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
    fun setModel(value: String) = prefs.edit().putString(K_MODEL, value.trim()).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean(K_SPEAK, true)
        set(v) = prefs.edit().putBoolean(K_SPEAK, v).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "eve_api_key_v1"
        private const val K_API = "api_key_enc"
        private const val K_MODEL = "model"
        private const val K_SPEAK = "speak"
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
    }
}
