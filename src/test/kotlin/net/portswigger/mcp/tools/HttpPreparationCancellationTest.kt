package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.Http
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.UserApprovalHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@ResourceLock("HttpRequestApprovalHandler")
@Timeout(20, unit = TimeUnit.SECONDS)
class HttpPreparationCancellationTest {
    @Test
    fun `HTTP1 owner close during approved request preparation prevents send`(): Unit = runBlocking {
        expectNoSendAfterPreparation("send_http1_request")
    }

    @Test
    fun `HTTP2 owner close during approved request preparation prevents send`(): Unit = runBlocking {
        expectNoSendAfterPreparation("send_http2_request")
    }

    private suspend fun expectNoSendAfterPreparation(name: String) = kotlinx.coroutines.coroutineScope {
        val api = mockk<MontoyaApi>(relaxed = true)
        every { api.burpSuite().version().edition() } returns BurpSuiteEdition.COMMUNITY_EDITION
        val storage = mockk<PersistedObject>(relaxed = true).apply {
            every { getBoolean(any()) } returns null
            every { getString(any()) } returns null
            every { getInteger(any()) } returns null
        }
        val config = McpConfig(storage, mockk<Logging>(relaxed = true))
        val owner = ToolExecution(maxConcurrentTools = 1)
        val server = Server(
            Implementation("http-preparation-cancellation-test", "1.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )
        val previousApproval = HttpRequestSecurity.approvalHandler
        val approval = mockk<UserApprovalHandler>()
        val http = mockk<Http>(relaxed = true)
        val request = mockk<HttpRequest>()
        val service = mockk<HttpService>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        mockkStatic(HttpService::class)
        mockkStatic(HttpHeader::class)
        mockkStatic(HttpRequest::class)
        try {
            HttpRequestSecurity.approvalHandler = approval
            coEvery { approval.requestApproval(any(), any(), config, any(), api) } returns true
            every { api.http() } returns http
            every { HttpService.httpService("example.test", 443, true) } returns service
            every { HttpHeader.httpHeader(any<String>(), any<String>()) } returns mockk<HttpHeader>()
            val prepare: () -> HttpRequest = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "Request preparation was not released" }
                request
            }
            every { HttpRequest.httpRequest(service, any<String>()) } answers { prepare() }
            every { HttpRequest.http2Request(service, any<List<HttpHeader>>(), any<String>()) } answers { prepare() }
            server.registerTools(api, config, owner)
            val arguments = Json.parseToJsonElement(if (name == "send_http1_request") {
                """{"content":"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n","targetHostname":"example.test","targetPort":443,"usesHttps":true}"""
            } else {
                """{"pseudoHeaders":{"method":"GET","path":"/","scheme":"https","authority":"example.test"},"headers":{},"requestBody":"","targetHostname":"example.test","targetPort":443,"usesHttps":true}"""
            }).jsonObject
            val call = CallToolRequest(CallToolRequestParams(name = name, arguments = arguments))
            val result = async(Dispatchers.Default) {
                runCatching { server.tools.getValue(name).handler.invoke(mockk<ClientConnection>(), call) }
            }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                coVerify(exactly = 1) { approval.requestApproval("example.test", 443, config, any(), api) }
                owner.close()
                assertFalse(owner.isOpen)
                assertEquals(1, owner.activeCount, "The blocked preparation remains unfinished until released")
                release.countDown()

                assertInstanceOf(CancellationException::class.java, withTimeout(5_000) { result.await() }.exceptionOrNull())
                assertEquals(0, owner.activeCount)
                verify(exactly = 0) { api.http() }
                verify(exactly = 0) { http.sendRequest(any<HttpRequest>()) }
                verify(exactly = 0) { http.sendRequest(any<HttpRequest>(), HttpMode.HTTP_2) }
            } finally {
                release.countDown()
                owner.close()
                withTimeout(5_000) { result.join() }
            }
        } finally {
            release.countDown()
            owner.close()
            HttpRequestSecurity.approvalHandler = previousApproval
            try {
                withTimeout(5_000) { server.close() }
            } finally {
                config.cleanup()
                unmockkStatic(HttpRequest::class)
                unmockkStatic(HttpHeader::class)
                unmockkStatic(HttpService::class)
            }
        }
    }
}
