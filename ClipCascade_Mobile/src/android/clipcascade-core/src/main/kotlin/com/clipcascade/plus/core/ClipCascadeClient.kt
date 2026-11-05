package com.clipcascade.plus.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ClipCredentials(
    val username: String,
    /** Lowercase hex SHA3-512 of the password. The raw password is never needed after setup. */
    val loginHash: String,
    /** The AES key from [ClipCipher.deriveKey], or null when encryption is off on all devices. */
    val encryptionKey: ByteArray?,
)

data class Clip(val type: ClipType, val content: String)

interface ClipListener {
    fun onClip(clip: Clip)

    /** Encryption settings differ between devices, or a clip was corrupted. The connection stays up. */
    fun onClipUndecryptable(reason: String) {}

    /** Called once per connection, unless [ClipConnection.close] ended it. */
    fun onDisconnected(reason: String, sessionExpired: Boolean)
}

/**
 * One connection attempt and its lifetime: no retries or policy, which belong to SyncService.
 * Each reconnect builds a new [ClipConnection].
 */
class ClipCascadeClient(baseUrl: String, private val okHttp: OkHttpClient, private val config: Config = Config()) {
    data class Config(
        val connectTimeoutMillis: Long = 20_000,
        /** The server beats every 20 s, so silence longer than this means the connection is dead. */
        val readDeadlineMillis: Long = 30_000,
        val deadlineCheckMillis: Long = 5_000,
    )

    val http = ClipCascadeHttp(baseUrl, okHttp)

    /** Logs in, or reuses [existing] if it's still valid. Returns the session to keep for next time. */
    fun authenticate(credentials: ClipCredentials, existing: Session?): Session {
        if (existing != null && http.isValid(existing)) return existing
        return http.login(credentials.username, credentials.loginHash)
    }

    /** Opens the socket and returns once subscribed. Throws [ConnectFailure] otherwise. */
    fun connect(session: Session, credentials: ClipCredentials, listener: ClipListener): ClipConnection {
        val info = http.serverInfo(session)
        if (info.mode != ServerMode.P2S) throw ConnectFailure.UnsupportedMode(info.mode)
        val connection = ClipConnection(http.baseUrl, okHttp, session, credentials, info, listener, config)
        connection.open()
        return connection
    }
}

class ClipConnection internal constructor(
    private val baseUrl: String,
    okHttp: OkHttpClient,
    private val session: Session,
    private val credentials: ClipCredentials,
    val serverInfo: ServerInfo,
    private val listener: ClipListener,
    private val config: ClipCascadeClient.Config,
) {
    private val socketClient = okHttp.newBuilder().pingInterval(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val ended = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private var openFailure: ConnectFailure? = null
    private var socket: WebSocket? = null
    private var deadlineChecker: ScheduledExecutorService? = null

    @Volatile private var lastInboundNanos = System.nanoTime()

    internal fun open() {
        val wsUrl = baseUrl.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/clipsocket"
        val request = Request.Builder().url(wsUrl).header("Cookie", session.cookieHeader).build()
        socket = socketClient.newWebSocket(request, Listener())
        val finished = ready.await(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
        openFailure?.let { end(notify = false); throw it }
        if (!finished) {
            end(notify = false)
            throw ConnectFailure.Network("Timed out connecting")
        }
        lastInboundNanos = System.nanoTime()
        deadlineChecker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "clip-deadline").apply { isDaemon = true }
        }.also { checker ->
            checker.scheduleWithFixedDelay(::checkDeadline, config.deadlineCheckMillis, config.deadlineCheckMillis, TimeUnit.MILLISECONDS)
        }
    }

    /** Queues a clip. False means it wasn't sent, so the caller should keep it for the next connection. */
    fun send(clip: Clip): Boolean {
        if (ended.get()) return false
        val payload = credentials.encryptionKey?.let { ClipCipher.encrypt(it, clip.content) } ?: clip.content
        val body = buildJsonObject {
            put("payload", payload)
            put("type", clip.type.wireName)
        }.toString()
        val frame = StompFrame("SEND", linkedMapOf("destination" to "/app/cliptext", "content-type" to "application/json"), body)
        return socket?.send(frame.marshal()) == true
    }

    /** Ends the connection without reporting it to the listener. */
    fun close() {
        end(notify = false)
    }

    /** Called by SyncService when it has reason to think the connection is dead, such as a network change. */
    fun abort(reason: String) {
        end(notify = true, reason = reason)
    }

    val isOpen: Boolean get() = !ended.get()

    private fun checkDeadline() {
        val silentMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastInboundNanos)
        if (silentMillis > config.readDeadlineMillis) end(notify = true, reason = "No heartbeat for ${silentMillis / 1000} s")
    }

    private fun end(notify: Boolean, reason: String = "Closed", sessionExpired: Boolean = false) {
        if (!ended.compareAndSet(false, true)) return
        deadlineChecker?.shutdownNow()
        // cancel(), not close(): a close handshake can hang on a half-dead connection.
        socket?.cancel()
        if (notify) listener.onDisconnected(reason, sessionExpired)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val connect = StompFrame(
                "CONNECT",
                linkedMapOf(
                    "accept-version" to "1.2,1.1,1.0",
                    "host" to (response.request.url.host),
                    // Keep this exactly: the server beats every 20 s and expects none from clients.
                    "heart-beat" to "0,20000",
                ),
            )
            webSocket.send(connect.marshal())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text.toByteArray(Charsets.UTF_8))

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.toByteArray())

        private fun handle(raw: ByteArray) {
            lastInboundNanos = System.nanoTime()
            val frame = StompFrame.parse(raw) ?: return
            when (frame.command) {
                "CONNECTED" -> {
                    socket(frameSubscribe())
                    ready.countDown()
                }
                "MESSAGE" -> deliver(frame.body)
                "ERROR" -> fail("Server error: ${frame.headers["message"] ?: "STOMP ERROR"}", sessionExpired = false)
            }
        }

        private fun frameSubscribe() = StompFrame(
            "SUBSCRIBE",
            linkedMapOf("id" to "sub-0", "destination" to "/user/queue/cliptext", "ack" to "auto"),
        )

        private fun socket(frame: StompFrame) {
            socket?.send(frame.marshal())
        }

        private fun deliver(body: String) {
            // One bad clip must not end the connection.
            val clip = try {
                val json = Json.parseToJsonElement(body).jsonObject
                val type = ClipType.fromWire(json["type"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content)
                    ?: return listener.onClipUndecryptable("Unknown clip type")
                val payload = json.getValue("payload").jsonPrimitive.content
                val key = credentials.encryptionKey
                Clip(type, if (key != null) ClipCipher.decrypt(key, payload) else payload)
            } catch (e: DecryptionException) {
                return listener.onClipUndecryptable(e.message ?: "Could not decrypt clip")
            } catch (e: Exception) {
                return listener.onClipUndecryptable("Malformed clip (${e.javaClass.simpleName})")
            }
            try {
                listener.onClip(clip)
            } catch (e: Exception) {
                listener.onClipUndecryptable("Clip handler failed (${e.javaClass.simpleName})")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val expired = response?.let { it.isRedirect && ClipCascadeHttp.isLoginRedirect(it) || it.code == 401 || it.code == 403 } == true
            fail(if (expired) "Session expired" else (t.message ?: t.javaClass.simpleName), expired, t)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Code 1008 "Session revoked" is how the server ends a logged-out session.
            fail("Server closed the connection ($code)", sessionExpired = code == 1008)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail("Connection closed ($code)", sessionExpired = code == 1008)
        }

        private fun fail(reason: String, sessionExpired: Boolean, cause: Throwable? = null) {
            if (ready.count > 0) {
                openFailure = if (sessionExpired) ConnectFailure.SessionExpired() else ConnectFailure.Network(reason, cause)
                ready.countDown()
                return
            }
            end(notify = true, reason = reason, sessionExpired = sessionExpired)
        }
    }
}
