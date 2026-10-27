package net.portswigger.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import net.portswigger.mcp.schema.asInputSchema
import kotlin.experimental.ExperimentalTypeInference
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonNull

@PublishedApi
internal class McpToolException(message: String) : IllegalArgumentException(message)

fun mcpError(message: String): Nothing = throw McpToolException(message)

@PublishedApi
internal val ToolJson = Json { ignoreUnknownKeys = true }

@PublishedApi
internal fun coerceWholeNumberArguments(arguments: JsonObject, descriptor: SerialDescriptor): JsonObject =
    JsonObject(arguments.mapValues { (name, value) ->
        val primitive = value as? JsonPrimitive
        val index = descriptor.getElementIndex(name)
        if (primitive == null || primitive.isString || index < 0) return@mapValues value
        val kind = descriptor.getElementDescriptor(index).kind
        if (kind != PrimitiveKind.INT && kind != PrimitiveKind.LONG) return@mapValues value
        if (primitive == JsonNull || primitive.content == "true" || primitive.content == "false") return@mapValues value
        val range = if (kind == PrimitiveKind.INT) "${Int.MIN_VALUE}..${Int.MAX_VALUE}" else "${Long.MIN_VALUE}..${Long.MAX_VALUE}"
        val message = "Expected a whole integer in $range at path: $.$name"
        val decimal = primitive.content.toBigDecimalOrNull() ?: throw SerializationException(message)

        try {
            when (kind) {
                PrimitiveKind.INT -> JsonPrimitive(decimal.intValueExact())
                PrimitiveKind.LONG -> JsonPrimitive(decimal.longValueExact())
            }
        } catch (error: ArithmeticException) {
            throw SerializationException(message, error)
        }
    })

@PublishedApi
internal fun toolErrorResult(toolName: String, error: Exception): CallToolResult {
    val detail = error.message?.substringBefore("\nJSON input:") ?: error::class.simpleName ?: "unknown error"
    val message = when (error) {
        is McpToolException -> detail
        is SerializationException, is IllegalArgumentException ->
            "Invalid arguments for '$toolName': $detail. Check the tool schema from tools/list and retry."
        else -> "Tool '$toolName' failed: $detail. Check Burp's extension output for details."
    }
    return CallToolResult(content = listOf(TextContent(message)), isError = true)
}

@OptIn(InternalSerializationApi::class)
inline fun <reified I : Any> Server.mcpTool(
    description: String,
    crossinline execute: I.() -> List<ContentBlock>
) {
    val toolName = I::class.simpleName?.toLowerSnakeCase() ?: error("Couldn't find name for ${I::class}")
    val serializer = I::class.serializer()
    val inputSchema = serializer.descriptor.asInputSchema()

    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, request ->
        try {
            CallToolResult(
                content = execute(
                    ToolJson.decodeFromJsonElement(
                        serializer,
                        coerceWholeNumberArguments(
                            request.params.arguments ?: JsonObject(emptyMap()),
                            serializer.descriptor
                        )
                    )
                ),
                isError = false
            )
        } catch (e: Exception) {
            toolErrorResult(toolName, e)
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
                val upperLimit = (offset.toLong() + count).coerceAtMost(items.size.toLong()).toInt()

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

@OptIn(ExperimentalTypeInference::class)
@OverloadResolutionByLambdaReturnType
@JvmName("mcpNamedToolString")
inline fun Server.mcpTool(
    name: String,
    description: String,
    crossinline execute: () -> List<ContentBlock>
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        try {
            CallToolResult(content = execute(), isError = false)
        } catch (e: Exception) {
            toolErrorResult(name, e)
        }
    }
    addTool(name = name, description = description, inputSchema = ToolSchema(), handler = handler)
}

inline fun Server.mcpTool(
    name: String,
    description: String,
    crossinline execute: () -> String
) {
    val handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult = { _, _ ->
        try {
            CallToolResult(content = listOf(TextContent(execute())), isError = false)
        } catch (e: Exception) {
            toolErrorResult(name, e)
        }
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
