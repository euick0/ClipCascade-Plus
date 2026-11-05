package com.clipcascade.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** What the service needs to run unattended. The raw password is never stored. */
data class SyncConfig(
    val serverUrl: String,
    val username: String,
    /** SHA3-512 hex of the password, as sent to the login form. */
    val loginHash: String,
    /** Base64 AES key, or null when encryption is off. */
    val encryptionKey: String?,
    val maxLocalBytes: Long? = null,
)

/** Values encrypted with an AES key held in the Android Keystore. */
class SecureStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sync_secure", Context.MODE_PRIVATE)

    fun saveConfig(config: SyncConfig) = put(
        KEY_CONFIG,
        JSONObject().apply {
            put("serverUrl", config.serverUrl)
            put("username", config.username)
            put("loginHash", config.loginHash)
            put("encryptionKey", config.encryptionKey ?: JSONObject.NULL)
            put("maxLocalBytes", config.maxLocalBytes ?: JSONObject.NULL)
        }.toString(),
    )

    /** Null if none is saved, or it can't be read (for example, the Keystore key was lost). */
    fun loadConfig(): SyncConfig? {
        val json = JSONObject(get(KEY_CONFIG) ?: return null)
        return SyncConfig(
            serverUrl = json.getString("serverUrl"),
            username = json.getString("username"),
            loginHash = json.getString("loginHash"),
            encryptionKey = json.optString("encryptionKey").takeIf { !json.isNull("encryptionKey") && it.isNotEmpty() },
            maxLocalBytes = if (json.isNull("maxLocalBytes")) null else json.getLong("maxLocalBytes"),
        )
    }

    fun saveSessionCookies(header: String?) = if (header == null) remove(KEY_SESSION) else put(KEY_SESSION, header)

    fun loadSessionCookies(): String? = get(KEY_SESSION)

    fun clear() = prefs.edit().clear().apply()

    private fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            SyncLog.log("Secure value unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun remove(name: String) = prefs.edit().remove(name).apply()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "clipcascade_plus_sync"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_CONFIG = "config"
        const val KEY_SESSION = "session"
    }
}
