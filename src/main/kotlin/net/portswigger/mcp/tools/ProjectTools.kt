package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.core.HighlightColor
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.Serializable
import net.portswigger.mcp.TrafficLog
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessType
import java.time.ZonedDateTime
import java.util.regex.Pattern

/** Cookie jar, annotations, Organizer and BCheck/Bambda import. */
fun Server.registerProjectTools(api: MontoyaApi, config: McpConfig, trafficLog: TrafficLog) {

    mcpTool<GetCookieJar>(
        "Lists cookies in Burp's session-handling cookie jar as 'domain path name=value (expires)'. Optional 'domainRegex' filter."
    ) {
        denyDataAccess(api, config, DataAccessType.HTTP_HISTORY, "Cookie jar")?.let { return@mcpTool it }

        val pattern = domainRegex?.let { Pattern.compile(it) }
        api.http().cookieJar().cookies()
            .filter { pattern == null || pattern.matcher(it.domain()).find() }
            .joinToString("\n") { cookie ->
                val expires = cookie.expiration().map { " (expires ${it})" }.orElse("")
                "${cookie.domain()} ${cookie.path() ?: "/"} ${cookie.name()}=${cookie.value()}$expires"
            }
            .ifEmpty { "Cookie jar is empty" }
    }

    mcpTool<SetCookie>(
        "Adds or updates a cookie in Burp's cookie jar (used by session handling rules). 'expires' is optional ISO-8601, e.g. 2026-12-31T00:00:00Z."
    ) {
        denyStateEditing(config)?.let { return@mcpTool it }
        api.http().cookieJar().setCookie(name, value, path, domain, expires?.let { ZonedDateTime.parse(it) })
        "Cookie $name set for $domain"
    }

    mcpTool<AnnotateProxyHistoryItem>(
        "Sets notes and/or highlight colour on a proxy history item by id. " +
                "highlight is one of ${HighlightColor.entries.joinToString("|") { it.name.lowercase() }}."
    ) {
        denyStateEditing(config)?.let { return@mcpTool it }
        val item = findProxyHistoryItem(api, id) ?: return@mcpTool "No proxy history item with id $id"

        notes?.let { item.annotations().setNotes(it) }
        highlight?.let { colour ->
            val parsed = HighlightColor.entries.firstOrNull { it.name.equals(colour.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown highlight '$colour'")
            item.annotations().setHighlightColor(parsed)
        }
        "Annotated proxy history item $id"
    }

    mcpTool<SendToOrganizer>(
        "Sends an existing exchange to the Organizer tab, identified by proxyHistoryId or trafficLogId."
    ) {
        denyStateEditing(config)?.let { return@mcpTool it }
        val exchange = when {
            proxyHistoryId != null -> findProxyHistoryItem(api, proxyHistoryId)?.let {
                burp.api.montoya.http.message.HttpRequestResponse.httpRequestResponse(it.finalRequest(), it.response())
            }
            trafficLogId != null -> trafficLog.find(trafficLogId)?.requestResponse
            else -> throw IllegalArgumentException("Pass proxyHistoryId or trafficLogId")
        } ?: return@mcpTool "No matching item found"

        api.organizer().sendToOrganizer(exchange)
        "Sent to Organizer"
    }

    mcpTool<ImportBambda>(
        "Imports a Bambda (Java snippet with YAML header) into Burp's Bambda library. Requires config editing tools to be enabled, since Bambdas run code."
    ) {
        if (!config.configEditingTooling) {
            return@mcpTool "User has disabled configuration editing. They can enable it in the MCP tab in Burp by selecting 'Enable tools that can edit your config'"
        }
        val result = api.bambda().importBambda(script)
        "Bambda import: ${result.status()}" + result.importErrors().joinToString("") { "\n- $it" }
    }

    if (api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
        mcpTool<ImportBCheck>("Imports a BCheck script into Burp Scanner, enabled by default.") {
            denyStateEditing(config)?.let { return@mcpTool it }
            val result = api.scanner().bChecks().importBCheck(script, enabled)
            "BCheck import: ${result.status()}" + result.importErrors().joinToString("") { "\n- $it" }
        }
    }
}

@Serializable
data class GetCookieJar(val domainRegex: String? = null)

@Serializable
data class SetCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String? = null,
    val expires: String? = null
)

@Serializable
data class AnnotateProxyHistoryItem(val id: Int, val notes: String? = null, val highlight: String? = null)

@Serializable
data class SendToOrganizer(val proxyHistoryId: Int? = null, val trafficLogId: Int? = null)

@Serializable
data class ImportBambda(val script: String)

@Serializable
data class ImportBCheck(val script: String, val enabled: Boolean = true)
