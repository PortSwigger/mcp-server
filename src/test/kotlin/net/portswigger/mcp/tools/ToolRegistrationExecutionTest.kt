package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.BurpSuite
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.core.Version
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import burp.api.montoya.utilities.Base64Utils
import burp.api.montoya.utilities.Utilities
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.Called
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Timeout(20, unit = TimeUnit.SECONDS)
class ToolRegistrationExecutionTest {
    private val fixtures = mutableListOf<RegisteredTools>()
    private val professionalNames = setOf(
        "get_scanner_issues", "generate_collaborator_payload", "get_collaborator_interactions",
    )

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        fixtures.forEach { fixture ->
            fixture.owner.close()
            try {
                withTimeout(5_000) { fixture.server.close() }
            } finally {
                fixture.config.cleanup()
            }
        }
    }

    @Test
    fun `every community handler rejects closed owner before Montoya interaction`(): Unit = runBlocking {
        expectClosedHandlers(BurpSuiteEdition.COMMUNITY_EDITION)
    }

    @Test
    fun `every professional handler including exports scanner and Collaborator rejects closed owner`(): Unit = runBlocking {
        expectClosedHandlers(BurpSuiteEdition.PROFESSIONAL)
    }

    private suspend fun expectClosedHandlers(edition: BurpSuiteEdition) {
        val fixture = fixture(edition)
        val expectedNames = if (edition == BurpSuiteEdition.PROFESSIONAL) {
            requestExamples.keys
        } else {
            requestExamples.keys - professionalNames
        }
        assertEquals(expectedNames, fixture.server.tools.keys, "Every registered handler needs a valid request example")
        fixture.owner.close()
        clearMocks(fixture.api, fixture.burpSuite, fixture.version, answers = false)

        val missedFence = linkedMapOf<String, String>()
        for (name in fixture.server.tools.keys.sorted()) {
            val attempt = runCatching { invoke(fixture, name) }
            val failure = attempt.exceptionOrNull()
            if (failure !is CancellationException) {
                missedFence[name] = failure?.let { "${it.javaClass.simpleName}: ${it.message}" }
                    ?: "returned ${attempt.getOrThrow()}"
            }
        }
        assertTrue(missedFence.isEmpty(), "Handlers that did not reject the closed shared owner: $missedFence")
        verify { fixture.api wasNot Called }
        verify { fixture.burpSuite wasNot Called }
        verify { fixture.version wasNot Called }
    }

    @Test
    fun `different registered tool names share aggregate capacity and recover after completion`(): Unit = runBlocking {
        val fixture = fixture(BurpSuiteEdition.PROFESSIONAL, maxConcurrentTools = 1)
        val utilities = mockk<Utilities>()
        val base64 = mockk<Base64Utils>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { fixture.api.utilities() } returns utilities
        every { utilities.base64Utils() } returns base64
        every { base64.encodeToString("hello") } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Base64 operation was not released" }
            "aGVsbG8="
        }
        every { fixture.burpSuite.exportUserOptionsAsJson() } returns "{}"
        val first = async(Dispatchers.Default) { invoke(fixture, "base64_encode") }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })

            val rejection = runCatching { invoke(fixture, "output_user_options") }.exceptionOrNull()
            assertInstanceOf(ToolCapacityExceededException::class.java, rejection,
                "A different registered tool must observe the same admission limit")
            verify(exactly = 0) { fixture.burpSuite.exportUserOptionsAsJson() }

            release.countDown()
            expectText(withTimeout(5_000) { first.await() }, "aGVsbG8=")
            expectText(invoke(fixture, "output_user_options"), "{}")
            verify(exactly = 1) { base64.encodeToString("hello") }
            verify(exactly = 1) { fixture.burpSuite.exportUserOptionsAsJson() }
        } finally {
            release.countDown()
            withTimeout(5_000) { first.join() }
        }
    }

    private fun fixture(edition: BurpSuiteEdition, maxConcurrentTools: Int = 32): RegisteredTools =
        RegisteredTools(edition, maxConcurrentTools).also { fixtures.add(it) }

    private suspend fun invoke(fixture: RegisteredTools, name: String): CallToolResult {
        val registered = fixture.server.tools.getValue(name)
        val request = CallToolRequest(CallToolRequestParams(
            name = name,
            arguments = requestExamples.getValue(name).jsonObject,
        ))
        return registered.handler.invoke(fixture.connection, request)
    }

    private fun expectText(result: CallToolResult, expected: String) {
        assertFalse(result.isError ?: false)
        assertEquals(listOf(TextContent(expected)), result.content)
    }

    private class RegisteredTools(edition: BurpSuiteEdition, maxConcurrentTools: Int) {
        val api = mockk<MontoyaApi>()
        val burpSuite = mockk<BurpSuite>()
        val version = mockk<Version>()
        val connection = mockk<ClientConnection>()
        val owner = ToolExecution(maxConcurrentTools)
        private val logging = mockk<Logging>(relaxed = true)
        private val storage = mockk<PersistedObject>(relaxed = true).apply {
            every { getBoolean(any()) } returns null
            every { getString(any()) } returns null
            every { getInteger(any()) } returns null
            every { getBoolean("configEditingTooling") } returns true
            every { getBoolean("requireHttpRequestApproval") } returns false
            every { getBoolean("requireDataAccessApproval") } returns false
        }
        val config = McpConfig(storage, logging)
        val server = Server(
            Implementation("registered-tool-execution-test", "1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )

        init {
            every { api.burpSuite() } returns burpSuite
            every { burpSuite.version() } returns version
            every { version.edition() } returns edition
            server.registerTools(api, config, owner)
        }
    }

    // Valid client requests, independent of the registration/wrapper implementation.
    private val requestExamples: JsonObject = Json.parseToJsonElement("""
        {
          "send_http1_request": {"content":"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n","targetHostname":"example.test","targetPort":443,"usesHttps":true},
          "send_http2_request": {"pseudoHeaders":{"method":"GET","path":"/","scheme":"https","authority":"example.test"},"headers":{},"requestBody":"","targetHostname":"example.test","targetPort":443,"usesHttps":true},
          "create_repeater_tab": {"tabName":null,"content":"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n","targetHostname":"example.test","targetPort":443,"usesHttps":true},
          "create_repeater_tab_http2": {"tabName":null,"pseudoHeaders":{"method":"GET","path":"/","scheme":"https","authority":"example.test"},"headers":{},"requestBody":"","targetHostname":"example.test","targetPort":443,"usesHttps":true},
          "send_to_intruder": {"tabName":null,"content":"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n","targetHostname":"example.test","targetPort":443,"usesHttps":true},
          "url_encode": {"content":"hello world"},
          "url_decode": {"content":"hello%20world"},
          "base64_encode": {"content":"hello"},
          "base64_decode": {"content":"aGVsbG8="},
          "generate_random_string": {"length":8,"characterSet":"abc"},
          "output_project_options": {},
          "output_user_options": {},
          "set_project_options": {"json":"{}"},
          "set_user_options": {"json":"{}"},
          "get_scanner_issues": {"count":1,"offset":0},
          "generate_collaborator_payload": {"customData":null},
          "get_collaborator_interactions": {"payloadId":null},
          "get_proxy_http_history": {"count":1,"offset":0},
          "get_proxy_http_history_regex": {"regex":".*","count":1,"offset":0},
          "get_organizer_items": {"count":1,"offset":0},
          "get_organizer_items_regex": {"regex":".*","count":1,"offset":0},
          "get_proxy_websocket_history": {"count":1,"offset":0},
          "get_proxy_websocket_history_regex": {"regex":".*","count":1,"offset":0},
          "set_task_execution_engine_state": {"running":true},
          "set_proxy_intercept_state": {"intercepting":true},
          "get_active_editor_contents": {},
          "set_active_editor_contents": {"text":"hello"}
        }
    """.trimIndent()).jsonObject
}
