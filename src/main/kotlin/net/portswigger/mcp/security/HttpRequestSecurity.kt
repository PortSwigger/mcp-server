package net.portswigger.mcp.security

import burp.api.montoya.MontoyaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.Dialogs
import net.portswigger.mcp.config.McpConfig

interface UserApprovalHandler {
    suspend fun requestApproval(
        hostname: String, port: Int, config: McpConfig, requestContent: String? = null, api: MontoyaApi? = null
    ): Boolean
}

class SwingUserApprovalHandler : UserApprovalHandler {
    override suspend fun requestApproval(
        hostname: String, port: Int, config: McpConfig, requestContent: String?, api: MontoyaApi?
    ): Boolean = withContext(Dispatchers.Swing) {
        val message = buildString {
            appendLine("An MCP client is requesting to send an HTTP request to:")
            appendLine()
            appendLine("Target: $hostname:$port")
            appendLine()
        }
        val options = arrayOf("Allow Once", "Always Allow Host", "Always Allow Host:Port", "Deny")
        val result = Dialogs.awaitOptionDialog(findBurpFrame(), message, options, requestContent, api)
        currentCoroutineContext().ensureActive()
        // Remembered settings commit synchronously; cancellation does not roll back a started write.
        when (result) {
            0 -> true
            1 -> {
                config.addAutoApproveTarget(hostname)
                true
            }
            2 -> {
                config.addAutoApproveTarget("$hostname:$port")
                true
            }
            else -> false
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
            when {
                approved.equals(target, ignoreCase = true) -> true

                approved.equals(hostOnly, ignoreCase = true) -> true

                approved.startsWith("*.") -> {
                    val domain = approved.substring(2)
                    isValidWildcardMatch(hostname, domain)
                }

                else -> false
            }
        }
    }

    private fun isValidWildcardMatch(hostname: String, domain: String): Boolean {
        if (domain.isEmpty() || domain.contains("*")) return false

        if (hostname.length <= domain.length) return false

        val expectedSuffix = ".$domain"
        if (!hostname.endsWith(expectedSuffix, ignoreCase = true)) return false

        val subdomain = hostname.substring(0, hostname.length - expectedSuffix.length)

        if (subdomain.isEmpty()) return false

        return subdomain.split(".").all { label ->
            label.isNotEmpty() && label.length <= 63 && !label.startsWith("-") && !label.endsWith("-") && label.matches(
                Regex("^[a-zA-Z0-9-]+$")
            )
        }
    }

    suspend fun checkHttpRequestPermission(
        hostname: String, port: Int, config: McpConfig, requestContent: String? = null, api: MontoyaApi? = null
    ): Boolean {
        if (!config.requireHttpRequestApproval) {
            return true
        }

        if (isAutoApproved(hostname, port, config)) {
            return true
        }

        val allowed = approvalHandler.requestApproval(hostname, port, config, requestContent, api)
        currentCoroutineContext().ensureActive()
        return allowed
    }
}
