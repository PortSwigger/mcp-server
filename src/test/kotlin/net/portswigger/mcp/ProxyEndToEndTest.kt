package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.UserApprovalHandler
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * End-to-end test verifying the full stack:
 * TestStdioMcpClient (stdio) ↔ proxy subprocess ↔ KtorServerManager (SSE)
 *
 * Requires libs/mcp-proxy-all.jar to be present (built via `./gradlew embedProxyJar` from root).
 */
@Timeout(30, unit = TimeUnit.SECONDS)
class ProxyEndToEndTest {
    private val logger = LoggerFactory.getLogger(ProxyEndToEndTest::class.java)

    private val api = mockk<MontoyaApi>(relaxed = true)
    private val serverManager = KtorServerManager(api)
    private val testPort = findAvailablePort()
    private val persistedObject = mockk<PersistedObject>()

    @Volatile
    private var serverStarted = false

    init {
        every { persistedObject.getBoolean(any()) } returns true
        every { persistedObject.getString(any()) } returns "127.0.0.1"
        every { persistedObject.getInteger("port") } returns testPort
        every { persistedObject.setBoolean(any(), any()) } returns Unit
        every { persistedObject.setString(any(), any()) } returns Unit
        every { persistedObject.setInteger(any(), any()) } returns Unit
    }

    private val mockLogging = mockk<Logging>().apply {
        every { logToError(any<String>()) } returns Unit
        every { logToOutput(any<String>()) } returns Unit
    }

    private val config = McpConfig(persistedObject, mockLogging)

    private lateinit var proxyProcess: Process
    private lateinit var client: TestStdioMcpClient

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    @Test
    fun `client timeout cancels pending HTTP1 approval through proxy`() = runBlocking {
        assertCancelledApproval("send_http1_request", mapOf("content" to "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"))
    }

    @Test
    fun `client timeout cancels pending HTTP2 approval through proxy`() = runBlocking {
        assertCancelledApproval("send_http2_request", mapOf(
            "pseudoHeaders" to mapOf("method" to "GET", "path" to "/", "scheme" to "https", "authority" to "example.com"),
            "headers" to emptyMap<String, String>(), "requestBody" to ""
        ))
    }

    private suspend fun assertCancelledApproval(tool: String, request: Map<String, Any>) = coroutineScope {
        config.requireHttpRequestApproval = true
        val originalHandler = HttpRequestSecurity.approvalHandler
        val pending = CompletableDeferred<CancellableContinuation<Boolean>>()
        val cancelled = CompletableDeferred<Unit>()
        HttpRequestSecurity.approvalHandler = object : UserApprovalHandler {
            override suspend fun requestApproval(
                hostname: String, port: Int, config: McpConfig, requestContent: String?, api: MontoyaApi?
            ): Boolean = suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancelled.complete(Unit) }
                pending.complete(continuation)
            }
        }
        try {
            val call = launch {
                withTimeout(1500) {
                    client.callTool(tool, request + mapOf("targetHostname" to "example.com", "targetPort" to 443, "usesHttps" to true))
                }
            }
            val approval = withTimeout(5000) { pending.await() }
            withTimeout(5000) { call.join() }
            assertTrue(call.isCancelled)
            withTimeout(5000) { cancelled.await() }
            approval.resume(true) // A late Allow must not send the HTTP request.
            client.ping()
            val http = api.http()
            verify(exactly = 0) { http.sendRequest(any()) }
            verify(exactly = 0) { http.sendRequest(any(), any<burp.api.montoya.http.HttpMode>()) }
        } finally {
            HttpRequestSecurity.approvalHandler = originalHandler
        }
    }

    @BeforeEach
    fun setup(): Unit = runBlocking {
        serverManager.start(config) { state ->
            if (state is ServerState.Running) {
                serverStarted = true
            }
        }

        var attempts = 0
        while (!serverStarted && attempts < 10) {
            delay(100)
            attempts++
        }
        if (!serverStarted) {
            throw IllegalStateException("Server failed to start after timeout")
        }

        val jarFile = File("libs/mcp-proxy-all.jar")
        check(jarFile.exists()) {
            "libs/mcp-proxy-all.jar not found. Build it and copy it to libs first: ./gradlew embedProxyJar (from proxy repo root)"
        }

        proxyProcess = ProcessBuilder(
            "java",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
            "-jar",
            jarFile.absolutePath,
            "--sse-url",
            "http://127.0.0.1:$testPort"
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

        client = TestStdioMcpClient()
        connectClientWithRetry()
        logger.info("Test client connected to proxy on port $testPort")
    }

    private suspend fun connectClientWithRetry() {
        val maxAttempts = 10
        val retryDelay = 500.milliseconds
        for (attempt in 1..maxAttempts) {
            check(proxyProcess.isAlive) { "Proxy process died during startup" }
            try {
                client.connectToServer(proxyProcess.inputStream, proxyProcess.outputStream)
                return
            } catch (e: Exception) {
                if (attempt == maxAttempts) throw e
                logger.info("Proxy not ready (attempt $attempt/$maxAttempts), retrying...")
                delay(retryDelay)
            }
        }
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        try {
            if (::client.isInitialized) {
                client.close()
            }
        } catch (e: Exception) {
            logger.warn("Error closing client: ${e.message}")
        }

        try {
            if (::proxyProcess.isInitialized) {
                proxyProcess.destroy()
                if (!proxyProcess.waitFor(2, TimeUnit.SECONDS)) {
                    proxyProcess.destroyForcibly()
                }
            }
        } catch (e: Exception) {
            logger.warn("Error destroying proxy process: ${e.message}")
        }

        serverManager.shutdown()
    }

    @Test
    fun `proxy should ping server`() {
        runBlocking {
            assertDoesNotThrow { client.ping() }
        }
    }

    @Test
    fun `proxy should list tools`() {
        runBlocking {
            val tools = client.listTools()
            assertFalse(tools.isEmpty(), "Tool list should not be empty")
            assertTrue(tools.any { it.name == "url_encode" }, "url_encode tool should be present")
        }
    }

    @Test
    fun `proxy should call url_encode tool`() {
        runBlocking {
            val result = client.callTool("url_encode", mapOf("content" to "hello world"))
            assertNotNull(result, "Tool call result should not be null")
            assertFalse(result?.isError ?: false, "Tool call should not return an error")
            assertTrue(result?.content?.first() is TextContent, "Result should contain TextContent")
        }
    }
}
