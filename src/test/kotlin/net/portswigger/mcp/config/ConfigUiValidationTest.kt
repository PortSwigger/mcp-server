package net.portswigger.mcp.config

import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class ConfigUiValidationTest {

    private lateinit var persistedObject: PersistedObject
    private lateinit var config: McpConfig
    private lateinit var configUi: ConfigUi
    private lateinit var isValidTargetMethod: Method
    private lateinit var isValidHostnameMethod: Method

    @BeforeEach
    fun setup() {
        val storage = mutableMapOf<String, Any>()
        
        persistedObject = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } answers { storage[firstArg()] as? Boolean ?: false }
            every { getString(any()) } answers { storage[firstArg()] as? String ?: "" }
            every { getInteger(any()) } answers { storage[firstArg()] as? Int ?: 0 }
            every { setBoolean(any(), any()) } answers { 
                storage[firstArg()] = secondArg<Boolean>()
            }
            every { setString(any(), any()) } answers { 
                storage[firstArg()] = secondArg<String>()
            }
            every { setInteger(any(), any()) } answers { 
                storage[firstArg()] = secondArg<Int>()
            }
        }
        config = McpConfig(persistedObject)
        configUi = ConfigUi(config, emptyList())
        
        isValidTargetMethod = ConfigUi::class.java.getDeclaredMethod("isValidTarget", String::class.java)
        isValidTargetMethod.isAccessible = true
        
        isValidHostnameMethod = ConfigUi::class.java.getDeclaredMethod("isValidHostname", String::class.java)
        isValidHostnameMethod.isAccessible = true
    }

    private fun isValidTarget(target: String): Boolean {
        return isValidTargetMethod.invoke(configUi, target) as Boolean
    }

    private fun isValidHostname(hostname: String): Boolean {
        return isValidHostnameMethod.invoke(configUi, hostname) as Boolean
    }

    @Test
    fun `isValidTarget should accept valid hostnames`() {
        assertTrue(isValidTarget("example.com"))
        assertTrue(isValidTarget("test.org"))
        assertTrue(isValidTarget("sub.domain.co.uk"))
        assertTrue(isValidTarget("localhost"))
        assertTrue(isValidTarget("127.0.0.1"))
    }

    @Test
    fun `isValidTarget should accept valid hostname with port`() {
        assertTrue(isValidTarget("example.com:80"))
        assertTrue(isValidTarget("example.com:8080"))
        assertTrue(isValidTarget("localhost:3000"))
        assertTrue(isValidTarget("127.0.0.1:9876"))
    }

    @Test
    fun `isValidTarget should accept wildcard domains`() {
        assertTrue(isValidTarget("*.example.com"))
        assertTrue(isValidTarget("*.api.test.org"))
        assertTrue(isValidTarget("*.co.uk"))
    }

    @Test
    fun `isValidTarget should reject invalid formats`() {
        assertFalse(isValidTarget("")) // Empty
        assertFalse(isValidTarget("   ")) // Whitespace only
        assertFalse(isValidTarget("example.com:80:443")) // Multiple ports
        assertFalse(isValidTarget("example.com:")) // Empty port
        assertFalse(isValidTarget("example.com:abc")) // Non-numeric port
        assertFalse(isValidTarget("example.com:0")) // Invalid port range
        assertFalse(isValidTarget("example.com:65536")) // Invalid port range
        assertFalse(isValidTarget("*.")) // Invalid wildcard
        assertFalse(isValidTarget("*")) // Invalid wildcard
    }

    @Test
    fun `isValidTarget should reject oversized input`() {
        val longHostname = "a".repeat(256)
        assertFalse(isValidTarget(longHostname))
    }

    @Test
    fun `isValidHostname should accept valid hostnames`() {
        assertTrue(isValidHostname("example.com"))
        assertTrue(isValidHostname("test-site.org"))
        assertTrue(isValidHostname("sub.domain.co.uk"))
        assertTrue(isValidHostname("a.b"))
        assertTrue(isValidHostname("x"))
    }

    @Test
    fun `isValidHostname should reject invalid hostnames`() {
        assertFalse(isValidHostname("")) // Empty
        assertFalse(isValidHostname(".example.com")) // Leading dot
        assertFalse(isValidHostname("example.com.")) // Trailing dot
        assertFalse(isValidHostname("example..com")) // Double dot
        assertFalse(isValidHostname("-example.com")) // Leading hyphen in label
        assertFalse(isValidHostname("example-.com")) // Trailing hyphen in label
        assertFalse(isValidHostname("example.com-")) // Trailing hyphen in TLD
    }

    @Test
    fun `isValidHostname should enforce length limits`() {
        // Test hostname length limit (253 chars) - use shorter test for simplicity
        val normalHostname = "a".repeat(50) + ".example.com"
        assertTrue(isValidHostname(normalHostname))
        
        val tooLongHostname = "a".repeat(254)
        assertFalse(isValidHostname(tooLongHostname))
        
        // Test label length limit (63 chars per label)
        val maxLabel = "a".repeat(60) + ".com" // 60 + 1 + 3 = 64 total, but each label <= 63
        assertTrue(isValidHostname(maxLabel))
        
        val tooLongLabel = "a".repeat(64) + ".com" // First label is 64 chars, too long
        assertFalse(isValidHostname(tooLongLabel))
    }

    @Test
    fun `isValidHostname should handle special characters`() {
        assertTrue(isValidHostname("test123.example.com"))
        assertTrue(isValidHostname("123.456.789.012")) // IP-like but treated as hostname
        assertFalse(isValidHostname("test@example.com")) // Special characters not allowed
        assertFalse(isValidHostname("test_site.com")) // Underscore not allowed in basic validation
    }

    @Test
    fun `isValidTarget should handle edge cases`() {
        assertTrue(isValidTarget("example.com:1"))
        
        assertTrue(isValidTarget("example.com:65535"))
        
        assertFalse(isValidTarget("*.example.com:80"))
        
        assertTrue(isValidTarget("x"))
        
        assertTrue(isValidTarget("x:80"))
    }
}