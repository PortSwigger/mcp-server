package net.portswigger.mcp.schema

import burp.api.montoya.collaborator.Interaction as CollaboratorInteraction
import burp.api.montoya.organizer.OrganizerItem
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import burp.api.montoya.proxy.ProxyWebSocketMessage
import burp.api.montoya.scanner.ScanTask
import burp.api.montoya.scanner.audit.Audit
import burp.api.montoya.scanner.audit.issues.AuditIssue
import burp.api.montoya.websocket.Direction
import kotlinx.serialization.Serializable

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

// Burp does not implement every ScanTask getter yet; unsupported ones throw rather than return a value.
private inline fun <T> ifSupported(getter: () -> T): T? = try {
    getter()
} catch (_: UnsupportedOperationException) {
    null
}

fun ScanTask.toSerializableForm(scanId: String): ScanTaskDetails {
    val audit = this as? Audit
    return ScanTaskDetails(
        scanId = scanId,
        type = if (audit != null) "audit" else "crawl",
        statusMessage = ifSupported { statusMessage() },
        requestCount = ifSupported { requestCount() },
        errorCount = ifSupported { errorCount() },
        insertionPointCount = audit?.let { ifSupported { it.insertionPointCount() } },
        issueCounts = audit?.let {
            ifSupported { it.issues().groupingBy { issue -> AuditIssueSeverity.valueOf(issue.severity().name) }.eachCount() }
        }
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

fun OrganizerItem.toSerializableForm(): OrganizerItemDetails {
    return OrganizerItemDetails(
        id = id(),
        status = status().displayName(),
        request = request()?.toString() ?: "<no request>",
        response = response()?.toString() ?: "<no response>",
        notes = annotations().notes()
    )
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
data class ScanTaskDetails(
    val scanId: String,
    val type: String,
    val statusMessage: String?,
    val requestCount: Int?,
    val errorCount: Int?,
    val insertionPointCount: Int?,
    val issueCounts: Map<AuditIssueSeverity, Int>?
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
data class OrganizerItemDetails(
    val id: Int,
    val status: String,
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

fun CollaboratorInteraction.toSerializableForm(): CollaboratorInteractionDetails {
    return CollaboratorInteractionDetails(
        id = id().toString(),
        type = type().name,
        timestamp = timeStamp().toString(),
        clientIp = clientIp().hostAddress,
        clientPort = clientPort(),
        customData = customData().orElse(null),
        dnsDetails = dnsDetails().orElse(null)?.let {
            CollaboratorDnsDetails(queryType = it.queryType().name)
        },
        httpDetails = httpDetails().orElse(null)?.let {
            CollaboratorHttpDetails(
                protocol = it.protocol().name,
                request = it.requestResponse()?.request()?.toString(),
                response = it.requestResponse()?.response()?.toString()
            )
        },
        smtpDetails = smtpDetails().orElse(null)?.let {
            CollaboratorSmtpDetails(
                protocol = it.protocol().name,
                conversation = it.conversation()
            )
        }
    )
}

@Serializable
data class CollaboratorInteractionDetails(
    val id: String,
    val type: String,
    val timestamp: String,
    val clientIp: String,
    val clientPort: Int,
    val customData: String?,
    val dnsDetails: CollaboratorDnsDetails?,
    val httpDetails: CollaboratorHttpDetails?,
    val smtpDetails: CollaboratorSmtpDetails?
)

@Serializable
data class CollaboratorDnsDetails(
    val queryType: String
)

@Serializable
data class CollaboratorHttpDetails(
    val protocol: String,
    val request: String?,
    val response: String?
)

@Serializable
data class CollaboratorSmtpDetails(
    val protocol: String,
    val conversation: String
)