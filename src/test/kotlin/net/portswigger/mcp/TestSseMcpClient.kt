package net.portswigger.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.withTimeout

class TestSseMcpClient {
    private val mcp = Client(clientInfo = Implementation(name = "test-mcp-client", version = "1.0.0"))
    private var connected = false
    private var httpClient: HttpClient? = null

    suspend fun connectToServer(sseUrl: String) {
        httpClient?.close()
        val connectionClient = HttpClient {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
            }
        }
        httpClient = connectionClient
        try {
            withTimeout(10_000) {
                mcp.connect(SseClientTransport(connectionClient, urlString = sseUrl))
            }
            connected = true
        } catch (error: Throwable) {
            connected = false
            connectionClient.close()
            throw error
        }
    }

    fun isConnected(): Boolean = connected

    suspend fun ping(): EmptyResult = withTimeout(10_000) { mcp.ping() }

    suspend fun listTools(): List<Tool> = withTimeout(10_000) { mcp.listTools().tools }

    suspend fun callTool(toolName: String, arguments: Map<String, Any>): CallToolResult? =
        withTimeout(10_000) { mcp.callTool(toolName, arguments) }

    suspend fun close() {
        try {
            withTimeout(10_000) { mcp.close() }
        } finally {
            connected = false
            httpClient?.close()
            httpClient = null
        }
    }
}
