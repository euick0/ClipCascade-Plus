package com.clipcascade.plus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StompTest {
    private fun parse(s: String) = StompFrame.parse(s.toByteArray(Charsets.UTF_8))

    @Test fun `heartbeat and junk are ignored`() {
        assertNull(parse("\n"))
        assertNull(parse(""))
        assertNull(parse("MESSAGE"))
    }

    @Test fun `round trips a multiline UTF-8 body`() {
        val body = "línea\n\nmás 🎉"
        val frame = StompFrame("SEND", linkedMapOf("destination" to "/app/cliptext"), body)
        val parsed = parse(frame.marshal())!!
        assertEquals("SEND", parsed.command)
        assertEquals(body, parsed.body)
        assertEquals("/app/cliptext", parsed.headers["destination"])
    }

    @Test fun `content-length counts bytes`() {
        assertEquals("2", parse(StompFrame("SEND", emptyMap(), "é").marshal())!!.headers["content-length"])
    }

    @Test fun `CRLF headers and body without content-length`() {
        val parsed = parse("MESSAGE\r\nsubscription:sub-0\r\n\r\nhello\u0000")!!
        assertEquals("sub-0", parsed.headers["subscription"])
        assertEquals("hello", parsed.body)
    }

    @Test fun `wrong content-length is rejected, not truncated`() {
        assertNull(parse("MESSAGE\ncontent-length:3\n\nhello\u0000"))
    }

    @Test fun `csrf is found with attributes in either order`() {
        assertEquals("abc", ClipCascadeHttp.parseCsrf("""<input type="hidden" name="_csrf" value="abc"/>"""))
        assertEquals("abc", ClipCascadeHttp.parseCsrf("""<input value='abc' name='_csrf'>"""))
        assertNull(ClipCascadeHttp.parseCsrf("""<input name="other" value="abc">"""))
    }
}
