package com.raiden.craffa.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.raiden.craffa.data.api.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

const val DEFAULT_BASE_URL = "http://100.104.161.86:8471"

/**
 * App settings. The API token is encrypted with an AES-256-GCM key that
 * lives in the Android Keystore (never leaves secure hardware where present);
 * only the ciphertext is stored in SharedPreferences. Backups are disabled
 * in the manifest, so the ciphertext never travels without its key.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("craffa_settings", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val configFlow: StateFlow<ServerConfig> = _config

    /** Current config, safe to read from any thread (API calls, workers, receivers). */
    val config: ServerConfig get() = _config.value

    fun save(baseUrl: String, token: String) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        prefs.edit()
            .putString(KEY_URL, cleanUrl)
            .putString(KEY_TOKEN, if (token.isBlank()) null else TokenCipher.encrypt(token.trim()))
            .apply()
        _config.value = ServerConfig(cleanUrl, token.trim())
    }

    private fun load(): ServerConfig {
        val url = prefs.getString(KEY_URL, null) ?: DEFAULT_BASE_URL
        val token = prefs.getString(KEY_TOKEN, null)?.let(TokenCipher::decrypt) ?: ""
        return ServerConfig(url, token)
    }

    // --- reminder bookkeeping (which reminders we've already notified / scheduled) ---

    var notifiedReminders: Set<String>
        get() = prefs.getStringSet(KEY_NOTIFIED, emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet(KEY_NOTIFIED, v).apply() }

    var scheduledReminders: Set<String>
        get() = prefs.getStringSet(KEY_SCHEDULED, emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet(KEY_SCHEDULED, v).apply() }

    @Synchronized
    fun markNotified(key: String) {
        notifiedReminders = notifiedReminders + key
    }

    private companion object {
        const val KEY_URL = "base_url"
        const val KEY_TOKEN = "token_enc"
        const val KEY_NOTIFIED = "reminders_notified"
        const val KEY_SCHEDULED = "reminders_scheduled"
    }
}

private object TokenCipher {
    private const val ALIAS = "craffa_api_token"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

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

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Null if the key is gone (e.g. app data restored elsewhere) — user re-enters the token. */
    fun decrypt(stored: String): String? = runCatching {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    }.getOrNull()
}
