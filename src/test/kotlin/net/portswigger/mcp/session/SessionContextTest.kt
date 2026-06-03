package net.portswigger.mcp.session

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SessionContextTest {

    private lateinit var ctx: SessionContext

    @BeforeEach
    fun setup() {
        ctx = SessionContext()
    }

    @Test
    fun `fingerprint extracts JWT from Authorization header`() {
        val jwt = "Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature"
        ctx.fingerprint(listOf("Authorization" to jwt))
        val compressed = ctx.compress("Authorization", jwt)
        assertEquals("{{\$AUTH}}", compressed)
    }

    @Test
    fun `compress returns original value for unknown headers`() {
        ctx.fingerprint(listOf("X-Custom-Header" to "custom-value"))
        val result = ctx.compress("X-Custom-Header", "custom-value")
        assertEquals("custom-value", result)
    }

    @Test
    fun `compress returns original if value does not match stored`() {
        ctx.fingerprint(listOf("Host" to "target.com"))
        val result = ctx.compress("Host", "other-target.com")
        assertEquals("other-target.com", result)
    }

    @Test
    fun `declarationBlock contains all fingerprinted variables`() {
        ctx.fingerprint(listOf(
            "Host" to "api.target.com",
            "User-Agent" to "Mozilla/5.0",
            "Authorization" to "Bearer token123"
        ))
        val block = ctx.declarationBlock()
        assertTrue(block.contains("\$AUTH"))
        assertTrue(block.contains("\$HOST"))
        assertTrue(block.contains("\$UA"))
        assertTrue(block.contains("SESSION VARIABLES"))
    }

    @Test
    fun `needsDeclaration returns true first time then false`() {
        ctx.fingerprint(listOf("Host" to "target.com"))
        assertTrue(ctx.needsDeclaration())
        assertFalse(ctx.needsDeclaration())
    }

    @Test
    fun `invalidateAuth removes auth headers but keeps structural headers`() {
        ctx.fingerprint(listOf(
            "Host" to "api.target.com",
            "Authorization" to "Bearer oldtoken",
            "Cookie" to "session=abc"
        ))
        // Mark as declared first
        ctx.needsDeclaration()
        ctx.invalidateAuth()

        // Auth gone — compress returns original value
        assertEquals("Bearer oldtoken", ctx.compress("Authorization", "Bearer oldtoken"))
        assertEquals("session=abc", ctx.compress("Cookie", "session=abc"))

        // Host still compressed
        assertEquals("{{\$HOST}}", ctx.compress("Host", "api.target.com"))

        // Declaration needed again after invalidation
        assertTrue(ctx.needsDeclaration())
    }

    @Test
    fun `reset clears all state`() {
        ctx.fingerprint(listOf("Host" to "target.com"))
        ctx.reset()
        assertTrue(ctx.isEmpty())
        assertEquals("target.com", ctx.compress("Host", "target.com"))
    }

    @Test
    fun `estimatedTokenSavingsPerRequest is positive for long JWT`() {
        val longJwt = "Bearer " + "a".repeat(1400)
        ctx.fingerprint(listOf("Authorization" to longJwt))
        assertTrue(ctx.estimatedTokenSavingsPerRequest() > 300)
    }

    @Test
    fun `compressRequest replaces known headers in raw HTTP string`() {
        ctx.fingerprint(listOf(
            "Host" to "api.target.com",
            "Authorization" to "Bearer token123",
            "User-Agent" to "Mozilla/5.0"
        ))
        val raw = "GET /api/data HTTP/1.1\r\nHost: api.target.com\r\nAuthorization: Bearer token123\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
        val compressed = ctx.compressRequest(raw)
        assertTrue(compressed.contains("Host: {{\$HOST}}"))
        assertTrue(compressed.contains("Authorization: {{\$AUTH}}"))
        assertTrue(compressed.contains("User-Agent: {{\$UA}}"))
        assertFalse(compressed.contains("api.target.com"))
    }

    @Test
    fun `compressRequest leaves body unchanged`() {
        ctx.fingerprint(listOf("Host" to "api.target.com"))
        val raw = "POST /api HTTP/1.1\r\nHost: api.target.com\r\n\r\n{\"host\":\"api.target.com\"}"
        val compressed = ctx.compressRequest(raw)
        assertTrue(compressed.contains("Host: {{\$HOST}}"))
        // Body should be unchanged
        assertTrue(compressed.contains("{\"host\":\"api.target.com\"}"))
    }
}
