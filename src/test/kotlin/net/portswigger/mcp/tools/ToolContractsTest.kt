package net.portswigger.mcp.tools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration

class ToolContractsTest {
    @Serializable
    private data class Numbers(@SerialName("integer") val intValue: Int, val longValue: Long)

    private fun decode(intValue: String, longValue: String = "0"): Numbers {
        val input = Json.parseToJsonElement("""{"integer":$intValue,"longValue":$longValue}""").jsonObject
        return ToolJson.decodeFromJsonElement(Numbers.serializer(), coerceWholeNumberArguments(input, Numbers.serializer().descriptor))
    }

    @Test
    fun `integer coercion is exact across decimal exponent and range boundaries`() {
        assertEquals(Numbers(443, 443), decode("443.0", "4.43e2"))
        assertEquals(Numbers(Int.MAX_VALUE, Long.MAX_VALUE), decode("2147483647.0", "9223372036854775807.0"))
        assertEquals(Numbers(Int.MIN_VALUE, Long.MIN_VALUE), decode("-2147483648.0", "-9223372036854775808.0"))
        assertEquals(Numbers(0, 0), decode("0e100000000", "0e-100000000"))
        assertThrows(SerializationException::class.java) { decode("443.5") }
        assertThrows(SerializationException::class.java) { decode("2147483648.0") }
        assertThrows(SerializationException::class.java) { decode("-2147483649.0") }
        assertThrows(SerializationException::class.java) { decode("0", "9223372036854775808.0") }
        assertThrows(SerializationException::class.java) { decode("0", "-9223372036854775809.0") }
        assertThrows(SerializationException::class.java) { decode("0", "0.1") }
    }

    @Test
    fun `huge exponents are rejected without expanding the integer`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            for (value in listOf("1e100000000", "-1e100000000", "1e-100000000", "1e2147483648")) {
                assertThrows(SerializationException::class.java) { decode(value) }
                assertThrows(SerializationException::class.java) { decode("0", value) }
            }
        }
    }

    @Test
    fun `argument errors preserve the wire field path without echoing the input`() {
        for (value in listOf("443.5", "true")) {
            val error = assertThrows(SerializationException::class.java) { decode(value) }
            val result = toolErrorResult("numbers", error)
            val text = (result.content.single() as TextContent).text
            assertEquals(true, result.isError)
            assertTrue(text.contains("$.integer"), text)
            assertFalse(text.contains("JSON input:"), text)
            assertFalse(text.contains("longValue"), text)
        }
    }

    @Test
    fun `undeclared arguments remain ignored`() {
        val input = Json.parseToJsonElement("""{"integer":443.0,"longValue":0,"extra":1e100000000}""").jsonObject
        val result = ToolJson.decodeFromJsonElement(Numbers.serializer(), coerceWholeNumberArguments(input, Numbers.serializer().descriptor))
        assertEquals(Numbers(443, 0), result)
    }
}
