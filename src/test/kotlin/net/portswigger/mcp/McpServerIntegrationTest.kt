package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

@Timeout(30, unit = TimeUnit.SECONDS)
class McpServerIntegrationTest {
    private val client = TestSseMcpClient()
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = ServerSocket(0).use { it.localPort }
    private val persistedObject = mockk<PersistedObject>()

    init {
        every { persistedObject.getBoolean(any()) } returns true
        every { persistedObject.getString(any()) } returns "127.0.0.1"
        every { persistedObject.getInteger("port") } returns testPort
        every { persistedObject.setBoolean(any(), any()) } returns Unit
        every { persistedObject.setString(any(), any()) } returns Unit
        every { persistedObject.setInteger(any(), any()) } returns Unit
    }

    private val mockLogging = mockk<Logging>(relaxed = true)
    private val config = McpConfig(persistedObject, mockLogging)

    @BeforeEach
    fun setup(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        serverManager.start(config) { state ->
            when (state) {
                ServerState.Running -> started.complete(Unit)
                is ServerState.Failed -> started.completeExceptionally(state.exception)
                else -> Unit
            }
        }
        withTimeout(10_000) { started.await() }
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        try {
            client.close()
        } finally {
            serverManager.shutdown()
            config.cleanup()
        }
    }

    @Test
    fun `server should accept connections and list tools`(): Unit = runBlocking {
        client.connectToServer("http://127.0.0.1:$testPort")
        assertTrue(client.isConnected(), "Client should be connected to server")

        val tools = client.listTools()
        assertFalse(tools.isEmpty(), "Server should have registered tools")
        val toolNames = tools.map { it.name }
        assertTrue(toolNames.contains("output_project_options"))
        assertTrue(toolNames.contains("output_user_options"))
        assertNotNull(client.ping(), "Ping should return a result")
    }
}
