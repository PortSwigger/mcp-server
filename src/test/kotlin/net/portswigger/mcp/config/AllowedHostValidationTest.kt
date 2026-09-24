package net.portswigger.mcp.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AllowedHostValidationTest {

    @Test
    fun `parseCommaSeparated should split and trim entries`() {
        assertEquals(
            listOf("192.168.178.67", "192.168.32.129"),
            AllowedHostValidation.parseCommaSeparated("192.168.178.67, 192.168.32.129")
        )
    }

    @Test
    fun `sanitizeAllowedHosts should drop invalid entries`() {
        assertEquals(
            "192.168.178.67,192.168.32.129",
            AllowedHostValidation.sanitizeAllowedHosts("192.168.178.67, bad host!, 192.168.32.129")
        )
    }

    @Test
    fun `validateAllowedHosts should return error for invalid entries`() {
        assertEquals(
            "Invalid allowed host(s): bad host!",
            AllowedHostValidation.validateAllowedHosts("192.168.178.67, bad host!")
        )
    }

    @Test
    fun `validateAllowedHosts should accept empty value`() {
        assertNull(AllowedHostValidation.validateAllowedHosts(""))
        assertNull(AllowedHostValidation.validateAllowedHosts("   "))
    }

    @Test
    fun `isValidAllowedHost should accept ipv4 addresses`() {
        assertTrue(AllowedHostValidation.isValidAllowedHost("192.168.178.67"))
        assertTrue(AllowedHostValidation.isValidAllowedHost("172.21.0.1"))
    }

    @Test
    fun `isValidAllowedHost should reject blank and malformed values`() {
        assertFalse(AllowedHostValidation.isValidAllowedHost(""))
        assertFalse(AllowedHostValidation.isValidAllowedHost("bad host!"))
        assertFalse(AllowedHostValidation.isValidAllowedHost("192.168.1.1,10.0.0.1"))
    }
}
