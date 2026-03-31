package net.portswigger.mcp.security

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AuditApprovalSecurityTest {

    private lateinit var persistedObject: PersistedObject
    private lateinit var config: McpConfig
    private lateinit var mockLogging: Logging
    private lateinit var storage: MutableMap<String, Any>

    @BeforeEach
    fun setup() {
        storage = mutableMapOf(
            "_autoApproveTargets" to "",
            "requireHttpRequestApproval" to true
        )
        persistedObject = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } answers { storage[firstArg()] as? Boolean ?: false }
            every { getString(any()) } answers { storage[firstArg()] as? String ?: "" }
            every { getInteger(any()) } answers { storage[firstArg()] as? Int ?: 0 }
            every { setBoolean(any(), any()) } answers { storage[firstArg()] = secondArg<Boolean>() }
            every { setString(any(), any()) } answers { storage[firstArg()] = secondArg<String>() }
            every { setInteger(any(), any()) } answers { storage[firstArg()] = secondArg<Int>() }
        }
        mockLogging = mockk<Logging>().apply {
            every { logToError(any<String>()) } returns Unit
        }
        config = McpConfig(persistedObject, mockLogging)
    }

    @Test
    fun `checkAuditPermission returns true when approval is disabled`() = runBlocking {
        storage["requireHttpRequestApproval"] = false
        val result = HttpRequestSecurity.checkAuditPermission("example.com", 443, config)
        assertTrue(result)
    }

    @Test
    fun `checkAuditPermission returns true when host is auto-approved`() = runBlocking {
        config.addAutoApproveTarget("example.com")
        val result = HttpRequestSecurity.checkAuditPermission("example.com", 443, config)
        assertTrue(result)
    }

    @Test
    fun `checkAuditPermission returns true when host port is auto-approved`() = runBlocking {
        config.addAutoApproveTarget("example.com:8080")
        val result = HttpRequestSecurity.checkAuditPermission("example.com", 8080, config)
        assertTrue(result)
    }

    @Test
    fun `checkAuditPermission delegates to auditApprovalHandler when not auto-approved`() = runBlocking {
        val mockHandler = mockk<UserApprovalHandler>()
        coEvery { mockHandler.requestApproval("example.com", 443, config, null, null) } returns false

        val original = HttpRequestSecurity.auditApprovalHandler
        try {
            HttpRequestSecurity.auditApprovalHandler = mockHandler
            val result = HttpRequestSecurity.checkAuditPermission("example.com", 443, config)
            assertFalse(result)
        } finally {
            HttpRequestSecurity.auditApprovalHandler = original
        }
    }

    @Test
    fun `checkAuditPermission uses auditApprovalHandler not approvalHandler`() = runBlocking {
        val mockAuditHandler = mockk<UserApprovalHandler>()
        val mockHttpHandler = mockk<UserApprovalHandler>()
        coEvery { mockAuditHandler.requestApproval("target.com", 443, config, null, null) } returns true
        coEvery { mockHttpHandler.requestApproval("target.com", 443, config, null, null) } returns false

        val originalAudit = HttpRequestSecurity.auditApprovalHandler
        val originalHttp = HttpRequestSecurity.approvalHandler
        try {
            HttpRequestSecurity.auditApprovalHandler = mockAuditHandler
            HttpRequestSecurity.approvalHandler = mockHttpHandler
            val result = HttpRequestSecurity.checkAuditPermission("target.com", 443, config)
            assertTrue(result)
        } finally {
            HttpRequestSecurity.auditApprovalHandler = originalAudit
            HttpRequestSecurity.approvalHandler = originalHttp
        }
    }
}
