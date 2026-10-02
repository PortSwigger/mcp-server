package net.portswigger.mcp.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_OUTPUT_LENGTH = 5_000
private const val MAX_CHUNK_LENGTH = 2_000
private const val MAX_HEADER_PREVIEW_LENGTH = 1_800

@Serializable
internal data class RecordedHttpMessage(val request: String, val response: String)

@Serializable
internal data class RecordedHttpHeaders(
    val source: String,
    val index: Int,
    val requestHeaders: String,
    val responseHeaders: String,
    val requestHeadersTruncated: Boolean,
    val responseHeadersTruncated: Boolean,
)

@Serializable
internal data class RecordedHttpChunk(
    val source: String,
    val index: Int,
    val part: String,
    val offset: Int,
    val nextOffset: Int,
    val totalLength: Int,
    val content: String,
)

internal fun encodeRecordedHttpHeaders(source: String, index: Int, message: RecordedHttpMessage): String {
    val requestHeaders = message.request.headersOnly()
    val responseHeaders = message.response.headersOnly()
    var previewLength = MAX_HEADER_PREVIEW_LENGTH
    while (true) {
        val request = requestHeaders.takeCodePoints(previewLength)
        val response = responseHeaders.takeCodePoints(previewLength)
        val encoded = Json.encodeToString(
            RecordedHttpHeaders(
                source, index, request.first, response.first, request.second, response.second
            )
        )
        if (encoded.length <= MAX_OUTPUT_LENGTH) return encoded
        check(previewLength > 0) { "Header metadata exceeds output limit" }
        previewLength /= 2
    }
}

internal fun encodeRecordedHttpChunk(
    source: String, index: Int, part: String, offset: Int, length: Int, message: RecordedHttpMessage
): String {
    require(part == "request" || part == "response") { "part must be 'request' or 'response'" }
    require(offset >= 0) { "offset must be non-negative" }
    require(length in 1..MAX_CHUNK_LENGTH) { "length must be between 1 and $MAX_CHUNK_LENGTH" }

    val content = if (part == "request") message.request else message.response
    val totalLength = content.codePointCount(0, content.length)
    require(offset <= totalLength) { "offset exceeds message length ($totalLength)" }

    val start = content.offsetByCodePoints(0, offset)
    var low = 0
    var high = minOf(length, totalLength - offset)
    var encoded = ""
    while (low <= high) {
        val size = (low + high) / 2
        val end = content.offsetByCodePoints(start, size)
        val candidate = Json.encodeToString(
            RecordedHttpChunk(source, index, part, offset, offset + size, totalLength, content.substring(start, end))
        )
        if (candidate.length <= MAX_OUTPUT_LENGTH) {
            encoded = candidate
            low = size + 1
        } else {
            high = size - 1
        }
    }
    return checkNotNull(encoded.takeIf { it.isNotEmpty() }) { "Chunk metadata exceeds output limit" }
}

private fun String.headersOnly(): String {
    val crlfEnd = indexOf("\r\n\r\n").takeIf { it >= 0 }
    val lfEnd = indexOf("\n\n").takeIf { it >= 0 }
    val end = listOfNotNull(crlfEnd, lfEnd).minOrNull()
    return if (end == null) this else substring(0, end)
}

private fun String.takeCodePoints(limit: Int): Pair<String, Boolean> {
    val count = codePointCount(0, length)
    return if (count <= limit) this to false else substring(0, offsetByCodePoints(0, limit)) to true
}
