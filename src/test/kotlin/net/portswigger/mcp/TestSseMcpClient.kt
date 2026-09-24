package net.portswigger.mcp

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import org.slf4j.LoggerFactory

class TestSseMcpClient {
    private val logger = LoggerFactory.getLogger(TestSseMcpClient::class.java)
    private val mcp: Client = Client(clientInfo = Implementation(name = "test-mcp-client", version = "1.0.0"))
    private var connected = false

    private lateinit var tools: List<Tool>

    suspend fun connectToServer(sseUrl: String) {
        try {
            val httpClient = HttpClient {
                install(SSE)
                install(HttpTimeout) {
                    requestTimeoutMillis = 30000
                    connectTimeoutMillis = 15000
                }
            }

            val transport = SseClientTransport(httpClient, urlString = sseUrl)

            mcp.connect(transport)
            connected = true

            val toolsResult = mcp.listTools()
            tools = toolsResult.tools
            println("Connected to server with tools: ${tools.joinToString(", ") { it.name }}")
        } catch (e: Exception) {
            println("Failed to connect to MCP server: $e")
            throw e
        }
    }

    fun isConnected(): Boolean = connected

    suspend fun ping(): EmptyResult {
        try {
            val pingRequest = mcp.ping()
            logger.info("Ping sent: $pingRequest")
            return pingRequest
        } catch (e: Exception) {
            logger.error("Failed to send ping: $e")
            throw e
        }
    }

    suspend fun listTools(): List<Tool> {
        try {
            val toolsResult = mcp.listTools()
            tools = toolsResult.tools
            logger.info("Tools listed: ${tools.joinToString(", ") { it.name }}")
            return tools
        } catch (e: Exception) {
            logger.error("Failed to list tools: $e")
            throw e
        }
    }

    suspend fun callTool(toolName: String, arguments: Map<String, Any>): CallToolResult? {
        try {
            return mcp.callTool(toolName, arguments)
        } catch (e: Exception) {
            logger.error("Failed to call tool: $e")
            throw e
        }
    }

    suspend fun close() {
        try {
            mcp.close()
            connected = false
            logger.info("MCP client closed successfully.")
        } catch (e: Exception) {
            logger.error("Failed to close MCP client: $e")
        }
    }
}