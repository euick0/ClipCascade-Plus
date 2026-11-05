package com.clipcascade.plus.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fixtures come from compat/generate_fixtures.py, which runs the desktop client's own code. */
class CipherCompatTest {
    private val fixtures = Json.parseToJsonElement(
        javaClass.getResource("/desktop-fixtures.json")!!.readText(),
    ).jsonObject

    private fun JsonObject.s(name: String) = getValue(name).jsonPrimitive.content
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun users() = fixtures.getValue("users").jsonArray.map { it.jsonObject }
    private fun keyOf(user: JsonObject) = user.s("key_hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `SHA3-512 matches hashlib across block boundaries`() {
        for (case in fixtures.getValue("sha3").jsonArray.map { it.jsonObject }) {
            assertEquals(case.s("sha3_512"), ClipCipher.loginHash(case.s("input")))
        }
    }

    @Test fun `login hash matches desktop`() {
        for (user in users()) assertEquals(user.s("login_hash"), ClipCipher.loginHash(user.s("password")))
    }

    @Test fun `derived key matches desktop PBKDF2`() {
        for (user in users()) {
            val key = ClipCipher.deriveKey(user.s("username"), user.s("password"), user.s("salt"), user.getValue("rounds").jsonPrimitive.int)
            assertEquals(user.s("key_hex"), key.hex(), "user ${user.s("username")}")
        }
    }

    @Test fun `decrypts every clip encrypted by desktop code and in Android layout`() {
        var count = 0
        for (user in users()) for (clip in user.getValue("clips").jsonArray.map { it.jsonObject }) {
            assertEquals(clip.s("plaintext"), ClipCipher.decrypt(keyOf(user), clip.s("payload")), clip.s("name") + "/" + clip.s("origin"))
            count++
        }
        assertEquals(30, count)
    }

    @Test fun `encrypted payload has the wire layout`() {
        val key = keyOf(users().first())
        val json = Json.parseToJsonElement(ClipCipher.encrypt(key, "x")).jsonObject
        assertEquals(setOf("nonce", "ciphertext", "tag"), json.keys)
        assertEquals(12, Base64.getDecoder().decode(json.s("nonce")).size)
        assertEquals(16, Base64.getDecoder().decode(json.s("tag")).size)
        assertEquals(1, Base64.getDecoder().decode(json.s("ciphertext")).size)
    }

    @Test fun `nonces are not reused`() {
        val key = keyOf(users().first())
        val nonces = (1..1000).map { Json.parseToJsonElement(ClipCipher.encrypt(key, "same")).jsonObject.s("nonce") }
        assertEquals(1000, nonces.toSet().size)
    }

    @Test fun `tampered clip and wrong key are rejected without leaking content`() {
        val key = keyOf(users().first())
        val sealed = ClipCipher.encrypt(key, "secret-text")
        val json = Json.parseToJsonElement(sealed).jsonObject
        val cipher = Base64.getDecoder().decode(json.s("ciphertext")).also { it[0] = (it[0] + 1).toByte() }
        val tampered = buildJsonObject {
            put("nonce", json.s("nonce")); put("tag", json.s("tag"))
            put("ciphertext", Base64.getEncoder().encodeToString(cipher))
        }.toString()
        assertFailsWith<DecryptionException> { ClipCipher.decrypt(key, tampered) }
        val wrong = assertFailsWith<DecryptionException> { ClipCipher.decrypt(ByteArray(32), sealed) }
        assertTrue("secret" !in wrong.message!!)
        assertFailsWith<DecryptionException> { ClipCipher.decrypt(key, "not json") }
    }

    /** Written out so compat/verify_kotlin_fixtures.py can decrypt it with the desktop code. */
    @Test fun `writes clips for the desktop code to verify`() {
        val out = System.getenv("CC_KOTLIN_FIXTURE_OUT") ?: return
        val data = buildJsonArray {
            for (user in users()) add(buildJsonObject {
                put("username", user.s("username")); put("password", user.s("password"))
                put("salt", user.s("salt")); put("rounds", user.getValue("rounds").jsonPrimitive.int)
                put("clips", buildJsonArray {
                    for (clip in user.getValue("clips").jsonArray.map { it.jsonObject }.filter { it.s("origin").startsWith("desktop") }) {
                        add(buildJsonObject {
                            put("name", clip.s("name")); put("plaintext", clip.s("plaintext"))
                            put("payload", ClipCipher.encrypt(keyOf(user), clip.s("plaintext")))
                        })
                    }
                })
            })
        }
        File(out).writeText(data.toString())
    }
}
