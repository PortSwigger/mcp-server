package net.portswigger.mcp.security

import burp.api.montoya.MontoyaApi
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AuditSwingUserApprovalHandler : UserApprovalHandler {
    override suspend fun requestApproval(
        hostname: String,
        port: Int,
        config: McpConfig,
        requestContent: String?,
        api: MontoyaApi?
    ): Boolean {
        return suspendCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val message = buildString {
                    appendLine("An MCP client is requesting to start an active scan on:")
                    appendLine()
                    appendLine("Target: $hostname:$port")
                    appendLine()
                }

                val auditDetails = buildString {
                    appendLine("=== Active Scan Request ===")
                    appendLine()
                    appendLine("Target: $hostname:$port")
                    appendLine()
                    appendLine("WARNING: Active scans will crawl and probe")
                    appendLine("the target automatically, potentially sending")
                    appendLine("many requests.")
                    appendLine()
                    appendLine("Only approve targets you are authorized to test.")
                }

                val options = arrayOf(
                    "Allow Once", "Always Allow Host", "Always Allow Host:Port", "Deny"
                )

                val burpFrame = findBurpFrame()
                val result = Dialogs.showOptionDialog(
                    burpFrame, message, options, auditDetails, api
                )

                when (result) {
                    0 -> continuation.resume(true)
                    1 -> {
                        config.addAutoApproveTarget(hostname)
                        continuation.resume(true)
                    }
                    2 -> {
                        config.addAutoApproveTarget("$hostname:$port")
                        continuation.resume(true)
                    }
                    else -> continuation.resume(false)
                }
            }
        }
    }
}
