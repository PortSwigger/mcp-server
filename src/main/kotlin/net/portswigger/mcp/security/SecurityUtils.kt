package net.portswigger.mcp.security

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.Frame

/**
 * Finds the Burp Suite main frame or the largest available frame as fallback
 */
fun findBurpFrame(): Frame? {
    val burpIdentifiers = listOf("Burp Suite", "Professional", "Community", "burp")

    return Frame.getFrames().find { frame ->
        frame.isVisible && frame.isDisplayable && burpIdentifiers.any { identifier ->
            frame.title.contains(identifier, ignoreCase = true) ||
                    frame.javaClass.name.contains(identifier, ignoreCase = true) ||
                    frame.javaClass.simpleName.contains(identifier, ignoreCase = true)
        }
    } ?: Frame.getFrames()
        .filter { it.isVisible && it.isDisplayable }
        .maxByOrNull { it.width * it.height }
}

// Keys that hold credential-bearing values in Burp's exported user/project options.
// Cross-referenced against SuiteConfigurationFragmentFields in the Burp desktop codebase.
private val SENSITIVE_KEYS = setOf(
    "password",             // socks proxy, platform auth, upstream proxy, client certs, app login
    "certificate_password", // proxy request listener PKCS12 password
    "hashed_key",           // Burp REST API key (SHA-256 of the actual key)
)

private const val REDACTED = "*****"

fun filterConfigCredentials(json: String): String {
    return try {
        Json.encodeToString(filterJsonElement(Json.parseToJsonElement(json)))
    } catch (_: SerializationException) {
        // Burp's export is expected to always be valid JSON. If it isn't, fail closed:
        // do not echo the original or the parser's message (it quotes surrounding input,
        // which can include credential values).
        """{"error":"failed to parse config json"}"""
    }
}

private fun filterJsonElement(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (key, value) -> filterValue(key, value) })
    is JsonArray -> JsonArray(element.map(::filterJsonElement))
    else -> element
}

private fun filterValue(key: String, value: JsonElement): JsonElement =
    if (key.lowercase() in SENSITIVE_KEYS && value is JsonPrimitive && value.isString) {
        JsonPrimitive(REDACTED)
    } else {
        filterJsonElement(value)
    }
