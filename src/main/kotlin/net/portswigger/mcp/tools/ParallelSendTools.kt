package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.message.requests.HttpRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.HttpRequestSecurity

const val MAX_PARALLEL_REQUESTS = 50

fun Server.registerParallelSendTools(api: MontoyaApi, config: McpConfig) {

    mcpTool<SendHttpRequestsParallel>(
        "Sends several raw HTTP requests to one target concurrently (useful for race-condition checks) and returns, per request, " +
                "status, body length, response time and the first maxResponseBytes of the response. " +
                "Set http2=true to send over HTTP/2. At most $MAX_PARALLEL_REQUESTS requests. " +
                "Full responses are available afterwards via get_traffic_log_summary (tool=extensions)."
    ) {
        require(contents.isNotEmpty()) { "contents must contain at least one request" }
        require(contents.size <= MAX_PARALLEL_REQUESTS) { "At most $MAX_PARALLEL_REQUESTS requests can be sent at once" }

        val normalized = contents.map { normalizeHttpContent(it) }
        val approvalDisplay = normalized.mapIndexed { i, c -> "--- request ${i + 1} of ${normalized.size} ---\n$c" }
            .joinToString("\n")
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, approvalDisplay, api)
        }
        if (!allowed) {
            api.logging().logToOutput("MCP parallel HTTP requests denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP ${normalized.size} parallel HTTP requests: $targetHostname:$targetPort")

        val requests = normalized.map { HttpRequest.httpRequest(toMontoyaService(), it) }
        val results = if (http2) api.http().sendRequests(requests, HttpMode.HTTP_2) else api.http().sendRequests(requests)

        results.mapIndexed { i, result ->
            val response = result?.response()
            val elapsed = result?.timingData()?.orElse(null)
                ?.timeBetweenRequestSentAndEndOfResponse()?.toMillis()?.let { "${it}ms" } ?: "?ms"
            val header = if (response == null) {
                "[$i] <no response>"
            } else {
                "[$i] ${response.statusCode()} ${response.body().length()}B $elapsed"
            }
            val excerpt = response?.toString()?.take(maxResponseBytes.coerceAtLeast(0)).orEmpty()
            if (excerpt.isEmpty()) header else "$header\n$excerpt"
        }.joinToString("\n\n")
    }
}

@Serializable
data class SendHttpRequestsParallel(
    val contents: List<String>,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean,
    val http2: Boolean = false,
    val maxResponseBytes: Int = 2_000
) : HttpServiceParams
