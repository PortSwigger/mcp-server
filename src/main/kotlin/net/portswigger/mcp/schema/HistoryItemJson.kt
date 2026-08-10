package net.portswigger.mcp.schema

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val MAX_HISTORY_ITEM_LENGTH = 5_000
private const val TRUNCATION_MARKER = "... (truncated)"

internal inline fun <reified T> encodeHistoryItem(item: T): String =
    limitHistoryItemJson(Json.encodeToString(item))

@PublishedApi
internal fun limitHistoryItemJson(serialized: String): String {
    if (serialized.length <= MAX_HISTORY_ITEM_LENGTH) return serialized

    val item = Json.parseToJsonElement(serialized)
    var lowerBound = TRUNCATION_MARKER.length
    var upperBound = serialized.length
    var best: String? = null

    while (lowerBound <= upperBound) {
        val fieldLimit = (lowerBound + upperBound) / 2
        val candidate = Json.encodeToString(
            JsonElement.serializer(), item.truncateStringsTo(fieldLimit)
        )

        if (candidate.length <= MAX_HISTORY_ITEM_LENGTH) {
            best = candidate
            lowerBound = fieldLimit + 1
        } else {
            upperBound = fieldLimit - 1
        }
    }

    return checkNotNull(best) {
        "History item JSON structure exceeds the $MAX_HISTORY_ITEM_LENGTH character limit"
    }
}

private fun JsonElement.truncateStringsTo(maxLength: Int): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.truncateStringsTo(maxLength) })
    is JsonArray -> JsonArray(map { it.truncateStringsTo(maxLength) })
    is JsonPrimitive -> if (isString) JsonPrimitive(content.truncateTo(maxLength)) else this
}

private fun String.truncateTo(maxLength: Int): String {
    if (length <= maxLength) return this

    var prefixLength = maxLength - TRUNCATION_MARKER.length
    if (prefixLength > 0 && this[prefixLength - 1].isHighSurrogate() && this[prefixLength].isLowSurrogate()) {
        prefixLength--
    }
    return take(prefixLength) + TRUNCATION_MARKER
}
