package net.portswigger.mcp.tools

import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.PAUSED
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState.RUNNING
import burp.api.montoya.core.BurpSuiteEdition
import burp.api.montoya.http.HttpMode
import burp.api.montoya.http.HttpService
import burp.api.montoya.http.message.HttpHeader
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.schema.toSerializableForm
import net.portswigger.mcp.schema.FieldSelectiveResponse
import net.portswigger.mcp.schema.HttpRequestResponse
import net.portswigger.mcp.schema.SearchExcerpt
import net.portswigger.mcp.security.HistoryAccessSecurity
import net.portswigger.mcp.security.HistoryAccessType
import net.portswigger.mcp.security.HttpRequestSecurity
import java.awt.KeyboardFocusManager
import java.util.regex.Pattern
import javax.swing.JTextArea

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

private fun validateRawBodyAccess(fields: Set<String>, config: McpConfig, api: MontoyaApi): Boolean {
    val rawBodyFields = setOf("requestBody", "responseBody")
    val requestsRawBodyAccess = fields.any { it in rawBodyFields }
    
    if (requestsRawBodyAccess) {
        // For now, we'll allow raw body access but log it for security awareness
        api.logging().logToOutput("MCP raw body access requested - fields: ${fields.filter { it in rawBodyFields }}")
    }
    
    return true
}

internal fun formatAndTruncateIfNeeded(content: String, outputFormat: String): String {
    val formatted = when (outputFormat.lowercase()) {
        "markdown", "md" -> convertJsonToMarkdown(content)
        else -> content
    }
    return truncateIfNeeded(formatted)
}

private fun convertJsonToMarkdown(jsonString: String): String {
    return try {
        val json = Json.parseToJsonElement(jsonString)
        buildString {
            appendLine("```json")
            appendLine(Json { prettyPrint = true }.encodeToString(json))
            appendLine("```")
        }
    } catch (e: Exception) {
        // If JSON parsing fails, return as code block
        buildString {
            appendLine("```")
            appendLine(jsonString)
            appendLine("```")
        }
    }
}

internal fun findExcerptAroundMatch(
    match: burp.api.montoya.proxy.ProxyHttpRequestResponse,
    regex: String,
    searchScope: Set<String>,
    excerptLength: Int,
    extractGroups: Boolean = false
): Pair<String, List<List<String>>?> {
    val pattern = Pattern.compile(regex)
    val searchText = buildString {
        if (searchScope.contains("request") || searchScope.contains("all")) {
            append("REQUEST:\n")
            append(match.request()?.toString() ?: "")
            append("\n\n")
        }
        if (searchScope.contains("response") || searchScope.contains("all")) {
            append("RESPONSE:\n")
            append(match.response()?.toString() ?: "")
        }
    }
    
    val matcher = pattern.matcher(searchText)
    if (matcher.find()) {
        val start = maxOf(0, matcher.start() - excerptLength / 2)
        val end = minOf(searchText.length, matcher.end() + excerptLength / 2)
        val excerpt = searchText.substring(start, end)
        val finalExcerpt = if (start > 0) "...$excerpt" else excerpt + if (end < searchText.length) "..." else ""
        
        val captureGroups = if (extractGroups) {
            extractCaptureGroups(searchText, regex)
        } else null
        
        return Pair(finalExcerpt, captureGroups)
    }
    
    return Pair("No match found", null)
}

private fun extractCaptureGroups(text: String, regex: String): List<List<String>> {
    return try {
        val pattern = Pattern.compile(regex)
        val matcher = pattern.matcher(text)
        val matches = mutableListOf<List<String>>()
        
        while (matcher.find()) {
            val groups = mutableListOf<String>()
            // Add the full match as group 0
            groups.add(matcher.group(0))
            // Add capture groups 1, 2, 3, etc.
            for (i in 1..matcher.groupCount()) {
                groups.add(matcher.group(i) ?: "")
            }
            matches.add(groups)
        }
        
        matches
    } catch (e: Exception) {
        emptyList()
    }
}

// Field extraction helper functions moved from serialization.kt
internal fun ProxyHttpRequestResponse.extractSimpleCookies(): List<String> {
    val cookies = mutableListOf<String>()
    
    // Extract cookies from request headers
    request()?.headers()?.forEach { header ->
        if (header.name().equals("Cookie", ignoreCase = true)) {
            header.value().split(";").forEach { cookie ->
                cookies.add(cookie.trim())
            }
        }
    }
    
    // Extract cookies from response headers
    response()?.headers()?.forEach { header ->
        if (header.name().equals("Set-Cookie", ignoreCase = true)) {
            cookies.add(header.value())
        }
    }
    
    return cookies.distinct()
}

internal fun ProxyHttpRequestResponse.extractSimpleLinks(): List<String> {
    val links = mutableListOf<String>()
    val responseBody = response()?.bodyToString() ?: return links
    
    // Extract href attributes from anchor tags
    val hrefRegex = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
    hrefRegex.findAll(responseBody).forEach { match ->
        links.add(match.groupValues[1])
    }
    
    // Extract action attributes from form tags
    val formRegex = Regex("<form[^>]+action=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
    formRegex.findAll(responseBody).forEach { match ->
        links.add(match.groupValues[1])
    }
    
    // Extract src attributes from script tags
    val scriptRegex = Regex("<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
    scriptRegex.findAll(responseBody).forEach { match ->
        links.add(match.groupValues[1])
    }
    
    return links.distinct()
}

internal fun ProxyHttpRequestResponse.extractVisibleText(): String {
    val responseBody = response()?.bodyToString() ?: return ""
    
    // Simple HTML tag removal - deterministic approach
    var text = responseBody
    
    // Remove script and style content entirely
    text = text.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    
    // Remove elements with display:none or visibility:hidden
    text = text.replace(Regex("<[^>]*style\\s*=\\s*[\"'][^\"']*display\\s*:\\s*none[^\"']*[\"'][^>]*>.*?</[^>]+>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    text = text.replace(Regex("<[^>]*style\\s*=\\s*[\"'][^\"']*visibility\\s*:\\s*hidden[^\"']*[\"'][^>]*>.*?</[^>]+>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
    
    // Remove HTML tags but keep content
    text = text.replace(Regex("<[^>]+>"), " ")
    
    // Decode common HTML entities
    text = text.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
    
    // Normalize whitespace
    text = text.replace(Regex("\\s+"), " ").trim()
    
    return text
}

internal fun ProxyHttpRequestResponse.extractRequestBody(): String? {
    // Raw body content requires explicit opt-in - security control
    return request()?.bodyToString()
}

internal fun ProxyHttpRequestResponse.extractResponseBody(): String? {
    // Raw body content requires explicit opt-in - security control
    return response()?.bodyToString()
}

internal fun ProxyHttpRequestResponse.extractPatterns(patterns: Map<String, String>): Map<String, List<List<String>>> {
    val results = mutableMapOf<String, List<List<String>>>()
    
    val requestText = request()?.toString() ?: ""
    val responseText = response()?.toString() ?: ""
    val fullText = "REQUEST:\n$requestText\n\nRESPONSE:\n$responseText"
    
    patterns.forEach { (name, pattern) ->
        try {
            val regex = Pattern.compile(pattern)
            val matcher = regex.matcher(fullText)
            val matches = mutableListOf<List<String>>()
            
            while (matcher.find()) {
                val groups = mutableListOf<String>()
                // Add the full match as group 0
                groups.add(matcher.group(0))
                // Add capture groups 1, 2, 3, etc.
                for (i in 1..matcher.groupCount()) {
                    groups.add(matcher.group(i) ?: "")
                }
                matches.add(groups)
            }
            
            results[name] = matches
        } catch (e: Exception) {
            // If regex is invalid, return empty list for this pattern
            results[name] = emptyList()
        }
    }
    
    return results
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
        mcpPaginatedTool<GetScannerIssues>("Displays information about issues identified by the scanner") {
            api.siteMap().issues().asSequence().map { Json.encodeToString(it.toSerializableForm()) }
        }
    }

    mcpPaginatedTool<GetProxyHttpHistory>("Displays items within the proxy HTTP history") {
        val allowed = runBlocking {
            checkHistoryPermissionOrDeny(HistoryAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        // Additional security validation for raw body access
        if (!validateRawBodyAccess(fields ?: emptySet(), config, api)) {
            return@mcpPaginatedTool sequenceOf("Raw body access denied by Burp Suite")
        }

        api.proxy().history().asSequence().map { 
            val result = it.toSerializableForm(fields ?: emptySet(), extractPatterns ?: emptyMap())
            val jsonString = when (result) {
                is HttpRequestResponse -> Json.encodeToString(HttpRequestResponse.serializer(), result)
                is FieldSelectiveResponse -> Json.encodeToString(FieldSelectiveResponse.serializer(), result)
                else -> throw IllegalStateException("Unexpected result type: ${result::class}")
            }
            formatAndTruncateIfNeeded(jsonString, outputFormat ?: "json") 
        }
    }

    mcpPaginatedTool<GetProxyHttpHistoryRegex>("Displays items matching a specified regex within the proxy HTTP history") {
        val allowed = runBlocking {
            checkHistoryPermissionOrDeny(HistoryAccessType.HTTP_HISTORY, config, api, "HTTP history")
        }
        if (!allowed) {
            return@mcpPaginatedTool sequenceOf("HTTP history access denied by Burp Suite")
        }

        val compiledRegex = Pattern.compile(regex)
        val matches = api.proxy().history { it.contains(compiledRegex) }.asSequence()
        
        val actualSearchScope = searchScope ?: setOf("all")
        val actualExcerptLength = excerptLength ?: 200
        val actualExtractGroups = extractGroups ?: false
        val actualOutputFormat = outputFormat ?: "json"
        
        if (actualSearchScope.contains("all") && !actualExtractGroups) {
            // Current behavior for backward compatibility
            matches.map { 
                val result = it.toSerializableForm()
                val jsonString = when (result) {
                    is HttpRequestResponse -> Json.encodeToString(HttpRequestResponse.serializer(), result)
                    is FieldSelectiveResponse -> Json.encodeToString(FieldSelectiveResponse.serializer(), result)
                    else -> throw IllegalStateException("Unexpected result type: ${result::class}")
                }
                formatAndTruncateIfNeeded(jsonString, actualOutputFormat) 
            }
        } else {
            // New behavior - return search excerpts with optional capture groups
            matches.map { match ->
                val (excerpt, captureGroups) = findExcerptAroundMatch(match, regex, actualSearchScope, actualExcerptLength, actualExtractGroups)
                val searchExcerpt = SearchExcerpt(
                    proxyId = match.annotations().notes() ?: "unknown",
                    url = match.request()?.url() ?: "",
                    method = match.request()?.method() ?: "",
                    status = match.response()?.statusCode()?.toInt(),
                    excerpt = excerpt,
                    extractedGroups = captureGroups,
                    timestamp = System.currentTimeMillis()
                )
                formatAndTruncateIfNeeded(Json.encodeToString(searchExcerpt), actualOutputFormat)
            }
        }
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
data class GetProxyHttpHistory(
    override val count: Int, 
    override val offset: Int,
    val fields: Set<String>? = null,
    val extractPatterns: Map<String, String>? = null,
    val outputFormat: String? = null
) : Paginated

@Serializable
data class GetProxyHttpHistoryRegex(
    val regex: String, 
    override val count: Int, 
    override val offset: Int,
    val searchScope: Set<String>? = null,
    val excerptLength: Int? = null,
    val extractGroups: Boolean? = null,
    val outputFormat: String? = null
) : Paginated

@Serializable
data class GetProxyWebsocketHistory(override val count: Int, override val offset: Int) : Paginated

@Serializable
data class GetProxyWebsocketHistoryRegex(val regex: String, override val count: Int, override val offset: Int) :
    Paginated