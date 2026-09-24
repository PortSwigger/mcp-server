package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import net.portswigger.mcp.TrafficLog
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessType
import java.util.regex.Pattern

fun Server.registerTrafficLogTools(api: MontoyaApi, config: McpConfig, trafficLog: TrafficLog) {

    mcpTool<GetTrafficLogSummary>(
        "Lists HTTP traffic sent by Burp tools other than the Proxy (Repeater, Intruder, Scanner, extensions incl. this MCP server) " +
                "since the extension loaded, one line per item: id, time, tool, METHOD URL -> status, length, MIME type. " +
                "This is the only way to see Repeater/Intruder responses. Newest first by default. " +
                "Filter with 'tool' (e.g. repeater, intruder, scanner, extensions), 'urlRegex' or 'regex' over the whole message. " +
                "Use get_traffic_log_item with an id to read a message."
    ) {
        denyDataAccess(api, config, DataAccessType.HTTP_HISTORY, "Traffic log")?.let { return@mcpTool it }

        val messagePattern = regex?.let { Pattern.compile(it) }
        val urlPattern = urlRegex?.let { Pattern.compile(it) }
        val entries = trafficLog.snapshot()

        val lines = (if (newestFirst) entries.asReversed() else entries).asSequence()
            .filter { tool == null || it.tool.name.equals(tool, ignoreCase = true) || it.tool.toolName().equals(tool, ignoreCase = true) }
            .filter { urlPattern == null || urlPattern.matcher(it.requestResponse.request().url()).find() }
            .filter { messagePattern == null || it.requestResponse.contains(messagePattern) }
            .map {
                "#${it.id} ${it.time.toLocalDateTime().withNano(0)} ${it.tool.name.lowercase()} " +
                        exchangeSummary(it.requestResponse.request(), it.requestResponse.response())
            }
            .toList()
        pageLines(lines, offset, count, "No matching traffic log entries")
    }

    mcpPaginatedBytesTool<GetTrafficLogItem>(
        "Returns one traffic log entry by id (see get_traffic_log_summary). " +
                "'part' is one of ${MessagePart.names} (default all). Paginated by bytes via offset/count."
    ) {
        denyDataAccess(api, config, DataAccessType.HTTP_HISTORY, "Traffic log")?.let { return@mcpPaginatedBytesTool it }

        val entry = trafficLog.find(id)
            ?: return@mcpPaginatedBytesTool "No traffic log entry with id $id (it may have been evicted; the log keeps the latest ${TrafficLog.DEFAULT_CAPACITY})"
        buildString {
            appendLine("# id=${entry.id} time=${entry.time} tool=${entry.tool.name.lowercase()}")
            append(renderMessage(entry.requestResponse.request(), entry.requestResponse.response(), MessagePart.parse(part)))
        }
    }
}

@Serializable
data class GetTrafficLogSummary(
    val tool: String? = null,
    val regex: String? = null,
    val urlRegex: String? = null,
    val newestFirst: Boolean = true,
    override val count: Int = DEFAULT_SUMMARY_PAGE_SIZE,
    override val offset: Int = 0
) : Paginated

@Serializable
data class GetTrafficLogItem(
    val id: Int,
    val part: String = "all",
    override val count: Int = DEFAULT_RESPONSE_PAGE_BYTES,
    override val offset: Int = 0
) : Paginated
