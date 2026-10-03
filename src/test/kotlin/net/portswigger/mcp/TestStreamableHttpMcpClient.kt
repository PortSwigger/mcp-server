package net.portswigger.mcp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.LATEST_PROTOCOL_VERSION
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

internal class TestStreamableHttpMcpClient {
    private val httpClient = HttpClient {
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    }
    private val mcp = Client(Implementation("streamable-http-integration-test", "1.0"))
    private var transport: StreamableHttpClientTransport? = null

    val sessionId: String? get() = transport?.sessionId

    suspend fun connectToServer(url: String) = withTimeout(10_000) {
        val connection = StreamableHttpClientTransport(client = httpClient, url = url)
        transport = connection
        mcp.connect(connection)
    }

    suspend fun listTools(): List<Tool> = withTimeout(10_000) { mcp.listTools().tools }

    suspend fun ping(): EmptyResult = withTimeout(10_000) { mcp.ping() }

    suspend fun callTool(name: String, arguments: Map<String, Any>): CallToolResult? =
        withTimeout(10_000) { mcp.callTool(name, arguments) }

    suspend fun close() {
        try {
            withTimeout(10_000) { mcp.close() }
        } finally {
            httpClient.close()
        }
    }
}

internal data class TestHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    fun json(): JsonObject = Json.parseToJsonElement(body).jsonObject
}

/**
 * Sends HTTP/1.1 directly so security tests exercise the exact Host and browser
 * headers supplied, without a client engine replacing restricted headers.
 */
internal suspend fun streamableHttpRequest(
    port: Int,
    body: String = "",
    method: String = "POST",
    headers: Map<String, String?> = emptyMap(),
    path: String = "/mcp",
): TestHttpResponse = withContext(Dispatchers.IO) {
    Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 10_000
        val payload = body.toByteArray(Charsets.UTF_8)
        val requestHeaders = linkedMapOf<String, String?>(
            "Host" to "127.0.0.1:$port",
            "User-Agent" to "burp-mcp-integration-test",
            "Accept" to "application/json, text/event-stream",
            "Content-Type" to "application/json",
            "Mcp-Protocol-Version" to LATEST_PROTOCOL_VERSION,
            "Content-Length" to payload.size.toString(),
            "Connection" to "close",
        ).apply { putAll(headers) }
        val requestHead = buildString {
            append("$method $path HTTP/1.1\r\n")
            requestHeaders.forEach { (name, value) ->
                if (value != null) append("$name: $value\r\n")
            }
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(requestHead.toByteArray(Charsets.US_ASCII))
            write(payload)
            flush()
        }
        val input = BufferedInputStream(socket.getInputStream())
        val statusLine = input.readHttpLine()
        val status = statusLine.split(' ')[1].toInt()
        val responseHeaders = linkedMapOf<String, String>()
        while (true) {
            val line = input.readHttpLine()
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            check(separator > 0) { "Malformed HTTP header: $line" }
            responseHeaders[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        val bytes = when {
            responseHeaders["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> {
                val output = ByteArrayOutputStream()
                while (true) {
                    val size = input.readHttpLine().substringBefore(';').toInt(16)
                    if (size == 0) {
                        while (input.readHttpLine().isNotEmpty()) { /* consume trailers */ }
                        break
                    }
                    val chunk = input.readNBytes(size)
                    check(chunk.size == size) { "Truncated HTTP chunk" }
                    output.write(chunk)
                    check(input.readHttpLine().isEmpty()) { "Missing chunk terminator" }
                }
                output.toByteArray()
            }
            responseHeaders["content-length"] != null -> {
                val length = responseHeaders.getValue("content-length").toInt()
                input.readNBytes(length).also { check(it.size == length) { "Truncated HTTP body" } }
            }
            status == 204 || status == 304 -> byteArrayOf()
            else -> input.readAllBytes()
        }
        TestHttpResponse(status, responseHeaders, bytes.toString(Charsets.UTF_8))
    }
}

/** Sends a full native POST while leaving its response unread for real FIN/RST lifecycle tests. */
internal suspend fun openPendingStreamableHttpRequest(port: Int, body: String): Socket = withContext(Dispatchers.IO) {
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 10_000
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "POST /mcp HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "User-Agent: burp-mcp-disconnect-test\r\n" +
            "Accept: application/json, text/event-stream\r\n" +
            "Content-Type: application/json\r\n" +
            "Mcp-Protocol-Version: $LATEST_PROTOCOL_VERSION\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(payload)
            flush()
        }
        socket
    } catch (failure: Throwable) {
        socket.close()
        throw failure
    }
}

private fun BufferedInputStream.readHttpLine(): String {
    val output = ByteArrayOutputStream()
    while (true) {
        val next = read()
        check(next != -1) { "Unexpected end of HTTP headers" }
        if (next == '\n'.code) return output.toString(Charsets.US_ASCII).removeSuffix("\r")
        check(output.size() < 16_384) { "HTTP header line too long" }
        output.write(next)
    }
}
