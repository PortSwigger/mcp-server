package net.portswigger.mcp.security

import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class HistoryAccessType {
    HTTP_HISTORY, WEBSOCKET_HISTORY
}

interface HistoryAccessApprovalHandler {
    suspend fun requestHistoryAccess(accessType: HistoryAccessType, config: McpConfig): Boolean
}

class SwingHistoryAccessApprovalHandler : HistoryAccessApprovalHandler {
    override suspend fun requestHistoryAccess(
        accessType: HistoryAccessType, config: McpConfig
    ): Boolean {
        return suspendCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val historyTypeName = when (accessType) {
                    HistoryAccessType.HTTP_HISTORY -> "HTTP history"
                    HistoryAccessType.WEBSOCKET_HISTORY -> "WebSocket history"
                }

                val message = buildString {
                    appendLine("An MCP client is requesting access to your Burp Suite $historyTypeName.")
                    appendLine()
                    appendLine("This may include sensitive data from previous web sessions.")
                    appendLine("Choose how you would like to respond:")
                }

                val options = arrayOf(
                    "Allow Once", "Always Allow $historyTypeName", "Deny"
                )

                val burpFrame = java.awt.Frame.getFrames().find { frame ->
                    frame.isVisible && frame.isDisplayable && (frame.title.contains(
                        "Burp Suite",
                        ignoreCase = true
                    ) || frame.title.contains("Professional", ignoreCase = true) || frame.title.contains(
                        "Community",
                        ignoreCase = true
                    ) || frame.javaClass.name.contains(
                        "burp",
                        ignoreCase = true
                    ) || frame.javaClass.simpleName.contains("Burp", ignoreCase = true))
                } ?: run {
                    java.awt.Frame.getFrames().filter { it.isVisible && it.isDisplayable }
                        .maxByOrNull { it.width * it.height }
                }

                val result = Dialogs.showOptionDialog(
                    burpFrame, message, "MCP History Access Security", options
                )

                when (result) {
                    0 -> {
                        continuation.resume(true)
                    }

                    1 -> {
                        when (accessType) {
                            HistoryAccessType.HTTP_HISTORY -> config.alwaysAllowHttpHistory = true
                            HistoryAccessType.WEBSOCKET_HISTORY -> config.alwaysAllowWebSocketHistory = true
                        }
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

object HistoryAccessSecurity {

    var approvalHandler: HistoryAccessApprovalHandler = SwingHistoryAccessApprovalHandler()

    suspend fun checkHistoryAccessPermission(
        accessType: HistoryAccessType, config: McpConfig
    ): Boolean {
        if (!config.requireHistoryAccessApproval) {
            return true
        }

        val isAlwaysAllowed = when (accessType) {
            HistoryAccessType.HTTP_HISTORY -> config.alwaysAllowHttpHistory
            HistoryAccessType.WEBSOCKET_HISTORY -> config.alwaysAllowWebSocketHistory
        }

        if (isAlwaysAllowed) {
            return true
        }

        return approvalHandler.requestHistoryAccess(accessType, config)
    }
}