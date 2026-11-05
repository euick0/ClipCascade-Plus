package com.clipcascade.plus.core

/**
 * SHA3-512, used only for the login form's password field.
 *
 * Android's MessageDigest offers SHA-3 only on recent API levels, and the app supports older ones.
 * Tests compare this against Python's hashlib, which the desktop client uses.
 */
internal object Sha3 {
    private const val RATE_BYTES = 72 // (1600 - 2 * 512) / 8
    private const val DIGEST_BYTES = 64

    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7fffffffffff7f76L, -0x7fffffff7fff8000L,
        0x000000000000808BL, 0x0000000080000001L, -0x7fffffff7fff7f7fL, -0x7fffffffffff7ff7L,
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, -0x7fffffffffffff75L, -0x7fffffffffff7f77L, -0x7fffffffffff7ffdL,
        -0x7fffffffffff7ffeL, -0x7fffffffffffff80L, 0x000000000000800AL, -0x7fffffff7ffffff6L,
        -0x7fffffff7fff7f7fL, -0x7fffffffffff7f80L, 0x0000000080000001L, -0x7fffffff7fff7ff8L,
    )
    private val ROTATIONS = intArrayOf(
        1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44,
    )
    private val PI_LANES = intArrayOf(
        10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1,
    )

    fun digest512(input: ByteArray): ByteArray {
        val state = LongArray(25)
        var offset = 0
        while (input.size - offset >= RATE_BYTES) {
            absorb(state, input, offset)
            permute(state)
            offset += RATE_BYTES
        }
        val last = ByteArray(RATE_BYTES)
        input.copyInto(last, 0, offset, input.size)
        last[input.size - offset] = 0x06
        last[RATE_BYTES - 1] = (last[RATE_BYTES - 1].toInt() or 0x80).toByte()
        absorb(state, last, 0)
        permute(state)
        return ByteArray(DIGEST_BYTES) { i -> (state[i / 8] ushr (8 * (i % 8))).toByte() }
    }

    fun hex512(input: String): String =
        digest512(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun absorb(state: LongArray, block: ByteArray, offset: Int) {
        for (lane in 0 until RATE_BYTES / 8) {
            var value = 0L
            for (b in 7 downTo 0) {
                value = (value shl 8) or (block[offset + lane * 8 + b].toLong() and 0xFF)
            }
            state[lane] = state[lane] xor value
        }
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        for (round in 0 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 25 step 5) a[y + x] = a[y + x] xor d
            }
            var last = a[1]
            for (i in 0 until 24) {
                val lane = PI_LANES[i]
                val saved = a[lane]
                a[lane] = java.lang.Long.rotateLeft(last, ROTATIONS[i])
                last = saved
            }
            for (y in 0 until 25 step 5) {
                for (x in 0 until 5) c[x] = a[y + x]
                for (x in 0 until 5) a[y + x] = c[x] xor (c[(x + 1) % 5].inv() and c[(x + 2) % 5])
            }
            a[0] = a[0] xor ROUND_CONSTANTS[round]
        }
    }
}
