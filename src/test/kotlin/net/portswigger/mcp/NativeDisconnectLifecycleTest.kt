package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessApprovalHandler
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.UserApprovalHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.ResourceLocks
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Characterizes the actual pinned Netty/SDK graph, without installing a newer cancellation plugin. */
@Timeout(60, unit = TimeUnit.SECONDS)
@ResourceLocks(
    ResourceLock("DataAccessApprovalHandler"),
    ResourceLock("HttpRequestApprovalHandler"),
)
class NativeDisconnectLifecycleTest {
    private class ApprovalWait(val job: Job) {
        val answer = CompletableDeferred<Boolean>()
        val finished = CompletableDeferred<Unit>()
    }

    /** This wait is cancellable, just like the production dialog continuation. */
    private class Approvals : AutoCloseable {
        private val previousHttp = HttpRequestSecurity.approvalHandler
        private val previousData = DataAccessSecurity.approvalHandler
        private val entered = Channel<ApprovalWait>(Channel.UNLIMITED)

        init {
            HttpRequestSecurity.approvalHandler = object : UserApprovalHandler {
                override suspend fun requestApproval(
                    hostname: String, port: Int, config: McpConfig, requestContent: String?, api: MontoyaApi?,
                ): Boolean = awaitAnswer()
            }
            DataAccessSecurity.approvalHandler = object : DataAccessApprovalHandler {
                override suspend fun requestDataAccess(accessType: DataAccessType, config: McpConfig): Boolean =
                    awaitAnswer()
            }
        }

        private suspend fun awaitAnswer(): Boolean {
            val wait = ApprovalWait(currentCoroutineContext().job)
            entered.send(wait)
            try {
                return wait.answer.await()
            } finally {
                wait.finished.complete(Unit)
            }
        }

        suspend fun next(): ApprovalWait = withTimeout(5_000) { entered.receive() }

        override fun close() {
            HttpRequestSecurity.approvalHandler = previousHttp
            DataAccessSecurity.approvalHandler = previousData
            entered.close()
        }
    }

    private class Fixture {
        val api = mockk<MontoyaApi>(relaxed = true)
        private val logging = mockk<Logging>(relaxed = true)
        private val values = ConcurrentHashMap<String, Any>()
        val port = ServerSocket(0).use { it.localPort }
        val protectedEffects = AtomicInteger()
        val effectEntered = Channel<Unit>(Channel.UNLIMITED)
        val config: McpConfig
        val manager = KtorServerManager(api)

        init {
            values["port"] = port
            val storage = mockk<PersistedObject>(relaxed = true) {
                every { getBoolean(any()) } answers { values[firstArg<String>()] as? Boolean }
                every { getString(any()) } answers { values[firstArg<String>()] as? String }
                every { getInteger(any()) } answers { values[firstArg<String>()] as? Int }
                every { setBoolean(any(), any()) } answers { values[firstArg<String>()] = secondArg<Boolean>() }
                every { setString(any(), any()) } answers { values[firstArg<String>()] = secondArg<String>() }
                every { setInteger(any(), any()) } answers { values[firstArg<String>()] = secondArg<Int>() }
            }
            every { api.logging() } returns logging
            every { api.burpSuite().version().edition() } returns BurpSuiteEdition.COMMUNITY_EDITION
            every { api.utilities().base64Utils().encodeToString("hello") } returns "aGVsbG8="
            every { api.proxy().history() } answers {
                protectedEffects.incrementAndGet()
                effectEntered.trySend(Unit).getOrThrow()
                emptyList()
            }
            every { api.http().sendRequest(any<HttpRequest>()) } answers {
                protectedEffects.incrementAndGet()
                effectEntered.trySend(Unit).getOrThrow()
                null
            }
            config = McpConfig(storage, logging)
        }

        suspend fun start() {
            val state = CompletableDeferred<ServerState>()
            manager.start(config) {
                if (it is ServerState.Running || it is ServerState.Failed) state.complete(it)
            }
            assertEquals(ServerState.Running, withTimeout(10_000) { state.await() })
        }

        suspend fun stop() {
            val state = CompletableDeferred<ServerState>()
            manager.stop {
                if (it is ServerState.Stopped || it is ServerState.Failed) state.complete(it)
            }
            assertEquals(ServerState.Stopped, withTimeout(15_000) { state.await() })
        }

        suspend fun expectSessionCount(expected: Int) = withTimeout(5_000) {
            while (manager.activeSessionCount != expected) delay(10)
        }

        suspend fun expectUtilityWorks() {
            val result = resultOf(streamableHttpRequest(port, utilityBody()))
            assertFalse(result.getValue("isError").jsonPrimitive.content.toBoolean())
            assertEquals("aGVsbG8=", result.getValue("content").jsonArray.single()
                .jsonObject.getValue("text").jsonPrimitive.content)
        }

        fun close() {
            manager.shutdown()
            config.cleanup()
            effectEntered.close()
        }
    }

    @Test
    fun `HTTP approval survives native close and reset and late Allow still sends`(): Unit = runBlocking {
        withHttpFactories {
            for (reset in listOf(false, true)) {
                val fixture = Fixture()
                Approvals().use { approvals ->
                    var socket: Socket? = null
                    try {
                        fixture.start()
                        socket = openPendingStreamableHttpRequest(fixture.port, httpBody())
                        val pending = approvals.next()
                        disconnect(socket, reset)
                        expectApprovalStillPending(fixture, pending)
                        assertEquals(0, fixture.protectedEffects.get())

                        pending.answer.complete(true)
                        withTimeout(5_000) { fixture.effectEntered.receive() }
                        withTimeout(5_000) { pending.finished.await() }
                        fixture.expectSessionCount(0)
                        assertEquals(1, fixture.protectedEffects.get(), "Late Allow can send after the native client left")
                        assertTrue(fixture.config.getAutoApproveTargetsList().isEmpty())
                        fixture.expectUtilityWorks()
                    } finally {
                        socket?.close()
                        fixture.close()
                    }
                }
            }
        }
    }

    @Test
    fun `data approval survives native close and reset and late Allow still reads`(): Unit = runBlocking {
        for (reset in listOf(false, true)) {
            val fixture = Fixture()
            Approvals().use { approvals ->
                var socket: Socket? = null
                try {
                    fixture.start()
                    socket = openPendingStreamableHttpRequest(fixture.port, historyBody())
                    val pending = approvals.next()
                    disconnect(socket, reset)
                    expectApprovalStillPending(fixture, pending)
                    assertEquals(0, fixture.protectedEffects.get())

                    pending.answer.complete(true)
                    withTimeout(5_000) { fixture.effectEntered.receive() }
                    withTimeout(5_000) { pending.finished.await() }
                    fixture.expectSessionCount(0)
                    assertEquals(1, fixture.protectedEffects.get(), "Late Allow can read after the native client left")
                    assertFalse(fixture.config.alwaysAllowHttpHistory)
                    fixture.expectUtilityWorks()
                } finally {
                    socket?.close()
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `owner stop cancels disconnected HTTP and data approvals and late Allow has no effect`(): Unit = runBlocking {
        withHttpFactories {
            val fixture = Fixture()
            val sockets = mutableListOf<Socket>()
            Approvals().use { approvals ->
                try {
                    fixture.start()
                    sockets += openPendingStreamableHttpRequest(fixture.port, httpBody())
                    val http = approvals.next()
                    disconnect(sockets.last(), reset = true)
                    sockets += openPendingStreamableHttpRequest(fixture.port, historyBody())
                    val data = approvals.next()
                    disconnect(sockets.last(), reset = false)
                    expectApprovalStillPending(fixture, http)
                    assertTrue(data.job.isActive)
                    fixture.expectSessionCount(2)

                    fixture.stop()
                    listOf(http, data).forEach { pending ->
                        withTimeout(5_000) { pending.finished.await() }
                        assertTrue(pending.job.isCancelled)
                        pending.answer.complete(true)
                    }
                    assertEquals(0, fixture.protectedEffects.get())
                    assertFalse(fixture.config.alwaysAllowHttpHistory)
                    assertTrue(fixture.config.getAutoApproveTargetsList().isEmpty())
                    assertEquals(0, fixture.manager.activeSessionCount)
                    ServerSocket(fixture.port).use { assertEquals(fixture.port, it.localPort) }
                    fixture.start()
                    fixture.expectUtilityWorks()
                } finally {
                    sockets.forEach { it.close() }
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun `disconnected approval retains capacity and typed and named overflow remain wire tool errors`(): Unit = runBlocking {
        val fixture = Fixture()
        val sockets = mutableListOf<Socket>()
        val pending = mutableListOf<ApprovalWait>()
        Approvals().use { approvals ->
            try {
                fixture.start()
                sockets += openPendingStreamableHttpRequest(fixture.port, historyBody("disconnected"))
                pending += approvals.next()
                disconnect(sockets.first(), reset = true)
                expectApprovalStillPending(fixture, pending.first())
                repeat(31) { index ->
                    sockets += openPendingStreamableHttpRequest(fixture.port, historyBody("held-$index"))
                    pending += approvals.next()
                }
                fixture.expectSessionCount(32)
                for (body in listOf(utilityBody(), toolBody("output_user_options", "named-overflow"))) {
                    val overflow = resultOf(streamableHttpRequest(fixture.port, body))
                    assertTrue(overflow.getValue("isError").jsonPrimitive.content.toBoolean())
                    assertTrue(overflow.getValue("content").jsonArray.single().jsonObject
                        .getValue("text").jsonPrimitive.content.contains("too many active tool calls"))
                }

                pending.first().answer.complete(true)
                withTimeout(5_000) { fixture.effectEntered.receive() }
                fixture.expectSessionCount(31)
                fixture.expectUtilityWorks()
                fixture.stop()
                pending.drop(1).forEach { approval ->
                    withTimeout(5_000) { approval.finished.await() }
                    assertTrue(approval.job.isCancelled)
                    approval.answer.complete(true)
                }
                assertEquals(1, fixture.protectedEffects.get())
                assertEquals(0, fixture.manager.activeSessionCount)
            } finally {
                sockets.forEach { it.close() }
                fixture.close()
            }
        }
    }

    @Test
    fun `stopping a disconnected synchronous call releases port while already started work can finish later`(): Unit = runBlocking {
        val fixture = Fixture()
        val entered = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        every { fixture.api.utilities().base64Utils().encodeToString("blocked") } answers {
            entered.complete(Unit)
            check(release.await(30, TimeUnit.SECONDS)) { "Blocked synchronous tool was not released" }
            finished.complete(Unit)
            "completed after stop"
        }
        var socket: Socket? = null
        try {
            fixture.start()
            socket = openPendingStreamableHttpRequest(fixture.port, utilityBody("blocked"))
            withTimeout(5_000) { entered.await() }
            disconnect(socket, reset = true)
            fixture.expectSessionCount(1)
            assertFalse(finished.isCompleted)

            fixture.stop()
            assertFalse(finished.isCompleted, "Stopping must not assume an arbitrary synchronous API was interrupted")
            ServerSocket(fixture.port).use { assertEquals(fixture.port, it.localPort) }
            fixture.start()
            fixture.expectUtilityWorks()
            release.countDown()
            withTimeout(5_000) { finished.await() }
            fixture.expectSessionCount(0)
        } finally {
            release.countDown()
            socket?.close()
            fixture.close()
        }
    }

    private suspend fun expectApprovalStillPending(fixture: Fixture, pending: ApprovalWait) {
        // A real FIN/RST has been sent. Observe the deployment's cancellation behavior before answering.
        assertNull(withTimeoutOrNull(500) { pending.finished.await() },
            "The pinned deployment unexpectedly canceled a native approval on socket disconnect")
        assertTrue(pending.job.isActive)
        assertTrue(fixture.manager.activeSessionCount > 0)
        fixture.expectUtilityWorks()
    }

    private fun disconnect(socket: Socket, reset: Boolean) {
        if (reset) socket.setSoLinger(true, 0)
        socket.close()
    }

    private suspend fun withHttpFactories(block: suspend () -> Unit) {
        mockkStatic(HttpService::class, HttpRequest::class)
        try {
            every { HttpService.httpService(any<String>(), any<Int>(), any<Boolean>()) } returns mockk(relaxed = true)
            every { HttpRequest.httpRequest(any<HttpService>(), any<String>()) } returns mockk(relaxed = true)
            block()
        } finally {
            unmockkStatic(HttpService::class, HttpRequest::class)
        }
    }

    private companion object {
        fun toolBody(name: String, id: String, arguments: JsonObject = JsonObject(emptyMap())): String =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", "tools/call")
                put("params", buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                })
            }.toString()

        fun historyBody(id: String = "history"): String = toolBody("get_proxy_http_history", id, buildJsonObject {
            put("count", 1)
            put("offset", 0)
        })

        fun utilityBody(content: String = "hello"): String = toolBody("base64_encode", "utility", buildJsonObject {
            put("content", content)
        })

        fun httpBody(): String = toolBody("send_http1_request", "http", buildJsonObject {
            put("targetHostname", "example.test")
            put("targetPort", 80)
            put("usesHttps", false)
            put("content", "GET / HTTP/1.1\r\nHost: example.test\r\n\r\n")
        })

        fun resultOf(response: TestHttpResponse): JsonObject {
            assertEquals(200, response.status, response.body)
            val envelope = response.json()
            assertNull(envelope["error"], response.body)
            return envelope.getValue("result").jsonObject
        }
    }
}
