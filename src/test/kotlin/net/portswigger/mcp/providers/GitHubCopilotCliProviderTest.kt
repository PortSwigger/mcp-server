package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GitHubCopilotCliProviderTest {

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
    fun `install should create Copilot config when missing`() {
        val proxyJarManager = mockk<ProxyJarManager>().apply {
            every { getProxyJar() } returns Path.of("/tmp/mcp-proxy-all.jar")
        }
        val provider = GitHubCopilotCliProvider(logging, proxyJarManager, tempDir)

        val result = provider.install(config)

        val configPath = tempDir.resolve(".copilot").resolve("mcp-config.json")
        assertTrue(configPath.toFile().exists())
        assertEquals("Installation successful. Please restart GitHub Copilot CLI if it is currently running.", result)

        val json = Json.parseToJsonElement(configPath.toFile().readText()).jsonObject
        val burp = json["mcpServers"]!!.jsonObject["burp"]!!.jsonObject
        assertEquals("local", burp["type"]!!.jsonPrimitive.content)
        assertEquals("java", burp["command"]!!.jsonPrimitive.content)
        assertEquals("-jar", burp["args"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("/tmp/mcp-proxy-all.jar", burp["args"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("http://127.0.0.1:9876", burp["args"]!!.jsonArray[3].jsonPrimitive.content)
        assertEquals("*", burp["tools"]!!.jsonArray[0].jsonPrimitive.content)
        verify { logging.logToOutput("Installed Burp MCP Server to GitHub Copilot CLI config") }
    }

    @Test
    fun `install should preserve existing config and replace burp entry`() {
        val configPath = tempDir.resolve(".copilot").resolve("mcp-config.json")
        configPath.parent.toFile().mkdirs()
        configPath.toFile().writeText(
            """
            {
              "mcpServers": {
                "other": {
                  "type": "local",
                  "command": "uvx",
                  "args": ["context7"]
                },
                "burp": {
                  "type": "remote",
                  "url": "http://old.example"
                }
              }
            }
            """.trimIndent()
        )

        val proxyJarManager = mockk<ProxyJarManager>().apply {
            every { getProxyJar() } returns Path.of("/tmp/mcp-proxy-all.jar")
        }
        val provider = GitHubCopilotCliProvider(logging, proxyJarManager, tempDir)

        provider.install(config)

        val json = Json.parseToJsonElement(configPath.toFile().readText()).jsonObject
        val servers = json["mcpServers"]!!.jsonObject
        assertEquals("uvx", servers["other"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("local", servers["burp"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("java", servers["burp"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("/tmp/mcp-proxy-all.jar", servers["burp"]!!.jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content)
    }
}
