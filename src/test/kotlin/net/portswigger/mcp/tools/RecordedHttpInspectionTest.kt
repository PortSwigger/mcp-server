package net.portswigger.mcp.tools

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RecordedHttpInspectionTest {
    @Test
    fun `headers omit bodies and report long previews`() {
        val item = RecordedHttpMessage(
            "POST / HTTP/1.1\r\nX-Long: ${"a".repeat(2_000)}\r\n\r\nsecret request body",
            "HTTP/1.1 200 OK\nContent-Type: text/plain\n\nsecret response body"
        )
        val headers = Json.decodeFromString<RecordedHttpHeaders>(encodeRecordedHttpHeaders("proxy", 3, item))

        assertEquals(3, headers.index)
        assertTrue(headers.requestHeaders.startsWith("POST / HTTP/1.1"))
        assertTrue(headers.requestHeadersTruncated)
        assertEquals("HTTP/1.1 200 OK\nContent-Type: text/plain", headers.responseHeaders)
        assertFalse(headers.responseHeadersTruncated)
        assertFalse(headers.requestHeaders.contains("secret"))
        assertFalse(headers.responseHeaders.contains("secret"))
    }

    @Test
    fun `escaped headers stay within output limit`() {
        val item = RecordedHttpMessage("X-Long: ${"\u0000".repeat(2_000)}", "HTTP/1.1 200 OK")
        val encoded = encodeRecordedHttpHeaders("proxy", 0, item)
        val headers = Json.decodeFromString<RecordedHttpHeaders>(encoded)

        assertTrue(encoded.length <= 5_000)
        assertTrue(headers.requestHeadersTruncated)
    }

    @Test
    fun `chunks reconstruct long unicode and escaped response`() {
        val response = "HTTP/1.1 200 OK\r\n\r\n" + "😀\\\u0000\"".repeat(2_000)
        val item = RecordedHttpMessage("GET / HTTP/1.1", response)
        val parts = StringBuilder()
        var offset = 0
        val totalLength = response.codePointCount(0, response.length)

        do {
            val encoded = encodeRecordedHttpChunk("proxy", 0, "response", offset, 2_000, item)
            val chunk = Json.decodeFromString<RecordedHttpChunk>(encoded)
            assertTrue(encoded.length <= 5_000)
            assertTrue(chunk.nextOffset > offset)
            parts.append(chunk.content)
            offset = chunk.nextOffset
        } while (offset < totalLength)

        assertEquals(totalLength, offset)
        assertEquals(response, parts.toString())
    }

    @Test
    fun `invalid chunk ranges fail clearly`() {
        val item = RecordedHttpMessage("request", "response")
        assertThrows(IllegalArgumentException::class.java) {
            encodeRecordedHttpChunk("proxy", 0, "response", -1, 1, item)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeRecordedHttpChunk("proxy", 0, "response", 0, 2_001, item)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeRecordedHttpChunk("proxy", 0, "response", 9, 1, item)
        }
    }
}
