package dev.phonerobot.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device token only. Provider credentials belong on the backend. Backups are disabled. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val alias = "phone-robot-device-token"
    private fun key(forceRecreate: Boolean = false): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (forceRecreate) {
            runCatching { store.deleteEntry(alias) }
        } else {
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): CloudSettings {
        val endpoint = prefs.getString("endpoint", "") ?: ""
        val enabled = prefs.getBoolean("enabled", false)
        val saved = prefs.getString("token", null) ?: return CloudSettings(endpoint, "", enabled)
        val token = runCatching {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            require(bytes.size > 12) { "Stored token is damaged" }
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
        }.getOrElse {
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                store.deleteEntry(alias)
            }
            prefs.edit().remove("token").putBoolean("enabled", false).apply()
            ""
        }
        return CloudSettings(endpoint, token, if (token.isEmpty()) false else enabled)
    }
    fun save(settings: CloudSettings) {
        val cipher = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        }.recoverCatching {
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(forceRecreate = true)) }
        }.getOrThrow()
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(settings.token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("endpoint", settings.endpoint).putString("token", encoded)
            .putBoolean("enabled", settings.enabled).commit()) { "Could not save settings." }
    }
}
