package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.ServerSocket

class AllowedHostIntegrationTest {
    private val client = TestSseMcpClient()
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = findAvailablePort()
    private val storage = mutableMapOf<String, Any>(
        "enabled" to true,
        "host" to "127.0.0.1",
        "port" to testPort,
        "_allowedHosts" to "192.168.178.67"
    )
    private val persistedObject = mockk<PersistedObject>().apply {
        every { getBoolean(any()) } answers { storage[firstArg()] as? Boolean ?: false }
        every { getString(any()) } answers { storage[firstArg()] as? String ?: "" }
        every { getInteger(any()) } answers { storage[firstArg()] as? Int ?: 0 }
        every { setBoolean(any(), any()) } answers { storage[firstArg()] = secondArg() }
        every { setString(any(), any()) } answers { storage[firstArg()] = secondArg() }
        every { setInteger(any(), any()) } answers { storage[firstArg()] = secondArg() }
    }
    private val mockLogging = mockk<Logging>(relaxed = true)
    private val config = McpConfig(persistedObject, mockLogging)

    @AfterEach
    fun tearDown() = runBlocking {
        if (client.isConnected()) {
            client.close()
        }
        serverManager.stop {}
        serverManager.shutdown()
    }

    @Test
    fun `server with remote allowed hosts should still accept localhost sse clients`() = runBlocking {
        var serverStarted = false
        serverManager.start(config) { state ->
            if (state is ServerState.Running) serverStarted = true
        }

        repeat(20) {
            if (serverStarted) return@repeat
            delay(100)
        }
        assertTrue(serverStarted, "Server should start")

        client.connectToServer("http://127.0.0.1:$testPort")
        assertTrue(client.isConnected())
        assertFalse(client.listTools().isEmpty())
    }

    private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }
}
