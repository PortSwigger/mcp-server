package net.portswigger.mcp

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import net.portswigger.mcp.config.ConfigUi
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.providers.ClaudeDesktopProvider
import net.portswigger.mcp.providers.ManualProxyInstallerProvider
import net.portswigger.mcp.providers.ProxyJarManager
import net.portswigger.mcp.tools.CapturedHttpRequests

@Suppress("unused")
class ExtensionBase : BurpExtension {

    override fun initialize(api: MontoyaApi) {
        api.extension().setName(ExtensionVersion.NAME)

        val config = McpConfig(api.persistence().extensionData(), api.logging())
        val capturedRequests = CapturedHttpRequests()
        val captureRegistration = api.http().registerHttpHandler(capturedRequests)
        val serverManager = KtorServerManager(api, capturedRequests)

        val proxyJarManager = ProxyJarManager(api.logging())

        val configUi = ConfigUi(
            config = config, providers = listOf(
                ClaudeDesktopProvider(api.logging(), proxyJarManager),
                ManualProxyInstallerProvider(api.logging(), proxyJarManager),
            )
        )

        configUi.onEnabledToggled { enabled ->
            configUi.getConfig()

            if (enabled) {
                serverManager.start(config) { state ->
                    configUi.updateServerState(state)
                }
            } else {
                serverManager.stop { state ->
                    configUi.updateServerState(state)
                }
            }
        }

        api.userInterface().registerSuiteTab(ExtensionVersion.TAB_TITLE, configUi.component)

        api.extension().registerUnloadingHandler {
            serverManager.shutdown()
            captureRegistration.deregister()
            capturedRequests.clear()
            configUi.cleanup()
            config.cleanup()
        }

        if (config.enabled) {
            serverManager.start(config) { state ->
                configUi.updateServerState(state)
            }
        }
    }
}
