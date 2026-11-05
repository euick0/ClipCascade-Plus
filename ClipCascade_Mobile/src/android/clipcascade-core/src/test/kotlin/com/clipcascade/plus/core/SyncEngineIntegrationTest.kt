package com.clipcascade.plus.core

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineIntegrationTest {
    private val url: String? = System.getenv("CC_TEST_SERVER_URL")
    private val username = System.getenv("CC_TEST_USERNAME") ?: "admin"
    private val password = System.getenv("CC_TEST_PASSWORD") ?: "admin123"
    private val http = OkHttpClient()
    private val creds by lazy { ClipCredentials(username, ClipCipher.loginHash(password), ClipCipher.deriveKey(username, password, "")) }

    private class Events : SyncEngineListener {
        val states = LinkedBlockingQueue<SyncState>()
        val clips = LinkedBlockingQueue<Clip>()
        @Volatile var session: Session? = null
        override fun onState(state: SyncState) { states.add(state) }
        override fun onRemoteClip(clip: Clip) { clips.add(clip) }
        override fun onSessionChanged(session: Session?) { this.session = session }
        override fun log(message: String) { println("engine: $message") }

        fun await(seconds: Long = 60, match: (SyncState) -> Boolean): SyncState {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                val next = states.poll(1, TimeUnit.SECONDS) ?: continue
                if (match(next)) return next
            }
            throw AssertionError("State not reached in $seconds s")
        }
    }

    private fun engine(events: Events, base: String = url!!, session: Session? = null, creds: ClipCredentials = this.creds) =
        SyncEngine(ClipCascadeClient(base, http), creds, events, session)

    @BeforeEach fun requireServer() = assumeTrue(url != null, "CC_TEST_SERVER_URL not set")

    @Test fun `connects, sends between engines, and filters echoes`() {
        val a = Events(); val b = Events()
        val ea = engine(a); val eb = engine(b)
        ea.start(); eb.start()
        try {
            a.await { it is SyncState.Connected }; b.await { it is SyncState.Connected }
            ea.sendClip(Clip(ClipType.TEXT, "one"))
            assertEquals("one", b.clips.poll(15, TimeUnit.SECONDS)?.content)
            assertNull(a.clips.poll(3, TimeUnit.SECONDS), "sender must not apply its own echo")
            // b received this, so b must not send it back.
            eb.sendClip(Clip(ClipType.TEXT, "one"))
            assertNull(a.clips.poll(3, TimeUnit.SECONDS))
        } finally { ea.stop(); eb.stop() }
    }

    @Test fun `an expired saved session logs in again without help`() {
        val events = Events()
        val dead = Session(mapOf("JSESSIONID" to "0123456789ABCDEF0123456789ABCDEF"))
        val e = engine(events, session = dead)
        e.start()
        try {
            events.await { it is SyncState.Connected }
            assertTrue(assertNotNull(events.session).cookieHeader != dead.cookieHeader)
        } finally { e.stop() }
    }

    @Test fun `recovers after the connection dies, and delivers the clip queued meanwhile`() {
        val observer = Events(); val obs = engine(observer); obs.start()
        ForwardingProxy(url!!).use { proxy ->
            val events = Events()
            val e = engine(events, proxy.url)
            e.start()
            try {
                observer.await { it is SyncState.Connected }
                events.await { it is SyncState.Connected }
                proxy.goDown()
                events.await { it is SyncState.Backoff }
                e.sendClip(Clip(ClipType.TEXT, "copied while offline"))
                proxy.comeBack()
                events.await(90) { it is SyncState.Connected }
                assertEquals("copied while offline", observer.clips.poll(15, TimeUnit.SECONDS)?.content)
            } finally { e.stop(); obs.stop() }
        }
    }

    @Test fun `network loss waits, and network return connects at once`() {
        val events = Events(); val e = engine(events)
        e.start()
        try {
            events.await { it is SyncState.Connected }
            e.onNetworkChanged(false)
            events.await(5) { it is SyncState.NoNetwork }
            e.onNetworkChanged(true)
            events.await(15) { it is SyncState.Connected }
        } finally { e.stop() }
    }

    @Test fun `bad credentials wait for a long time instead of hammering the login`() {
        val events = Events()
        val wrong = creds.copy(loginHash = ClipCipher.loginHash("wrong-password"))
        val e = engine(events, creds = wrong)
        e.start()
        try {
            val state = assertIs<SyncState.NeedsLogin>(events.await { it is SyncState.NeedsLogin })
            assertTrue(state.retryAtMillis - System.currentTimeMillis() > 25 * 60_000)
        } finally { e.stop() }
    }

    @Test fun `sensitive clip expiry clears it elsewhere only while it is the newest`() {
        val a = Events(); val b = Events()
        val ea = engine(a); val eb = engine(b)
        ea.start(); eb.start()
        try {
            a.await { it is SyncState.Connected }; b.await { it is SyncState.Connected }
            ea.sendClip(Clip(ClipType.TEXT, "hunter2"))
            assertEquals("hunter2", b.clips.poll(15, TimeUnit.SECONDS)?.content)
            val results = LinkedBlockingQueue<Boolean>()
            ea.expireIfCurrent(LoopGuard.hash(ClipType.TEXT, "hunter2")) { results.add(it) }
            assertEquals(true, results.poll(5, TimeUnit.SECONDS))
            assertEquals("", b.clips.poll(15, TimeUnit.SECONDS)?.content)

            ea.sendClip(Clip(ClipType.TEXT, "hunter3"))
            assertEquals("hunter3", b.clips.poll(15, TimeUnit.SECONDS)?.content)
            eb.sendClip(Clip(ClipType.TEXT, "copied later on the PC"))
            assertEquals("copied later on the PC", a.clips.poll(15, TimeUnit.SECONDS)?.content)
            ea.expireIfCurrent(LoopGuard.hash(ClipType.TEXT, "hunter3")) { results.add(it) }
            assertEquals(false, results.poll(5, TimeUnit.SECONDS))
            assertNull(b.clips.poll(3, TimeUnit.SECONDS), "a newer clip must not be overwritten")
        } finally { ea.stop(); eb.stop() }
    }
}

/** Forwards TCP. goDown() kills connections and refuses new ones, like a server restart. */
private class ForwardingProxy(target: String) : AutoCloseable {
    private val targetUri = java.net.URI(target)
    private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
    @Volatile private var down = false
    val url = "http://127.0.0.1:${server.localPort}"

    init {
        thread {
            while (!server.isClosed) {
                val inbound = runCatching { server.accept() }.getOrNull() ?: break
                if (down) { inbound.close(); continue }
                val outbound = java.net.Socket(targetUri.host, targetUri.port)
                sockets += inbound; sockets += outbound
                pump(inbound, outbound); pump(outbound, inbound)
            }
        }
    }

    private fun pump(from: java.net.Socket, to: java.net.Socket) = thread {
        val buffer = ByteArray(16_384)
        runCatching {
            while (true) {
                val n = from.getInputStream().read(buffer)
                if (n < 0) { to.shutdownOutput(); break }
                to.getOutputStream().write(buffer, 0, n); to.getOutputStream().flush()
            }
        }
    }

    private fun thread(body: () -> Unit) = Thread(body).apply { isDaemon = true; start() }

    fun goDown() { down = true; sockets.forEach { runCatching { it.close() } }; sockets.clear() }
    fun comeBack() { down = false }
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } } }
}
