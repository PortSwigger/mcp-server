package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
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
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.security.DataAccessApprovalHandler
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.UserApprovalHandler
import net.portswigger.mcp.security.SwingDataAccessApprovalHandler
import net.portswigger.mcp.security.SwingUserApprovalHandler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.ResourceLocks
import java.awt.EventQueue
import java.awt.SecondaryLoop
import java.awt.Toolkit
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JDialog

/** Exercises cancellation through the real Netty listener and SDK transport. */
@Timeout(60, unit = TimeUnit.SECONDS)
@ResourceLocks(
    ResourceLock("DataAccessApprovalHandler"),
    ResourceLock("HttpRequestApprovalHandler"),
    ResourceLock("SwingDialogs"),
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
    fun `native close and reset cancel HTTP approval and late Allow cannot send`(): Unit = runBlocking {
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
                        expectApprovalCancelled(fixture, pending)
                        assertEquals(0, fixture.protectedEffects.get())

                        pending.answer.complete(true)
                        assertEquals(0, fixture.protectedEffects.get(), "Late Allow must not send after the native client left")
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
    fun `native close and reset cancel data approval and late Allow cannot read`(): Unit = runBlocking {
        for (reset in listOf(false, true)) {
            val fixture = Fixture()
            Approvals().use { approvals ->
                var socket: Socket? = null
                try {
                    fixture.start()
                    socket = openPendingStreamableHttpRequest(fixture.port, historyBody())
                    val pending = approvals.next()
                    disconnect(socket, reset)
                    expectApprovalCancelled(fixture, pending)
                    assertEquals(0, fixture.protectedEffects.get())

                    pending.answer.complete(true)
                    assertEquals(0, fixture.protectedEffects.get(), "Late Allow must not read after the native client left")
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
    fun `native disconnect disposes Swing approvals and ignores late Always Allow`(): Unit = runBlocking {
        val previousHttp = HttpRequestSecurity.approvalHandler
        val previousData = DataAccessSecurity.approvalHandler
        HttpRequestSecurity.approvalHandler = SwingUserApprovalHandler()
        DataAccessSecurity.approvalHandler = SwingDataAccessApprovalHandler()
        mockkObject(Dialogs)
        try {
            withHttpFactories {
                for (body in listOf(httpBody(), historyBody())) {
                    for (reset in listOf(false, true)) {
                        val fixture = Fixture()
                        val shown = CompletableDeferred<Unit>()
                        val disposed = CompletableDeferred<Unit>()
                        val dialog = mockk<JDialog>()
                        var loop: SecondaryLoop? = null
                        every { dialog.dispose() } answers {
                            assertTrue(EventQueue.isDispatchThread())
                            disposed.complete(Unit)
                            loop?.exit()
                            Unit
                        }
                        every { Dialogs.showOptionDialog(any(), any(), any(), any(), any(), any()) } answers {
                            assertTrue(EventQueue.isDispatchThread())
                            loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
                            assertTrue(arg<(JDialog) -> Boolean>(5).invoke(dialog))
                            shown.complete(Unit)
                            assertTrue(loop!!.enter())
                            1 // A stale Always Allow result after cancellation disposes the dialog.
                        }
                        var socket: Socket? = null
                        try {
                            fixture.start()
                            socket = openPendingStreamableHttpRequest(fixture.port, body)
                            withTimeout(5_000) { shown.await() }
                            disconnect(socket, reset)
                            withTimeout(5_000) { disposed.await() }
                            fixture.expectSessionCount(0)
                            assertEquals(0, fixture.protectedEffects.get())
                            assertTrue(fixture.config.getAutoApproveTargetsList().isEmpty())
                            assertFalse(fixture.config.alwaysAllowHttpHistory)
                            fixture.expectUtilityWorks()
                        } finally {
                            socket?.close()
                            EventQueue.invokeLater { dialog.dispose() }
                            fixture.close()
                            javax.swing.SwingUtilities.invokeAndWait { }
                        }
                    }
                }
            }
        } finally {
            HttpRequestSecurity.approvalHandler = previousHttp
            DataAccessSecurity.approvalHandler = previousData
            unmockkObject(Dialogs)
        }
    }

    @Test
    fun `separate cancellation POST cannot cancel stateless calls with the same request ID`(): Unit = runBlocking {
        val fixture = Fixture()
        val sockets = mutableListOf<Socket>()
        Approvals().use { approvals ->
            try {
                fixture.start()
                sockets += openPendingStreamableHttpRequest(fixture.port, historyBody("shared-id"))
                val first = approvals.next()
                sockets += openPendingStreamableHttpRequest(fixture.port, historyBody("shared-id"))
                val second = approvals.next()
                val cancellation = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/cancelled")
                    put("params", buildJsonObject { put("requestId", "shared-id") })
                }
                assertEquals(202, streamableHttpRequest(fixture.port, cancellation.toString()).status)
                assertNull(withTimeoutOrNull(500) { first.finished.await() })
                assertTrue(first.job.isActive)
                assertTrue(second.job.isActive)
                fixture.expectSessionCount(2)

                disconnect(sockets.first(), reset = true)
                withTimeout(5_000) { first.finished.await() }
                assertTrue(first.job.isCancelled)
                assertTrue(second.job.isActive, "Disconnecting one client must not cancel another request with its ID")
                fixture.expectSessionCount(1)
                first.answer.complete(true)
                second.answer.complete(false)
                withTimeout(5_000) { second.finished.await() }
                fixture.expectSessionCount(0)
                assertEquals(0, fixture.protectedEffects.get())
                fixture.expectUtilityWorks()
            } finally {
                sockets.forEach { it.close() }
                fixture.close()
            }
        }
    }

    @Test
    fun `owner stop cancels connected HTTP and data approvals and late Allow has no effect`(): Unit = runBlocking {
        withHttpFactories {
            val fixture = Fixture()
            val sockets = mutableListOf<Socket>()
            Approvals().use { approvals ->
                try {
                    fixture.start()
                    sockets += openPendingStreamableHttpRequest(fixture.port, httpBody())
                    val http = approvals.next()
                    sockets += openPendingStreamableHttpRequest(fixture.port, historyBody())
                    val data = approvals.next()
                    assertTrue(http.job.isActive)
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
    fun `disconnect releases capacity while other approvals and overflow tool errors remain intact`(): Unit = runBlocking {
        val fixture = Fixture()
        val sockets = mutableListOf<Socket>()
        val pending = mutableListOf<ApprovalWait>()
        Approvals().use { approvals ->
            try {
                fixture.start()
                sockets += openPendingStreamableHttpRequest(fixture.port, historyBody("disconnected"))
                pending += approvals.next()
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

                disconnect(sockets.first(), reset = true)
                withTimeout(5_000) { pending.first().finished.await() }
                assertTrue(pending.first().job.isCancelled)
                pending.first().answer.complete(true)
                fixture.expectSessionCount(31)
                assertTrue(pending.drop(1).all { it.job.isActive })
                fixture.expectUtilityWorks()
                fixture.stop()
                pending.drop(1).forEach { approval ->
                    withTimeout(5_000) { approval.finished.await() }
                    assertTrue(approval.job.isCancelled)
                    approval.answer.complete(true)
                }
                assertEquals(0, fixture.protectedEffects.get())
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

    private suspend fun expectApprovalCancelled(fixture: Fixture, pending: ApprovalWait) {
        withTimeout(5_000) { pending.finished.await() }
        assertTrue(pending.job.isCancelled)
        fixture.expectSessionCount(0)
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
