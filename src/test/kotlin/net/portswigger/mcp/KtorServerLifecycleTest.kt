package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessApprovalHandler
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.BufferedInputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

@Timeout(45, unit = TimeUnit.SECONDS)
class KtorServerLifecycleTest {
    private class Fixture(executor: ExecutorService? = null, requestedPort: Int? = null) {
        val api = mockk<MontoyaApi>(relaxed = true)
        val logging = mockk<Logging>(relaxed = true)
        private val values = ConcurrentHashMap<String, Any>()
        val port = requestedPort ?: ServerSocket(0).use { it.localPort }
        val config: McpConfig
        val manager: KtorServerManager

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
            config = McpConfig(storage, logging)
            manager = if (executor == null) KtorServerManager(api) else KtorServerManager(api, executor)
        }

        suspend fun start(): ServerState {
            val result = CompletableDeferred<ServerState>()
            manager.start(config) { state ->
                if (state is ServerState.Running || state is ServerState.Failed) result.complete(state)
            }
            return withTimeout(10_000) { result.await() }
        }

        suspend fun stop() {
            val result = CompletableDeferred<ServerState>()
            manager.stop { state ->
                if (state is ServerState.Stopped || state is ServerState.Failed) result.complete(state)
            }
            assertEquals(ServerState.Stopped, withTimeout(10_000) { result.await() })
        }

        suspend fun expectNoLegacyConnections() = withTimeout(5_000) {
            while (manager.activeSessionCount != 0 || manager.activeLegacyTransportCount != 0) delay(10)
        }

        fun close() {
            manager.shutdown()
            config.cleanup()
        }
    }

    @Test
    fun `failed bind releases partial generation and same manager can retry and stop repeatedly`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            ServerSocket(fixture.port).use {
                val failure = assertInstanceOf(ServerState.Failed::class.java, fixture.start())
                assertTrue(generateSequence(failure.exception) { it.cause }.any { it is BindException })
                assertEquals(0, fixture.manager.activeSessionCount)
            }
            assertEquals(ServerState.Running, fixture.start())
            TestStreamableHttpMcpClient().let { client ->
                try {
                    client.connectToServer("http://127.0.0.1:${fixture.port}/mcp")
                    assertNotNull(client.ping())
                } finally { client.close() }
            }
            fixture.stop()
            fixture.stop()
            ServerSocket(fixture.port).use { assertEquals(fixture.port, it.localPort) }
            assertEquals(ServerState.Running, fixture.start())
        } finally { fixture.close() }
        ServerSocket(fixture.port).use { assertEquals(fixture.port, it.localPort) }
    }

    @Test
    fun `linkage startup failure is reported and a corrected registration can retry`(): Unit = runBlocking {
        val fixture = Fixture()
        val linkage = NoSuchMethodError("synthetic test of an embedded dependency failure")
        try {
            every { fixture.api.burpSuite().version().edition() } throws linkage
            val state = assertInstanceOf(ServerState.Failed::class.java, fixture.start())
            assertSame(linkage, state.exception)
            verify(exactly = 1) { fixture.logging.logToError(linkage) }
            ServerSocket(fixture.port).use { assertEquals(fixture.port, it.localPort) }
            every { fixture.api.burpSuite().version().edition() } returns BurpSuiteEdition.COMMUNITY_EDITION
            assertEquals(ServerState.Running, fixture.start())
        } finally { fixture.close() }
    }

    @Test
    fun `shutdown fences queued starts and later starts cannot reopen listener`() {
        val executor = PausedExecutor()
        val fixture = Fixture(executor)
        val states = mutableListOf<ServerState>()
        fixture.manager.start(fixture.config, states::add)
        SwingUtilities.invokeAndWait { fixture.manager.shutdown() }
        val countAtUnload = states.size
        fixture.manager.start(fixture.config, states::add)
        executor.runAll()
        assertEquals(countAtUnload, states.size)
        assertFalse(states.any { it is ServerState.Running || it is ServerState.Failed })
        verify(exactly = 0) { fixture.api.burpSuite() }
        assertTrue(executor.isTerminated)
        ServerSocket(fixture.port).use { assertEquals(fixture.port, it.localPort) }
        fixture.close()
    }

    @Test
    fun `EDT shutdown of running listener returns before delayed cleanup and distinct manager can retry bind`(): Unit = runBlocking {
        val executor = GatedExecutor()
        val original = Fixture(executor)
        val replacement = Fixture(requestedPort = original.port)
        var cleanup: GatedExecutor.Gate? = null
        try {
            assertEquals(ServerState.Running, original.start())
            assertEquals(200, streamableHttpRequest(original.port,
                """{"jsonrpc":"2.0","id":"running","method":"ping"}""").status)
            val heldCleanup = executor.holdNextTask()
            cleanup = heldCleanup
            SwingUtilities.invokeAndWait { original.manager.shutdown() }
            assertTrue(withContext(Dispatchers.IO) { heldCleanup.entered.await(5, TimeUnit.SECONDS) },
                "The original listener cleanup should be held after EDT shutdown has returned")
            // The old socket is still bound, but its closed execution owner rejects new work immediately.
            assertEquals(503, streamableHttpRequest(original.port,
                """{"jsonrpc":"2.0","id":"fenced","method":"ping"}""").status)
            val failed = assertInstanceOf(ServerState.Failed::class.java, replacement.start())
            assertTrue(generateSequence(failed.exception) { it.cause }.any { it is BindException })

            heldCleanup.release.countDown()
            assertTrue(withContext(Dispatchers.IO) { executor.awaitTermination(10, TimeUnit.SECONDS) })
            assertEquals(ServerState.Running, replacement.start(),
                "A failed replacement must be retryable after the old listener releases the port")
            assertEquals(200, streamableHttpRequest(original.port,
                """{"jsonrpc":"2.0","id":"replacement","method":"ping"}""").status)
            assertEquals(0, original.manager.activeSessionCount)
        } finally {
            cleanup?.release?.countDown()
            original.close()
            replacement.close()
        }
        ServerSocket(original.port).use { assertEquals(original.port, it.localPort) }
    }

    @Test
    fun `superseded queued start never binds its snapshot and only current callback reaches Running`() {
        val executor = PausedExecutor()
        val fixture = Fixture(executor)
        val first = mutableListOf<ServerState>()
        val second = mutableListOf<ServerState>()
        val newPort = ServerSocket(0).use { it.localPort }
        try {
            fixture.manager.start(fixture.config, first::add)
            fixture.config.port = newPort
            fixture.manager.start(fixture.config, second::add)
            executor.runAll()
            assertEquals(listOf(ServerState.Starting), first)
            assertEquals(listOf(ServerState.Starting, ServerState.Running), second)
            verify(exactly = 1) { fixture.api.burpSuite().version().edition() }
            ServerSocket(fixture.port).use { assertEquals(fixture.port, it.localPort) }
        } finally {
            SwingUtilities.invokeAndWait { fixture.manager.shutdown() }
            executor.runAll()
            fixture.close()
        }
        ServerSocket(newPort).use { assertEquals(newPort, it.localPort) }
    }

    @Test
    @ResourceLock("DataAccessApprovalHandler")
    fun `pending approval leaves another client usable and late allow after stop invokes no protected API`(): Unit = runBlocking {
        val fixture = Fixture()
        val previous = DataAccessSecurity.approvalHandler
        val entered = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        DataAccessSecurity.approvalHandler = object : DataAccessApprovalHandler {
            override suspend fun requestDataAccess(accessType: DataAccessType, config: McpConfig): Boolean =
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    answer.await()
                }
        }
        val pendingClient = TestStreamableHttpMcpClient()
        val otherClient = TestStreamableHttpMcpClient()
        try {
            assertEquals(ServerState.Running, fixture.start())
            pendingClient.connectToServer("http://127.0.0.1:${fixture.port}/mcp")
            otherClient.connectToServer("http://127.0.0.1:${fixture.port}/mcp")
            val request = async {
                runCatching { pendingClient.callTool("get_proxy_http_history", mapOf("count" to 1, "offset" to 0)) }
            }
            withTimeout(5_000) { entered.await() }
            assertFalse(otherClient.listTools().isEmpty())
            val result = otherClient.callTool("base64_encode", mapOf("content" to "hello"))
            assertEquals("aGVsbG8=", (result!!.content.single() as TextContent).text)
            val stopping = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<ServerState>()
            fixture.manager.stop { state ->
                if (state is ServerState.Stopping) stopping.complete(Unit)
                if (state is ServerState.Stopped || state is ServerState.Failed) stopped.complete(state)
            }
            withTimeout(5_000) { stopping.await() }
            answer.complete(true)
            assertEquals(ServerState.Stopped, withTimeout(10_000) { stopped.await() })
            withTimeout(10_000) { request.await() }
            verify(exactly = 0) { fixture.api.proxy() }
            assertFalse(fixture.config.alwaysAllowHttpHistory)
        } finally {
            answer.complete(false)
            pendingClient.close()
            otherClient.close()
            fixture.close()
            DataAccessSecurity.approvalHandler = previous
        }
    }

    @Test
    fun `idle graceful and reset legacy disconnects release transports and stale IDs return 404`(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            assertEquals(ServerState.Running, fixture.start())
            assertEquals(400, streamableHttpRequest(fixture.port, "{}", path = "/").status)
            assertEquals(404, streamableHttpRequest(fixture.port, "{}", path = "/?sessionId=missing").status)
            repeat(6) { iteration ->
                val connection = withContext(Dispatchers.IO) { openLegacySocket(fixture.port) }
                withTimeout(5_000) {
                    while (fixture.manager.activeSessionCount != 1 || fixture.manager.activeLegacyTransportCount != 1) delay(10)
                }
                if (iteration % 2 == 1) connection.first.setSoLinger(true, 0)
                connection.first.close()
                fixture.expectNoLegacyConnections()
                val oldId = connection.second
                assertEquals(404, streamableHttpRequest(fixture.port, "{}", path = "/?sessionId=$oldId").status)
                assertEquals(200, streamableHttpRequest(fixture.port,
                    """{"jsonrpc":"2.0","id":"alive","method":"ping"}""").status)
            }
        } finally { fixture.close() }
    }

    private fun openLegacySocket(port: Int): Pair<Socket, String> {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                    "User-Agent: burp-mcp-lifecycle-test\r\nAccept: text/event-stream\r\n\r\n").toByteArray())
                flush()
            }
            val input = BufferedInputStream(socket.getInputStream())
            check(input.asciiLine().contains("200"))
            var chunked = false
            while (true) {
                val line = input.asciiLine()
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("transfer-encoding:") && line.lowercase().contains("chunked")) chunked = true
            }
            check(chunked) { "Expected streaming chunked SSE response" }
            val events = StringBuilder()
            while (true) {
                val length = input.asciiLine().substringBefore(';').toInt(16)
                check(length > 0)
                val bytes = input.readNBytes(length)
                check(bytes.size == length)
                events.append(bytes.toString(Charsets.UTF_8))
                check(input.asciiLine().isEmpty())
                val id = Regex("data: [?]sessionId=([a-fA-F0-9-]+)").find(events)?.groupValues?.get(1)
                if (id != null) return socket to id
            }
        } catch (failure: Throwable) {
            socket.close()
            throw failure
        }
    }

    private fun BufferedInputStream.asciiLine(): String {
        val result = StringBuilder()
        while (true) {
            val value = read()
            check(value != -1) { "Unexpected EOF in SSE response" }
            if (value == 10) return result.toString().removeSuffix("\r")
            result.append(value.toChar())
        }
    }

    /** Real serial executor, with a controlled barrier before one queued cleanup action. */
    private class GatedExecutor : AbstractExecutorService() {
        class Gate {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
        }

        private val delegate = Executors.newSingleThreadExecutor()
        private var nextGate: Gate? = null

        @Synchronized
        fun holdNextTask(): Gate = Gate().also {
            check(nextGate == null)
            nextGate = it
        }

        override fun execute(command: Runnable) {
            val gate = synchronized(this) { nextGate.also { nextGate = null } }
            delegate.execute {
                gate?.let {
                    it.entered.countDown()
                    check(it.release.await(30, TimeUnit.SECONDS)) { "Lifecycle cleanup was not released" }
                }
                command.run()
            }
        }

        override fun shutdown() = delegate.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
        override fun isShutdown(): Boolean = delegate.isShutdown
        override fun isTerminated(): Boolean = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = delegate.awaitTermination(timeout, unit)
    }

    private class PausedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.addLast(command)
        }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> = tasks.toMutableList().also { stopped = true; tasks.clear() }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
