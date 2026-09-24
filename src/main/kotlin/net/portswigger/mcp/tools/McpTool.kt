package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.portswigger.mcp.schema.asInputSchema
import kotlin.experimental.ExperimentalTypeInference
import kotlin.math.ceil

@OptIn(InternalSerializationApi::class)
inline fun <reified I : Any> Server.mcpTool(
    description: String,
    crossinline execute: I.() -> List<ContentBlock>
) {
    val toolName = I::class.simpleName?.toLowerSnakeCase() ?: error("Couldn't find name for ${I::class}")
    val serializer = I::class.serializer()
    val inputSchema = I::class.asInputSchema()

    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, request ->
        try {
            CallToolResult(
                content = execute(
                    Json.decodeFromJsonElement(
                        serializer,
                        request.params.arguments ?: JsonObject(emptyMap())
                    )
                ),
                isError = false
            )
        } catch (e: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error: ${e.message}")),
                isError = true
            )
        }
    }

    addTool(name = toolName, description = description, inputSchema = inputSchema, handler = handler)
}

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mcpToolString")
inline fun <reified I : Any> Server.mcpTool(
    description: String,
    crossinline execute: I.() -> String
) {
    mcpTool<I>(description, execute = {
        listOf(TextContent(execute(this)))
    })
}

inline fun <reified I : Any> Server.mcpUnitTool(
    description: String,
    crossinline execute: I.() -> Unit
) {
    mcpTool<I>(description, execute = {
        execute(this)

        listOf(TextContent("Executed tool"))
    })
}

inline fun <reified I : Paginated, J : Any> Server.mcpPaginatedTool(
    description: String,
    noinline mapper: (J) -> CharSequence = { it.toString() },
    crossinline execute: I.() -> List<J>
) {
    mcpTool<I>(description, execute = {

        val items = execute(this)

        when {
            offset >= items.size -> {
                "Reached end of items"
            }

            else -> {
                val upperLimit = (offset + count).coerceAtMost(items.size)

                items.subList(offset, upperLimit)
                    .joinToString(separator = "\n\n", transform = mapper)
            }
        }
    })
}

inline fun <reified I : Paginated> Server.mcpPaginatedTool(
    description: String,
    crossinline execute: I.() -> Sequence<String>
) {
    mcpTool<I>(description, execute = {
        val seq = execute(this)
        val paginated = seq.drop(offset).take(count).toList()

        if (paginated.isEmpty()) {
            listOf(TextContent("Reached end of items"))
        } else {
            listOf(TextContent(paginated.joinToString(separator = "\n\n")))
        }
    })
}

const val DEFAULT_RESPONSE_PAGE_BYTES = 800_000

/**
 * Variant of mcpPaginatedTool for tools that return a single (potentially large) string.
 *
 * Here, [offset] is a **byte offset** into the response and [count] is the **maximum number
 * of bytes** to return in this page. Both are optional (defaults: offset=0,
 * count=[DEFAULT_RESPONSE_PAGE_BYTES], which stays under the 1 MB MCP tool-result limit).
 * If the whole response fits into the first page it is returned verbatim, otherwise a header
 * tells the caller which offset to request next. Page boundaries never split a UTF-8 character.
 */
inline fun <reified I : Paginated> Server.mcpPaginatedBytesTool(
    description: String,
    crossinline execute: I.() -> String
) {
    mcpTool<I>(description, execute = {
        val full = execute(this)
        val bytes = full.toByteArray(Charsets.UTF_8)
        val totalBytes = bytes.size
        val pageSize = count.coerceAtLeast(1)
        val start = utf8Boundary(bytes, offset.coerceAtLeast(0))

        if (start == 0 && totalBytes <= pageSize) {
            full
        } else if (start >= totalBytes) {
            "Reached end of response (total bytes: $totalBytes)"
        } else {
            val end = utf8Boundary(bytes, (start + pageSize).coerceAtMost(totalBytes)).let { if (it <= start) (start + pageSize).coerceAtMost(totalBytes) else it }
            val chunk = String(bytes, start, end - start, Charsets.UTF_8)
            val remaining = totalBytes - end
            val totalPages = ceil(totalBytes.toDouble() / pageSize).toInt()
            val currentPage = ceil(end.toDouble() / pageSize).toInt()

            buildString {
                appendLine("=== Page $currentPage of ~$totalPages | bytes $start–$end of $totalBytes | ${if (remaining > 0) "$remaining bytes remaining" else "last page"} ===")
                if (remaining > 0) {
                    appendLine("Call again with offset=$end to get the next page.")
                }
                appendLine()
                append(chunk)
            }
        }
    })
}

/** Moves [index] back to the start of the UTF-8 character it falls into. */
fun utf8Boundary(bytes: ByteArray, index: Int): Int {
    var i = index.coerceAtMost(bytes.size)
    while (i in 1 until bytes.size && (bytes[i].toInt() and 0xC0) == 0x80) i--
    return i
}

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mcpNamedToolString")
inline fun Server.mcpTool(
    name: String,
    description: String,
    crossinline execute: () -> List<ContentBlock>
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        CallToolResult(content = execute(), isError = false)
    }
    addTool(name = name, description = description, inputSchema = ToolSchema(), handler = handler)
}

inline fun Server.mcpTool(
    name: String,
    description: String,
    crossinline execute: () -> String
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        CallToolResult(content = listOf(TextContent(execute())), isError = false)
    }
    addTool(name = name, description = description, inputSchema = ToolSchema(), handler = handler)
}

fun String.toLowerSnakeCase(): String {
    return this
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z])([A-Z][a-z])"), "$1_$2")
        .replace(Regex("[\\s-]+"), "_")
        .lowercase()
}

interface Paginated {
    val count: Int
    val offset: Int
}
