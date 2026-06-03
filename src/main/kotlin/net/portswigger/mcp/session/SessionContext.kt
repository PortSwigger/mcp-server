package net.portswigger.mcp.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds session-scoped stable header values for a single MCP session.
 *
 * On first request, [fingerprintFromRawRequest] extracts stable header values.
 * On subsequent requests, [compressRequest] replaces known values with {{$VAR}} references.
 * [declarationBlock] produces the one-time context header prepended to Claude's first result.
 * [invalidateAuth] clears JWT/auth state on 401/403, triggering re-declaration next call.
 */
class SessionContext {

    private val variables = ConcurrentHashMap<String, String>() // varToken -> value
    private val declared = AtomicBoolean(false)

    /** Header name (lowercase) -> variable token used in output. */
    private val headerToVar = mapOf(
        "authorization"   to "\$AUTH",
        "cookie"          to "\$COOKIES",
        "x-csrf-token"    to "\$CSRF",
        "x-xsrf-token"    to "\$CSRF",
        "x-auth-token"    to "\$AUTH",
        "x-api-key"       to "\$APIKEY",
        "host"            to "\$HOST",
        "user-agent"      to "\$UA",
        "content-type"    to "\$CT",
        "accept"          to "\$ACCEPT",
        "accept-language" to "\$LANG",
        "accept-encoding" to "\$ENC",
        "referer"         to "\$REFERER",
        "origin"          to "\$ORIGIN",
        "connection"      to "\$CONN"
    )

    private val authVarTokens = setOf("\$AUTH", "\$COOKIES", "\$CSRF", "\$APIKEY")

    /**
     * Ingest headers from the first (or post-invalidation) request.
     */
    fun fingerprint(headers: List<Pair<String, String>>) {
        for ((name, value) in headers) {
            val lower = name.lowercase()
            val varToken = headerToVar[lower] ?: continue
            if (value.isNotBlank()) {
                variables[varToken] = value
            }
        }
        declared.set(false)
    }

    /**
     * Parse a raw HTTP request string and fingerprint its headers.
     */
    fun fingerprintFromRawRequest(rawRequest: String) {
        fingerprint(parseHeaders(rawRequest))
    }

    /**
     * Compress a single header value.
     * Returns the variable reference token if this header is known and matches, otherwise the original value.
     */
    fun compress(headerName: String, headerValue: String): String {
        val lower = headerName.lowercase()
        val varToken = headerToVar[lower] ?: return headerValue
        val stored = variables[varToken] ?: return headerValue
        return if (stored == headerValue) "{{\$${varToken.removePrefix("\$")}}}" else headerValue
    }

    /**
     * Compress known header values in a raw HTTP request string.
     * Only processes the headers section (before the first blank line).
     */
    fun compressRequest(rawRequest: String): String {
        if (variables.isEmpty()) return rawRequest

        // Detect line ending style
        val lineEnding = if (rawRequest.contains("\r\n")) "\r\n" else "\n"
        val blankLine = lineEnding + lineEnding
        val sepIdx = rawRequest.indexOf(blankLine)

        val headerSection = if (sepIdx >= 0) rawRequest.substring(0, sepIdx) else rawRequest
        val body = if (sepIdx >= 0) rawRequest.substring(sepIdx) else ""

        val lines = headerSection.split(lineEnding)
        val processedLines = lines.mapIndexed { index, line ->
            if (index == 0) {
                line // Request line — keep as-is
            } else {
                val colonIdx = line.indexOf(": ")
                if (colonIdx > 0) {
                    val name = line.substring(0, colonIdx)
                    val value = line.substring(colonIdx + 2)
                    "$name: ${compress(name, value)}"
                } else {
                    line
                }
            }
        }

        return processedLines.joinToString(lineEnding) + body
    }

    /**
     * Returns true if the declaration block has not yet been sent this session
     * (or was invalidated). Calling this atomically marks it as sent.
     */
    fun needsDeclaration(): Boolean = declared.compareAndSet(false, true)

    /**
     * Produces the declaration block prepended as a leading entry to Claude.
     * Format is intentionally compact — each line is ~10 tokens.
     */
    fun declarationBlock(): String {
        if (variables.isEmpty()) return ""
        val lines = variables.entries
            .sortedBy { it.key }
            .joinToString("\n") { (k, v) ->
                val display = if (v.length > 120) "${v.take(60)}…[${v.length} chars]" else v
                "$k = $display"
            }
        return "[SESSION VARIABLES — declared once, referenced below as {{\$VAR}}]\n$lines\n[END SESSION VARIABLES]"
    }

    /**
     * Invalidate auth/session-specific variables (e.g. on receiving a 401/403).
     * Stable structural headers (Host, UA, CT) are retained.
     */
    fun invalidateAuth() {
        authVarTokens.forEach { variables.remove(it) }
        declared.set(false)
    }

    /**
     * Full reset — wipe all variables and declaration state.
     */
    fun reset() {
        variables.clear()
        declared.set(false)
    }

    /** Whether any variables have been fingerprinted. */
    fun isEmpty(): Boolean = variables.isEmpty()

    /** Read-only snapshot of current variables (for UI display). */
    fun snapshot(): Map<String, String> = variables.toMap()

    /**
     * Estimated token savings per request compared to sending raw values.
     * Rough heuristic: 1 token ≈ 4 chars. Variable ref costs ~6 tokens.
     */
    fun estimatedTokenSavingsPerRequest(): Int {
        return variables.entries.sumOf { (_, v) ->
            val rawTokens = v.length / 4
            val refTokens = 6
            maxOf(0, rawTokens - refTokens)
        }
    }

    private fun parseHeaders(rawRequest: String): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        val lines = rawRequest.split(Regex("\r?\n"))
        var pastFirstLine = false
        for (line in lines) {
            if (!pastFirstLine) { pastFirstLine = true; continue }
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(": ")
            if (colonIdx > 0) {
                headers.add(line.substring(0, colonIdx) to line.substring(colonIdx + 2))
            }
        }
        return headers
    }
}
