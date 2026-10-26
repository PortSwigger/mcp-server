package net.portswigger.mcp.security

import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class DataAccessType() {
    HTTP_HISTORY(), WEBSOCKET_HISTORY(), ORGANIZER(), SITE_MAP();
}

interface DataAccessApprovalHandler {
    suspend fun requestDataAccess(accessType: DataAccessType, config: McpConfig): Boolean
}

class SwingDataAccessApprovalHandler : DataAccessApprovalHandler {
    override suspend fun requestDataAccess(
        accessType: DataAccessType, config: McpConfig
    ): Boolean {
        return suspendCoroutine { continuation ->
            SwingUtilities.invokeLater {
                val accessTypeName = when (accessType) {
                    DataAccessType.HTTP_HISTORY -> "HTTP history"
                    DataAccessType.WEBSOCKET_HISTORY -> "WebSocket history"
                    DataAccessType.ORGANIZER -> "Organizer items"
                    DataAccessType.SITE_MAP -> "site map"
                }

                val message = buildString {
                    appendLine("An MCP client is requesting access to your Burp Suite $accessTypeName.")
                    appendLine()
                    appendLine("This may include sensitive data from previous web sessions.")
                    appendLine("Choose how you would like to respond:")
                }

                val options = arrayOf(
                    "Allow Once", "Always Allow $accessTypeName", "Deny"
                )

                val burpFrame = findBurpFrame()

                val result = Dialogs.showOptionDialog(
                    burpFrame, message, options
                )

                when (result) {
                    0 -> {
                        continuation.resume(true)
                    }

                    1 -> {
                        when (accessType) {
                            DataAccessType.HTTP_HISTORY -> config.alwaysAllowHttpHistory = true
                            DataAccessType.WEBSOCKET_HISTORY -> config.alwaysAllowWebSocketHistory = true
                            DataAccessType.ORGANIZER -> config.alwaysAllowOrganizer = true
                            DataAccessType.SITE_MAP -> config.alwaysAllowSiteMap = true
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

object DataAccessSecurity {

    var approvalHandler: DataAccessApprovalHandler = SwingDataAccessApprovalHandler()

    suspend fun checkDataAccessPermission(
        accessType: DataAccessType, config: McpConfig
    ): Boolean {
        if (!config.requireDataAccessApproval) {
            return true
        }

        val isAlwaysAllowed = when (accessType) {
            DataAccessType.HTTP_HISTORY -> config.alwaysAllowHttpHistory
            DataAccessType.WEBSOCKET_HISTORY -> config.alwaysAllowWebSocketHistory
            DataAccessType.ORGANIZER -> config.alwaysAllowOrganizer
            DataAccessType.SITE_MAP -> config.alwaysAllowSiteMap
        }

        if (isAlwaysAllowed) {
            return true
        }

        return approvalHandler.requestDataAccess(accessType, config)
    }
}