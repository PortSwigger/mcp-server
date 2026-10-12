package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.PAUSED
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.RUNNING
import burp.api.montoya.collaborator.InteractionFilter
import burp.api.montoya.collaborator.PayloadOption
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.ExtensionVersion
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

fun Server.registerTools(api: MontoyaApi, config: McpConfig, capturedRequests: CapturedHttpRequests) {

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

        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        val response = api.http().sendRequest(request)
        val raw = response?.toString() ?: "<no response>"
        presentResponse(raw, responseEndMarker, truncateAtEndMarker == true)
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
        val raw = response?.toString() ?: "<no response>"
        presentResponse(raw, responseEndMarker, truncateAtEndMarker == true)
    }

    mcpTool<CreateRepeaterTab>("Creates an HTTP/1.1 Repeater tab with the specified raw HTTP request and optional tab name. Make sure to use carriage returns appropriately. Prefer create_repeater_tab_http2 for modern web targets that speak HTTP/2. The request is not sent until send_repeater_request. Optional notes are written to the Repeater tab Notes field when that field can be found. The Burp tool and Repeater tab that are already open stay as they are.") {
        val fixedContent = normalizeHttpContent(content)
        val request = HttpRequest.httpRequest(toMontoyaService(), fixedContent)
        openRepeaterTab(api, request, tabName, HttpMode.HTTP_1, notes)
    }

    mcpTool<CreateRepeaterTabHttp2>("Creates an HTTP/2 Repeater tab with the specified HTTP/2 request and optional tab name. Use this by default for modern web targets. Do NOT pass headers to the body parameter. The request is not sent until send_repeater_request. The Burp tool and Repeater tab that are already open stay as they are.") {
        val headerList = buildHttp2HeaderList(pseudoHeaders, headers)
        val request = HttpRequest.http2Request(toMontoyaService(), headerList, requestBody)
        openRepeaterTab(api, request, tabName, HttpMode.HTTP_2, notes)
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
        "User has disabled configuration editing. They can enable it in the ${ExtensionVersion.TAB_TITLE} tab in Burp by selecting 'Enable tools that can edit your config'"

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

        val collaboratorClients = CollaboratorClients(api)

        mcpTool<GenerateCollaboratorPayload>(
            "Generates a Burp Collaborator payload for out-of-band testing. " +
            "Inject the payload into a request, then poll with get_collaborator_interactions. " +
            "Optional customData is stored with the payload. " +
            "Set withoutServerLocation to omit the server hostname. " +
            "Set linkToCollaboratorTab to use Burp's default generator so the interaction shows in the Collaborator tab. " +
            "Those tab payloads are not returned by get_collaborator_interactions. " +
            "Set includeSecretKey to also return the client secret. get_collaborator_client returns it at any time."
        ) {
            api.logging().logToOutput("MCP generating Collaborator payload${customData?.let { " with custom data" } ?: ""}")

            if (linkToCollaboratorTab == true) {
                val generator = api.collaborator().defaultPayloadGenerator()
                val options = if (withoutServerLocation == true) {
                    arrayOf(PayloadOption.WITHOUT_SERVER_LOCATION)
                } else {
                    emptyArray()
                }
                val payload = generator.generatePayload(*options)
                val serverAddress = payload.server().orElse(null)?.address()
                return@mcpTool buildString {
                    appendLine("Payload: $payload")
                    appendLine("Payload ID: ${payload.id()}")
                    if (serverAddress != null) appendLine("Collaborator server: $serverAddress")
                    append("Linked to the Burp Collaborator tab. Poll that tab. get_collaborator_interactions does not see this payload.")
                    if (customData != null) {
                        append(" customData was ignored because the default generator does not accept it.")
                    }
                }
            }

            val collaboratorClient = collaboratorClients.client()
            val payload = when {
                customData != null && withoutServerLocation == true ->
                    collaboratorClient.generatePayload(customData, PayloadOption.WITHOUT_SERVER_LOCATION)
                customData != null -> collaboratorClient.generatePayload(customData)
                withoutServerLocation == true ->
                    collaboratorClient.generatePayload(PayloadOption.WITHOUT_SERVER_LOCATION)
                else -> collaboratorClient.generatePayload()
            }

            val server = collaboratorClient.server()
            buildString {
                appendLine("Payload: $payload")
                appendLine("Payload ID: ${payload.id()}")
                append("Collaborator server: ${server.address()}")
                if (includeSecretKey == true) {
                    val secret = runCatching { collaboratorClient.getSecretKey().toString() }.getOrNull()
                    if (secret != null) append("\nSecret key: $secret")
                }
            }
        }

        mcpTool<GetCollaboratorInteractions>(
            "Polls the MCP Collaborator client for DNS, HTTP, and SMTP interactions. " +
            "Filter with payloadId from generate_collaborator_payload, with the payload string, or with interactionType (DNS, HTTP, SMTP). " +
            "Does not include payloads created with linkToCollaboratorTab."
        ) {
            val collaboratorClient = collaboratorClients.client()
            api.logging().logToOutput("MCP polling Collaborator interactions${payloadId?.let { " for payload: $it" } ?: ""}")

            val fetched = when {
                payloadId != null ->
                    collaboratorClient.getInteractions(InteractionFilter.interactionIdFilter(payloadId))
                payload != null ->
                    collaboratorClient.getInteractions(InteractionFilter.interactionPayloadFilter(payload))
                else -> collaboratorClient.getAllInteractions()
            }
            val interactions = fetched.filter { interaction ->
                val payloadMatches = payload == null || payloadId == null ||
                    InteractionFilter.interactionPayloadFilter(payload).matches(collaboratorClient.server(), interaction)
                val typeMatches = interactionType == null ||
                    interaction.type().name.equals(interactionType, ignoreCase = true)
                payloadMatches && typeMatches
            }

            if (interactions.isEmpty()) {
                "No interactions detected"
            } else {
                interactions.joinToString("\n\n") {
                    Json.encodeToString(it.toSerializableForm())
                }
            }
        }

        mcpTool<GetCollaboratorClient>(
            "Returns the Collaborator server address and the secret key for the MCP Collaborator client. " +
            "The secret is stored in the project file so later polls see the same payloads. " +
            "Pass secretKey to restore a different client instead."
        ) {
            if (!secretKey.isNullOrBlank()) {
                val restored = api.collaborator().restoreClient(
                    burp.api.montoya.collaborator.SecretKey.secretKey(secretKey)
                )
                collaboratorClients.use(restored)
                val address = restored.server().address()
                val literal = runCatching { restored.server().isLiteralAddress() }.getOrNull()
                runCatching {
                    api.persistence().extensionData().setString(CollaboratorClients.SECRET_KEY, secretKey)
                }
                return@mcpTool "Restored Collaborator client.\nCollaborator server: $address\nLiteral address: $literal\nSecret key: $secretKey"
            }

            val collaboratorClient = collaboratorClients.client()
            val secret = runCatching { collaboratorClient.getSecretKey().toString() }.getOrElse { "<unavailable>" }
            val server = collaboratorClient.server()
            val literal = runCatching { server.isLiteralAddress() }.getOrNull()
            "Collaborator server: ${server.address()}\nLiteral address: $literal\nSecret key: $secret"
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

    mcpPaginatedTool<GetProxyHttpHistorySummary>(
        "Lists proxy HTTP history as one short JSON object per item: id, time, method, url, status, notes, edited. " +
            "Use the id with send_proxy_history_to_repeater and set_proxy_history_notes. " +
            "Optional regex limits the items. Newest items are at the end of proxy history, so page with offset."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }
        val compiled = regex?.takeIf { it.isNotBlank() }?.let { Pattern.compile(it) }
        val history = if (compiled != null) api.proxy().history { it.contains(compiled) } else api.proxy().history()
        history.asSequence().map { proxyHistorySummaryLine(it) }
    }

    mcpTool<GetProxyHttpHistoryItem>(
        "Returns one proxy HTTP history item by id, including the request, response, and notes."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) return@mcpTool "HTTP history access denied by Burp Suite"
        val item = findProxyHistoryItem(api, historyId) ?: return@mcpTool "No proxy history item with id $historyId."
        encodeHistoryItem(item.toSerializableForm())
    }

    mcpTool<SetProxyHistoryNotes>(
        "Writes the proxy HTTP history Notes field (the Comment column) for one history id or for items matching a regex. " +
            "Optional highlightColor is a Burp color name: RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PINK, MAGENTA, GRAY, or NONE. " +
            "Set append to add to the existing note instead of replacing it."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) return@mcpTool "HTTP history access denied by Burp Suite"
        annotateProxyHistory(api, historyId, regex, notes, highlightColor, append == true)
    }

    mcpTool<SendProxyHistoryToRepeater>(
        "Sends one proxy HTTP history item to a Repeater tab using Repeater.sendToRepeater. " +
            "The Burp tool and Repeater tab that are already open stay as they are. " +
            "Pass historyId from get_proxy_http_history_summary. " +
            "useFinalRequest selects the request Burp actually sent after proxy match-and-replace. " +
            "Notes are copied from the history item unless notes is set or copyNotes is false. " +
            "Set sendNow to issue that Repeater tab and return the response in this call."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) return@mcpTool "HTTP history access denied by Burp Suite"

        val item = findProxyHistoryItem(api, historyId) ?: return@mcpTool "No proxy history item with id $historyId."
        val source = if (useFinalRequest != false) {
            runCatching { item.finalRequest() }.getOrNull() ?: item.request()
        } else {
            item.request()
        } ?: return@mcpTool "Proxy history item $historyId has no request."

        val mode = when (runCatching { source.httpVersion() }.getOrNull()?.uppercase()) {
            "HTTP/2" -> HttpMode.HTTP_2
            else -> HttpMode.HTTP_1
        }
        val tabNotes = notes ?: if (copyNotes != false) {
            runCatching { item.annotations()?.notes() }.getOrNull()
        } else {
            null
        }
        val name = tabName?.takeIf { it.isNotBlank() } ?: "history-$historyId"
        val opened = openRepeaterTab(api, source, name, mode, tabNotes)
        if (sendNow != true) return@mcpTool opened
        opened + "\n\n" + issueRepeaterTab(
            api, config, name, null, null, null, responseEndMarker, truncateAtEndMarker, null, issueFrom
        )
    }

    mcpTool<ListCapturedHttpRequests>(
        "Lists recent HTTP requests captured through Montoya since this extension loaded. " +
            "Each entry has a capture ID, Burp tool, method, and URL. Use the capture ID, not Burp Logger's visible #, " +
            "with send_recorded_request_to_repeater source captured. Only the most recent 500 requests are retained."
    ) {
        val allowed = runBlocking {
            checkDataAccessOrDeny(DataAccessType.CAPTURED_REQUESTS, config, api, "captured requests")
        }
        if (!allowed) return@mcpTool "Captured request access denied by Burp Suite"
        val entries = capturedRequests.recent(limit.coerceIn(1, 100))
        if (entries.isEmpty()) return@mcpTool "No HTTP requests have been captured since this extension loaded."
        entries.joinToString("\n") { entry ->
            Json.encodeToString(
                CapturedHttpRequestSummary(
                    id = entry.id,
                    tool = entry.tool,
                    method = runCatching { entry.request.method() }.getOrNull(),
                    url = runCatching { entry.request.url() }.getOrNull()
                )
            )
        }
    }

    mcpTool<SendRecordedRequestToRepeater>(
        "Opens a recorded HTTP request in a Repeater tab, clicks Repeater Send, and returns the fresh response. " +
            "For source proxy, requestId is the Proxy HTTP history ID. For source captured, requestId is a capture ID " +
            "from list_captured_http_requests; it is not Burp Logger's visible #. " +
            "Only requests captured since this extension loaded are available for source captured. " +
            "If the Repeater Send control cannot be used, the tool reports that the request was not sent."
    ) {
        val normalizedSource = source.trim().lowercase()
        val selected = when (normalizedSource) {
            "proxy" -> {
                val allowed = runBlocking {
                    checkDataAccessOrDeny(DataAccessType.HTTP_HISTORY, config, api, "HTTP history")
                }
                if (!allowed) return@mcpTool "HTTP history access denied by Burp Suite"
                val item = findProxyHistoryItem(api, requestId)
                    ?: return@mcpTool "No proxy history item with id $requestId."
                val request = runCatching { item.finalRequest() }.getOrNull() ?: item.request()
                    ?: return@mcpTool "Proxy history item $requestId has no request."
                val copiedNotes = runCatching { item.annotations()?.notes() }.getOrNull()
                request to copiedNotes
            }
            "captured" -> {
                val allowed = runBlocking {
                    checkDataAccessOrDeny(DataAccessType.CAPTURED_REQUESTS, config, api, "captured requests")
                }
                if (!allowed) return@mcpTool "Captured request access denied by Burp Suite"
                val entry = capturedRequests.get(requestId)
                    ?: return@mcpTool "No captured HTTP request with id $requestId. Use list_captured_http_requests for available IDs."
                entry.request to null
            }
            else -> return@mcpTool "source must be proxy or captured. Burp Logger's visible # is not a capture ID."
        }

        val request = selected.first
        val service = request.httpService()
        val allowedToSend = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(service.host(), service.port(), config, request.toString(), api)
        }
        if (!allowedToSend) return@mcpTool "Send HTTP request denied by Burp Suite"

        val mode = if (runCatching { request.httpVersion() }.getOrNull()?.equals("HTTP/2", true) == true) {
            HttpMode.HTTP_2
        } else {
            HttpMode.HTTP_1
        }
        val name = tabName?.takeIf { it.isNotBlank() } ?: "$normalizedSource-$requestId"
        val opened = openRepeaterTab(api, request, name, mode, notes ?: selected.second)
        opened + "\n\n" + issueRepeaterTab(api, config, name, null, null, null, null, null, null, null)
    }

    mcpTool<SendRepeaterRequest>(
        "Clicks Send in the named Repeater tab and returns the response shown in that tab's response pane. " +
            "The request leaves from Repeater. The Burp tool and Repeater tab that are already open stay as they are, so manual testing can continue. " +
            "Leave issueFrom unset so the response appears in the tab. " +
            "issueFrom http is only a fallback when Repeater Send cannot be clicked, and that fallback does not fill the response pane. " +
            "responseEndMarker is the literal word or characters that mark the end of the server response. " +
            "Set truncateAtEndMarker to keep only the response through that marker. " +
            "notes is written to the Repeater tab Notes field."
    ) {
        issueRepeaterTab(
            api,
            config,
            tabName,
            content,
            httpMode,
            connectionId,
            responseEndMarker,
            truncateAtEndMarker,
            notes,
            issueFrom
        )
    }

    mcpTool<SetRepeaterNotes>(
        "Writes the Notes panel of one Repeater tab opened by MCP. The note is stored for that tab and typed into its Notes editor. The Burp tool and Repeater tab that are already open stay as they are."
    ) {
        val tab = RepeaterSession.get(tabName)
            ?: return@mcpTool "No Repeater tab named '$tabName'."
        val next = if (append == true && tab.notes.isNotBlank()) tab.notes + "\n" + notes else notes
        tab.notes = next
        RepeaterSession.save(tab)
        val written = RepeaterUi.trySetNotes(
            suiteFrameOf(api),
            tabName,
            next,
            expectedRequest = tab.request.toString()
        )
        if (written) {
            "Updated Repeater notes on '$tabName'."
        } else {
            "Saved Repeater notes for '$tabName' in this session. Burp's Notes panel for that tab could not be updated."
        }
    }

    mcpTool<SetRepeaterResponseEndMarker>(
        "Records the word or characters that mark the end of the server response for a Repeater tab. " +
            "Later send_repeater_request and get_repeater_tab use this marker. " +
            "Pass an empty marker to clear it. truncateAtEndMarker drops everything after the marker when the response is shown."
    ) {
        val tab = RepeaterSession.get(tabName)
            ?: return@mcpTool "No Repeater tab named '$tabName'."
        tab.responseEndMarker = marker?.takeIf { it.isNotEmpty() }
        if (truncateAtEndMarker != null) tab.truncateAtEndMarker = truncateAtEndMarker
        RepeaterSession.save(tab)
        val shown = tab.responseEndMarker ?: "<cleared>"
        "Repeater tab '$tabName' end marker is $shown. Truncate: ${tab.truncateAtEndMarker}."
    }

    mcpTool<GetRepeaterTab>("Returns the request, notes, end marker, connection id, and last response for a Repeater tab opened by MCP.") {
        describeRepeaterTab(tabName)
    }

    mcpTool("list_repeater_tabs", "Lists Repeater tabs opened by MCP in this Burp session, including notes and end markers.") {
        listRepeaterTabs()
    }

    mcpTool<ListExtensionTools>(
        "Lists tools exposed by loaded extensions. " +
            "Montoya cannot enumerate another extension's API, so this probes command headers that extensions spoof. " +
            "ATOR answers X-ATOR-Command: status and reports which Burp tools it updates, plus commands status, refresh, export, and import. " +
            "Other extensions can answer X-Burp-Mcp-Discover: tools with a JSON body that contains a tools array. " +
            "Pass header and command to probe one extension instead of the built-in probes."
    ) {
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission("127.0.0.1", 1, config, "extension tool probe", api)
        }
        if (!allowed) return@mcpTool "Extension probe denied by Burp Suite"

        val names = loadedExtensionNames(suiteFrameOf(api))
        val probes = if (!header.isNullOrBlank() && !command.isNullOrBlank()) {
            listOf(ExtensionCommand(header, header, command))
        } else {
            KNOWN_EXTENSION_PROBES
        }
        buildString {
            appendLine("This extension: ${api.extension().filename()} bapp=${api.extension().isBapp()}")
            if (names.isEmpty()) {
                appendLine("Extensions table: not readable from here. Probes follow.")
            } else {
                appendLine("Extensions table:")
                names.forEach { appendLine("- $it") }
            }
            appendLine()
            probes.forEach { probe ->
                appendLine(
                    probeExtension(
                        api,
                        probe.header,
                        probe.command,
                        body.orEmpty(),
                        targetHostname ?: "127.0.0.1",
                        targetPort ?: 1,
                        usesHttps == true
                    )
                )
                appendLine()
            }
        }.trimEnd()
    }

    mcpTool<CallExtensionCommand>(
        "Calls a command exposed by a loaded extension. The request is sent with Http.sendRequest, so the tool source is Extensions. " +
            "ATOR spoofs X-ATOR-Command and does not forward the request. Commands: status, refresh, export, import. " +
            "import reads the ATOR export JSON from body. " +
            "Use header X-Burp-Mcp-Discover for extensions that follow that discovery header."
    ) {
        val hostname = targetHostname ?: "127.0.0.1"
        val port = targetPort ?: 1
        val https = usesHttps == true
        val allowed = runBlocking {
            HttpRequestSecurity.checkHttpRequestPermission(hostname, port, config, "$header: $command\n${body.orEmpty()}", api)
        }
        if (!allowed) return@mcpTool "Extension command denied by Burp Suite"
        probeExtension(api, header, command, body.orEmpty(), hostname, port, https)
    }
}

private fun suiteFrameOf(api: MontoyaApi) = RepeaterUi.suiteFrameOrNull {
    api.userInterface().swingUtils().suiteFrame()
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
    override val usesHttps: Boolean,
    val responseEndMarker: String? = null,
    val truncateAtEndMarker: Boolean? = null
) : HttpServiceParams

@Serializable
data class SendHttp2Request(
    val pseudoHeaders: Map<String, String>,
    val headers: Map<String, String>,
    val requestBody: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean,
    val responseEndMarker: String? = null,
    val truncateAtEndMarker: Boolean? = null
) : HttpServiceParams

@Serializable
data class CreateRepeaterTab(
    val tabName: String?,
    val content: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean,
    val notes: String? = null
) : HttpServiceParams

@Serializable
data class CreateRepeaterTabHttp2(
    val tabName: String?,
    val pseudoHeaders: Map<String, String>,
    val headers: Map<String, String>,
    val requestBody: String,
    override val targetHostname: String,
    override val targetPort: Int,
    override val usesHttps: Boolean,
    val notes: String? = null
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
    val customData: String? = null,
    val withoutServerLocation: Boolean? = null,
    val linkToCollaboratorTab: Boolean? = null,
    val includeSecretKey: Boolean? = null
)

@Serializable
data class GetCollaboratorInteractions(
    val payloadId: String? = null,
    val payload: String? = null,
    val interactionType: String? = null
)

@Serializable
data class GetCollaboratorClient(
    val secretKey: String? = null
)

@Serializable
data class GetProxyHttpHistorySummary(
    val regex: String? = null,
    override val count: Int,
    override val offset: Int
) : Paginated

@Serializable
data class GetProxyHttpHistoryItem(val historyId: Int)

@Serializable
data class SetProxyHistoryNotes(
    val historyId: Int? = null,
    val regex: String? = null,
    val notes: String,
    val highlightColor: String? = null,
    val append: Boolean? = null
)

@Serializable
data class SendProxyHistoryToRepeater(
    val historyId: Int,
    val tabName: String? = null,
    val useFinalRequest: Boolean? = null,
    val notes: String? = null,
    val copyNotes: Boolean? = null,
    val sendNow: Boolean? = null,
    val responseEndMarker: String? = null,
    val truncateAtEndMarker: Boolean? = null,
    val issueFrom: String? = null
)

@Serializable
data class ListCapturedHttpRequests(val limit: Int = 20)

@Serializable
data class SendRecordedRequestToRepeater(
    val source: String,
    val requestId: Int,
    val tabName: String? = null,
    val notes: String? = null
)

@Serializable
internal data class CapturedHttpRequestSummary(
    val id: Int,
    val tool: String,
    val method: String? = null,
    val url: String? = null
)

@Serializable
data class SendRepeaterRequest(
    val tabName: String,
    val content: String? = null,
    val httpMode: String? = null,
    val connectionId: String? = null,
    val responseEndMarker: String? = null,
    val truncateAtEndMarker: Boolean? = null,
    val notes: String? = null,
    val issueFrom: String? = null
)

@Serializable
data class SetRepeaterNotes(
    val tabName: String,
    val notes: String,
    val append: Boolean? = null
)

@Serializable
data class SetRepeaterResponseEndMarker(
    val tabName: String,
    val marker: String? = null,
    val truncateAtEndMarker: Boolean? = null
)

@Serializable
data class GetRepeaterTab(val tabName: String)

@Serializable
data class ListExtensionTools(
    val header: String? = null,
    val command: String? = null,
    val body: String? = null,
    val targetHostname: String? = null,
    val targetPort: Int? = null,
    val usesHttps: Boolean? = null
)

@Serializable
data class CallExtensionCommand(
    val header: String,
    val command: String,
    val body: String? = null,
    val targetHostname: String? = null,
    val targetPort: Int? = null,
    val usesHttps: Boolean? = null
)
