package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.tools.ToolExecution
import net.portswigger.mcp.tools.registerTools
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class KtorServerManager(
    private val api: MontoyaApi,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : ServerManager {

    private data class Listener(
        val engine: EmbeddedServer<*, *>,
        val mcpServer: Server,
        val tools: ToolExecution,
        val legacy: LegacySseEndpoint
    )

    private val lifecycleLock = Any()
    @Volatile private var listener: Listener? = null
    @Volatile private var closed = false
    private var transition = 0L

    internal val activeSessionCount: Int
        get() = listener?.mcpServer?.sessions?.size ?: 0

    internal val activeLegacyTransportCount: Int
        get() = listener?.legacy?.activeTransportCount ?: 0

    override fun start(config: McpConfig, callback: (ServerState) -> Unit) {
        val host = config.host
        val port = config.port
        enqueue(ServerState.Starting, callback) { token ->
            stopListener()
            if (!isCurrent(token)) return@enqueue

            val tools = ToolExecution()
            var mcpServer: Server? = null
            var engine: EmbeddedServer<*, *>? = null
            try {
                val sharedServer = Server(
                    serverInfo = Implementation("burp-suite", "1.1.2"),
                    options = ServerOptions(
                        capabilities = ServerCapabilities(
                            tools = ServerCapabilities.Tools(listChanged = false)
                        )
                    )
                )
                mcpServer = sharedServer
                sharedServer.registerTools(api, config, tools)
                val legacy = LegacySseEndpoint(sharedServer)
                engine = embeddedServer(Netty, port = port, host = host) {
                    install(CORS) {
                        allowHost("localhost:$port")
                        allowHost("127.0.0.1:$port")
                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Accept)
                        allowHeader("Last-Event-ID")
                        allowCredentials = false
                        allowNonSimpleContentTypes = true
                        maxAgeInSeconds = 3600
                    }

                    intercept(ApplicationCallPipeline.Call) {
                        val origin = call.request.header("Origin")
                        val hostHeader = call.request.header("Host")
                        val referer = call.request.header("Referer")
                        val userAgent = call.request.header("User-Agent")
                        val rejected = when {
                            origin != null && !isValidOrigin(origin) -> "origin: $origin"
                            isBrowserRequest(userAgent) -> "browser request"
                            hostHeader != null && !isValidHost(hostHeader, port) -> "host: $hostHeader"
                            referer != null && !isValidReferer(referer) -> "referer: $referer"
                            else -> null
                        }
                        if (rejected != null) {
                            api.logging().logToOutput("Blocked MCP request from $rejected")
                            call.respond(HttpStatusCode.Forbidden)
                            finish()
                            return@intercept
                        }
                        if (!tools.isOpen) {
                            call.respond(HttpStatusCode.ServiceUnavailable)
                            finish()
                            return@intercept
                        }
                        call.response.header("X-Frame-Options", "DENY")
                        call.response.header("X-Content-Type-Options", "nosniff")
                        call.response.header("Referrer-Policy", "same-origin")
                        call.response.header("Content-Security-Policy", "default-src 'none'")
                    }

                    mcpStatelessStreamableHttp(path = "/mcp") { sharedServer }
                    legacy.mount(this)
                }
                listener = Listener(engine, sharedServer, tools, legacy)
                if (!isCurrent(token)) {
                    stopListener()
                    return@enqueue
                }
                engine.start(wait = false)
                if (!isCurrent(token)) {
                    stopListener()
                    return@enqueue
                }
                api.logging().logToOutput("Started MCP server on $host:$port")
                notifyState(token, callback, ServerState.Running)
            } catch (failure: Throwable) {
                listener = null
                try {
                    closeResources(engine, mcpServer, tools)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }
    }

    override fun stop(callback: (ServerState) -> Unit) {
        enqueue(ServerState.Stopping, callback) { token ->
            stopListener()
            api.logging().logToOutput("Stopped MCP server")
            notifyState(token, callback, ServerState.Stopped)
        }
    }

    private fun enqueue(
        initialState: ServerState,
        callback: (ServerState) -> Unit,
        action: (Long) -> Unit
    ) {
        synchronized(lifecycleLock) {
            if (closed) return
            val token = ++transition
            // Fence tool admission immediately, even while lifecycle work is queued.
            listener?.tools?.close()
            callback(initialState)
            executor.execute {
                try {
                    if (isCurrent(token)) action(token)
                } catch (failure: Throwable) {
                    if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    api.logging().logToError(failure)
                    notifyState(token, callback, ServerState.Failed(failure))
                }
            }
        }
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lifecycleLock) {
        !closed && transition == token
    }

    private fun notifyState(token: Long, callback: (ServerState) -> Unit, state: ServerState) {
        synchronized(lifecycleLock) {
            if (!closed && transition == token) callback(state)
        }
    }

    private fun stopListener() {
        val previous = listener ?: return
        listener = null
        closeResources(previous.engine, previous.mcpServer, previous.tools)
    }

    private fun closeResources(engine: EmbeddedServer<*, *>?, mcpServer: Server?, tools: ToolExecution) {
        tools.close()
        var failure: Throwable? = null
        try {
            if (mcpServer != null) {
                runBlocking { withTimeout(5000) { mcpServer.close() } }
            }
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        } finally {
            try {
                engine?.stop(1000, 5000)
            } catch (stopFailure: Throwable) {
                if (failure == null) failure = stopFailure else failure.addSuppressed(stopFailure)
            }
        }
        failure?.let { throw it }
    }

    override fun shutdown() {
        synchronized(lifecycleLock) {
            if (!closed) {
                closed = true
                ++transition
                listener?.tools?.close()
                executor.execute {
                    try {
                        stopListener()
                    } catch (failure: Throwable) {
                        if (failure is VirtualMachineError || failure is ThreadDeath) throw failure
                        api.logging().logToError(failure)
                    }
                }
                executor.shutdown()
            }
        }
        // Approval dialogs are disposed on the EDT; never wait for it from itself.
        if (!SwingUtilities.isEventDispatchThread()) {
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    api.logging().logToError("MCP cleanup is still running after the shutdown wait")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun isValidOrigin(origin: String): Boolean {
        try {
            val url = URI(origin).toURL()
            val hostname = url.host.lowercase()

            val allowedHosts = setOf("localhost", "127.0.0.1")

            return hostname in allowedHosts
        } catch (_: Exception) {
            return false
        }
    }

    private fun isBrowserRequest(userAgent: String?): Boolean {
        if (userAgent == null) return false

        val userAgentLower = userAgent.lowercase()
        val browserIndicators = listOf(
            "mozilla/", "chrome/", "safari/", "webkit/", "gecko/", "firefox/", "edge/", "opera/", "browser"
        )

        return browserIndicators.any { userAgentLower.contains(it) }
    }

    private fun isValidHost(host: String, expectedPort: Int): Boolean {
        try {
            val parts = host.split(":")
            val hostname = parts[0].lowercase()
            val port = if (parts.size > 1) parts[1].toIntOrNull() else null

            val allowedHosts = setOf("localhost", "127.0.0.1")
            if (hostname !in allowedHosts) {
                return false
            }

            if (port != null && port != expectedPort) {
                return false
            }

            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun isValidReferer(referer: String): Boolean {
        try {
            val url = URI(referer).toURL()
            val hostname = url.host.lowercase()

            val allowedHosts = setOf("localhost", "127.0.0.1")
            return hostname in allowedHosts

        } catch (_: Exception) {
            return false
        }
    }
}