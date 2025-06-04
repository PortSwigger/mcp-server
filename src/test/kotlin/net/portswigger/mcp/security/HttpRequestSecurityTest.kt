package net.portswigger.mcp.security

import burp.api.montoya.persistence.PersistedObject
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class HttpRequestSecurityTest {

    private lateinit var persistedObject: PersistedObject
    private lateinit var config: McpConfig
    private lateinit var mockApprovalHandler: UserApprovalHandler
    private lateinit var originalApprovalHandler: UserApprovalHandler

    @BeforeEach
    fun setup() {
        originalApprovalHandler = HttpRequestSecurity.approvalHandler
        
        mockApprovalHandler = mockk<UserApprovalHandler>()
        HttpRequestSecurity.approvalHandler = mockApprovalHandler
        val storage = mutableMapOf<String, Any>(
            "enabled" to true,
            "configEditingTooling" to false,
            "requireHttpRequestApproval" to true,
            "host" to "127.0.0.1",
            "_autoApproveTargets" to "",
            "port" to 9876
        )
        
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
    }
    
    @AfterEach
    fun tearDown() {
        HttpRequestSecurity.approvalHandler = originalApprovalHandler
    }

    @Test
    fun `checkHttpRequestPermission should allow when approval disabled`() {
        config.requireHttpRequestApproval = false
        
        runBlocking {
            val result = HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config)
            assertTrue(result)
        }
    }

    @Test
    fun `checkHttpRequestPermission should allow auto-approved hostname`() {
        config.addAutoApproveTarget("example.com")
        
        runBlocking {
            val result = HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config)
            assertTrue(result)
        }
    }

    @Test
    fun `checkHttpRequestPermission should allow auto-approved hostname with port`() {
        config.addAutoApproveTarget("example.com:8080")
        
        coEvery { mockApprovalHandler.requestApproval("example.com", 80, config) } returns false
        
        runBlocking {
            val result1 = HttpRequestSecurity.checkHttpRequestPermission("example.com", 8080, config)
            assertTrue(result1)
            
            val result2 = HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config)
            assertFalse(result2)
        }
    }

    @Test
    fun `checkHttpRequestPermission should allow wildcard domains`() {
        config.addAutoApproveTarget("*.example.com")
        
        coEvery { mockApprovalHandler.requestApproval("example.com", 80, config) } returns false
        
        runBlocking {
            val result1 = HttpRequestSecurity.checkHttpRequestPermission("api.example.com", 80, config)
            assertTrue(result1)
            
            val result2 = HttpRequestSecurity.checkHttpRequestPermission("test.example.com", 443, config)
            assertTrue(result2)

            // Wildcard doesn't match exact domain
            val result3 = HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config)
            assertFalse(result3)
        }
    }

    @Test
    fun `checkHttpRequestPermission should be case insensitive`() {
        config.addAutoApproveTarget("Example.COM")
        
        runBlocking {
            val result1 = HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config)
            assertTrue(result1)
            
            val result2 = HttpRequestSecurity.checkHttpRequestPermission("EXAMPLE.COM", 80, config)
            assertTrue(result2)
        }
    }

    @Test
    fun `checkHttpRequestPermission should handle multiple targets`() {
        config.addAutoApproveTarget("example.com")
        config.addAutoApproveTarget("test.org:8080")
        config.addAutoApproveTarget("*.api.com")
        
        coEvery { mockApprovalHandler.requestApproval("test.org", 80, config) } returns false
        coEvery { mockApprovalHandler.requestApproval("notfound.com", 80, config) } returns false
        
        runBlocking {
            assertTrue(HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config))
            assertTrue(HttpRequestSecurity.checkHttpRequestPermission("example.com", 443, config))
            assertTrue(HttpRequestSecurity.checkHttpRequestPermission("test.org", 8080, config))
            assertTrue(HttpRequestSecurity.checkHttpRequestPermission("v1.api.com", 443, config))
            assertFalse(HttpRequestSecurity.checkHttpRequestPermission("test.org", 80, config))
            assertFalse(HttpRequestSecurity.checkHttpRequestPermission("notfound.com", 80, config))
        }
    }

    @Test
    fun `checkHttpRequestPermission should handle empty auto-approve list`() {
        coEvery { mockApprovalHandler.requestApproval("example.com", 80, config) } returns false
        
        runBlocking {
            val result = HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config)
            assertFalse(result)
        }
    }

    @Test
    fun `isAutoApproved should handle malformed targets gracefully`() {
        val storage = mutableMapOf<String, Any>(
            "enabled" to true,
            "configEditingTooling" to false,
            "requireHttpRequestApproval" to true,
            "host" to "127.0.0.1",
            "_autoApproveTargets" to "example.com,,  ,test.org",
            "port" to 9876
        )
        
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
        
        coEvery { mockApprovalHandler.requestApproval("empty.com", 80, config) } returns false
        
        runBlocking {
            assertTrue(HttpRequestSecurity.checkHttpRequestPermission("example.com", 80, config))
            assertTrue(HttpRequestSecurity.checkHttpRequestPermission("test.org", 80, config))
            assertFalse(HttpRequestSecurity.checkHttpRequestPermission("empty.com", 80, config))
        }
    }
}