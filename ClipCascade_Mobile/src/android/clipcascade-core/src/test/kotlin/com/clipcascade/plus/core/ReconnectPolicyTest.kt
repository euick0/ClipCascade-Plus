package com.clipcascade.plus.core

import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconnectPolicyTest {
    @Test fun `delays grow, stay jittered, and are capped at 60 s`() {
        val policy = ReconnectPolicy(Random(42))
        val delays = (1..40).map { policy.nextDelayMillis(IOException()) }
        assertTrue(delays.first() in 500..1_000)
        assertTrue(delays.all { it in 500..60_000 })
        assertTrue(delays.takeLast(10).all { it >= 30_000 })
        assertTrue(delays.takeLast(10).toSet().size > 1, "jitter expected")
    }

    @Test fun `reset starts again from the first delay`() {
        val policy = ReconnectPolicy(Random(1))
        repeat(20) { policy.nextDelayMillis(IOException()) }
        policy.reset()
        assertTrue(policy.nextDelayMillis(IOException()) <= 1_000)
    }

    @Test fun `bad credentials wait 30 minutes and never hit the fast path`() {
        val policy = ReconnectPolicy(Random(1))
        repeat(3) { assertEquals(1_800_000, policy.nextDelayMillis(ConnectFailure.BadCredentials())) }
    }
}
