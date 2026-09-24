package net.portswigger.mcp.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NormalizeHttpContentTest {

    @Nested
    inner class PreludeNormalization {

        @Test
        fun `literal backslash-r backslash-n sequences become CRLF`() {
            val input = "GET /foo HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n"
            val expected = "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n"

            assertEquals(expected, normalizeHttpContent(input))
        }

        @Test
        fun `bare LFs become CRLF`() {
            val input = "GET /foo HTTP/1.1\nHost: example.com\n\n"
            val expected = "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n"

            assertEquals(expected, normalizeHttpContent(input))
        }

        @Test
        fun `already-correct CRLFs are preserved`() {
            val input = "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n"

            assertEquals(input, normalizeHttpContent(input))
        }

        @Test
        fun `literal backslash-n only is treated as a line break`() {
            val input = "GET /foo HTTP/1.1\\nHost: example.com\\n\\n"
            val expected = "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n"

            assertEquals(expected, normalizeHttpContent(input))
        }

        @Test
        fun `stray literal backslash-r characters are stripped`() {
            val input = "GET /foo HTTP/1.1\\r\\nHost: example.com\\r\\r\\n\\r\\n"
            val expected = "GET /foo HTTP/1.1\r\nHost: example.com\r\n\r\n"

            assertEquals(expected, normalizeHttpContent(input))
        }
    }

    @Nested
    inner class BodyPreservation {

        @Test
        fun `JSON body containing literal backslash-n is left untouched`() {
            val input =
                "POST /api HTTP/1.1\\r\\nHost: example.com\\r\\nContent-Type: application/json\\r\\nContent-Length: 25\\r\\n\\r\\n" +
                        "{\"text\": \"hello\\nworld\"}"
            val expected =
                "POST /api HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\nContent-Length: 25\r\n\r\n" +
                        "{\"text\": \"hello\\nworld\"}"

            assertEquals(expected, normalizeHttpContent(input))
        }

        @Test
        fun `body containing real LFs is left untouched`() {
            val input =
                "POST /api HTTP/1.1\r\nHost: example.com\r\nContent-Length: 11\r\n\r\n" +
                        "line1\nline2"
            val expected =
                "POST /api HTTP/1.1\r\nHost: example.com\r\nContent-Length: 11\r\n\r\n" +
                        "line1\nline2"

            assertEquals(expected, normalizeHttpContent(input))
        }

        @Test
        fun `body containing literal backslash-r-backslash-n is left untouched`() {
            val input =
                "POST /api HTTP/1.1\\r\\nHost: example.com\\r\\nContent-Length: 18\\r\\n\\r\\n" +
                        "raw=value\\r\\nother"
            val expected =
                "POST /api HTTP/1.1\r\nHost: example.com\r\nContent-Length: 18\r\n\r\n" +
                        "raw=value\\r\\nother"

            assertEquals(expected, normalizeHttpContent(input))
        }

        @Test
        fun `empty body after blank line is preserved`() {
            val input = "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n"
            val expected = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"

            assertEquals(expected, normalizeHttpContent(input))
        }
    }

    @Nested
    inner class SeparatorDetection {

        @Test
        fun `earliest separator wins when both literal and actual are present`() {
            // Prelude uses actual CRLF; body happens to contain a literal "\r\n\r\n" sequence.
            // The actual blank line must be detected first, leaving the body verbatim.
            val input =
                "POST /api HTTP/1.1\r\nHost: example.com\r\n\r\n" +
                        "payload=foo\\r\\n\\r\\nbar"
            val expected =
                "POST /api HTTP/1.1\r\nHost: example.com\r\n\r\n" +
                        "payload=foo\\r\\n\\r\\nbar"

            assertEquals(expected, normalizeHttpContent(input))
        }

        @Test
        fun `no blank line means whole content is treated as prelude`() {
            val input = "GET /foo HTTP/1.1\\r\\nHost: example.com\\r\\n"
            val expected = "GET /foo HTTP/1.1\r\nHost: example.com\r\n"

            assertEquals(expected, normalizeHttpContent(input))
        }
    }
}
