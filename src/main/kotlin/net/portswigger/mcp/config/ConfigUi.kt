package net.portswigger.mcp.config

import io.ktor.util.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.portswigger.mcp.ServerState
import net.portswigger.mcp.Swing
import net.portswigger.mcp.config.components.*
import net.portswigger.mcp.providers.Provider
import java.awt.BorderLayout
import java.awt.Component.CENTER_ALIGNMENT
import java.awt.GridBagLayout
import javax.swing.*
import javax.swing.Box.*
import javax.swing.JOptionPane.ERROR_MESSAGE

class ConfigUi(private val config: McpConfig, private val providers: List<Provider>) {

    private val panel = JPanel(BorderLayout())
    val component: JComponent get() = panel

    private val listenerHandles = mutableListOf<ListenerHandle>()

    private val enabledToggle: ToggleSwitch = Design.createToggleSwitch(false) { enabled ->
        if (suppressToggleEvents) return@createToggleSwitch

        if (enabled) {
            ConfigValidation.validateServerConfig(hostField.text, portField.text)?.let { error ->
                validationErrorLabel.text = error
                validationErrorLabel.isVisible = true
                suppressToggleEvents = true
                enabledToggle.setState(false, animate = true)
                suppressToggleEvents = false
                return@createToggleSwitch
            }
        }

        validationErrorLabel.isVisible = false
        config.enabled = enabled
        toggleListener?.invoke(enabled)
    }
    private val validationErrorLabel = WarningLabel()
    private val hostField = JTextField(15)
    private val portField = JTextField(5)
    private val reinstallNotice = WarningLabel("Make sure to reinstall after changing server settings")

    private lateinit var serverConfigurationPanel: ServerConfigurationPanel
    private lateinit var advancedOptionsPanel: AdvancedOptionsPanel
    private lateinit var autoApproveTargetsPanel: AutoApproveTargetsPanel
    private lateinit var installationPanel: InstallationPanel

    private var toggleListener: ((Boolean) -> Unit)? = null
    private var suppressToggleEvents: Boolean = false

    init {
        enabledToggle.setState(config.enabled, animate = false)
        hostField.text = config.host
        portField.text = config.port.toString()

        initializeComponents()
        buildUi()
    }

    private fun initializeComponents() {
        serverConfigurationPanel = ServerConfigurationPanel(
            config = config, enabledToggle = enabledToggle, validationErrorLabel = validationErrorLabel
        )

        advancedOptionsPanel = AdvancedOptionsPanel(
            hostField = hostField, portField = portField, reinstallNotice = reinstallNotice
        )

        autoApproveTargetsPanel = AutoApproveTargetsPanel(config = config)

        installationPanel = InstallationPanel(
            config = config, providers = providers, reinstallNotice = reinstallNotice, parentComponent = panel
        )

        setupConfigListeners()
    }

    private fun setupConfigListeners() {
        val historyAccessRefreshListener = {
            SwingUtilities.invokeLater {
                serverConfigurationPanel.updateHistoryAccessCheckboxes()
            }
        }
        val handle = config.addHistoryAccessChangeListener(historyAccessRefreshListener)
        listenerHandles.add(handle)
    }

    fun cleanup() {
        listenerHandles.forEach { it.remove() }
        listenerHandles.clear()

        if (::autoApproveTargetsPanel.isInitialized) {
            autoApproveTargetsPanel.cleanup()
        }
    }

    fun onEnabledToggled(listener: (Boolean) -> Unit) {
        toggleListener = listener
    }

    fun getConfig(): McpConfig {
        config.host = hostField.text
        portField.text.toIntOrNull()?.let { config.port = it }
        return config
    }

    fun updateServerState(state: ServerState) {
        CoroutineScope(Dispatchers.Swing).launch {
            suppressToggleEvents = true

            val enableAdvancedOptions = state is ServerState.Stopped || state is ServerState.Failed
            if (::advancedOptionsPanel.isInitialized) {
                advancedOptionsPanel.setFieldsEnabled(enableAdvancedOptions)
            }

            when (state) {
                ServerState.Starting, ServerState.Stopping -> {
                    enabledToggle.isEnabled = false
                }

                ServerState.Running -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(true, animate = false)
                }

                ServerState.Stopped -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(false, animate = false)
                }

                is ServerState.Failed -> {
                    enabledToggle.isEnabled = true
                    enabledToggle.setState(false, animate = false)

                    val friendlyMessage = when (state.exception) {
                        is UnresolvedAddressException -> "Unable to resolve address"
                        else -> state.exception.message ?: state.exception.javaClass.simpleName
                    }

                    Dialogs.showMessageDialog(
                        panel, "Failed to start Burp MCP Server: $friendlyMessage", ERROR_MESSAGE
                    )
                }
            }

            suppressToggleEvents = false
        }
    }

    private fun buildUi() {
        val leftPanel = JPanel(GridBagLayout())

        val headerBox = createVerticalBox().apply {
            add(JLabel("Burp MCP Server").apply {
                font = Design.Typography.headlineMedium
                foreground = Design.Colors.onSurface
                alignmentX = CENTER_ALIGNMENT
            })
            add(createVerticalStrut(Design.Spacing.MD))
            add(JLabel("Burp MCP Server exposes Burp tooling to AI clients.").apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurfaceVariant
                alignmentX = CENTER_ALIGNMENT
            })
            add(createVerticalStrut(Design.Spacing.MD))
            add(
                Anchor(
                    text = "Learn more about the Model Context Protocol",
                    url = "https://modelcontextprotocol.io/introduction"
                ).apply { alignmentX = CENTER_ALIGNMENT })
        }

        leftPanel.add(headerBox)

        val rightPanelContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Design.Colors.surface
            border = BorderFactory.createEmptyBorder(
                Design.Spacing.LG, Design.Spacing.LG, Design.Spacing.LG, Design.Spacing.LG
            )
        }

        val rightPanel = JScrollPane(rightPanelContent).apply {
            border = null
            background = Design.Colors.surface
            viewport.background = Design.Colors.surface
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = 16
        }

        rightPanelContent.add(serverConfigurationPanel)
        rightPanelContent.add(createVerticalStrut(Design.Spacing.LG))

        rightPanelContent.add(autoApproveTargetsPanel)

        rightPanelContent.add(createVerticalStrut(15))
        rightPanelContent.add(advancedOptionsPanel)
        rightPanelContent.add(createVerticalGlue())
        rightPanelContent.add(reinstallNotice)
        rightPanelContent.add(createVerticalStrut(10))

        rightPanelContent.add(installationPanel)

        val columnsPanel = ResponsiveColumnsPanel(leftPanel, rightPanel)
        panel.add(columnsPanel, BorderLayout.CENTER)
    }
}