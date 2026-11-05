package com.clipcascade.plus.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopGuardTest {
    private var now = 0L
    private val guard = LoopGuard(nowMillis = { now })
    private val T = ClipType.TEXT

    @Test fun `own echo is dropped`() {
        assertTrue(guard.shouldSend(T, "x"))
        assertFalse(guard.shouldApply(T, "x"))
    }

    @Test fun `same local content is not sent twice`() {
        assertTrue(guard.shouldSend(T, "x"))
        assertFalse(guard.shouldSend(T, "x"))
    }

    @Test fun `received content is never sent back`() {
        assertTrue(guard.shouldApply(T, "from pc"))
        assertFalse(guard.shouldSend(T, "from pc"))
    }

    @Test fun `late echo of an older send does not overwrite a newer clip`() {
        assertTrue(guard.shouldSend(T, "x"))
        assertTrue(guard.shouldSend(T, "y"))
        assertFalse(guard.shouldApply(T, "x"))
        assertFalse(guard.shouldApply(T, "y"))
        assertFalse(guard.shouldSend(T, "y"))
    }

    @Test fun `content can come back after something else`() {
        assertTrue(guard.shouldSend(T, "x"))
        assertFalse(guard.shouldApply(T, "x"))
        assertTrue(guard.shouldApply(T, "y"))
        assertTrue(guard.shouldSend(T, "x"))
    }

    @Test fun `a clip from elsewhere matching an expired echo is applied`() {
        assertTrue(guard.shouldSend(T, "x"))
        assertTrue(guard.shouldApply(T, "y"))
        now += 61_000
        assertTrue(guard.shouldApply(T, "x"))
    }

    @Test fun `type is part of identity`() {
        assertTrue(guard.shouldApply(ClipType.TEXT, "abc"))
        assertTrue(guard.shouldApply(ClipType.IMAGE, "abc"))
    }
}
