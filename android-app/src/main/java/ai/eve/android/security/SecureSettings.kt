package ai.eve.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.eve.core.llm.ProviderId
import ai.eve.core.llm.RoutingMode
import ai.eve.core.providers.ModelCatalog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class KeyState { NONE, SAVED, UNREADABLE }

/**
 * API keys are encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available) and never leaves it. Ciphertext sits in private prefs.
 * Keys are never logged, never sent to the model and never exposed as a tool.
 *
 * Preference names and the Keystore alias are unchanged from earlier builds, so keys saved by
 * previous versions keep working after an update.
 */
class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("eve_settings", Context.MODE_PRIVATE)

    /** True when this launch removed a corrupted model value saved by an older build. */
    var migratedCorruptModel = false
        private set

    init { migrate() }

    // ── keys ──
    private fun keyPref(p: ProviderId) = when (p) {
        ProviderId.ANTHROPIC -> "api_key_enc" // legacy name, kept for upgrade compatibility
        ProviderId.OPENAI -> "key_openai"
        ProviderId.GEMINI -> "key_google"
    }

    fun key(p: ProviderId): String? = prefs.getString(keyPref(p), null)?.let { runCatching { decrypt(it) }.getOrNull() }?.takeIf { it.isNotBlank() }

    fun keyState(p: ProviderId): KeyState {
        val stored = prefs.getString(keyPref(p), null) ?: return KeyState.NONE
        return if (runCatching { decrypt(stored) }.isSuccess) KeyState.SAVED else KeyState.UNREADABLE
    }

    fun setKey(p: ProviderId, value: String?) {
        val v = value?.trim()
        prefs.edit().apply { if (v.isNullOrEmpty()) remove(keyPref(p)) else putString(keyPref(p), encrypt(v)) }.apply()
    }

    // ── models ──
    private fun modelPref(p: ProviderId) = when (p) {
        ProviderId.ANTHROPIC -> "model" // legacy name
        ProviderId.OPENAI -> "model_openai"
        ProviderId.GEMINI -> "model_google"
    }

    fun model(p: ProviderId): String = ModelCatalog.sanitize(p, prefs.getString(modelPref(p), null))

    /** Returns false (and stores nothing) when the value cannot be a model ID. */
    fun setModel(p: ProviderId, value: String): Boolean {
        if (!ModelCatalog.isPlausibleModelId(value)) return false
        prefs.edit().putString(modelPref(p), value.trim()).apply()
        return true
    }

    // ── routing ──
    fun enabled(p: ProviderId): Boolean = prefs.getBoolean("enabled_${p.key}", true)
    fun setEnabled(p: ProviderId, v: Boolean) = prefs.edit().putBoolean("enabled_${p.key}", v).apply()

    var mode: RoutingMode
        get() = runCatching { RoutingMode.valueOf(prefs.getString("routing_mode", null) ?: "") }.getOrDefault(RoutingMode.AUTOMATIC)
        set(v) = prefs.edit().putString("routing_mode", v.name).apply()

    var preferred: ProviderId?
        get() = ProviderId.fromKey(prefs.getString("preferred", null))
        set(v) = prefs.edit().apply { if (v == null) remove("preferred") else putString("preferred", v.key) }.apply()

    var fallback: Boolean
        get() = prefs.getBoolean("fallback", true)
        set(v) = prefs.edit().putBoolean("fallback", v).apply()

    var collaboration: Boolean
        get() = prefs.getBoolean("collaboration", true)
        set(v) = prefs.edit().putBoolean("collaboration", v).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean(K_SPEAK, true)
        set(v) = prefs.edit().putBoolean(K_SPEAK, v).apply()

    /**
     * v1 → v2: older builds pre-filled the Anthropic "Model" field with the router's display
     * label ("auto:anthropic:…,openai:…") and could save it as the model ID, which made every
     * Claude request fail. Remove any value that cannot be a model ID; keys are untouched.
     */
    private fun migrate() {
        if (prefs.getInt(K_VERSION, 1) >= SETTINGS_VERSION) return
        val e = prefs.edit()
        for (p in ProviderId.entries) {
            val v = prefs.getString(modelPref(p), null)
            if (v != null && !ModelCatalog.isPlausibleModelId(v)) { e.remove(modelPref(p)); migratedCorruptModel = true }
        }
        e.putInt(K_VERSION, SETTINGS_VERSION).apply()
    }

    private fun secretKey(): SecretKey {
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
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, ct) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val ALIAS = "eve_api_key_v1" // unchanged: existing ciphertext stays decryptable
        private const val K_SPEAK = "speak"
        private const val K_VERSION = "settings_version"
        private const val SETTINGS_VERSION = 2
    }
}
