package com.clipcascade.plus.core

import java.security.MessageDigest

/**
 * Stops clips bouncing between the phone and the server, which echoes every clip to all of a
 * user's sessions, the sender included.
 *
 * Comparing against the last clip alone is not enough. If X then Y are sent quickly, X's echo
 * arrives while Y is current and would look new. It would overwrite the clipboard with old content
 * and could be sent again, so echoes of recent sends are tracked separately.
 *
 * Only hashes are held, never clip content.
 */
class LoopGuard(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val echoWindowMillis: Long = 60_000,
) {
    private var current: String? = null
    private val awaitingEcho = ArrayDeque<Pair<String, Long>>()

    /** The local clipboard changed. True if this clip should be sent. */
    @Synchronized
    fun shouldSend(type: ClipType, content: String): Boolean {
        val hash = hash(type, content)
        if (hash == current) return false
        current = hash
        expireEchoes()
        awaitingEcho.addLast(hash to nowMillis())
        return true
    }

    /** A clip arrived from the server. True if it is new and should be applied to the clipboard. */
    @Synchronized
    fun shouldApply(type: ClipType, content: String): Boolean {
        val hash = hash(type, content)
        expireEchoes()
        val echo = awaitingEcho.indexOfFirst { it.first == hash }
        if (echo >= 0) {
            awaitingEcho.removeAt(echo)
            return false
        }
        if (hash == current) return false
        current = hash
        return true
    }

    /** The hash of the newest clip seen in either direction, used to check a clip is still current. */
    @Synchronized
    fun currentHash(): String? = current

    private fun expireEchoes() {
        val cutoff = nowMillis() - echoWindowMillis
        while (awaitingEcho.isNotEmpty() && awaitingEcho.first().second < cutoff) awaitingEcho.removeFirst()
    }

    companion object {
        fun hash(type: ClipType, content: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(type.wireName.toByteArray(Charsets.UTF_8))
            digest.update(0)
            return digest.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
