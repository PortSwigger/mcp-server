package net.portswigger.mcp

import burp.api.montoya.MontoyaApi
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.tools.registerTools
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KtorServerManager(private val api: MontoyaApi) : ServerManager {

    private var server: EmbeddedServer<*, *>? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    override fun start(config: McpConfig, callback: (ServerState) -> Unit) {
        callback(ServerState.Starting)

        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null

                val remoteAllowedHosts = config.getAllowedHostsList()
                val allowRemoteAccess = remoteAllowedHosts.isNotEmpty()
                val mcpAllowedHosts = if (allowRemoteAccess) {
                    (listOf("localhost", "127.0.0.1", "[::1]") + remoteAllowedHosts).distinct()
                } else {
                    null
                }

                val serverOptions = ServerOptions(
                    capabilities = ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = false)
                    )
                )

                server = embeddedServer(Netty, port = config.port, host = config.host) {
                    install(CORS) {
                        if (allowRemoteAccess) {
                            anyHost()
                        } else {
                            allowHost("localhost:${config.port}")
                            allowHost("127.0.0.1:${config.port}")
                        }

                        allowMethod(HttpMethod.Get)
                        allowMethod(HttpMethod.Post)

                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Accept)
                        allowHeader(HttpHeaders.Origin)
                        allowHeader("Referer")
                        allowHeader(HttpHeaders.UserAgent)
                        allowHeader("Last-Event-ID")

                        allowCredentials = false
                        allowNonSimpleContentTypes = true
                        maxAgeInSeconds = 3600
                    }

                    mcp(
                        enableDnsRebindingProtection = true,
                        allowedHosts = mcpAllowedHosts,
                        allowedOrigins = null,
                    ) {
                        Server(
                            serverInfo = Implementation("burp-suite", "1.1.2"),
                            options = serverOptions,
                        ).also { sessionServer ->
                            sessionServer.registerTools(api, config)
                        }
                    }
                }.apply {
                    start(wait = false)
                }

                if (allowRemoteAccess) {
                    api.logging().logToOutput(
                        "Started MCP server on ${config.host}:${config.port} with remote access for: ${remoteAllowedHosts.joinToString()}"
                    )
                } else {
                    api.logging().logToOutput("Started MCP server on ${config.host}:${config.port}")
                }
                callback(ServerState.Running)

            } catch (e: Exception) {
                api.logging().logToError(e)
                callback(ServerState.Failed(e))
            }
        }
    }

    override fun stop(callback: (ServerState) -> Unit) {
        callback(ServerState.Stopping)

        executor.submit {
            try {
                server?.stop(1000, 5000)
                server = null
                api.logging().logToOutput("Stopped MCP server")
                callback(ServerState.Stopped)
            } catch (e: Exception) {
                api.logging().logToError(e)
                callback(ServerState.Failed(e))
            }
        }
    }

    override fun shutdown() {
        server?.stop(1000, 5000)
        server = null

        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }
}
