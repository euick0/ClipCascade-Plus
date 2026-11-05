package com.clipcascade.plus.core

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs against the official server image. Set CC_TEST_SERVER_URL, CC_TEST_USERNAME and
 * CC_TEST_PASSWORD. Set CC_DESKTOP_PEER_CMD to include the desktop-code interop tests.
 */
class ServerIntegrationTest {
    private val url = System.getenv("CC_TEST_SERVER_URL")
    private val username = System.getenv("CC_TEST_USERNAME") ?: "admin"
    private val password = System.getenv("CC_TEST_PASSWORD") ?: "admin123"
    private val http = OkHttpClient()
    private val key by lazy { ClipCipher.deriveKey(username, password, "") }
    private val creds by lazy { ClipCredentials(username, ClipCipher.loginHash(password), key) }

    private inner class Collector : ClipListener {
        val clips = LinkedBlockingQueue<Clip>()
        val bad = LinkedBlockingQueue<String>()
        val disconnects = LinkedBlockingQueue<Pair<String, Boolean>>()
        override fun onClip(clip: Clip) { clips.add(clip) }
        override fun onClipUndecryptable(reason: String) { bad.add(reason) }
        override fun onDisconnected(reason: String, sessionExpired: Boolean) { disconnects.add(reason to sessionExpired) }
    }

    private fun client() = ClipCascadeClient(url, http)
    private fun open(listener: ClipListener, credentials: ClipCredentials = creds): ClipConnection {
        val c = client()
        return c.connect(c.authenticate(credentials, null), credentials, listener)
    }

    @BeforeEach fun requireServer() = assumeTrue(url != null, "CC_TEST_SERVER_URL not set")

    @Test fun `login discovery and session reuse`() {
        val c = client()
        val session = c.authenticate(creds, null)
        assertTrue(c.http.isValid(session))
        val info = c.http.serverInfo(session)
        assertEquals(ServerMode.P2S, info.mode)
        assertNotNull(info.maxSizeBytes)
        assertEquals(session.cookieHeader, c.authenticate(creds, session).cookieHeader)
    }

    @Test fun `bad credentials are distinguished from network errors`() {
        assertFailsWith<ConnectFailure.BadCredentials> { client().http.login(username, ClipCipher.loginHash("wrong-password")) }
    }

    @Test fun `unreachable server is a network failure`() {
        assertFailsWith<ConnectFailure.Network> { ClipCascadeClient("http://127.0.0.1:9", http).http.login("a", "b") }
    }

    @Test fun `dead session is detected, not parsed as HTML`() {
        val c = client()
        val dead = Session(mapOf("JSESSIONID" to "0123456789ABCDEF0123456789ABCDEF"))
        assertFalse(c.http.isValid(dead))
        assertFailsWith<ConnectFailure.SessionExpired> { c.http.serverInfo(dead) }
        // A session object that was never valid must log in again, not be reused.
        assertNotEquals(dead.cookieHeader, c.authenticate(creds, dead).cookieHeader)
    }

    @Test fun `clips travel between two sessions and echo to the sender`() {
        val a = Collector(); val b = Collector()
        val ca = open(a); val cb = open(b)
        try {
            for (text in listOf("hello", "pässwörd 日本語 🎉", "multi\nline\n\n\"quoted\"", "", "x".repeat(200_000))) {
                val clip = Clip(ClipType.TEXT, text)
                assertTrue(ca.send(clip))
                assertEquals(clip, b.clips.poll(15, TimeUnit.SECONDS), "other session, length ${text.length}")
                assertEquals(clip, a.clips.poll(15, TimeUnit.SECONDS), "echo, length ${text.length}")
            }
            for (type in listOf(ClipType.IMAGE, ClipType.FILES)) {
                val clip = Clip(type, "aGVsbG8=")
                assertTrue(ca.send(clip))
                assertEquals(clip, b.clips.poll(15, TimeUnit.SECONDS))
                a.clips.poll(15, TimeUnit.SECONDS)
            }
            assertNull(a.bad.poll()); assertNull(b.bad.poll())
        } finally { ca.close(); cb.close() }
    }

    @Test fun `wrong key reports the clip and keeps the connection`() {
        val a = Collector(); val b = Collector()
        val sender = open(a); val receiver = open(b, creds.copy(encryptionKey = ByteArray(32)))
        try {
            assertTrue(sender.send(Clip(ClipType.TEXT, "encrypted with the real key")))
            assertNotNull(b.bad.poll(15, TimeUnit.SECONDS))
            assertTrue(receiver.isOpen)
            assertNull(b.disconnects.poll())
        } finally { sender.close(); receiver.close() }
    }

    @Test fun `server heartbeats keep the read deadline satisfied`() {
        val a = Collector()
        val config = ClipCascadeClient.Config(readDeadlineMillis = 30_000, deadlineCheckMillis = 1_000)
        val c = ClipCascadeClient(url, http, config)
        val connection = c.connect(c.authenticate(creds, null), creds, a)
        try {
            assertNull(a.disconnects.poll(35, TimeUnit.SECONDS), "connection should stay up past the deadline")
            assertTrue(connection.isOpen)
        } finally { connection.close() }
    }

    @Test fun `silence past the read deadline is reported as a dead connection`() {
        val a = Collector()
        // Shorter than the server's 20 s beat, standing in for a connection that has gone silent.
        val config = ClipCascadeClient.Config(readDeadlineMillis = 2_000, deadlineCheckMillis = 250)
        val c = ClipCascadeClient(url, http, config)
        val connection = c.connect(c.authenticate(creds, null), creds, a)
        val (reason, expired) = assertNotNull(a.disconnects.poll(10, TimeUnit.SECONDS))
        assertTrue("heartbeat" in reason, reason)
        assertFalse(expired)
        assertFalse(connection.isOpen)
    }

    private fun peer(mode: String, text: String): Process? {
        val cmd = System.getenv("CC_DESKTOP_PEER_CMD") ?: return null
        return ProcessBuilder(cmd.split(" ") + listOf(url, username, password, mode, text))
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    }

    @Test fun `desktop client code to Kotlin through the real server`() {
        assumeTrue(System.getenv("CC_DESKTOP_PEER_CMD") != null, "CC_DESKTOP_PEER_CMD not set")
        val a = Collector(); val c = open(a)
        try {
            val text = "from desktop code pässwörd 🎉"
            assertEquals(0, peer("send", text)!!.apply { waitFor(60, TimeUnit.SECONDS) }.exitValue())
            assertEquals(Clip(ClipType.TEXT, text), a.clips.poll(15, TimeUnit.SECONDS))
        } finally { c.close() }
    }

    @Test fun `Kotlin to desktop client code through the real server`() {
        assumeTrue(System.getenv("CC_DESKTOP_PEER_CMD") != null, "CC_DESKTOP_PEER_CMD not set")
        val a = Collector(); val c = open(a)
        try {
            val text = "from kotlin pässwörd 🎉"
            val p = peer("recv", text)!!
            assertEquals("READY", p.inputStream.bufferedReader().readLine())
            assertTrue(c.send(Clip(ClipType.TEXT, text)))
            assertTrue(p.waitFor(40, TimeUnit.SECONDS), "peer timed out")
            assertEquals(0, p.exitValue())
        } finally { c.close() }
    }

    @Test fun `empty clip reaches the desktop code, which is how sensitive clips expire`() {
        assumeTrue(System.getenv("CC_DESKTOP_PEER_CMD") != null, "CC_DESKTOP_PEER_CMD not set")
        val a = Collector(); val c = open(a)
        try {
            val p = peer("recv", "")!!
            assertEquals("READY", p.inputStream.bufferedReader().readLine())
            assertTrue(c.send(Clip(ClipType.TEXT, "")))
            assertTrue(p.waitFor(40, TimeUnit.SECONDS), "peer timed out")
            assertEquals(0, p.exitValue())
        } finally { c.close() }
    }
}
