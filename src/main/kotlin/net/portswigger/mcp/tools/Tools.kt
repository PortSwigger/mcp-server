package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.PAUSED
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.RUNNING
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.HttpRequestResponse
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.logging.Logging
import burp.api.montoya.scanner.AuditConfiguration
import burp.api.montoya.scanner.BuiltInAuditConfiguration
import burp.api.montoya.scanner.CrawlConfiguration
import burp.api.montoya.scanner.audit.Audit
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.toSerializableForm
import net.portswigger.mcp.security.HistoryAccessSecurity
import net.portswigger.mcp.security.HistoryAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import java.awt.KeyboardFocusManager
import java.util.regex.Pattern
import javax.swing.JTextArea

private data class FocusedAuditTarget(
    val uri: java.net.URI,
    val host: String,
    val port: Int,
    val usesHttps: Boolean,
)

private fun normalizedPath(path: String?): String {
    if (path.isNullOrBlank()) {
        return "/"
    }
    return if (path.startsWith("/")) path else "/$path"
}

private fun resolvedPort(uri: java.net.URI): Int? {
    if (uri.port != -1) {
        return uri.port
    }

    return when (uri.scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> null
    }
}

private fun isPathWithinScope(requestPath: String?, targetPath: String?): Boolean {
    val normalizedTargetPath = normalizedPath(targetPath).removeSuffix("/").ifEmpty { "/" }
    if (normalizedTargetPath == "/") {
        return true
    }

    val normalizedRequestPath = normalizedPath(requestPath).removeSuffix("/").ifEmpty { "/" }
    return normalizedRequestPath == normalizedTargetPath || normalizedRequestPath.startsWith("$normalizedTargetPath/")
}

private fun matchesFocusedAuditTarget(requestUri: java.net.URI, target: FocusedAuditTarget): Boolean {
    val requestScheme = requestUri.scheme?.lowercase() ?: return false
    val targetScheme = target.uri.scheme?.lowercase() ?: return false
    if (requestScheme != targetScheme) {
        return false
    }

    val requestHost = requestUri.host ?: return false
    if (!requestHost.equals(target.host, ignoreCase = true)) {
        return false
    }

    val requestPort = resolvedPort(requestUri) ?: return false
    if (requestPort != target.port) {
        return false
    }

    return isPathWithinScope(requestUri.path, target.uri.path)
}

private fun parseFocusedAuditTarget(targetUrl: String): FocusedAuditTarget {
    val uri = java.net.URI(targetUrl)
    val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("targetUrl must include a scheme")
    val host = uri.host ?: throw IllegalArgumentException("targetUrl must include a host")
    val usesHttps = when (scheme) {
        "https" -> true
        "http" -> false
        else -> throw IllegalArgumentException("targetUrl scheme must be http or https")
    }
    val port = if (uri.port != -1) uri.port else if (usesHttps) 443 else 80
    return FocusedAuditTarget(uri, host, port, usesHttps)
}

private fun validateFocusedAuditRequest(target: FocusedAuditTarget, request: String) {
    if (request.isBlank()) {
        throw IllegalArgumentException("request must not be blank")
    }

    val lines = request.replace("\r\n", "\n").split('\n')
    if (lines.isEmpty() || lines.first().isBlank()) {
        throw IllegalArgumentException("request must include a request line")
    }

    val hostHeader = lines
        .drop(1)
        .takeWhile { it.isNotEmpty() }
        .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()

    if (hostHeader != null) {
        val requestHost = hostHeader.substringBefore(':').trim()
        if (!requestHost.equals(target.host, ignoreCase = true)) {
            throw IllegalArgumentException("request host must match targetUrl host")
        }
    }
}

private fun addMatchingResponsesToAudit(
    audit: Audit,
    requestResponses: List<HttpRequestResponse>,
    target: FocusedAuditTarget,
    seen: MutableSet<String>,
    logging: Logging,
) {
    requestResponses.forEach { requestResponse ->
        if (requestResponse.response() == null) {
            return@forEach
        }

        val request = requestResponse.request()
        val url = request.url()
        val method = request.method()

        try {
            val requestUri = java.net.URI(url)
            if (matchesFocusedAuditTarget(requestUri, target)) {
                val dedupKey = "$method:$url"
                if (seen.add(dedupKey)) {
                    audit.addRequestResponse(requestResponse)
                    logging.logToOutput("MCP start_active_audit: added site map item to audit: $url")
                }
            }
        } catch (_: Exception) {
            // Skip malformed URLs
        }
    }
}

private fun validateScanDuration(scanDurationSeconds: Int?) {
    if (scanDurationSeconds != null && scanDurationSeconds <= 0) {
        throw IllegalArgumentException("scanDurationSeconds must be greater than 0")
    }
}

private fun includeInScopeIfNeeded(api: MontoyaApi, targetUrl: String): (() -> Unit)? {
    val scope = api.scope()
    if (scope.isInScope(targetUrl)) {
        return null
    }

    scope.includeInScope(targetUrl)
    return { scope.excludeFromScope(targetUrl) }
}

private suspend fun checkHistoryPermissionOrDeny(
    accessType: HistoryAccessType, config: McpConfig, api: MontoyaApi, logMessage: String
): Boolean {
    val allowed = HistoryAccessSecurity.checkHistoryAccessPermission(accessType, config)
    if (!allowed) {
        api.logging().logToOutput("MCP $logMessage access denied")
        return false
    }
    api.logging().logToOutput("MCP $logMessage access granted")
    return true
}

private fun truncateIfNeeded(serialized: String): String {
    return if (serialized.length > 5000) {
        serialized.substring(0, 5000) + "... (truncated)"
    } else {
        serialized
    }
}

fun Server.registerTools(api: MontoyaApi, config: McpConfig) {

    mcpTool<SendHttp1Request>("Issues an HTTP/1.1 request and returns the response.") {
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, content, api)
        }
        if (!allowed) {
            api.logging().logToOutput("MCP HTTP request denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP HTTP/1.1 request: $targetHostname:$targetPort")

        val fixedContent = content.replace("\r", "").replace("\n", "\r\n")

        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        val response = api.http().sendRequest(request)

        response?.toString() ?: "<no response>"
    }

    mcpTool<SendHttp2Request>("Issues an HTTP/2 request and returns the response. Do NOT pass headers to the body parameter.") {
        val http2RequestDisplay = buildString {
            pseudoHeaders.forEach { (key, value) ->
                val headerName = if (key.startsWith(":")) key else ":$key"
                appendLine("$headerName: $value")
            }
            headers.forEach { (key, value) ->
                appendLine("$key: $value")
            }
            if (requestBody.isNotBlank()) {
                appendLine()
                append(requestBody)
            }
        }

        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, http2RequestDisplay, api)
        }
        if (!allowed) {
            api.logging().logToOutput("MCP HTTP request denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP HTTP/2 request: $targetHostname:$targetPort")

        val orderedPseudoHeaderNames = listOf(":scheme", ":method", ":path", ":authority")

        val fixedPseudoHeaders = LinkedHashMap<String, String>().apply {
            orderedPseudoHeaderNames.forEach { name ->
                val value = pseudoHeaders[name.removePrefix(":")] ?: pseudoHeaders[name]
                if (value != null) {
                    put(name, value)
                }
            }

            pseudoHeaders.forEach { (key, value) ->
                val properKey = if (key.startsWith(":")) key else ":$key"
                if (!containsKey(properKey)) {
                    put(properKey, value)
                }
            }
        }

        val headerList = (fixedPseudoHeaders + headers).map { HttpHeader.httpHeader(it.key.lowercase(), it.value) }

        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        val response = api.http().sendRequest(request, HttpMode.HTTP_2)

        response?.toString() ?: "<no response>"
    }

    mcpTool<CreateRepeaterTab>("Creates a new Repeater tab with the specified HTTP request and optional tab name. Make sure to use carriage returns appropriately.") {
        val request = HttpRequest.httpRequest(toMontoyaService(), content)
        api.repeater().sendToRepeater(request, tabName)
    }

    mcpTool<SendToIntruder>("Sends an HTTP request to Intruder with the specified HTTP request and optional tab name. Make sure to use carriage returns appropriately.") {
        val request = HttpRequest.httpRequest(toMontoyaService(), content)
        api.intruder().sendToIntruder(request, tabName)
    }

    mcpTool<UrlEncode>("URL encodes the input string") {
        api.utilities().urlUtils().encode(content)
    }

    mcpTool<UrlDecode>("URL decodes the input string") {
        api.utilities().urlUtils().decode(content)
    }

    mcpTool<Base64Encode>("Base64 encodes the input string") {
        api.utilities().base64Utils().encodeToString(content)
    }

    mcpTool<Base64Decode>("Base64 decodes the input string") {
        api.utilities().base64Utils().decode(content).toString()
    }

    mcpTool<GenerateRandomString>("Generates a random string of specified length and character set") {
        api.utilities().randomUtils().randomString(length, characterSet)
    }

    mcpTool(
        "output_project_options",
        "Outputs current project-level configuration in JSON format. You can use this to determine the schema for available config options."
    ) {
        api.burpSuite().exportProjectOptionsAsJson()
    }

    mcpTool(
        "output_user_options",
        "Outputs current user-level configuration in JSON format. You can use this to determine the schema for available config options."
    ) {
        api.burpSuite().exportUserOptionsAsJson()
    }

    val toolingDisabledMessage =
        "User has disabled configuration editing. They can enable it in the MCP tab in Burp by selecting 'Enable tools that can edit your config'"

    mcpTool<SetProjectOptions>("Sets project-level configuration in JSON format. This will be merged with existing configuration. Make sure to export before doing this, so you know what the schema is. Make sure the JSON has a top level 'user_options' object!") {
        if (config.configEditingTooling) {
            api.logging().logToOutput("Setting project-level configuration: $json")
            api.burpSuite().importProjectOptionsFromJson(json)

            "Project configuration has been applied"
        } else {
            toolingDisabledMessage
        }
    }


    mcpTool<SetUserOptions>("Sets user-level configuration in JSON format. This will be merged with existing configuration. Make sure to export before doing this, so you know what the schema is. Make sure the JSON has a top level 'project_options' object!") {
        if (config.configEditingTooling) {
            api.logging().logToOutput("Setting user-level configuration: $json")
            api.burpSuite().importUserOptionsFromJson(json)

            "User configuration has been applied"
        } else {
            toolingDisabledMessage
        }
    }

    if (api.burpSuite().version().edition() == BurpSuiteEdition.PROFESSIONAL) {
        val auditRegistry = ActiveAuditRegistry()

        mcpPaginatedTool<GetScannerIssues>("Displays information about issues identified by the scanner") {
            api.siteMap().issues().asSequence().map { Json.encodeToString(it.toSerializableForm()) }
        }

        val collaboratorClient by lazy { api.collaborator().createClient() }

        mcpTool<GenerateCollaboratorPayload>(
            "Generates a Burp Collaborator payload URL for out-of-band (OOB) testing. " +
            "Inject this payload into requests to detect server-side interactions (DNS lookups, HTTP requests, SMTP). " +
            "Use get_collaborator_interactions with the returned payloadId to check for interactions."
        ) {
            api.logging().logToOutput("MCP generating Collaborator payload${customData?.let { " with custom data" } ?: ""}")

            val payload = if (customData != null) {
                collaboratorClient.generatePayload(customData)
            } else {
                collaboratorClient.generatePayload()
            }

            val server = collaboratorClient.server()
            "Payload: $payload\nPayload ID: ${payload.id()}\nCollaborator server: ${server.address()}"
        }

        mcpTool<GetCollaboratorInteractions>(
            "Polls Burp Collaborator for out-of-band interactions (DNS, HTTP, SMTP). " +
            "Optionally filter by payloadId from generate_collaborator_payload. " +
            "Returns interaction details including type, timestamp, client IP, and protocol-specific data."
        ) {
            api.logging().logToOutput("MCP polling Collaborator interactions${payloadId?.let { " for payload: $it" } ?: ""}")

            val interactions = if (payloadId != null) {
                collaboratorClient.getInteractions(InteractionFilter.interactionIdFilter(payloadId))
            } else {
                collaboratorClient.getAllInteractions()
            }

            if (interactions.isEmpty()) {
                "No interactions detected"
            } else {
                interactions.joinToString("\n\n") {
                    Json.encodeToString(it.toSerializableForm())
                }
            }
        }

        mcpTool<StartActiveAudit>(
            "Starts a Burp active scan (crawl + audit) for the target URL. " +
            "If scanDurationSeconds is provided, the scan stops automatically after that many seconds. " +
            "If omitted, the scan keeps running until stop_active_audit is called. " +
            "Returns an auditId that can be used with stop_active_audit. " +
            "Use get_scanner_issues to retrieve findings."
        ) {
            validateScanDuration(scanDurationSeconds)
            val target = parseFocusedAuditTarget(targetUrl)
            val allowed = runBlocking {
                HttpRequestSecurity.checkAuditPermission(
                    target.host, target.port, config, api
                )
            }
            if (!allowed) {
                api.logging().logToOutput("MCP start_active_audit denied for $targetUrl")
                return@mcpTool "Active scan denied for $targetUrl. Approve the target in the consent dialog or add it to Auto-Approved HTTP Targets."
            }

            api.logging().logToOutput("MCP start_active_audit: starting active audit for $targetUrl")
            val scopeCleanup = includeInScopeIfNeeded(api, targetUrl)
            var crawl: burp.api.montoya.scanner.Crawl? = null
            var audit: Audit? = null
            var auditId: String? = null

            try {
                val crawlConfig = CrawlConfiguration.crawlConfiguration(targetUrl)
                val activeCrawl = api.scanner().startCrawl(crawlConfig)
                crawl = activeCrawl
                api.logging().logToOutput("MCP start_active_audit: crawl started for $targetUrl")

                val auditConfig = AuditConfiguration.auditConfiguration(
                    BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS
                )
                val activeAudit = api.scanner().startAudit(auditConfig)
                audit = activeAudit
                api.logging().logToOutput("MCP start_active_audit: audit started for $targetUrl")
                activeAudit.addRequest(HttpRequest.httpRequestFromUrl(targetUrl))
                api.logging().logToOutput("MCP start_active_audit: added initial audit request for $targetUrl")

                val seen = mutableSetOf<String>()
                val pollIntervalMs = 2000L
                val deadlineNanos = scanDurationSeconds?.let { System.nanoTime() + it.toLong() * 1_000_000_000L }

                val pollingThread = Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        val sleepMillis = deadlineNanos?.let { deadline ->
                            val remainingNanos = deadline - System.nanoTime()
                            if (remainingNanos <= 0L) {
                                api.logging().logToOutput(
                                    "MCP start_active_audit: scan duration reached (${scanDurationSeconds} seconds)"
                                )
                                val timeoutAuditId = auditId
                                if (timeoutAuditId != null) {
                                    val stopMessage = auditRegistry.stopById(
                                        timeoutAuditId,
                                        interruptPollingThread = false
                                    )
                                    if (stopMessage != null) {
                                        api.logging().logToOutput("MCP start_active_audit: $stopMessage")
                                    }
                                }
                                return@Thread
                            }
                            minOf(pollIntervalMs, maxOf(1L, remainingNanos / 1_000_000L))
                        } ?: pollIntervalMs

                        try {
                            Thread.sleep(sleepMillis)
                            addMatchingResponsesToAudit(
                                audit = activeAudit,
                                requestResponses = api.siteMap().requestResponses(),
                                target = target,
                                seen = seen,
                                logging = api.logging(),
                            )
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        } catch (e: Exception) {
                            api.logging().logToOutput("MCP start_active_audit: polling error: ${e.message}")
                        }
                    }
                }.apply { isDaemon = true }

                auditId = auditRegistry.register(
                    crawl = activeCrawl,
                    audit = activeAudit,
                    pollingThread = pollingThread,
                    cleanup = scopeCleanup,
                )
                pollingThread.start()

                api.logging().logToOutput("MCP start_active_audit: registered as $auditId")
                val stopMessage = if (scanDurationSeconds == null) {
                    "Use stop_active_audit to stop."
                } else {
                    "It will stop automatically after $scanDurationSeconds seconds or sooner if stop_active_audit is used."
                }
                "Active scan started for $targetUrl (auditId: $auditId). Use get_scanner_issues to retrieve findings. $stopMessage"
            } catch (e: Exception) {
                if (auditId != null) {
                    auditRegistry.stopById(auditId!!, interruptPollingThread = false)
                } else {
                    try {
                        audit?.delete()
                    } catch (_: Exception) {}
                    try {
                        crawl?.delete()
                    } catch (_: Exception) {}
                    try {
                        scopeCleanup?.invoke()
                    } catch (_: Exception) {}
                }
                throw e
            }
        }

        mcpTool<StartActiveAuditForRequest>(
            "Starts a focused Burp active audit for a specific HTTP request. " +
            "Returns an auditId that can be used with stop_active_audit. " +
            "Use get_scanner_issues to retrieve findings."
        ) {
            val target = parseFocusedAuditTarget(targetUrl)
            validateFocusedAuditRequest(target, request)
            val allowed = runBlocking {
                HttpRequestSecurity.checkAuditPermission(
                    target.host, target.port, config, api
                )
            }
            if (!allowed) {
                api.logging().logToOutput("MCP start_active_audit_for_request denied for $targetUrl")
                return@mcpTool "Active scan denied for $targetUrl. Approve the target in the consent dialog or add it to Auto-Approved HTTP Targets."
            }

            api.logging().logToOutput("MCP start_active_audit_for_request: starting focused audit for $targetUrl")
            val scopeCleanup = includeInScopeIfNeeded(api, targetUrl)
            var audit: Audit? = null
            var auditId: String? = null

            try {
                val auditConfig = AuditConfiguration.auditConfiguration(
                    BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS
                )
                val activeAudit = api.scanner().startAudit(auditConfig)
                audit = activeAudit
                api.logging().logToOutput("MCP start_active_audit_for_request: audit started for $targetUrl")

                val service = HttpService.httpService(target.host, target.port, target.usesHttps)
                val fixedRequest = request.replace("\r", "").replace("\n", "\r\n")
                val httpRequest = HttpRequest.httpRequest(service, fixedRequest)

                if (response != null) {
                    val httpResponse = HttpResponse.httpResponse(response)
                    val requestResponse = HttpRequestResponse.httpRequestResponse(httpRequest, httpResponse)
                    activeAudit.addRequestResponse(requestResponse)
                    api.logging().logToOutput("MCP start_active_audit_for_request: injected request-response for $targetUrl")
                } else {
                    activeAudit.addRequest(httpRequest)
                    api.logging().logToOutput("MCP start_active_audit_for_request: injected request for $targetUrl")
                }

                auditId = auditRegistry.register(audit = activeAudit, cleanup = scopeCleanup)
                api.logging().logToOutput("MCP start_active_audit_for_request: registered as $auditId")
                "Focused active audit started for $targetUrl (auditId: $auditId). Use get_scanner_issues to retrieve findings. Use stop_active_audit to stop."
            } catch (e: Exception) {
                if (auditId != null) {
                    auditRegistry.stopById(auditId!!, interruptPollingThread = false)
                } else {
                    try {
                        audit?.delete()
                    } catch (_: Exception) {}
                    try {
                        scopeCleanup?.invoke()
                    } catch (_: Exception) {}
                }
                throw e
            }
        }

        mcpTool<StopActiveAudit>(
            "Stops running active audits started via MCP. " +
            "With no auditId, stops all. With an auditId, stops only that audit."
        ) {
            if (auditId != null) {
                val message = auditRegistry.stopById(auditId)
                if (message != null) {
                    api.logging().logToOutput("MCP stop_active_audit: $message")
                    message
                } else {
                    api.logging().logToOutput("MCP stop_active_audit: $auditId not found")
                    "Audit $auditId not found"
                }
            } else {
                val result = auditRegistry.stopAll()
                val message = if (result.failed > 0) {
                    "Stopped ${result.total} audit(s); ${result.failed} failed: ${result.errors.joinToString(", ")}"
                } else {
                    "Stopped ${result.total} audit(s)"
                }
                api.logging().logToOutput("MCP stop_active_audit: $message")
                message
            }
        }
    }

    mcpPaginatedTool<GetProxyHttpHistory>("Displays items within the proxy HTTP history") {
        val allowed = runBlocking {
            checkHistoryPermissionOrDeny(HistoryAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        api.proxy().history().asSequence().map { truncateIfNeeded(Json.encodeToString(it.toSerializableForm())) }
    }

    mcpPaginatedTool<GetProxyHttpHistoryRegex>("Displays items matching a specified regex within the proxy HTTP history") {
        val allowed = runBlocking {
            checkHistoryPermissionOrDeny(HistoryAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.proxy().history { it.contains(compiledRegex) }.asSequence()
            .map { truncateIfNeeded(Json.encodeToString(it.toSerializableForm())) }
    }

    mcpPaginatedTool<GetProxyWebsocketHistory>("Displays items within the proxy WebSocket history") {
        val allowed = runBlocking {
            checkHistoryPermissionOrDeny(HistoryAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("WebSocket history access denied by Burp Suite")
        }

        api.proxy().webSocketHistory().asSequence()
            .map { truncateIfNeeded(Json.encodeToString(it.toSerializableForm())) }
    }

    mcpPaginatedTool<GetProxyWebsocketHistoryRegex>("Displays items matching a specified regex within the proxy WebSocket history") {
        val allowed = runBlocking {
            checkHistoryPermissionOrDeny(HistoryAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("WebSocket history access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.proxy().webSocketHistory { it.contains(compiledRegex) }.asSequence()
            .map { truncateIfNeeded(Json.encodeToString(it.toSerializableForm())) }
    }

    mcpTool<SetTaskExecutionEngineState>("Sets the state of Burp's task execution engine (paused or unpaused)") {
        api.burpSuite().taskExecutionEngine().state = if (running) RUNNING else PAUSED

        "Task execution engine is now ${if (running) "running" else "paused"}"
    }

    mcpTool<SetProxyInterceptState>("Enables or disables Burp Proxy Intercept") {
        if (intercepting) {
            api.proxy().enableIntercept()
        } else {
            api.proxy().disableIntercept()
        }

        "Intercept has been ${if (intercepting) "enabled" else "disabled"}"
    }

    mcpTool("get_active_editor_contents", "Outputs the contents of the user's active message editor") {
        getActiveEditor(api)?.text ?: "<No active editor>"
    }

    mcpTool<SetActiveEditorContents>("Sets the content of the user's active message editor") {
        val editor = getActiveEditor(api) ?: return@mcpTool "<No active editor>"

        if (!editor.isEditable) {
            return@mcpTool "<Current editor is not editable>"
        }

        editor.text = text

        "Editor text has been set"
    }
}

fun getActiveEditor(api: MontoyaApi): JTextArea? {
    val frame = api.userInterface().swingUtils().suiteFrame()

    val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val permanentFocusOwner = focusManager.permanentFocusOwner

    val isInBurpWindow = generateSequence(permanentFocusOwner) { it.parent }.any { it == frame }

    return if (isInBurpWindow && permanentFocusOwner is JTextArea) {
        permanentFocusOwner
    } else {
        null
    }
}

interface HttpServiceParams {
    val targetHostname: String
    val targetPort: Int
    val usesHttps: Boolean

    fun toMontoyaService(): HttpService = HttpService.httpService(targetHostname, targetPort, usesHttps)
}

@Serializable
data class SendHttp1Request(
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class SendHttp2Request(
    val pseudoHeaders: Map<String, String>,
    val headers: Map<String, String>,
    val requestBody: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class CreateRepeaterTab(
    val tabName: String?,
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class SendToIntruder(
    val tabName: String?,
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class UrlEncode(val content: String)

@Serializable
data class UrlDecode(val content: String)

@Serializable
data class Base64Encode(val content: String)

@Serializable
data class Base64Decode(val content: String)

@Serializable
data class GenerateRandomString(val length: Int, val characterSet: String)

@Serializable
data class SetProjectOptions(val json: String)

@Serializable
data class SetUserOptions(val json: String)

@Serializable
data class SetTaskExecutionEngineState(val running: Boolean)

@Serializable
data class SetProxyInterceptState(val intercepting: Boolean)

@Serializable
data class SetActiveEditorContents(val text: String)

@Serializable
data class GetScannerIssues(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyHttpHistory(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyHttpHistoryRegex(val regex: String, override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyWebsocketHistory(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyWebsocketHistoryRegex(val regex: String, override val count: Int, override val offset: Int) :
    Paginated

@Serializable
data class GenerateCollaboratorPayload(
    val customData: String? = null
)

@Serializable
data class GetCollaboratorInteractions(
    val payloadId: String? = null
)

@Serializable
data class StartActiveAudit(
    val targetUrl: String,
    val scanDurationSeconds: Int? = null
)

@Serializable
data class StartActiveAuditForRequest(
    val targetUrl: String,
    val request: String,
    val response: String? = null
)

@Serializable
data class StopActiveAudit(
    val auditId: String? = null
)
