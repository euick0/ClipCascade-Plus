package com.clipcascade.plus.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DecryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Must stay byte-compatible with ClipCascade_Desktop/src/utils/cipher_manager.py.
 * compat/generate_fixtures.py and compat/verify_kotlin_fixtures.py run that exact file against this.
 */
object ClipCipher {
    const val DEFAULT_HASH_ROUNDS = 664937
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8

    // The official Android client sends 12 bytes and the desktop client 16. Decryption accepts any length.
    private const val NONCE_BYTES = 12
    private val random = SecureRandom()

    /** What the login form's password field carries: lowercase hex SHA3-512 of the raw password. */
    fun loginHash(rawPassword: String): String = Sha3.hex512(rawPassword)

    /** PBKDF2-HMAC-SHA256 over explicit UTF-8, so non-ASCII passwords match Python's str.encode(). */
    fun deriveKey(username: String, rawPassword: String, salt: String, rounds: Int = DEFAULT_HASH_ROUNDS): ByteArray {
        require(rounds > 0) { "rounds must be positive" }
        val mac = Mac.getInstance("HmacSHA256")
        // An empty HMAC key is valid for PBKDF2, but SecretKeySpec refuses a zero-length array.
        val passwordBytes = rawPassword.toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(if (passwordBytes.isEmpty()) ByteArray(1) else passwordBytes, "HmacSHA256"))
        mac.update((username + rawPassword + salt).toByteArray(Charsets.UTF_8))
        mac.update(byteArrayOf(0, 0, 0, 1)) // Block 1. SHA-256 output already covers all 32 key bytes.
        var u = mac.doFinal()
        val out = u.copyOf()
        repeat(rounds - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out.copyOf(KEY_BYTES)
    }

    fun encrypt(key: ByteArray, plaintext: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return buildJsonObject {
            put("nonce", b64.encodeToString(nonce))
            put("ciphertext", b64.encodeToString(sealed.copyOfRange(0, sealed.size - TAG_BYTES)))
            put("tag", b64.encodeToString(sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)))
        }.toString()
    }

    fun decrypt(key: ByteArray, payload: String): String {
        try {
            val json = Json.parseToJsonElement(payload).jsonObject
            val b64 = Base64.getDecoder()
            val nonce = b64.decode(json.getValue("nonce").jsonPrimitive.content)
            val ciphertext = b64.decode(json.getValue("ciphertext").jsonPrimitive.content)
            val tag = b64.decode(json.getValue("tag").jsonPrimitive.content)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            return String(cipher.doFinal(ciphertext + tag), Charsets.UTF_8)
        } catch (e: Exception) {
            // Never include the payload here: this message can reach the exportable log.
            throw DecryptionException("Could not decrypt clip (${e.javaClass.simpleName})", e)
        }
    }
}
