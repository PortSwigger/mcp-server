package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessType
import java.util.regex.Pattern

/**
 * Compact views over proxy history: a one-line-per-item listing to locate traffic cheaply,
 * and a lookup by Burp's history id that returns only the requested part of one message.
 */
fun Server.registerHistoryTools(api: MontoyaApi, config: McpConfig) {

    mcpTool<GetProxyHttpHistorySummary>(
        "Lists proxy HTTP history as one line per item: id, time, METHOD URL -> status, body length, MIME type, notes. " +
                "Newest first by default. Optional 'regex' matches anywhere in the request/response, 'urlRegex' only the URL. " +
                "Use get_proxy_http_history_item with an id to read a message."
    ) {
        denyDataAccess(api, config, DataAccessType.HTTP_HISTORY, "HTTP history")?.let { return@mcpTool it }

        val messagePattern = regex?.let { Pattern.compile(it) }
        val urlPattern = urlRegex?.let { Pattern.compile(it) }
        val items = if (messagePattern != null) {
            api.proxy().history { it.contains(messagePattern) }
        } else {
            api.proxy().history()
        }

        val lines = (if (newestFirst) items.asReversed() else items).asSequence()
            .filter { urlPattern == null || urlPattern.matcher(it.url()).find() }
            .map { it.summaryLine() }
            .toList()
        pageLines(lines, offset, count, "No matching proxy history items")
    }

    mcpPaginatedBytesTool<GetProxyHttpHistoryItem>(
        "Returns one proxy HTTP history item by id (see get_proxy_http_history_summary). " +
                "'part' is one of ${MessagePart.names} (default all). Paginated by bytes via offset/count."
    ) {
        denyDataAccess(api, config, DataAccessType.HTTP_HISTORY, "HTTP history")?.let { return@mcpPaginatedBytesTool it }

        val item = findProxyHistoryItem(api, id) ?: return@mcpPaginatedBytesTool "No proxy history item with id $id"
        buildString {
            appendLine("# id=${item.id()} time=${item.time()} edited=${item.edited()}${notesSuffix(item.annotations().notes())}")
            append(renderMessage(item.finalRequest() ?: item.request(), item.response(), MessagePart.parse(part)))
        }
    }
}

internal fun findProxyHistoryItem(api: MontoyaApi, id: Int): ProxyHttpRequestResponse? =
    api.proxy().history { it.id() == id }.firstOrNull()

private fun ProxyHttpRequestResponse.summaryLine(): String =
    "#${id()} ${time().toLocalDateTime().withNano(0)} ${exchangeSummary(finalRequest() ?: request(), response())}" +
            notesSuffix(annotations().notes())

@Serializable
data class GetProxyHttpHistorySummary(
    val regex: String? = null,
    val urlRegex: String? = null,
    val newestFirst: Boolean = true,
    override val count: Int = DEFAULT_SUMMARY_PAGE_SIZE,
    override val offset: Int = 0
) : Paginated

@Serializable
data class GetProxyHttpHistoryItem(
    val id: Int,
    val part: String = "all",
    override val count: Int = DEFAULT_RESPONSE_PAGE_BYTES,
    override val offset: Int = 0
) : Paginated
