package com.clipcascade.plus.core

/** Minimal STOMP framing, matching what ClipCascade_Desktop/src/stomp_ws/frame.py accepts and sends. */
internal data class StompFrame(val command: String, val headers: Map<String, String>, val body: String = "") {
    fun marshal(): String {
        val sb = StringBuilder(command).append('\n')
        headers.forEach { (k, v) -> sb.append(k).append(':').append(v).append('\n') }
        // content-length counts UTF-8 bytes.
        sb.append("content-length:").append(body.toByteArray(Charsets.UTF_8).size).append('\n')
        return sb.append('\n').append(body).append('\u0000').toString()
    }

    companion object {
        /** Returns null for heartbeats and malformed frames. A bad frame must never end the connection. */
        fun parse(raw: ByteArray): StompFrame? {
            val separator = findHeaderEnd(raw) ?: return null
            val lines = String(raw, 0, separator.first, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
            val command = lines.firstOrNull()?.trim().orEmpty()
            if (command.isEmpty()) return null
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) return null
                // The first occurrence of a repeated header wins, per the STOMP spec.
                headers.putIfAbsent(line.substring(0, colon), line.substring(colon + 1))
            }
            val bodyStart = separator.second
            val declared = headers["content-length"]
            val bodyEnd = if (declared != null) {
                val length = declared.toIntOrNull() ?: return null
                val end = bodyStart + length
                if (length < 0 || end >= raw.size || raw[end] != 0.toByte()) return null
                end
            } else {
                (bodyStart until raw.size).firstOrNull { raw[it] == 0.toByte() } ?: return null
            }
            return StompFrame(command, headers, String(raw, bodyStart, bodyEnd - bodyStart, Charsets.UTF_8))
        }

        /** First byte of the blank line, and first byte after it. Handles LF and CRLF. */
        private fun findHeaderEnd(raw: ByteArray): Pair<Int, Int>? {
            val nl = '\n'.code.toByte()
            val cr = '\r'.code.toByte()
            for (i in raw.indices) {
                if (raw[i] != nl) continue
                val next = i + 1
                if (next < raw.size && raw[next] == nl) return i to next + 1
                if (next + 1 < raw.size && raw[next] == cr && raw[next + 1] == nl) return i to next + 2
            }
            return null
        }
    }
}
