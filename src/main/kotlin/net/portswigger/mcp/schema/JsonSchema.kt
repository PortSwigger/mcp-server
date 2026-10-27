package net.portswigger.mcp.schema

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.asInputSchema(): ToolSchema {
    val schema = asJsonSchema()
    return ToolSchema(
        properties = schema.getValue("properties").jsonObject,
        required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
    )
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerialDescriptor.asJsonSchema(): JsonObject {
    val type = when (kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR, SerialKind.ENUM -> "string"
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "integer"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.BOOLEAN -> "boolean"
        StructureKind.LIST -> "array"
        StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> "object"
        else -> error("Unsupported tool schema: $serialName ($kind)")
    }
    return buildJsonObject {
        put("type", if (isNullable) {
            JsonArray(listOf(JsonPrimitive(type), JsonPrimitive("null")))
        } else {
            JsonPrimitive(type)
        })
        when (kind) {
            SerialKind.ENUM -> {
                val values = (0 until elementsCount).map { JsonPrimitive(getElementName(it)) }
                put("enum", JsonArray(values + if (isNullable) listOf(JsonNull) else emptyList()))
            }
            StructureKind.LIST -> put("items", getElementDescriptor(0).asJsonSchema())
            StructureKind.MAP -> put("additionalProperties", getElementDescriptor(1).asJsonSchema())
            StructureKind.CLASS, StructureKind.OBJECT -> {
                put("properties", buildJsonObject {
                    for (index in 0 until elementsCount) {
                        val property = getElementDescriptor(index).asJsonSchema().toMutableMap()
                        val constraint = getElementAnnotations(index).filterIsInstance<ConstrainedInteger>().singleOrNull()?.value
                        if (constraint != null) {
                            property["minimum"] = JsonPrimitive(constraint.minimum)
                            constraint.maximum?.let { property["maximum"] = JsonPrimitive(it) }
                        }
                        put(getElementName(index), JsonObject(property))
                    }
                })
                val required = (0 until elementsCount).filterNot(::isElementOptional)
                put("required", JsonArray(required.map { JsonPrimitive(getElementName(it)) }))
            }
            else -> Unit
        }
    }
}
