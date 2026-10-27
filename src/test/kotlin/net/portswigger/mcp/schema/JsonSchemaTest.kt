package net.portswigger.mcp.schema

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import net.portswigger.mcp.tools.SendHttp1Request
import net.portswigger.mcp.tools.GenerateRandomString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonSchemaTest {
    @Serializable
    private data class Contract(
        @SerialName("wire_name") val renamed: String,
        val nullableButRequired: String?,
        val defaulted: String = "default",
        @Required val requiredWithDefault: String = "required",
        val nested: List<Map<String, String?>>,
        @Transient val ignored: String = "ignored"
    ) {
        val computed: String get() = renamed
    }

    @Test
    fun `schema follows serialized names optionality and nested nullability`() {
        val schema = Contract.serializer().descriptor.asInputSchema()
        assertEquals(setOf("wire_name", "nullableButRequired", "requiredWithDefault", "nested"), schema.required!!.toSet())
        val properties = schema.properties!!
        assertEquals(setOf("wire_name", "nullableButRequired", "defaulted", "requiredWithDefault", "nested"), properties.keys)
        assertEquals(setOf("string", "null"), properties.getValue("nullableButRequired").jsonObject.getValue("type").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val mapValue = properties.getValue("nested").jsonObject.getValue("items").jsonObject.getValue("additionalProperties").jsonObject
        assertEquals(setOf("string", "null"), mapValue.getValue("type").jsonArray.map { it.jsonPrimitive.content }.toSet())

        val input = """{"wire_name":"name","nullableButRequired":null,"requiredWithDefault":"value","nested":[{"key":null}]}"""
        assertEquals("default", Json.decodeFromString<Contract>(input).defaulted)
    }

    @Test
    fun `explicit constraints describe the same bounds enforced at runtime`() {
        val port = SendHttp1Request.serializer().descriptor.asInputSchema().properties!!.getValue("targetPort").jsonObject
        assertEquals(JsonPrimitive(1), port["minimum"])
        assertEquals(JsonPrimitive(65535), port["maximum"])
        val length = GenerateRandomString.serializer().descriptor.asInputSchema().properties!!.getValue("length").jsonObject
        assertEquals(JsonPrimitive(0), length["minimum"])
        assertThrows(IllegalArgumentException::class.java) { SendHttp1Request("", "example.com", 65536, true) }
        assertThrows(IllegalArgumentException::class.java) { GenerateRandomString(-1, "a") }
    }

    @Serializable
    private data class Unconstrained(val count: Int)

    @Test
    fun `property names do not silently add constraints`() {
        val count = Unconstrained.serializer().descriptor.asInputSchema().properties!!.getValue("count").jsonObject
        assertFalse(count.containsKey("minimum"))
        assertEquals(0, Json.decodeFromString<Unconstrained>("""{"count":0}""").count)
    }
}
