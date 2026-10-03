package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.utilities.Base64Utils
import burp.api.montoya.utilities.Utilities
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Timeout(45, unit = TimeUnit.SECONDS)
class StreamableHttpIntegrationTest {
    private val api = mockk<MontoyaApi>(relaxed = true)
    private val logging = mockk<Logging>(relaxed = true)
    private val utilities = mockk<Utilities>()
    private val base64 = mockk<Base64Utils>()
    private val manager = KtorServerManager(api)
    private val nativeClients = mutableListOf<TestStreamableHttpMcpClient>()
    private val legacyClients = mutableListOf<TestSseMcpClient>()
    private val testPort = availablePort()
    private val integers = mutableMapOf("port" to testPort)
    private val storage = mockk<PersistedObject>(relaxed = true).apply {
        every { getBoolean(any()) } returns null
        every { getString(any()) } returns null
        every { getInteger(any()) } answers { integers[firstArg<String>()] }
        every { setInteger(any(), any()) } answers {
            integers[firstArg<String>()] = secondArg<Int>()
        }
    }
    private val config = McpConfig(storage, logging)

    init {
        every { api.logging() } returns logging
        every { api.utilities() } returns utilities
        every { utilities.base64Utils() } returns base64
        every { base64.encodeToString(any<String>()) } answers {
            Base64.getEncoder().encodeToString(firstArg<String>().toByteArray(Charsets.UTF_8))
        }
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        var closeFailure: Throwable? = null
        try {
            nativeClients.forEach { client ->
                try {
                    client.close()
                } catch (error: Throwable) {
                    if (closeFailure == null) closeFailure = error else closeFailure!!.addSuppressed(error)
                }
            }
            legacyClients.forEach { client ->
                try {
                    withTimeout(10_000) { client.close() }
                } catch (error: Throwable) {
                    if (closeFailure == null) closeFailure = error else closeFailure!!.addSuppressed(error)
                }
            }
        } finally {
            manager.shutdown()
            config.cleanup()
        }
        closeFailure?.let { throw it }
    }

    private suspend fun startServer(edition: BurpSuiteEdition = BurpSuiteEdition.COMMUNITY_EDITION) {
        every { api.burpSuite().version().edition() } returns edition
        val started = CompletableDeferred<Unit>()
        manager.start(config) { state ->
            when (state) {
                ServerState.Running -> started.complete(Unit)
                is ServerState.Failed -> started.completeExceptionally(state.exception)
                else -> Unit
            }
        }
        withTimeout(10_000) { started.await() }
    }

    private suspend fun stopServer() {
        val stopped = CompletableDeferred<Unit>()
        manager.stop { state ->
            when (state) {
                ServerState.Stopped -> stopped.complete(Unit)
                is ServerState.Failed -> stopped.completeExceptionally(state.exception)
                else -> Unit
            }
        }
        withTimeout(10_000) { stopped.await() }
    }

    private suspend fun nativeClient(port: Int = testPort): TestStreamableHttpMcpClient =
        TestStreamableHttpMcpClient().also {
            nativeClients.add(it)
            it.connectToServer("http://127.0.0.1:$port/mcp")
        }

    private suspend fun legacyClient(): TestSseMcpClient = TestSseMcpClient().also {
        legacyClients.add(it)
        withTimeout(10_000) { it.connectToServer("http://127.0.0.1:$testPort") }
    }

    private suspend fun expectSessionCount(expected: Int) {
        withTimeoutOrNull(5_000) {
            while (manager.activeSessionCount != expected) delay(10)
        }
        assertEquals(expected, manager.activeSessionCount, "Server session count did not settle within 5 seconds")
    }

    @Test
    fun `initialize notification list ping and tool result use stateless JSON responses`(): Unit = runBlocking {
        startServer()
        val initialized = streamableHttpRequest(testPort, initializeBody())
        val initializeResult = expectJsonResult(initialized, "initialize")
        assertEquals(LATEST_PROTOCOL_VERSION, initializeResult.getValue("protocolVersion").jsonPrimitive.content)
        assertEquals("burp-suite", initializeResult.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
        assertNotNull(initializeResult.getValue("capabilities").jsonObject["tools"])
        expectSessionCount(0)

        val notification = streamableHttpRequest(
            testPort,
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertEquals(202, notification.status)
        assertEquals("", notification.body)
        assertNull(notification.headers["mcp-session-id"])
        expectSessionCount(0)

        val listed = expectJsonResult(streamableHttpRequest(testPort, rpcBody("tools/list", "list")), "list")
        assertTrue(listed.getValue("tools").jsonArray.any {
            it.jsonObject.getValue("name").jsonPrimitive.content == "base64_encode"
        })
        val ping = expectJsonResult(streamableHttpRequest(testPort, rpcBody("ping", "ping")), "ping")
        assertTrue(ping.isEmpty())

        val encoded = expectJsonResult(streamableHttpRequest(testPort, encodeBody("hello")), "encode")
        assertFalse(encoded.getValue("isError").jsonPrimitive.content.toBoolean())
        assertEquals("aGVsbG8=", encoded.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        verify(exactly = 1) { base64.encodeToString("hello") }
        expectSessionCount(0)
    }

    @Test
    fun `SDK native client connects lists pings and returns exact tool content`(): Unit = runBlocking {
        startServer()
        val client = nativeClient()
        assertNull(client.sessionId)
        assertTrue(client.listTools().any { it.name == "base64_encode" })
        assertNotNull(client.ping())
        expectText(client.callTool("base64_encode", mapOf("content" to "hello")), "aGVsbG8=")
        expectSessionCount(0)
    }

    @Test
    fun `community native and legacy catalogs including schemas are equal`(): Unit = runBlocking {
        expectCatalogEquality(BurpSuiteEdition.COMMUNITY_EDITION)
    }

    @Test
    fun `professional native and legacy catalogs including schemas are equal`(): Unit = runBlocking {
        expectCatalogEquality(BurpSuiteEdition.PROFESSIONAL)
    }

    private suspend fun expectCatalogEquality(edition: BurpSuiteEdition) {
        startServer(edition)
        val native = nativeClient()
        val legacy = legacyClient()
        val nativeTools = native.listTools().associateBy { it.name }
        val legacyTools = legacy.listTools().associateBy { it.name }
        assertFalse(nativeTools.isEmpty())
        assertEquals(legacyTools, nativeTools, "Names, descriptions and complete input schemas must match")
        val professionalTools = setOf("get_scanner_issues", "generate_collaborator_payload", "get_collaborator_interactions")
        professionalTools.forEach { name ->
            assertEquals(edition == BurpSuiteEdition.PROFESSIONAL, name in nativeTools, name)
        }
        expectText(native.callTool("base64_encode", mapOf("content" to "hello")), "aGVsbG8=")
        expectText(legacy.callTool("base64_encode", mapOf("content" to "hello")), "aGVsbG8=")
        expectSessionCount(1)
    }

    @Test
    fun `GET and DELETE reject unsupported stateless methods without creating sessions`(): Unit = runBlocking {
        startServer()
        for (method in listOf("GET", "DELETE")) {
            val response = streamableHttpRequest(
                testPort,
                method = method,
                headers = mapOf("Mcp-Session-Id" to "unrecognized-session", "Last-Event-ID" to "event-1"),
            )
            assertEquals(405, response.status, method)
            assertEquals("POST", response.headers["allow"], method)
            assertNull(response.headers["mcp-session-id"])
        }
        expectSessionCount(0)
        expectJsonResult(streamableHttpRequest(testPort, rpcBody("ping", "after-methods")), "after-methods")
    }

    @Test
    fun `media ranges and protocol headers follow the pinned SDK contract`(): Unit = runBlocking {
        startServer()
        for (accept in listOf("application/json", "text/event-stream", "application/jsonp, text/event-stream",
            "application/json, text/event-stream;q=0")) {
            val response = streamableHttpRequest(testPort, encodeBody("hello"), headers = mapOf("Accept" to accept))
            expectHttpError(response, 406)
            expectSessionCount(0)
        }
        for (contentType in listOf<String?>("text/plain", null)) {
            val response = streamableHttpRequest(testPort, encodeBody("hello"), headers = mapOf("Content-Type" to contentType))
            expectHttpError(response, 415)
            expectSessionCount(0)
        }
        val invalidVersion = streamableHttpRequest(
            testPort,
            encodeBody("hello"),
            headers = mapOf("Mcp-Protocol-Version" to "2099-01-01"),
        )
        expectHttpError(invalidVersion, 400)
        assertEquals("encode", invalidVersion.json().getValue("id").jsonPrimitive.content)
        assertTrue(invalidVersion.json().getValue("error").jsonObject.getValue("message").jsonPrimitive.content.contains("Unsupported protocol version"))
        verify(exactly = 0) { base64.encodeToString(any<String>()) }
        expectSessionCount(0)

        // Absent Accept means no preference; stateless requests need no session or prior initialize.
        for (accept in listOf<String?>(null, "*/*", "application/*, text/*",
            "application/json;q=1, text/event-stream;q=1")) {
            expectJsonResult(streamableHttpRequest(
                testPort,
                rpcBody("ping", "permissive"),
                headers = mapOf(
                    "Accept" to accept,
                    "Content-Type" to "application/json; charset=utf-8",
                    "Mcp-Protocol-Version" to null,
                    "Mcp-Session-Id" to "ignored-by-stateless-endpoint",
                ),
            ), "permissive")
            expectSessionCount(0)
        }
    }

    @Test
    fun `malformed invalid oversized and tool error bodies preserve JSON errors and cleanup`(): Unit = runBlocking {
        startServer()
        val malformed = streamableHttpRequest(testPort, "{")
        expectHttpError(malformed, 400, -32700)
        expectSessionCount(0)
        val invalidBody = streamableHttpRequest(testPort, "42")
        expectHttpError(invalidBody, 400, -32600)
        expectSessionCount(0)
        val oversized = streamableHttpRequest(testPort, " ".repeat(4 * 1024 * 1024) + "{}")
        expectHttpError(oversized, 413, -32600)
        expectSessionCount(0)

        val unknown = streamableHttpRequest(testPort, rpcBody("does/not/exist", "unknown"))
        assertEquals(200, unknown.status)
        assertEquals("unknown", unknown.json().getValue("id").jsonPrimitive.content)
        assertEquals(-32601, unknown.json().getValue("error").jsonObject.getValue("code").jsonPrimitive.int)
        expectSessionCount(0)

        val missingArguments = expectJsonResult(
            streamableHttpRequest(testPort, rpcBody("tools/call", "invalid-tool", buildJsonObject {
                put("name", "base64_encode")
                put("arguments", JsonObject(emptyMap()))
            })),
            "invalid-tool",
        )
        assertTrue(missingArguments.getValue("isError").jsonPrimitive.content.toBoolean())
        assertTrue(missingArguments.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content.startsWith("Error:"))
        verify(exactly = 0) { base64.encodeToString(any<String>()) }
        expectSessionCount(0)
        expectJsonResult(streamableHttpRequest(testPort, rpcBody("ping", "after-errors")), "after-errors")
    }

    @Test
    fun `effective security headers reject before invoking Montoya tools`(): Unit = runBlocking {
        startServer()
        val accepted = streamableHttpRequest(testPort, encodeBody("hello"), headers = mapOf(
            "Origin" to "http://localhost:$testPort",
            "Referer" to "http://127.0.0.1:$testPort/client",
        ))
        expectJsonResult(accepted, "encode")
        assertEquals("DENY", accepted.headers["x-frame-options"])
        assertEquals("nosniff", accepted.headers["x-content-type-options"])
        assertEquals("same-origin", accepted.headers["referrer-policy"])
        assertEquals("default-src 'none'", accepted.headers["content-security-policy"])
        clearMocks(api, utilities, base64, answers = false)

        val rejectedHeaders = listOf(
            mapOf("Host" to "attacker.example:$testPort"),
            mapOf("Host" to "127.0.0.1:${testPort + 1}"),
            mapOf("Origin" to "https://attacker.example"),
            mapOf("Origin" to "not an origin"),
            mapOf("Referer" to "https://attacker.example/client"),
            mapOf("Referer" to "not a referer"),
            mapOf("User-Agent" to "Mozilla/5.0"),
            mapOf("User-Agent" to "Mozilla/5.0", "Origin" to "http://localhost:$testPort"),
        )
        for (headers in rejectedHeaders) {
            val response = streamableHttpRequest(testPort, encodeBody("hello"), headers = headers)
            assertEquals(403, response.status, "Wire headers: $headers")
            expectSessionCount(0)
        }
        verify(exactly = 0) { api.utilities() }
        verify(exactly = 0) { utilities.base64Utils() }
        verify(exactly = 0) { base64.encodeToString(any<String>()) }
    }

    @Test
    fun `simultaneous clients can reuse identical request IDs without crossing responses`(): Unit = runBlocking {
        startServer()
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        every { base64.encodeToString(any<String>()) } answers {
            val content = firstArg<String>()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Concurrent tool calls were not released" }
            Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        }
        val inputs = listOf("hello", "world")
        val requests = inputs.map { content ->
            async { streamableHttpRequest(testPort, encodeBody(content, "shared-id")) }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) },
                "Both independent HTTP requests should enter their tool handlers")
        } finally {
            release.countDown()
        }
        requests.awaitAll().zip(inputs).forEach { (response, input) ->
            val result = expectJsonResult(response, "shared-id")
            assertEquals(Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8)),
                result.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        }
        verify(exactly = 1) { base64.encodeToString("hello") }
        verify(exactly = 1) { base64.encodeToString("world") }
        expectSessionCount(0)
    }

    @Test
    fun `native requests and client closes leave other native and legacy clients usable`(): Unit = runBlocking {
        startServer()
        val legacy = legacyClient()
        val first = nativeClient()
        val second = nativeClient()
        expectSessionCount(1)
        repeat(3) {
            expectJsonResult(streamableHttpRequest(testPort, rpcBody("ping", "repeat-$it")), "repeat-$it")
            expectHttpError(streamableHttpRequest(testPort, "{"), 400, -32700)
            expectHttpError(streamableHttpRequest(testPort, "42"), 400, -32600)
            expectHttpError(streamableHttpRequest(testPort, rpcBody("ping", "media"),
                headers = mapOf("Accept" to "application/json")), 406)
            expectSessionCount(1)
            assertNotNull(legacy.ping())
        }
        first.close()
        nativeClients.remove(first)
        assertNotNull(legacy.ping())
        assertNotNull(second.ping())
        expectText(second.callTool("base64_encode", mapOf("content" to "hello")), "aGVsbG8=")
        expectSessionCount(1)
        legacy.close()
        legacyClients.remove(legacy)
        expectSessionCount(0)
        assertNotNull(second.ping())
        assertFalse(second.listTools().isEmpty())
        expectSessionCount(0)
    }

    @Test
    fun `running listener snapshots its port and clean restart serves the new port`(): Unit = runBlocking {
        startServer()
        val nextPort = availablePort()
        config.port = nextPort
        // Editing persisted configuration must not change the running listener's Host validation.
        expectJsonResult(streamableHttpRequest(testPort, rpcBody("ping", "old-port")), "old-port")
        assertEquals(403, streamableHttpRequest(testPort, rpcBody("ping", "wrong-port"),
            headers = mapOf("Host" to "127.0.0.1:$nextPort")).status)
        stopServer()
        expectSessionCount(0)
        ServerSocket(testPort).use { assertEquals(testPort, it.localPort, "Stopped listener must release its port") }
        startServer()
        val restarted = nativeClient(nextPort)
        assertNotNull(restarted.ping())
        expectText(restarted.callTool("base64_encode", mapOf("content" to "hello")), "aGVsbG8=")
        expectSessionCount(0)
    }

    private fun initializeBody(): String = rpcBody("initialize", "initialize", buildJsonObject {
        put("protocolVersion", LATEST_PROTOCOL_VERSION)
        put("capabilities", JsonObject(emptyMap()))
        put("clientInfo", buildJsonObject {
            put("name", "raw-http-integration-test")
            put("version", "1.0")
        })
    })

    private fun encodeBody(content: String, id: String = "encode"): String =
        rpcBody("tools/call", id, buildJsonObject {
            put("name", "base64_encode")
            put("arguments", buildJsonObject { put("content", content) })
        })

    private fun rpcBody(method: String, id: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }.toString()

    private fun expectJsonResult(response: TestHttpResponse, id: String): JsonObject {
        assertEquals(200, response.status, response.body)
        assertEquals("application/json", response.headers.getValue("content-type").substringBefore(';'))
        assertNull(response.headers["mcp-session-id"])
        val envelope = response.json()
        assertEquals(JsonPrimitive("2.0"), envelope["jsonrpc"])
        assertEquals(JsonPrimitive(id), envelope["id"])
        assertNull(envelope["error"], response.body)
        return envelope.getValue("result").jsonObject
    }

    private fun expectHttpError(response: TestHttpResponse, status: Int, code: Int? = null) {
        assertEquals(status, response.status, response.body)
        assertEquals("application/json", response.headers.getValue("content-type").substringBefore(';'))
        assertNull(response.headers["mcp-session-id"])
        val envelope = response.json()
        assertEquals(JsonPrimitive("2.0"), envelope["jsonrpc"])
        assertNull(envelope["result"])
        val error = envelope.getValue("error").jsonObject
        assertTrue(error.getValue("message").jsonPrimitive.content.isNotEmpty())
        if (code != null) assertEquals(code, error.getValue("code").jsonPrimitive.int)
    }

    private fun expectText(result: CallToolResult?, expected: String) {
        assertNotNull(result)
        assertFalse(result!!.isError ?: false)
        assertEquals(listOf(TextContent(expected)), result.content)
    }

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
}
