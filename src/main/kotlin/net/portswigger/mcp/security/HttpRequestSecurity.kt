package net.portswigger.mcp.security

import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface UserApprovalHandler {
    suspend fun requestApproval(hostname: String, port: Int, config: McpConfig, requestContent: String? = null): Boolean
}

class SwingUserApprovalHandler : UserApprovalHandler {
    override suspend fun requestApproval(
        hostname: String,
        port: Int,
        config: McpConfig,
        requestContent: String?
    ): Boolean {
        return suspendCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val message = buildString {
                    appendLine("An MCP client is requesting to send an HTTP request to:")
                    appendLine()
                    appendLine("Target: $hostname:$port")
                    appendLine()
                }

                val options = arrayOf(
                    "Allow Once", "Always Allow Host", "Always Allow Host:Port", "Deny"
                )

                val burpFrame = java.awt.Frame.getFrames().find { frame ->
                    frame.isVisible && frame.isDisplayable && (
                            frame.title.contains("Burp Suite", ignoreCase = true) ||
                                    frame.title.contains("Professional", ignoreCase = true) ||
                                    frame.title.contains("Community", ignoreCase = true) ||
                                    frame.javaClass.name.contains("burp", ignoreCase = true) ||
                                    frame.javaClass.simpleName.contains("Burp", ignoreCase = true)
                            )
                } ?: run {
                    java.awt.Frame.getFrames()
                        .filter { it.isVisible && it.isDisplayable }
                        .maxByOrNull { it.width * it.height }
                }

                val result = Dialogs.showOptionDialog(
                    burpFrame, message, "MCP HTTP Request Security", options, requestContent
                )

                when (result) {
                    0 -> {
                        continuation.resume(true)
                    }

                    1 -> {
                        config.addAutoApproveTarget(hostname)
                        continuation.resume(true)
                    }

                    2 -> {
                        config.addAutoApproveTarget("$hostname:$port")
                        continuation.resume(true)
                    }

                    else -> {
                        continuation.resume(false)
                    }
                }
            }
        }
    }
}

object HttpRequestSecurity {

    var approvalHandler: UserApprovalHandler = SwingUserApprovalHandler()

    private fun isAutoApproved(hostname: String, port: Int, config: McpConfig): Boolean {
        val target = "$hostname:$port"
        val hostOnly = hostname
        val targets = config.getAutoApproveTargetsList()

        return targets.any { approved ->
            approved.equals(target, ignoreCase = true) || approved.equals(
                hostOnly,
                ignoreCase = true
            ) || (approved.startsWith("*.") && hostname.endsWith(
                approved.substring(2),
                ignoreCase = true
            ) && hostname != approved.substring(2))
        }
    }

    suspend fun checkHttpRequestPermission(
        hostname: String,
        port: Int,
        config: McpConfig,
        requestContent: String? = null
    ): Boolean {
        if (!config.requireHttpRequestApproval) {
            return true
        }

        if (isAutoApproved(hostname, port, config)) {
            return true
        }

        return approvalHandler.requestApproval(hostname, port, config, requestContent)
    }
}