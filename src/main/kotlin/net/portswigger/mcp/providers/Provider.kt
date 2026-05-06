package net.portswigger.mcp.providers

import burp.api.montoya.logging.Logging
import kotlinx.serialization.json.*
import net.portswigger.mcp.config.McpConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

interface Provider {
    val name: String
    val installButtonText: String
    val confirmationText: String?
    fun install(config: McpConfig): String?
}

class CodexCliProvider(
    private val logging: Logging,
    private val proxyJarManager: ProxyJarManager,
    private val userHome: Path = Path.of(System.getProperty("user.home"))
) : Provider {

    private val serverName = "burp"
    private val configFileName = "config.toml"

    override val name = "Codex CLI"
    override val installButtonText = "Install to $name"
    override val confirmationText =
        "Install to $name?\nThis will create or update $name's MCP configuration ($configFileName)."

    override fun install(config: McpConfig): String {
        val proxyJarPath = proxyJarManager.getProxyJar()
        val path = configFilePath()
        path.parent.createDirectories()

        val burpBlock = buildString {
            appendLine("[mcp_servers.$serverName]")
            appendLine("command = \"java\"")
            appendLine(
                "args = [\"-jar\", \"${escapeTomlString(proxyJarPath.toString())}\", \"--sse-url\", \"http://${config.host}:${config.port}\"]"
            )
        }.trimEnd()

        val updatedContent = upsertTomlTable(
            existingContent = if (path.exists()) path.readText() else "",
            tableHeader = "[mcp_servers.$serverName]",
            newBlock = burpBlock
        )

        path.writeText(updatedContent)
        logging.logToOutput("Installed Burp MCP Server to Codex CLI config")

        return "Installation successful. Please restart $name if it is currently running."
    }

    private fun configFilePath(): Path {
        return userHome.resolve(".codex").resolve(configFileName)
    }

    internal fun upsertTomlTable(existingContent: String, tableHeader: String, newBlock: String): String {
        if (existingContent.isBlank()) {
            return "$newBlock\n"
        }

        val headerRegex = Regex("(?m)^\\[.*]\\s*$")
        val match = Regex("(?m)^${Regex.escape(tableHeader)}\\s*$").find(existingContent)

        if (match == null) {
            val separator = if (existingContent.endsWith("\n\n") || existingContent.endsWith("\r\n\r\n")) "" else "\n\n"
            return existingContent.trimEnd() + separator + newBlock + "\n"
        }

        val startIndex = match.range.first
        val nextHeader = headerRegex.find(existingContent, match.range.last + 1)
        val endIndex = nextHeader?.range?.first ?: existingContent.length

        val prefix = existingContent.substring(0, startIndex).trimEnd()
        val suffix = existingContent.substring(endIndex).trimStart('\n', '\r')

        return buildString {
            if (prefix.isNotEmpty()) {
                append(prefix)
                append("\n\n")
            }
            append(newBlock)
            append("\n")
            if (suffix.isNotBlank()) {
                append("\n")
                append(suffix)
                if (!suffix.endsWith("\n")) {
                    append("\n")
                }
            }
        }
    }

    private fun escapeTomlString(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

class ClaudeDesktopProvider(private val logging: Logging, private val proxyJarManager: ProxyJarManager) : Provider {

    private val claudeConfigFileName = "claude_desktop_config.json"
    private val serverName = "burp"

    override val name = "Claude Desktop"
    override val installButtonText = "Install to $name"
    override val confirmationText =
        "Install to $name?\nThis will create an entry within $name's MCP configuration file ($claudeConfigFileName)"

    override fun install(config: McpConfig): String {
        val proxyJarFile = proxyJarManager.getProxyJar()

        val path = configFilePath() ?: error("Could not find Claude config path")
        val content = Json.parseToJsonElement(path.readText()).jsonObject.toMutableMap()

        val javaPath = javaPath()
        logging.logToOutput("Using Java from: $javaPath")

        val sseUrl = "http://${config.host}:${config.port}"
        val burpServerConfig = buildJsonObject {
            put("command", JsonPrimitive(javaPath))
            put("args", buildJsonArray {
                add(JsonPrimitive("-jar"))
                add(JsonPrimitive(proxyJarFile.toString()))
                add(JsonPrimitive("--sse-url"))
                add(JsonPrimitive(sseUrl))
            })
        }

        val mcpServers = content["mcpServers"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
        mcpServers[serverName] = burpServerConfig
        content["mcpServers"] = JsonObject(mcpServers)

        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        path.writeText(json.encodeToString(JsonObject.serializer(), JsonObject(content)))

        logging.logToOutput("Installed Burp MCP Server to Claude Desktop config")

        return "Installation successful. Please restart $name if it is currently running."
    }

    private fun configFilePath(): Path? {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")

        val basePath = when {
            os.contains("win") -> Path.of(home, "AppData", "Roaming", "Claude")
            os.contains("mac") || os.contains("darwin") -> Path.of(home, "Library", "Application Support", "Claude")
            os.contains("linux") -> Path.of(home, ".config", "Claude")
            else -> return null
        }

        if (!basePath.exists()) return null

        val configFile = basePath.resolve(claudeConfigFileName)
        if (!configFile.exists()) {
            createDefaultConfig(configFile)
        }

        return configFile
    }

    private fun createDefaultConfig(path: Path): Boolean {
        try {
            val defaultConfig = buildJsonObject {
                put("mcpServers", buildJsonObject {})
            }

            val json = Json {
                prettyPrint = true
                encodeDefaults = true
            }

            path.writeText(json.encodeToString(JsonObject.serializer(), defaultConfig))
            logging.logToOutput("Created default Claude Desktop config at $path")
            return true
        } catch (e: Exception) {
            logging.logToError("Failed to create default Claude Desktop config: ${e.message}")
            return false
        }
    }

    private fun javaPath(): String {
        val javaHome = System.getProperty("java.home")
        val os = System.getProperty("os.name").lowercase()

        return if (os.contains("win")) {
            "$javaHome\\bin\\java.exe"
        } else {
            "$javaHome/bin/java"
        }
    }
}

class ManualProxyInstallerProvider(private val logging: Logging, private val proxyJarManager: ProxyJarManager) :
    Provider {
    override val name = "Proxy jar"
    override val installButtonText = "Extract server proxy jar"
    override val confirmationText = null

    override fun install(config: McpConfig): String? {
        val proxyJarFile = proxyJarManager.getProxyJar()

        val fileChooser = JFileChooser().apply {
            dialogTitle = "Save proxy jar"
            selectedFile = File("mcp-proxy.jar")
        }

        if (fileChooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) {
            return null
        }

        val destinationFile = fileChooser.selectedFile
        try {
            Files.copy(proxyJarFile, destinationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            logging.logToOutput("MCP proxy jar saved successfully to ${destinationFile.absolutePath}")
        } catch (ex: Exception) {
            logging.logToError("Failed to save installer: ${ex.message}")
            throw ex
        }

        return "Extracted proxy jar to $destinationFile"
    }
}
