package net.portswigger.mcp.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig

enum class DataAccessType() {
    HTTP_HISTORY(), WEBSOCKET_HISTORY(), ORGANIZER();
}

interface DataAccessApprovalHandler {
    suspend fun requestDataAccess(accessType: DataAccessType, config: McpConfig): Boolean
}

class SwingDataAccessApprovalHandler : DataAccessApprovalHandler {
    override suspend fun requestDataAccess(
        accessType: DataAccessType, config: McpConfig
    ): Boolean = withContext(Dispatchers.Swing) {
        val accessTypeName = when (accessType) {
            DataAccessType.HTTP_HISTORY -> "HTTP history"
            DataAccessType.WEBSOCKET_HISTORY -> "WebSocket history"
            DataAccessType.ORGANIZER -> "Organizer items"
        }
        val message = buildString {
            appendLine("An MCP client is requesting access to your Burp Suite $accessTypeName.")
            appendLine()
            appendLine("This may include sensitive data from previous web sessions.")
            appendLine("Choose how you would like to respond:")
        }
        val options = arrayOf("Allow Once", "Always Allow $accessTypeName", "Deny")
        val result = Dialogs.requestOption(findBurpFrame(), message, options)
        currentCoroutineContext().ensureActive()
        // Remembered settings commit synchronously; cancellation does not roll back a started write.
        when (result) {
            0 -> true
            1 -> {
                when (accessType) {
                    DataAccessType.HTTP_HISTORY -> config.alwaysAllowHttpHistory = true
                    DataAccessType.WEBSOCKET_HISTORY -> config.alwaysAllowWebSocketHistory = true
                    DataAccessType.ORGANIZER -> config.alwaysAllowOrganizer = true
                }
                true
            }
            else -> false
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
        }

        if (isAlwaysAllowed) {
            return true
        }

        val allowed = approvalHandler.requestDataAccess(accessType, config)
        currentCoroutineContext().ensureActive()
        return allowed
    }
}
