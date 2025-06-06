package net.portswigger.mcp.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TargetValidationTest {

    private fun isValidTarget(target: String): Boolean {
        return TargetValidation.isValidTarget(target)
    }

    @Test
    fun `isValidTarget should accept valid formats`() {
        // Basic hostnames
        assertTrue(isValidTarget("example.com"))
        assertTrue(isValidTarget("test.org"))
        assertTrue(isValidTarget("sub.domain.co.uk"))
        assertTrue(isValidTarget("localhost"))
        assertTrue(isValidTarget("127.0.0.1"))

        // With ports
        assertTrue(isValidTarget("example.com:80"))
        assertTrue(isValidTarget("example.com:8080"))
        assertTrue(isValidTarget("localhost:3000"))
        assertTrue(isValidTarget("127.0.0.1:9876"))

        // Wildcards
        assertTrue(isValidTarget("*.example.com"))
        assertTrue(isValidTarget("*.api.test.org"))
        assertTrue(isValidTarget("*.co.uk"))

        // IPv6 formats
        assertTrue(isValidTarget("::1"))
        assertTrue(isValidTarget("[::1]:8080"))

        // Edge cases with permissive validation
        assertTrue(isValidTarget("256.0.0.1")) // Invalid IPv4 but allowed
        assertTrue(isValidTarget("test@example.com")) // Special chars allowed
        assertTrue(isValidTarget("*.*.com")) // Multiple wildcards allowed
    }

    @Test
    fun `isValidTarget should reject invalid formats`() {
        // Empty/blank input
        assertFalse(isValidTarget(""))
        assertFalse(isValidTarget("   "))

        // Invalid ports
        assertFalse(isValidTarget("example.com:"))
        assertFalse(isValidTarget("example.com:abc"))
        assertFalse(isValidTarget("example.com:0"))
        assertFalse(isValidTarget("example.com:65536"))

        // Control characters
        assertFalse(isValidTarget("example\tcom"))
        assertFalse(isValidTarget("example\ncom"))
        assertFalse(isValidTarget("example\rcom"))

        // Oversized input
        assertFalse(isValidTarget("a".repeat(256)))
    }


}