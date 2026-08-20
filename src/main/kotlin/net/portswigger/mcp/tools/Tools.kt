package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.PAUSED
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.RUNNING
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.scanner.AuditConfiguration
import burp.api.montoya.scanner.BuiltInAuditConfiguration
import burp.api.montoya.scanner.CrawlConfiguration
import burp.api.montoya.scanner.ReportFormat
import burp.api.montoya.scanner.ScanTask
import burp.api.montoya.utilities.CompressionType
import burp.api.montoya.proxy.http.ProxyRequestHandler
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction
import burp.api.montoya.proxy.http.InterceptedRequest
import burp.api.montoya.http.handler.HttpHandler
import burp.api.montoya.http.handler.HttpRequestToBeSent
import burp.api.montoya.http.handler.HttpResponseReceived
import burp.api.montoya.http.handler.RequestToBeSentAction
import burp.api.montoya.http.handler.ResponseReceivedAction
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.encodeHistoryItem
import net.portswigger.mcp.schema.toSerializableForm
import net.portswigger.mcp.security.DataAccessSecurity
import net.portswigger.mcp.security.DataAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import net.portswigger.mcp.security.filterConfigCredentials
import java.awt.KeyboardFocusManager
import java.util.regex.Pattern
import javax.swing.JTextArea

private suspend fun checkDataAccessOrDeny(
    accessType: DataAccessType, config: McpConfig, api: MontoyaApi, logMessage: String
): Boolean {
    val allowed = DataAccessSecurity.checkDataAccessPermission(accessType, config)
    if (!allowed) {
        api.logging().logToOutput("MCP $logMessage access denied")
        return false
    }
    api.logging().logToOutput("MCP $logMessage access granted")
    return true
}

private fun buildHttp2HeaderList(
    pseudoHeaders: Map<String, String>, headers: Map<String, String>
): List<HttpHeader> {
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

    return (fixedPseudoHeaders + headers).map { HttpHeader.httpHeader(it.key.lowercase(), it.value) }
}

/**
 * Normalizes HTTP request line endings from MCP clients.
 *
 * MCP clients (e.g. Claude Code) often emit `\r\n` as the 4-character literal
 * sequence backslash-r-backslash-n in JSON tool parameters rather than actual
 * CR (0x0D) + LF (0x0A) bytes. The resulting text parses as a single line,
 * which strict servers (e.g. Apache-Coyote) reject with 400 Bad Request and
 * which Burp/Montoya may "repair" by injecting headers after the body
 * separator.
 *
 * Normalization is applied only to the request prelude (request line and
 * headers, up to and including the first blank line). The body is preserved
 * verbatim so that legitimate escape sequences in bodies — e.g. `\n` inside a
 * JSON string literal — and binary payloads remain byte-exact. If no blank
 * line is present, the entire content is treated as prelude.
 */
internal fun normalizeHttpContent(content: String): String {
    val preludeEnd = findPreludeEnd(content) ?: return normalizePrelude(content)
    return normalizePrelude(content.substring(0, preludeEnd)) + content.substring(preludeEnd)
}

private val BLANK_LINE_MARKERS = listOf(
    "\r\n\r\n",         // actual CRLF blank line
    "\n\n",              // actual LF blank line
    "\\r\\n\\r\\n",     // literal CRLF blank line
    "\\n\\n",            // literal LF blank line
)

private fun findPreludeEnd(content: String): Int? {
    var bestStart = -1
    var bestLen = 0
    for (marker in BLANK_LINE_MARKERS) {
        val idx = content.indexOf(marker)
        if (idx >= 0 && (bestStart < 0 || idx < bestStart)) {
            bestStart = idx
            bestLen = marker.length
        }
    }
    return if (bestStart < 0) null else bestStart + bestLen
}

private fun normalizePrelude(prelude: String): String = prelude
    .replace("\\r\\n", "\n")   // Literal \r\n escape sequences → LF
    .replace("\\n", "\n")      // Remaining literal \n → LF
    .replace("\\r", "")        // Remaining literal \r → remove
    .replace("\r", "")          // Actual CR → remove
    .replace("\n", "\r\n")      // All LF → proper CRLF

fun Server.registerTools(api: MontoyaApi, config: McpConfig) {

    // --- Live traffic modifiers: proxy match-and-replace + global header injection.
    // Rules are managed by the tools below; empty registries are a no-op.
    api.proxy().registerRequestHandler(object : ProxyRequestHandler {
        override fun handleRequestReceived(r: InterceptedRequest): ProxyRequestReceivedAction =
            ProxyRequestReceivedAction.continueWith(r)

        override fun handleRequestToBeSent(r: InterceptedRequest): ProxyRequestToBeSentAction {
            val rules = MatchReplaceRules.all()
            val headers = InjectedHeaders.all()
            if (rules.isEmpty() && headers.isEmpty()) return ProxyRequestToBeSentAction.continueWith(r)
            var request: HttpRequest = r
            if (rules.isNotEmpty()) {
                var text = r.toString()
                for ((match, replace) in rules) {
                    text = try { Regex(match).replace(text, replace) } catch (e: Exception) { text }
                }
                request = HttpRequest.httpRequest(r.httpService(), text)
            }
            for ((name, value) in headers) request = request.withUpdatedHeader(name, value)
            return ProxyRequestToBeSentAction.continueWith(request)
        }
    })

    api.http().registerHttpHandler(object : HttpHandler {
        override fun handleHttpRequestToBeSent(r: HttpRequestToBeSent): RequestToBeSentAction {
            val headers = InjectedHeaders.all()
            if (headers.isEmpty()) return RequestToBeSentAction.continueWith(r)
            var req: HttpRequest = r
            for ((name, value) in headers) req = req.withUpdatedHeader(name, value)
            return RequestToBeSentAction.continueWith(req)
        }

        override fun handleHttpResponseReceived(r: HttpResponseReceived): ResponseReceivedAction =
            ResponseReceivedAction.continueWith(r)
    })

    mcpTool<SendHttp1Request>("Issues an HTTP/1.1 request and returns the response.") {
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, content, api)
        }
        if (!allowed) {
            api.logging().logToOutput("MCP HTTP request denied: $targetHostname:$targetPort")
            return@mcpTool "Send HTTP request denied by Burp Suite"
        }

        api.logging().logToOutput("MCP HTTP/1.1 request: $targetHostname:$targetPort")

        val fixedContent = normalizeHttpContent(content)

        var request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        for ((name, value) in InjectedHeaders.all()) request = request.withUpdatedHeader(name, value)
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

        val headerList = buildHttp2HeaderList(pseudoHeaders, headers)

        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        val response = api.http().sendRequest(request, HttpMode.HTTP_2)

        response?.toString() ?: "<no response>"
    }

    mcpUnitTool<CreateRepeaterTab>("Creates an HTTP/1.1 Repeater tab with the specified raw HTTP request and optional tab name. Make sure to use carriage returns appropriately. Prefer create_repeater_tab_http2 for modern web targets that speak HTTP/2.") {
        val fixedContent = normalizeHttpContent(content)
        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        api.repeater().sendToRepeater(request, tabName)
    }

    mcpUnitTool<CreateRepeaterTabHttp2>("Creates an HTTP/2 Repeater tab with the specified HTTP/2 request and optional tab name. Use this by default for modern web targets. Do NOT pass headers to the body parameter.") {
        val headerList = buildHttp2HeaderList(pseudoHeaders, headers)
        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        api.repeater().sendToRepeater(request, tabName)
    }

    mcpUnitTool<SendToIntruder>("Sends an HTTP request to Intruder with the specified HTTP request and optional tab name. Make sure to use carriage returns appropriately.") {
        val fixedContent = normalizeHttpContent(content)
        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
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
        val json = api.burpSuite().exportProjectOptionsAsJson()
        if (config.filterConfigCredentials) {
            filterConfigCredentials(json)
        } else {
            json
        }
    }

    mcpTool(
        "output_user_options",
        "Outputs current user-level configuration in JSON format. You can use this to determine the schema for available config options."
    ) {
        val json = api.burpSuite().exportUserOptionsAsJson()
        if (config.filterConfigCredentials) {
            filterConfigCredentials(json)
        } else {
            json
        }
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
    }

    mcpPaginatedTool<GetProxyHttpHistory>("Displays items within the proxy HTTP history") {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        api.proxy().history().asSequence().map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetProxyHttpHistoryRegex>("Displays items matching a specified regex within the proxy HTTP history") {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.proxy().history { it.contains(compiledRegex) }.asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetOrganizerItems>("Displays items within the Organizer tab") {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("Organizer access denied by Burp Suite")
        }

        api.organizer().items().asSequence().map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetOrganizerItemsRegex>("Displays items matching a specified regex within the Organizer tab") {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.ORGANIZER, config, api, "Organizer")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("Organizer access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.organizer().items { it.contains(compiledRegex) }.asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetProxyWebsocketHistory>("Displays items within the proxy WebSocket history") {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("WebSocket history access denied by Burp Suite")
        }

        api.proxy().webSocketHistory().asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
    }

    mcpPaginatedTool<GetProxyWebsocketHistoryRegex>("Displays items matching a specified regex within the proxy WebSocket history") {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.WEBSOCKET_HISTORY, config, api, "WebSocket history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("WebSocket history access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        api.proxy().webSocketHistory { it.contains(compiledRegex) }.asSequence()
            .map { encodeHistoryItem(it.toSerializableForm()) }
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

    mcpTool<StartActiveScan>(
        "Starts a Burp active scan (audit) of the given URL using the built-in active audit checks. " +
                "Returns immediately; poll findings later with get_scanner_issues."
    ) {
        val request = HttpRequest.httpRequestFromUrl(url)
        val service = request.httpService()

        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                service.host(), service.port(), config, request.toString(), api
            )
        }
        if (!allowed) {
            api.logging().logToOutput("MCP active scan denied: $url")
            return@mcpTool "Active scan of $url denied by Burp Suite"
        }

        api.logging().logToOutput("MCP active scan starting: $url")
        val audit = api.scanner().startAudit(
            AuditConfiguration.auditConfiguration(BuiltInAuditConfiguration.LEGACY_ACTIVE_AUDIT_CHECKS)
        )
        audit.addRequest(request)
        val id = ScanRegistry.add("active-audit", url, audit)

        "Started active scan #$id of $url. Poll with scan_status; read findings with get_scanner_issues."
    }

    mcpTool<StartPassiveScan>(
        "Fetches a URL and runs Burp's passive audit checks on the response (no attack traffic sent to the target). " +
                "Read findings with get_scanner_issues."
    ) {
        val request = HttpRequest.httpRequestFromUrl(url)
        val service = request.httpService()
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                service.host(), service.port(), config, request.toString(), api
            )
        }
        if (!allowed) return@mcpTool "Passive scan of $url denied by Burp Suite"

        val audit = api.scanner().startAudit(
            AuditConfiguration.auditConfiguration(BuiltInAuditConfiguration.LEGACY_PASSIVE_AUDIT_CHECKS)
        )
        audit.addRequest(request)
        val id = ScanRegistry.add("passive-audit", url, audit)
        "Started passive scan #$id of $url."
    }

    mcpTool(
        "scan_status",
        "Reports live status (running/finished, request and error counts) of every scan/crawl task started this session."
    ) {
        val tasks = ScanRegistry.all()
        if (tasks.isEmpty()) "No scan or crawl tasks have been started this session."
        else tasks.joinToString("\n") { entry ->
            // Some ScanTask methods may be unimplemented in the running Burp build; guard each.
            val status = runCatching { entry.task.statusMessage() }.getOrElse { "n/a" }
            val reqs = runCatching { entry.task.requestCount() }.getOrNull()
            val errs = runCatching { entry.task.errorCount() }.getOrNull()
            buildString {
                append("#${entry.id} [${entry.kind}] ${entry.target} -> ").append(status)
                if (reqs != null) append(" | requests=").append(reqs)
                if (errs != null) append(" | errors=").append(errs)
            }
        }
    }

    mcpTool<ExportScanReport>(
        "Exports all current scanner issues to an HTML or XML report file at the given absolute path. format: HTML or XML."
    ) {
        val fmt = if (format.equals("XML", ignoreCase = true)) ReportFormat.XML else ReportFormat.HTML
        val issues = api.siteMap().issues()
        if (issues.isEmpty()) return@mcpTool "No scanner issues to export."
        api.scanner().generateReport(issues, fmt, Path.of(path))
        "Exported ${issues.size} issue(s) as ${fmt.name} to $path"
    }

    mcpTool<StartCrawl>(
        "Starts a Burp crawl (spider) from one or more seed URLs (comma-separated). Burp discovers " +
                "linked content and adds it to the site map. Combine with start_active_scan to audit what is found."
    ) {
        val seeds = url.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (seeds.isEmpty()) return@mcpTool "No seed URLs provided"

        for (seed in seeds) {
            val request = HttpRequest.httpRequestFromUrl(seed)
            val service = request.httpService()
            val allowed = runBlocking {
                HttpRequestSecurity.checkHttpRequestPermission(
                    service.host(), service.port(), config, request.toString(), api
                )
            }
            if (!allowed) {
                api.logging().logToOutput("MCP crawl denied: $seed")
                return@mcpTool "Crawl of $seed denied by Burp Suite"
            }
        }

        api.logging().logToOutput("MCP crawl starting: $seeds")
        val crawl = api.scanner().startCrawl(CrawlConfiguration.crawlConfiguration(*seeds.toTypedArray()))
        val id = ScanRegistry.add("crawl", seeds.joinToString(","), crawl)

        "Started crawl #$id from ${seeds.size} seed(s): ${seeds.joinToString(", ")}. " +
                "Discovered content appears in the site map (get_site_map); poll scan_status; then start_active_scan."
    }

    // ---------------- Site map ----------------
    mcpPaginatedTool<GetSiteMap, String>(
        "Lists site map entries (method, URL, status), optionally filtered to a URL prefix. Paginated via count/offset."
    ) {
        val entries = if (prefix.isNullOrBlank()) api.siteMap().requestResponses()
        else api.siteMap().requestResponses(burp.api.montoya.sitemap.SiteMapFilter.prefixFilter(prefix))
        entries.map { rr ->
            val status = rr.response()?.statusCode()?.toString() ?: "-"
            "${rr.request().method()} ${rr.request().url()} -> $status"
        }
    }

    mcpTool<AddToSiteMap>("Fetches a URL and adds the request/response to Burp's site map.") {
        val request = HttpRequest.httpRequestFromUrl(url)
        val service = request.httpService()
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(
                service.host(), service.port(), config, request.toString(), api
            )
        }
        if (!allowed) return@mcpTool "Adding $url denied by Burp Suite"
        val rr = api.http().sendRequest(request)
        api.siteMap().add(rr)
        "Added to site map: $url (${rr.response()?.statusCode() ?: "-"})"
    }

    // ---------------- Scope ----------------
    mcpTool<ScopeAdd>("Adds a URL prefix to Burp's target scope.") {
        api.scope().includeInScope(url)
        "Added to scope: $url"
    }
    mcpTool<ScopeRemove>("Removes a URL from Burp's target scope.") {
        api.scope().excludeFromScope(url)
        "Removed from scope: $url"
    }
    mcpTool<ScopeCheck>("Checks whether a URL is in Burp's target scope.") {
        "In scope: ${api.scope().isInScope(url)}"
    }

    // ---------------- Intruder replacement: real sniper fuzzing with results ----------------
    mcpTool<RunIntruderAttack>(
        "Runs an automated sniper-style attack: replaces the marker (default FUZZ) in the request content with each " +
                "payload, sends every request through Burp, and returns status code, response length and time per payload. " +
                "This is the automatable equivalent of Intruder (Montoya cannot run the Intruder UI attack itself)."
    ) {
        val mark = if (marker.isNullOrEmpty()) "FUZZ" else marker
        val list = payloads.take(1000)
        if (list.isEmpty()) return@mcpTool "No payloads provided."

        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(targetHostname, targetPort, config, content, api)
        }
        if (!allowed) return@mcpTool "Attack against $targetHostname:$targetPort denied by Burp Suite"

        val header = "payload | status | length | ms"
        val rows = list.map { payload ->
            val body = normalizeHttpContent(content.replace(mark, payload))
            val request = HttpRequest.httpRequest(toMontoyaService(), body)
            val start = System.currentTimeMillis()
            val resp = api.http().sendRequest(request)
            val ms = System.currentTimeMillis() - start
            val code = resp?.response()?.statusCode()?.toString() ?: "-"
            val len = resp?.response()?.toByteArray()?.length()?.toString() ?: "-"
            val shown = if (payload.length > 40) payload.take(40) + "…" else payload
            "$shown | $code | $len | $ms"
        }
        (listOf("Sniper attack: ${list.size} requests to $targetHostname:$targetPort", header) + rows)
            .joinToString("\n")
    }

    // ---------------- Decoder extras ----------------
    mcpTool<HexEncode>("Hex-encodes the input string (UTF-8 bytes).") {
        content.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    }
    mcpTool<HexDecode>("Hex-decodes the input string to text (UTF-8).") {
        val clean = content.trim().replace(" ", "").replace("\n", "")
        String(ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() },
            Charsets.UTF_8)
    }
    mcpTool<HtmlEncode>("HTML-entity-encodes the input string.") {
        api.utilities().htmlUtils().encode(content)
    }
    mcpTool<HtmlDecode>("HTML-entity-decodes the input string.") {
        api.utilities().htmlUtils().decode(content)
    }
    mcpTool<GzipCompress>("Gzip-compresses the input text and returns the result base64-encoded.") {
        val compressed = api.utilities().compressionUtils()
            .compress(burp.api.montoya.core.ByteArray.byteArray(content), CompressionType.GZIP)
        api.utilities().base64Utils().encodeToString(compressed)
    }
    mcpTool<GzipDecompress>("Gzip-decompresses base64-encoded gzip data and returns the resulting text.") {
        val raw = api.utilities().base64Utils().decode(content)
        val out = api.utilities().compressionUtils().decompress(raw, CompressionType.GZIP)
        out.toString()
    }

    // ---------------- Custom scan checks (BCheck) ----------------
    mcpTool<ImportBcheck>(
        "Imports a BCheck script into Burp's scanner. Once imported it runs as a custom scan check on subsequent " +
                "audits. Returns the import status and any parse errors."
    ) {
        val result = api.scanner().bChecks().importBCheck(script)
        val errors = result.importErrors()
        buildString {
            append("BCheck import status: ").append(result.status())
            if (errors.isNotEmpty()) append("\nErrors:\n").append(errors.joinToString("\n") { "  - $it" })
            else append("\nImported successfully; it will run on future scans.")
        }
    }

    // ---------------- Proxy match-and-replace ----------------
    mcpTool<AddMatchReplaceRule>(
        "Adds a regex match-and-replace rule applied to requests as they leave the Proxy (browser traffic). " +
                "match is a Java regex over the full request text; replace is the replacement (supports \$1 groups)."
    ) {
        MatchReplaceRules.add(match, replace)
        "Added match-and-replace rule #${MatchReplaceRules.all().size}: /$match/ -> \"$replace\". " +
                "Applies to proxied requests."
    }
    mcpTool("list_match_replace_rules", "Lists the active proxy match-and-replace rules.") {
        val rules = MatchReplaceRules.all()
        if (rules.isEmpty()) "No match-and-replace rules configured."
        else rules.mapIndexed { i, (m, r) -> "#${i + 1}: /$m/ -> \"$r\"" }.joinToString("\n")
    }
    mcpTool("clear_match_replace_rules", "Removes all proxy match-and-replace rules.") {
        val n = MatchReplaceRules.all().size
        MatchReplaceRules.clear()
        "Cleared $n match-and-replace rule(s)."
    }

    // ---------------- Global request-header injection (session-handling style) ----------------
    mcpTool<AddRequestHeader>(
        "Injects (or overwrites) a header on every HTTP request Burp sends, across all tools. Useful for adding an " +
                "Authorization or session header to authenticate all traffic."
    ) {
        InjectedHeaders.add(name, value)
        "Now injecting header on all requests: $name: $value (${InjectedHeaders.all().size} header(s) active)."
    }
    mcpTool("list_request_headers", "Lists the headers currently injected into all outgoing requests.") {
        val headers = InjectedHeaders.all()
        if (headers.isEmpty()) "No injected headers."
        else headers.joinToString("\n") { (n, v) -> "$n: $v" }
    }
    mcpTool("clear_request_headers", "Stops injecting all previously added request headers.") {
        val n = InjectedHeaders.all().size
        InjectedHeaders.clear()
        "Cleared $n injected header(s)."
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
data class CreateRepeaterTabHttp2(
    val tabName: String?,
    val pseudoHeaders: Map<String, String>,
    val headers: Map<String, String>,
    val requestBody: String,
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
data class StartActiveScan(val url: String)

@Serializable
data class StartCrawl(val url: String)

@Serializable
data class StartPassiveScan(val url: String)

@Serializable
data class ExportScanReport(val path: String, val format: String)

@Serializable
data class GetSiteMap(val prefix: String? = null, override val count: Int, override val offset: Int) : Paginated

@Serializable
data class AddToSiteMap(val url: String)

@Serializable
data class ScopeAdd(val url: String)

@Serializable
data class ScopeRemove(val url: String)

@Serializable
data class ScopeCheck(val url: String)

@Serializable
data class RunIntruderAttack(
    val content: String,
    val marker: String? = null,
    val payloads: List<String>,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean
) : HttpServiceParams

@Serializable
data class HexEncode(val content: String)

@Serializable
data class HexDecode(val content: String)

@Serializable
data class HtmlEncode(val content: String)

@Serializable
data class HtmlDecode(val content: String)

@Serializable
data class GzipCompress(val content: String)

@Serializable
data class GzipDecompress(val content: String)

@Serializable
data class ImportBcheck(val script: String)

@Serializable
data class AddMatchReplaceRule(val match: String, val replace: String)

@Serializable
data class AddRequestHeader(val name: String, val value: String)

/** Regex match-and-replace rules applied to proxied requests. */
object MatchReplaceRules {
    private val rules = mutableListOf<Pair<String, String>>()
    @Synchronized fun add(match: String, replace: String) { rules.add(match to replace) }
    @Synchronized fun all(): List<Pair<String, String>> = rules.toList()
    @Synchronized fun clear() { rules.clear() }
}

/** Headers injected into every outgoing HTTP request. */
object InjectedHeaders {
    private val headers = mutableListOf<Pair<String, String>>()
    @Synchronized fun add(name: String, value: String) {
        headers.removeAll { it.first.equals(name, ignoreCase = true) }
        headers.add(name to value)
    }
    @Synchronized fun all(): List<Pair<String, String>> = headers.toList()
    @Synchronized fun clear() { headers.clear() }
}

/** Tracks scan/crawl tasks started this session so scan_status can report on them. */
object ScanRegistry {
    data class Entry(val id: Int, val kind: String, val target: String, val task: ScanTask)

    private val entries = mutableListOf<Entry>()
    private var counter = 0

    @Synchronized
    fun add(kind: String, target: String, task: ScanTask): Int {
        val id = counter++
        entries.add(Entry(id, kind, target, task))
        return id
    }

    @Synchronized
    fun all(): List<Entry> = entries.toList()
}

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
data class GetOrganizerItems(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetOrganizerItemsRegex(val regex: String, override val count: Int, override val offset: Int) : Paginated

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
