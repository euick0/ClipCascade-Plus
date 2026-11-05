package com.clipcascade.plus.core

import kotlin.random.Random

/** How long to wait before the next connection attempt. Holds no timers, so it's easy to test. */
class ReconnectPolicy(
    private val random: Random = Random.Default,
    private val initialMillis: Long = 1_000,
    private val capMillis: Long = 60_000,
    /** Wrong credentials won't fix themselves, and retrying quickly triggers the server's lockout. */
    private val badCredentialsMillis: Long = 30 * 60_000,
) {
    private var attempts = 0

    /** Full-range jitter stops many clients reconnecting in step after a server restart. */
    fun nextDelayMillis(failure: Throwable?): Long {
        if (failure is ConnectFailure.BadCredentials) return badCredentialsMillis
        val ceiling = minOf(capMillis, initialMillis shl minOf(attempts, 16))
        attempts++
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }

    fun reset() {
        attempts = 0
    }
}
