package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import kotlinx.coroutines.runBlocking
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessType

const val DEFAULT_SUMMARY_PAGE_SIZE = 100

const val STATE_EDITING_DISABLED_MESSAGE =
    "User has disabled tools that change Burp state. They can enable it in the MCP tab in Burp by selecting 'Enable tools that change Burp state'"

/** Returns null when data access is granted, otherwise the denial message to hand back to the client. */
internal fun denyDataAccess(api: MontoyaApi, config: McpConfig, accessType: DataAccessType, label: String): String? {
    val allowed = runBlocking { checkDataAccessOrDeny(accessType, config, api, label) }
    return if (allowed) null else "$label access denied by Burp Suite"
}

/** Returns null when state-changing tools are enabled, otherwise the message to hand back to the client. */
internal fun denyStateEditing(config: McpConfig): String? =
    if (config.stateEditingTooling) null else STATE_EDITING_DISABLED_MESSAGE

/**
 * Renders one page of one-line-per-item output with a header telling the caller how to continue.
 * Lines are materialised once so the total is exact.
 */
internal fun pageLines(lines: List<String>, offset: Int, count: Int, emptyMessage: String = "No items"): String {
    if (lines.isEmpty()) return emptyMessage
    val start = offset.coerceAtLeast(0)
    if (start >= lines.size) return "Reached end of items (total: ${lines.size})"
    val end = (start + count.coerceAtLeast(1)).coerceAtMost(lines.size)
    return buildString {
        append("=== Items ${start + 1}–$end of ${lines.size}")
        if (end < lines.size) append(" | call again with offset=$end for more")
        appendLine(" ===")
        lines.subList(start, end).forEach { appendLine(it) }
    }.trimEnd()
}

enum class MessagePart {
    ALL, REQUEST, RESPONSE, REQUEST_HEADERS, REQUEST_BODY, RESPONSE_HEADERS, RESPONSE_BODY;

    companion object {
        val names = entries.joinToString("|") { it.name.lowercase() }

        fun parse(value: String): MessagePart =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown part '$value', expected one of: $names")
    }
}

internal fun renderMessage(request: HttpRequest?, response: HttpResponse?, part: MessagePart): String {
    fun HttpRequest.headerSection() = toString().take(bodyOffset())
    fun HttpResponse.headerSection() = toString().take(bodyOffset())
    val noRequest = "<no request>"
    val noResponse = "<no response>"

    return when (part) {
        MessagePart.ALL -> "${request?.toString() ?: noRequest}\n\n=== RESPONSE ===\n\n${response?.toString() ?: noResponse}"
        MessagePart.REQUEST -> request?.toString() ?: noRequest
        MessagePart.RESPONSE -> response?.toString() ?: noResponse
        MessagePart.REQUEST_HEADERS -> request?.headerSection() ?: noRequest
        MessagePart.REQUEST_BODY -> request?.bodyToString() ?: noRequest
        MessagePart.RESPONSE_HEADERS -> response?.headerSection() ?: noResponse
        MessagePart.RESPONSE_BODY -> response?.bodyToString() ?: noResponse
    }
}

/** Compact `METHOD URL -> STATUS LENGTHB mime` description of an exchange. */
internal fun exchangeSummary(request: HttpRequest?, response: HttpResponse?): String {
    val requestPart = request?.let { "${it.method()} ${it.url()}" } ?: "<no request>"
    val responsePart = response?.let { "${it.statusCode()} ${it.body().length()}B ${it.mimeType().name.lowercase()}" }
        ?: "<no response>"
    return "$requestPart -> $responsePart"
}

internal fun notesSuffix(notes: String?): String =
    notes?.takeIf { it.isNotBlank() }?.let { " | notes: ${it.replace('\n', ' ').take(120)}" } ?: ""
