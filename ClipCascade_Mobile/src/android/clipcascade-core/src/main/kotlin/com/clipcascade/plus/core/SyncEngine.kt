package com.clipcascade.plus.core

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

sealed class SyncState {
    object Disconnected : SyncState()
    object Connecting : SyncState()
    object Authenticating : SyncState()
    object Connected : SyncState()
    data class Backoff(val retryAtMillis: Long, val reason: String) : SyncState()
    data class NoNetwork(val reason: String = "No network") : SyncState()
    data class NeedsLogin(val retryAtMillis: Long) : SyncState()
    data class Unsupported(val mode: ServerMode) : SyncState()
}

interface SyncEngineListener {
    fun onState(state: SyncState) {}

    /** A new clip from another device, already checked by the loop guard. */
    fun onRemoteClip(clip: Clip) {}

    fun onSessionChanged(session: Session?) {}

    /** Must never contain clip content. Goes to the exportable log. */
    fun log(message: String) {}
}

/**
 * The state machine behind SyncService: Disconnected, Connecting, Authenticating, Connected, Backoff.
 * It keeps trying until [stop] is called. Everything runs on one thread, so transitions never race.
 * A failure in any step becomes a retry, not a stopped engine.
 */
class SyncEngine(
    private val client: ClipCascadeClient,
    private val credentials: ClipCredentials,
    private val listener: SyncEngineListener,
    initialSession: Session? = null,
    private val guard: LoopGuard = LoopGuard(),
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "clip-sync").apply { isDaemon = true }
    }
    private var session: Session? = initialSession
    private var connection: ClipConnection? = null
    private var retry: ScheduledFuture<*>? = null
    private var running = false
    private var networkAvailable = true
    private var generation = 0

    /** The newest clip that couldn't be sent, kept for the next connection. Replaced by newer clips. */
    private var unsent: Clip? = null

    @Volatile var state: SyncState = SyncState.Disconnected
        private set

    fun start() = post {
        if (running) return@post
        running = true
        attempt()
    }

    fun stop() {
        post {
            running = false
            cancelRetry()
            closeConnection()
            setState(SyncState.Disconnected)
        }
        executor.shutdown()
    }

    /** From the "Reconnect now" action, the watchdog, or a network change. Skips any wait. */
    fun reconnectNow(reason: String) = post {
        if (!running) return@post
        listener.log("Reconnect requested: $reason")
        closeConnection()
        policy.reset()
        attempt()
    }

    /** The default network changed. The old socket may be dead, so connect on the new one at once. */
    fun onNetworkChanged(available: Boolean) = post {
        networkAvailable = available
        if (!running) return@post
        closeConnection()
        if (available) {
            policy.reset()
            attempt()
        } else {
            cancelRetry()
            setState(SyncState.NoNetwork())
        }
    }

    /**
     * The first network report after starting describes the network already in use. An open
     * connection is fine, so this only connects if there isn't one.
     */
    fun onInitialNetwork() = post {
        networkAvailable = true
        if (running && connection == null) {
            policy.reset()
            attempt()
        }
    }

    /** From the watchdog. Leaves a healthy connection, or one being made, alone. */
    fun ensureConnected() = post {
        if (running && connection?.isOpen != true) {
            listener.log("Watchdog found no connection")
            policy.reset()
            closeConnection()
            attempt()
        }
    }

    /** Called by capture providers. Dedupes, then sends now or keeps the clip for reconnection. */
    fun sendClip(clip: Clip) = post {
        if (!guard.shouldSend(clip.type, clip.content)) return@post
        transmit(clip)
    }

    /**
     * Sends even if this content was sent before, for manual sends. It still goes through the guard,
     * so the echo is recognised and the clip counts as the newest for sensitive expiry.
     */
    fun sendClipForced(clip: Clip) = post {
        guard.shouldSend(clip.type, clip.content)
        transmit(clip)
    }

    /**
     * Sensitive-clip expiry: true means [clipHash] is still the newest clip, and an empty clip has
     * been sent to overwrite it elsewhere. False means something newer already replaced it.
     */
    fun expireIfCurrent(clipHash: String, onResult: (Boolean) -> Unit) = post {
        val current = guard.currentHash() == clipHash
        if (current) {
            guard.shouldSend(ClipType.TEXT, "")
            transmit(Clip(ClipType.TEXT, ""))
        }
        onResult(current)
    }

    private fun transmit(clip: Clip) {
        val sent = runCatching { connection?.takeIf { it.isOpen }?.send(clip) == true }
            .onFailure { listener.log("Send failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        if (!sent) {
            unsent = clip
            listener.log("Clip kept for reconnection (${clip.type.wireName})")
        }
    }

    private fun attempt() {
        cancelRetry()
        if (!running || connection != null) return
        if (!networkAvailable) {
            setState(SyncState.NoNetwork())
            return
        }
        val thisGeneration = ++generation
        try {
            setState(SyncState.Authenticating)
            val authenticated = client.authenticate(credentials, session)
            if (authenticated !== session) listener.onSessionChanged(authenticated)
            session = authenticated
            setState(SyncState.Connecting)
            val opened = client.connect(authenticated, credentials, EngineListener(thisGeneration))
            connection = opened
            policy.reset()
            setState(SyncState.Connected)
            listener.log("Connected")
            unsent?.let { pending ->
                unsent = null
                transmit(pending)
            }
        } catch (e: ConnectFailure.SessionExpired) {
            listener.log("Session expired, logging in again")
            session = null
            listener.onSessionChanged(null)
            scheduleRetry(e, "Session expired")
        } catch (e: ConnectFailure.BadCredentials) {
            session = null
            val delay = policy.nextDelayMillis(e)
            listener.log("Login refused. Retrying in ${delay / 60_000} min")
            setState(SyncState.NeedsLogin(nowMillis() + delay))
            schedule(delay)
        } catch (e: ConnectFailure.UnsupportedMode) {
            listener.log("Server is in ${e.mode} mode")
            setState(SyncState.Unsupported(e.mode))
        } catch (e: Exception) {
            // Includes bugs. Whatever went wrong, the answer is to try again.
            scheduleRetry(e, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun scheduleRetry(failure: Throwable, reason: String) {
        val delay = policy.nextDelayMillis(failure)
        listener.log("Retrying in ${delay / 1000} s: $reason")
        setState(SyncState.Backoff(nowMillis() + delay, reason))
        schedule(delay)
    }

    private fun schedule(delayMillis: Long) {
        cancelRetry()
        retry = executor.schedule({ guarded { attempt() } }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun cancelRetry() {
        retry?.cancel(false)
        retry = null
    }

    private fun closeConnection() {
        connection?.close()
        connection = null
    }

    private fun setState(next: SyncState) {
        if (next == state) return
        state = next
        runCatching { listener.onState(next) }
    }

    private fun post(block: () -> Unit) {
        if (!executor.isShutdown) runCatching { executor.execute { guarded(block) } }
    }

    /** An exception escaping a scheduled task would silently end retries, so none may escape. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            runCatching { listener.log("Unexpected error: ${e.javaClass.simpleName}") }
            if (running && connection == null && retry == null) schedule(policy.nextDelayMillis(e))
        }
    }

    private inner class EngineListener(private val forGeneration: Int) : ClipListener {
        override fun onClip(clip: Clip) = post {
            if (forGeneration == generation && guard.shouldApply(clip.type, clip.content)) listener.onRemoteClip(clip)
        }

        override fun onClipUndecryptable(reason: String) {
            listener.log("Clip ignored: $reason")
        }

        override fun onDisconnected(reason: String, sessionExpired: Boolean) = post {
            if (forGeneration != generation) return@post
            connection = null
            if (sessionExpired) {
                session = null
                listener.onSessionChanged(null)
            }
            if (running) scheduleRetry(Exception(reason), reason)
        }
    }
}
