package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.portswigger.mcp.config.McpConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CodexCliProviderTest {

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
    fun `install should create Codex config when missing`() {
        val proxyJarManager = mockk<ProxyJarManager>().apply {
            every { getProxyJar() } returns Path.of("/tmp/mcp-proxy-all.jar")
        }
        val provider = CodexCliProvider(logging, proxyJarManager, tempDir)

        val result = provider.install(config)

        val configPath = tempDir.resolve(".codex").resolve("config.toml")
        val content = configPath.toFile().readText()

        assertTrue(configPath.toFile().exists())
        assertEquals("Installation successful. Please restart Codex CLI if it is currently running.", result)
        assertTrue(content.contains("[mcp_servers.burp]"))
        assertTrue(content.contains("command = \"java\""))
        assertTrue(content.contains("/tmp/mcp-proxy-all.jar"))
        assertTrue(content.contains("http://127.0.0.1:9876"))
        verify { logging.logToOutput("Installed Burp MCP Server to Codex CLI config") }
    }

    @Test
    fun `install should replace existing burp table and preserve other config`() {
        val configPath = tempDir.resolve(".codex").resolve("config.toml")
        configPath.parent.toFile().mkdirs()
        configPath.toFile().writeText(
            """
            model = "gpt-5-codex"

            [features]
            multi_agent = true

            [mcp_servers.burp]
            command = "java"
            args = ["-jar", "/tmp/old.jar", "--sse-url", "http://localhost:9999"]

            [mcp_servers.other]
            command = "uvx"
            args = ["context7"]
            """.trimIndent()
        )

        val proxyJarManager = mockk<ProxyJarManager>().apply {
            every { getProxyJar() } returns Path.of("/tmp/mcp-proxy-all.jar")
        }
        val provider = CodexCliProvider(logging, proxyJarManager, tempDir)

        provider.install(config)

        val content = configPath.toFile().readText()
        assertTrue(content.contains("model = \"gpt-5-codex\""))
        assertTrue(content.contains("[features]"))
        assertTrue(content.contains("[mcp_servers.other]"))
        assertTrue(content.contains("/tmp/mcp-proxy-all.jar"))
        assertTrue(!content.contains("/tmp/old.jar"))
    }

    @Test
    fun `upsertTomlTable should append missing table`() {
        val provider = CodexCliProvider(logging, mockk(relaxed = true), tempDir)

        val updated = provider.upsertTomlTable(
            existingContent = "model = \"gpt-5-codex\"\n",
            tableHeader = "[mcp_servers.burp]",
            newBlock = "[mcp_servers.burp]\ncommand = \"java\""
        )

        assertEquals(
            "model = \"gpt-5-codex\"\n\n[mcp_servers.burp]\ncommand = \"java\"\n",
            updated
        )
    }
}
