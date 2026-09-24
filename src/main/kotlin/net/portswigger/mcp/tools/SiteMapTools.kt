package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.sitemap.SiteMapFilter
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessType
import java.util.regex.Pattern

/**
 * Read access to the Target site map and to the project scope, plus scope changes
 * (gated behind the state-editing setting).
 */
fun Server.registerSiteMapTools(api: MontoyaApi, config: McpConfig) {

    fun siteMapItems(urlPrefix: String?, regex: String?): List<HttpRequestResponse> {
        val items = if (urlPrefix.isNullOrBlank()) {
            api.siteMap().requestResponses()
        } else {
            api.siteMap().requestResponses(SiteMapFilter.prefixFilter(urlPrefix))
        }
        val pattern = regex?.let { Pattern.compile(it) } ?: return items
        return items.filter { it.contains(pattern) }
    }

    mcpTool<GetSiteMapSummary>(
        "Lists the Target site map as one line per request: index, METHOD URL -> status, body length, MIME type. " +
                "Filter with 'urlPrefix' (e.g. https://example.com/api/) and/or 'regex' over the whole message. " +
                "Use get_site_map_item with the same filters and an index to read a message. " +
                "Indexes are only stable while the site map does not change."
    ) {
        denyDataAccess(api, config, DataAccessType.HTTP_HISTORY, "Site map")?.let { return@mcpTool it }

        val lines = siteMapItems(urlPrefix, regex).mapIndexed { index, item ->
            "[$index] ${exchangeSummary(item.request(), item.response())}${notesSuffix(item.annotations().notes())}"
        }
        pageLines(lines, offset, count, "No matching site map items")
    }

    mcpPaginatedBytesTool<GetSiteMapItem>(
        "Returns one site map item by index from get_site_map_summary (pass the same urlPrefix/regex). " +
                "'part' is one of ${MessagePart.names} (default all). Paginated by bytes via offset/count."
    ) {
        denyDataAccess(api, config, DataAccessType.HTTP_HISTORY, "Site map")?.let { return@mcpPaginatedBytesTool it }

        val item = siteMapItems(urlPrefix, regex).getOrNull(index)
            ?: return@mcpPaginatedBytesTool "No site map item at index $index for these filters"
        renderMessage(item.request(), item.response(), MessagePart.parse(part))
    }

    mcpTool<CheckScope>("Reports whether each of the given URLs is in the project's target scope") {
        urls.joinToString("\n") { url ->
            "${if (api.scope().isInScope(url)) "IN " else "OUT"} $url"
        }
    }

    mcpTool(
        "output_scope_config",
        "Outputs the project's target scope rules (include/exclude) as JSON"
    ) {
        api.burpSuite().exportProjectOptionsAsJson("target.scope")
    }

    mcpTool<IncludeInScope>("Adds a URL (prefix) to the project's target scope") {
        denyStateEditing(config)?.let { return@mcpTool it }
        api.logging().logToOutput("MCP adding to scope: $url")
        api.scope().includeInScope(url)
        "Included in scope: $url"
    }

    mcpTool<ExcludeFromScope>("Excludes a URL (prefix) from the project's target scope") {
        denyStateEditing(config)?.let { return@mcpTool it }
        api.logging().logToOutput("MCP excluding from scope: $url")
        api.scope().excludeFromScope(url)
        "Excluded from scope: $url"
    }
}

@Serializable
data class GetSiteMapSummary(
    val urlPrefix: String? = null,
    val regex: String? = null,
    override val count: Int = DEFAULT_SUMMARY_PAGE_SIZE,
    override val offset: Int = 0
) : Paginated

@Serializable
data class GetSiteMapItem(
    val index: Int,
    val urlPrefix: String? = null,
    val regex: String? = null,
    val part: String = "all",
    override val count: Int = DEFAULT_RESPONSE_PAGE_BYTES,
    override val offset: Int = 0
) : Paginated

@Serializable
data class CheckScope(val urls: List<String>)

@Serializable
data class IncludeInScope(val url: String)

@Serializable
data class ExcludeFromScope(val url: String)
