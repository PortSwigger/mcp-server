package net.portswigger.mcp.security

import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

interface UserApprovalHandler {
    suspend fun requestApproval(hostname: String, port: Int, config: McpConfig): Boolean
}

class SwingUserApprovalHandler : UserApprovalHandler {
    override suspend fun requestApproval(hostname: String, port: Int, config: McpConfig): Boolean {
        return suspendCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val message = buildString {
                    appendLine("An MCP client is requesting to send an HTTP request to:")
                    appendLine()
                    appendLine("Target: $hostname:$port")
                    appendLine()
                    appendLine("Do you want to allow this request?")
                    appendLine()
                    appendLine("Note: This could be used to access internal services or")
                    appendLine("perform server-side request forgery (SSRF) attacks.")
                }

                val options = arrayOf(
                    "Allow Once",
                    "Always Allow $hostname",
                    "Always Allow $hostname:$port", 
                    "Deny"
                )

                val result = Dialogs.showOptionDialog(
                    null,
                    message,
                    "MCP HTTP Request Security",
                    options
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
            approved.equals(target, ignoreCase = true) ||
            approved.equals(hostOnly, ignoreCase = true) ||
            (approved.startsWith("*.") && 
             hostname.endsWith(approved.substring(2), ignoreCase = true) && 
             hostname != approved.substring(2))
        }
    }

    suspend fun checkHttpRequestPermission(
        hostname: String, 
        port: Int, 
        config: McpConfig
    ): Boolean {
        if (!config.requireHttpRequestApproval) {
            return true
        }

        if (isAutoApproved(hostname, port, config)) {
            return true
        }

        return approvalHandler.requestApproval(hostname, port, config)
    }
}