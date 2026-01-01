package net.portswigger.mcp.schema

import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketMessage
import burp.api.montoya.scanner.audit.issues.AuditIssue
import burp.api.montoya.websocket.Direction
import kotlinx.serialization.Serializable
import java.util.regex.Pattern
import net.portswigger.mcp.tools.*

fun AuditIssue.toSerializableForm(): IssueDetails {
    return IssueDetails(
        name = name(),
        detail = detail(),
        remediation = remediation(),
        httpService = HttpService(
            host = httpService().host(),
            port = httpService().port(),
            secure = httpService().secure()
        ),
        baseUrl = baseUrl(),
        severity = AuditIssueSeverity.valueOf(severity().name),
        confidence = AuditIssueConfidence.valueOf(confidence().name),
        requestResponses = requestResponses().map { it.toSerializableForm() },
        collaboratorInteractions = collaboratorInteractions().map {
            Interaction(
                interactionId = it.id().toString(),
                timestamp = it.timeStamp().toString()
            )
        },
        definition = AuditIssueDefinition(
            id = definition().name(),
            background = definition().background(),
            remediation = definition().remediation(),
            typeIndex = definition().typeIndex(),
        )
    )
}

fun burp.api.montoya.http.message.HttpRequestResponse.toSerializableForm(): HttpRequestResponse {
    return HttpRequestResponse(
        request = request()?.toString() ?: "<no request>",
        response = response()?.toString() ?: "<no response>",
        notes = annotations().notes()
    )
}

fun ProxyHttpRequestResponse.toSerializableForm(): HttpRequestResponse {
    return HttpRequestResponse(
        request = request()?.toString() ?: "<no request>",
        response = response()?.toString() ?: "<no response>",
        notes = annotations().notes()
    )
}

fun ProxyHttpRequestResponse.toSerializableForm(fields: Set<String>, extractPatterns: Map<String, String> = emptyMap()): Any {
    return if (fields.isEmpty() && extractPatterns.isEmpty()) {
        // Current behavior - return full HttpRequestResponse
        HttpRequestResponse(
            request = request()?.toString() ?: "<no request>",
            response = response()?.toString() ?: "<no response>",
            notes = annotations().notes()
        )
    } else {
        // New behavior - return only requested fields and extracted patterns
        FieldSelectiveResponse(
            url = if ("url" in fields) request()?.url() else null,
            method = if ("method" in fields) request()?.method() else null,
            status = if ("status" in fields) response()?.statusCode()?.toInt() else null,
            requestHeaders = if ("headers" in fields) 
                request()?.headers()?.associate { it.name() to it.value() } else null,
            responseHeaders = if ("headers" in fields) 
                response()?.headers()?.associate { it.name() to it.value() } else null,
            cookies = if ("cookies" in fields) extractSimpleCookies() else null,
            links = if ("links" in fields) extractSimpleLinks() else null,
            visibleText = if ("text" in fields) extractVisibleText() else null,
            requestBody = if ("requestBody" in fields) extractRequestBody() else null,
            responseBody = if ("responseBody" in fields) extractResponseBody() else null,
            extractedPatterns = if (extractPatterns.isNotEmpty()) extractPatterns(extractPatterns) else null,
            notes = annotations().notes()
        )
    }
}

fun ProxyWebSocketMessage.toSerializableForm(): WebSocketMessage {
    return WebSocketMessage(
        payload = payload()?.toString() ?: "<no payload>",
        direction =
            if (direction() == Direction.CLIENT_TO_SERVER)
                WebSocketMessageDirection.CLIENT_TO_SERVER
            else
                WebSocketMessageDirection.SERVER_TO_CLIENT,
        notes = annotations().notes()
    )
}

@Serializable
data class IssueDetails(
    val name: String?,
    val detail: String?,
    val remediation: String?,
    val httpService: HttpService?,
    val baseUrl: String?,
    val severity: AuditIssueSeverity,
    val confidence: AuditIssueConfidence,
    val requestResponses: List<HttpRequestResponse>,
    val collaboratorInteractions: List<Interaction>,
    val definition: AuditIssueDefinition
)

@Serializable
data class HttpService(
    val host: String,
    val port: Int,
    val secure: Boolean
)

@Serializable
enum class AuditIssueSeverity {
    HIGH,
    MEDIUM,
    LOW,
    INFORMATION,
    FALSE_POSITIVE;
}

@Serializable
enum class AuditIssueConfidence {
    CERTAIN,
    FIRM,
    TENTATIVE
}

@Serializable
data class HttpRequestResponse(
    val request: String?,
    val response: String?,
    val notes: String?
)

@Serializable
data class Interaction(
    val interactionId: String,
    val timestamp: String
)

@Serializable
data class AuditIssueDefinition(
    val id: String,
    val background: String?,
    val remediation: String?,
    val typeIndex: Int
)


@Serializable
enum class WebSocketMessageDirection {
    CLIENT_TO_SERVER,
    SERVER_TO_CLIENT
}

@Serializable
data class WebSocketMessage(
    val payload: String?,
    val direction: WebSocketMessageDirection,
    val notes: String?
)

@Serializable
data class FieldSelectiveResponse(
    val url: String? = null,
    val method: String? = null,
    val status: Int? = null,
    val requestHeaders: Map<String, String>? = null,
    val responseHeaders: Map<String, String>? = null,
    val cookies: List<String>? = null,
    val links: List<String>? = null,
    val visibleText: String? = null,
    val requestBody: String? = null,
    val responseBody: String? = null,
    val extractedPatterns: Map<String, List<List<String>>>? = null,
    val notes: String? = null
)

@Serializable
data class SearchExcerpt(
    val proxyId: String,
    val url: String,
    val method: String,
    val status: Int?,
    val excerpt: String,
    val extractedGroups: List<List<String>>? = null,
    val timestamp: Long
)