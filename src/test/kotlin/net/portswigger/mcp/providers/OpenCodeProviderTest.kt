package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class OpenCodeProviderTest {

    private val schemaKey = "\$schema"

    @TempDir
    lateinit var tempDir: Path

    private lateinit var logging: Logging
    private lateinit var config: McpConfig

    @BeforeEach
    fun setup() {
        val storage = mutableMapOf<String, Any>()

        val persistedObject = mockk<PersistedObject>().apply {
            every { getBoolean(any()) } answers {
                val key = firstArg<String>()
                storage[key] as? Boolean ?: when (key) {
                    "enabled" -> true
                    else -> false
                }
            }
            every { getString(any()) } answers {
                val key = firstArg<String>()
                storage[key] as? String ?: when (key) {
                    "host" -> "127.0.0.1"
                    else -> ""
                }
            }
            every { getInteger(any()) } answers {
                val key = firstArg<String>()
                storage[key] as? Int ?: when (key) {
                    "port" -> 9876
                    else -> 0
                }
            }
            every { setBoolean(any(), any()) } answers {
                storage[firstArg()] = secondArg<Boolean>()
            }
            every { setString(any(), any()) } answers {
                storage[firstArg()] = secondArg<String>()
            }
            every { setInteger(any(), any()) } answers {
                storage[firstArg()] = secondArg<Int>()
            }
        }

        logging = mockk<Logging>().apply {
            every { logToOutput(any<String>()) } returns Unit
            every { logToError(any<String>()) } returns Unit
        }

        config = McpConfig(persistedObject, logging)
    }

    @Test
    fun `install should create OpenCode config when missing`() {
        val provider = OpenCodeProvider(logging, tempDir)

        val result = provider.install(config)

        val configPath = tempDir.resolve(".config").resolve("opencode").resolve("opencode.json")
        assertTrue(configPath.toFile().exists())
        assertEquals("Installation successful. Please restart OpenCode if it is currently running.", result)

        val json = Json.parseToJsonElement(configPath.toFile().readText()).jsonObject
        assertEquals("https://opencode.ai/config.json", json[schemaKey]!!.jsonPrimitive.content)
        assertEquals("remote", json["mcp"]!!.jsonObject["burp"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:9876", json["mcp"]!!.jsonObject["burp"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(json["mcp"]!!.jsonObject["burp"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        verify { logging.logToOutput("Installed Burp MCP Server to OpenCode config") }
    }

    @Test
    fun `install should preserve existing config and replace burp entry`() {
        val configPath = tempDir.resolve(".config").resolve("opencode").resolve("opencode.json")
        configPath.parent.toFile().mkdirs()
        configPath.toFile().writeText(
            """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "mcp": {
                "jira": {
                  "type": "local",
                  "command": ["uvx", "mcp-atlassian"],
                  "enabled": true
                },
                "burp": {
                  "type": "local",
                  "command": ["java", "-jar", "/tmp/old.jar"],
                  "enabled": false
                }
              }
            }
            """.trimIndent()
        )

        val provider = OpenCodeProvider(logging, tempDir)

        provider.install(config)

        val json = Json.parseToJsonElement(configPath.toFile().readText()).jsonObject
        assertEquals("local", json["mcp"]!!.jsonObject["jira"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("remote", json["mcp"]!!.jsonObject["burp"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:9876", json["mcp"]!!.jsonObject["burp"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(json["mcp"]!!.jsonObject["burp"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
    }
}
