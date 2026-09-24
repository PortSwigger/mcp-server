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

/**
 * Variant of mcpPaginatedTool for tools that return a single (potentially large) string.
 *
 * Here, [offset] is a **byte offset** into the response and [count] is the **maximum number
 * of bytes** to return in this page. The caller (Claude) should start with offset=0 and keep
 * calling with offset += count until it receives a page whose length is less than count, or
 * it receives the "Reached end of response" message.
 *
 * A safe default page size is 800 000 bytes, which stays well under the 1 MB MCP tool-result
 * limit even after JSON encoding overhead.
 */
inline fun <reified I : Paginated> Server.mcpPaginatedBytesTool(
    description: String,
    crossinline execute: I.() -> String
) {
    mcpTool<I>(description, execute = {
        val full = execute(this)
        val bytes = full.toByteArray(Charsets.UTF_8)
        val totalBytes = bytes.size

        if (offset >= totalBytes) {
            val totalPages = ceil(totalBytes.toDouble() / count).toInt().coerceAtLeast(1)
            "Reached end of response (total bytes: $totalBytes, total pages at this page size: $totalPages)"
        } else {
            val end = (offset + count).coerceAtMost(totalBytes)
            val chunk = bytes.sliceArray(offset until end).toString(Charsets.UTF_8)
            val remaining = totalBytes - end
            val totalPages = ceil(totalBytes.toDouble() / count).toInt()
            val currentPage = ceil(end.toDouble() / count).toInt()

            buildString {
                appendLine("=== Page $currentPage of $totalPages | bytes $offset–$end of $totalBytes | ${if (remaining > 0) "$remaining bytes remaining" else "last page"} ===")
                if (remaining > 0) {
                    appendLine("Call again with offset=$end to get the next page.")
                }
                appendLine()
                append(chunk)
            }
        }
    })
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
