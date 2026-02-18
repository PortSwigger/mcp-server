package net.portswigger.mcp.security

import java.awt.Frame
import net.portswigger.mcp.config.McpConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SecurityConfig(
    val options: Map<String, Map<String, JsonElement>>
)

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

fun filterConfigCredentials(json: String): String {
    return try {
        val jsonElement = Json.parseToJsonElement(json)
        val filteredElement = filterJsonElement(jsonElement)
        Json.encodeToString(filteredElement)
    } catch (e: Exception) {
        throw RuntimeException("Failed to filter credentials", e)
    }
}

private fun filterJsonElement(element: JsonElement): JsonElement {
    return when (element) {
        is JsonObject -> filterJsonObject(element)
        is JsonArray -> filterJsonArray(element)
        else -> element
    }
}

private fun filterJsonObject(obj: JsonObject): JsonObject {
    val filteredMap = obj.mapValues { (key, value) ->
        when {
            value is JsonPrimitive && value.isString && key == "password" -> 
                JsonPrimitive("*****")
            else -> filterJsonElement(value)
        }
    }
    return JsonObject(filteredMap)
}

private fun filterJsonArray(array: JsonArray): JsonArray {
    val filteredList = array.map { element -> filterJsonElement(element) }
    return JsonArray(filteredList)
}