package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.Annotations
import burp.api.montoya.core.Registration
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpMessage
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.organizer.OrganizerItem
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

private const val MAX_SEARCH_RESULTS = 100
private const val MAX_BODY_WINDOW = 65_536
private const val MAX_ARTIFACTS = 1_000
private val HTTP_PARTS = setOf("request_line", "request_headers", "request_body", "response_headers", "response_body")
private val HTTP_SOURCES = setOf("proxy", "organizer", "site_map", "repeater", "mcp_send")

@Serializable
data class SearchHttpMessages(
    val sources: List<String> = HTTP_SOURCES.toList(),
    val method: String? = null,
    val host: String? = null,
    val urlRegex: String? = null,
    val status: Int? = null,
    val mimeType: String? = null,
    val newestFirst: Boolean = true,
    val count: Int = 20,
    val offset: Int = 0
) {
    init {
        require(sources.isNotEmpty()) { "sources must not be empty" }
        require(sources.all { it in HTTP_SOURCES }) { "sources must contain only ${HTTP_SOURCES.joinToString()}" }
        require(count in 1..MAX_SEARCH_RESULTS) { "count must be between 1 and $MAX_SEARCH_RESULTS" }
        require(offset >= 0) { "offset must be 0 or greater" }
        require(status == null || status in 100..999) { "status must be between 100 and 999" }
        urlRegex?.let(::compileRegex)
    }
}

@Serializable
data class InspectHttpMessage(
    val handle: String,
    val parts: List<String>,
    val bodyOffset: Int = 0,
    val bodyLength: Int = 4_096
) {
    init {
        require(handle.isNotBlank()) { "handle must not be blank" }
        require(parts.isNotEmpty()) { "parts must not be empty" }
        require(parts.all { it in HTTP_PARTS }) { "parts must contain only ${HTTP_PARTS.joinToString()}" }
        require(bodyOffset >= 0) { "bodyOffset must be 0 or greater" }
        require(bodyLength in 0..MAX_BODY_WINDOW) { "bodyLength must be between 0 and $MAX_BODY_WINDOW" }
    }
}

@Serializable
data class SearchHttpMessagesResult(val items: List<HttpMessageSummary>, val nextOffset: Int?)

@Serializable
data class HttpMessageSummary(
    val handle: String,
    val source: String,
    val sourceId: String,
    val requestFingerprint: String,
    val responseFingerprint: String?,
    val sourceLive: Boolean,
    val snapshotAvailable: Boolean,
    val method: String,
    val url: String,
    val urlTruncated: Boolean,
    val service: HttpServiceSummary,
    val status: Int?,
    val mimeType: String,
    val requestSize: Int,
    val responseSize: Int?,
    val capturedAt: String,
    val sourceTimestamp: String?,
    val notes: String,
    val annotationsTruncated: Boolean,
    val highlightColor: String?
)

@Serializable
data class HttpServiceSummary(val host: String, val port: Int, val secure: Boolean)

@Serializable
data class InspectHttpMessageResult(
    val handle: String,
    val source: String,
    val sourceLive: Boolean,
    val snapshotAvailable: Boolean,
    val requestFingerprint: String,
    val responseFingerprint: String?,
    val parts: List<InspectedHttpPart>
)

@Serializable
data class InspectedHttpPart(
    val name: String,
    val available: Boolean,
    val content: String?,
    val originalSize: Int,
    val rangeStart: Int,
    val rangeEndExclusive: Int,
    val hasMore: Boolean,
    val truncated: Boolean,
    val redacted: Boolean
)

internal fun Server.registerHttpMessageTools(api: MontoyaApi, config: McpConfig, artifacts: HttpArtifactRegistry) {
    mcpStructuredTool<SearchHttpMessages, SearchHttpMessagesResult>(
        "Search compact metadata for HTTP artifacts from Proxy, Organizer, site map, Repeater, or MCP sends. " +
            "Returns stable extension-owned handles, never headers or bodies. Evicted artifacts can be recovered by searching live sources again."
    ) {
        val requestedSources = sources.distinct().toSet()
        requireSourceAccess(api, config, requestedSources)
        artifacts.refresh(requestedSources)
        val urlPattern = urlRegex?.let(Pattern::compile)
        val matching = artifacts.all().asSequence().filter { artifact ->
            artifact.source in requestedSources &&
                (method == null || artifact.method.equals(method, ignoreCase = true)) &&
                (host == null || artifact.service.host.equals(host, ignoreCase = true)) &&
                (urlPattern == null || urlPattern.matcher(artifact.url).find()) &&
                (status == null || artifact.status == status) &&
                (mimeType == null || artifact.mimeType.equals(mimeType, ignoreCase = true))
        }
        val ordered = if (newestFirst) matching.sortedByDescending { it.capturedAt }.asSequence() else matching
        val page = ordered.drop(offset).take(count + 1).toList()
        SearchHttpMessagesResult(page.take(count).map(HttpArtifact::summary), if (page.size > count) offset + count else null)
    }

    mcpStructuredTool<InspectHttpMessage, InspectHttpMessageResult>(
        "Inspect selected request or response parts for a handle returned by search_http_messages. " +
            "Body windows are byte ranges; unknown or evicted handles require another search."
    ) {
        val artifact = artifacts.resolve(handle)
        requireSourceAccess(api, config, setOf(artifact.source))
        InspectHttpMessageResult(
            handle, artifact.source, artifact.sourceLive, artifact.snapshotAvailable,
            artifact.requestFingerprint, artifact.responseFingerprint,
            parts.distinct().map { part ->
                when (part) {
                    "request_line" -> textPart(part, "${artifact.request.method()} ${artifact.request.path()} ${artifact.request.httpVersion()}")
                    "request_headers" -> textPart(part, artifact.request.headers().asText())
                    "request_body" -> bodyPart(part, artifact.request, bodyOffset, bodyLength)
                    "response_headers" -> artifact.response?.let { response ->
                        textPart(part, "${response.httpVersion()} ${response.statusCode()} ${response.reasonPhrase()}\r\n${response.headers().asText()}")
                    } ?: unavailablePart(part)
                    "response_body" -> artifact.response?.let { bodyPart(part, it, bodyOffset, bodyLength) } ?: unavailablePart(part)
                    else -> error("validated part was not handled")
                }
            }
        )
    }
}

private fun requireSourceAccess(api: MontoyaApi, config: McpConfig, sources: Set<String>) {
    sources.mapTo(mutableSetOf()) {
        when (it) {
            "organizer" -> DataAccessType.ORGANIZER
            else -> DataAccessType.HTTP_HISTORY
        }
    }
        .forEach { accessType ->
            val allowed = runBlocking { DataAccessSecurity.checkDataAccessPermission(accessType, config) }
            api.logging().logToOutput("MCP ${accessType.name.lowercase()} access ${if (allowed) "granted" else "denied"}")
            if (!allowed) mcpError("HTTP artifact access was denied in Burp Suite. Allow project data access and retry.")
        }
}

internal class HttpArtifactRegistry(
    private val api: MontoyaApi,
    private val maxArtifacts: Int = MAX_ARTIFACTS,
    private val now: () -> Instant = Instant::now
) : AutoCloseable {
    private val byIdentity = LinkedHashMap<String, HttpArtifact>(16, 0.75f, true)
    private val byHandle = mutableMapOf<String, HttpArtifact>()
    private val sendSequence = AtomicLong()
    private var handlerRegistration: Registration? = null

    @Synchronized
    fun start() {
        if (handlerRegistration != null) return
        handlerRegistration = api.http().registerHttpHandler(object : HttpHandler {
            override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent) =
                RequestToBeSentAction.continueWith(requestToBeSent)

            override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
                if (responseReceived.toolSource().isFromTool(ToolType.REPEATER)) {
                    capture(
                        "repeater", "${responseReceived.messageId()}:${responseReceived.initiatingRequest().fingerprint()}",
                        responseReceived.initiatingRequest(), responseReceived, responseReceived.annotations(), sourceLive = false
                    )
                }
                return ResponseReceivedAction.continueWith(responseReceived)
            }
        })
    }

    fun captureMcpSend(exchange: HttpRequestResponse) {
        runCatching {
            val request = exchange.request() ?: return
            capture(
                "mcp_send", sendSequence.incrementAndGet().toString(), request, exchange.response(),
                exchange.annotations(), sourceLive = false
            )
        }.onFailure { api.logging().logToError("Could not index MCP HTTP exchange") }
    }

    @Synchronized
    fun refresh(sources: Set<String>) {
        val queryable = sources intersect setOf("proxy", "organizer", "site_map")
        byIdentity.values.filter { it.source in queryable }.forEach { it.sourceLive = false }
        if ("proxy" in sources) api.proxy().history().forEach(::captureProxy)
        if ("organizer" in sources) api.organizer().items().forEach(::captureOrganizer)
        if ("site_map" in sources) api.siteMap().requestResponses().forEach(::captureSiteMap)
    }

    @Synchronized
    fun all(): List<HttpArtifact> = byIdentity.values.toList()

    @Synchronized
    fun resolve(handle: String): HttpArtifact = byHandle[handle]
        ?: mcpError("Unknown HTTP artifact handle. It may have been evicted or the extension restarted; run search_http_messages again.")

    override fun close() {
        handlerRegistration?.deregister()
        handlerRegistration = null
    }

    private fun captureProxy(item: ProxyHttpRequestResponse) = capture(
        "proxy", item.id().toString(), item.finalRequest(), item.response(), item.annotations(),
        item.time().toInstant(), true, item.mimeType().name
    )

    private fun captureOrganizer(item: OrganizerItem) {
        val request = item.request() ?: return
        capture("organizer", item.id().toString(), request, item.response(), item.annotations(), sourceLive = true)
    }

    private fun captureSiteMap(item: HttpRequestResponse) {
        val request = item.request() ?: return
        val response = item.response()
        capture(
            "site_map", "${request.fingerprint()}:${response?.fingerprint() ?: "no_response"}",
            request, response, item.annotations(), sourceLive = true
        )
    }

    @Synchronized
    private fun capture(
        source: String,
        sourceId: String,
        request: HttpRequest,
        response: HttpResponse?,
        annotations: Annotations,
        sourceTimestamp: Instant? = null,
        sourceLive: Boolean,
        mimeType: String? = null
    ): HttpArtifact {
        val identity = "$source:$sourceId"
        val requestFingerprint = request.fingerprint()
        val responseFingerprint = response?.fingerprint()
        val notes = annotations.notes().orEmpty().bounded(500)
        val existing = byIdentity[identity]
        if (existing != null && existing.requestFingerprint == requestFingerprint && existing.responseFingerprint == responseFingerprint) {
            existing.sourceLive = sourceLive
            existing.sourceTimestamp = sourceTimestamp ?: existing.sourceTimestamp
            existing.notes = notes.value
            existing.annotationsTruncated = notes.truncated
            existing.highlightColor = if (annotations.hasHighlightColor()) annotations.highlightColor().name else null
            return existing
        }

        val requestSnapshot = runCatching { request.copyToTempFile() }.getOrNull()
        val responseSnapshot = response?.let { runCatching { it.copyToTempFile() }.getOrNull() }
        val service = request.httpService()
        val url = request.url().bounded(2_048)
        val artifact = HttpArtifact(
            existing?.handle ?: identity.stableHandle(),
            source, sourceId, requestSnapshot ?: request, responseSnapshot ?: response, requestFingerprint, responseFingerprint,
            sourceLive, requestSnapshot != null && (response == null || responseSnapshot != null),
            request.method().bounded(32).value, url.value, url.truncated,
            HttpServiceSummary(service.host().bounded(253).value, service.port(), service.secure()),
            response?.statusCode()?.toInt(), mimeType ?: response?.mimeType()?.name ?: request.contentType().name,
            request.toByteArray().length(), response?.toByteArray()?.length(), existing?.capturedAt ?: now(), sourceTimestamp,
            notes.value, notes.truncated, if (annotations.hasHighlightColor()) annotations.highlightColor().name else null
        )
        existing?.let { byHandle.remove(it.handle) }
        byIdentity[identity] = artifact
        byHandle[artifact.handle] = artifact
        while (byIdentity.size > maxArtifacts) {
            val evicted = byIdentity.entries.first()
            byIdentity.remove(evicted.key)
            byHandle.remove(evicted.value.handle)
        }
        return artifact
    }
}

internal data class HttpArtifact(
    val handle: String,
    val source: String,
    val sourceId: String,
    val request: HttpRequest,
    val response: HttpResponse?,
    val requestFingerprint: String,
    val responseFingerprint: String?,
    var sourceLive: Boolean,
    val snapshotAvailable: Boolean,
    val method: String,
    val url: String,
    val urlTruncated: Boolean,
    val service: HttpServiceSummary,
    val status: Int?,
    val mimeType: String,
    val requestSize: Int,
    val responseSize: Int?,
    val capturedAt: Instant,
    var sourceTimestamp: Instant?,
    var notes: String,
    var annotationsTruncated: Boolean,
    var highlightColor: String?
) {
    fun summary() = HttpMessageSummary(
        handle, source, sourceId, requestFingerprint, responseFingerprint, sourceLive, snapshotAvailable,
        method, url, urlTruncated, service, status, mimeType, requestSize, responseSize,
        capturedAt.toString(), sourceTimestamp?.toString(), notes, annotationsTruncated, highlightColor
    )
}

private fun HttpMessage.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray().bytes)
    .joinToString("") { "%02x".format(it) }

private fun String.stableHandle(): String = "http_" + MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .take(16)
    .joinToString("") { "%02x".format(it) }

private fun List<HttpHeader>.asText() = joinToString("\r\n") { "${it.name()}: ${it.value()}" }

private fun textPart(name: String, content: String): InspectedHttpPart {
    val size = content.toByteArray().size
    return InspectedHttpPart(name, true, content, size, 0, size, false, false, false)
}

private fun bodyPart(name: String, message: HttpMessage, offset: Int, length: Int): InspectedHttpPart {
    val body = message.body()
    val start = offset.coerceAtMost(body.length())
    val end = (start.toLong() + length).coerceAtMost(body.length().toLong()).toInt()
    return InspectedHttpPart(
        name, true, body.subArray(start, end).toString(), body.length(), start, end,
        hasMore = end < body.length(), truncated = start > 0 || end < body.length(), redacted = false
    )
}

private fun unavailablePart(name: String) = InspectedHttpPart(name, false, null, 0, 0, 0, false, false, false)

private data class BoundedText(val value: String, val truncated: Boolean)
private fun String.bounded(maxLength: Int) = BoundedText(take(maxLength), length > maxLength)

private fun compileRegex(regex: String) {
    try {
        Pattern.compile(regex)
    } catch (error: PatternSyntaxException) {
        throw IllegalArgumentException("urlRegex is invalid at index ${error.index}: ${error.description}")
    }
}
