package net.portswigger.mcp.config

import io.ktor.util.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.portswigger.mcp.ServerState
import net.portswigger.mcp.Swing
import net.portswigger.mcp.providers.Provider
import java.awt.*
import java.awt.Component.CENTER_ALIGNMENT
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.BorderFactory.createEmptyBorder
import javax.swing.Box.*
import javax.swing.JOptionPane.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.concurrent.thread

class ConfigUi(private val config: McpConfig, private val providers: List<Provider>) {

    class WarningLabel(content: String = "") : JLabel(content) {
        init {
            foreground = UIManager.getColor("Burp.warningBarBackground")
            isVisible = false
            alignmentX = LEFT_ALIGNMENT
        }

        override fun updateUI() {
            super.updateUI()
            foreground = UIManager.getColor("Burp.warningBarBackground")
        }
    }

    private val panel = JPanel(BorderLayout())
    val component: JComponent get() = panel

    private val enabledCheckBox = JCheckBox("Enabled").apply { alignmentX = LEFT_ALIGNMENT }
    private val validationErrorLabel = WarningLabel()

    private val hostField = JTextField(15)
    private val portField = JTextField(5)
    private val reinstallNotice = WarningLabel("Make sure to reinstall after changing server settings")

    private var toggleListener: ((Boolean) -> Unit)? = null
    private var suppressToggleEvents: Boolean = false
    private var installationAvailable: Boolean = false

    init {
        enabledCheckBox.isSelected = config.enabled
        hostField.text = config.host
        portField.text = config.port.toString()

        buildUi()

        enabledCheckBox.addItemListener {
            if (suppressToggleEvents) {
                return@addItemListener
            }

            val checked = it.stateChange == ItemEvent.SELECTED

            if (checked) {
                val error = getValidationError()

                if (error != null) {
                    validationErrorLabel.text = error
                    validationErrorLabel.isVisible = true

                    suppressToggleEvents = true
                    enabledCheckBox.isSelected = false
                    suppressToggleEvents = false
                    return@addItemListener
                }
            }

            validationErrorLabel.isVisible = false

            toggleListener?.invoke(checked)
        }

        trackChanges(hostField)
        trackChanges(portField)
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

            hostField.isEnabled = enableAdvancedOptions
            portField.isEnabled = enableAdvancedOptions

            installationAvailable = false

            when (state) {
                ServerState.Starting, ServerState.Stopping -> {
                    enabledCheckBox.isEnabled = false
                }

                ServerState.Running -> {
                    enabledCheckBox.isEnabled = true
                    enabledCheckBox.isSelected = true

                    installationAvailable = true
                }

                ServerState.Stopped -> {
                    enabledCheckBox.isEnabled = true
                    enabledCheckBox.isSelected = false
                }

                is ServerState.Failed -> {
                    enabledCheckBox.isEnabled = true
                    enabledCheckBox.isSelected = false

                    val friendlyMessage = when (state.exception) {
                        is UnresolvedAddressException -> "Unable to resolve address"
                        else -> state.exception.message ?: state.exception.javaClass.simpleName
                    }

                    showMessageDialog(
                        panel,
                        "Failed to start Burp MCP Server: $friendlyMessage",
                        "Error",
                        ERROR_MESSAGE
                    )
                }
            }

            suppressToggleEvents = false
        }
    }

    private fun getValidationError(): String? {
        val host = hostField.text.trim()
        val port = portField.text.trim().toIntOrNull()

        if (host.isBlank() || !host.matches(Regex("^[a-zA-Z0-9.-]+$"))) {
            return "Host must be a non-empty alphanumeric string"
        }

        if (port == null) {
            return "Port must be a valid number"
        }

        if (port < 1024 || port > 65535) {
            return "Port is not within valid range"
        }

        return null
    }

    private fun trackChanges(field: JTextField) {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = handle()
            override fun removeUpdate(e: DocumentEvent?) = handle()
            override fun changedUpdate(e: DocumentEvent?) = handle()
            fun handle() {
                reinstallNotice.isVisible = true
            }
        })
    }

    private fun buildUi() {
        val leftPanel = JPanel(GridBagLayout())

        val headerBox = createVerticalBox().apply {
            add(object : JLabel("Burp MCP Server") {
                init {
                    font = font.deriveFont(Font.BOLD, 24f)
                    alignmentX = CENTER_ALIGNMENT
                }

                override fun getForeground() = UIManager.getColor("Burp.burpTitle")
            })
            add(createVerticalStrut(25))
            add(JLabel("Burp MCP Server exposes Burp tooling to AI clients.").apply {
                alignmentX = CENTER_ALIGNMENT
            })
            add(createVerticalStrut(15))
            add(
                Anchor(
                    text = "Learn more about the Model Context Protocol",
                    url = "https://modelcontextprotocol.io/introduction"
                ).apply { alignmentX = CENTER_ALIGNMENT }
            )
        }

        leftPanel.add(headerBox)

        val rightPanel = object : JPanel() {
            init {
                applyStyles()
            }

            override fun updateUI() {
                super.updateUI()
                applyStyles()
            }

            private fun applyStyles() {
                background = UIManager.getColor("Burp.backgrounder")
            }
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = createEmptyBorder(15, 15, 15, 15)
        }

        val configEditingToolingCheckBox = JCheckBox("Enable tools that can edit your config").apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = config.configEditingTooling
            addItemListener { event -> config.configEditingTooling = event.stateChange == ItemEvent.SELECTED }
        }

        val httpRequestApprovalCheckBox = JCheckBox("Require approval for HTTP requests").apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = config.requireHttpRequestApproval
            addItemListener { event -> config.requireHttpRequestApproval = event.stateChange == ItemEvent.SELECTED }
        }

        rightPanel.add(enabledCheckBox)
        rightPanel.add(createVerticalStrut(10))
        rightPanel.add(configEditingToolingCheckBox)
        rightPanel.add(createVerticalStrut(10))
        rightPanel.add(httpRequestApprovalCheckBox)
        rightPanel.add(createVerticalStrut(10))
        
        val autoApprovePanel = createAutoApprovePanel()
        rightPanel.add(autoApprovePanel)
        
        rightPanel.add(validationErrorLabel)
        rightPanel.add(createVerticalStrut(15))

        val advancedPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder("Advanced options")
            isOpaque = false
        }

        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }

        advancedPanel.add(JLabel("Server host:"), gbc)
        gbc.gridx = 1
        advancedPanel.add(hostField, gbc)

        gbc.gridx = 0
        gbc.gridy = 1
        advancedPanel.add(JLabel("Server port:"), gbc)
        gbc.gridx = 1
        advancedPanel.add(portField, gbc)

        val advancedWrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(advancedPanel)
            alignmentX = LEFT_ALIGNMENT
        }

        rightPanel.add(advancedWrapper)
        rightPanel.add(createVerticalGlue())
        rightPanel.add(reinstallNotice)
        rightPanel.add(createVerticalStrut(10))

        val installOptions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }

        providers.forEach { provider ->
            val item = JButton(provider.installButtonText)
            item.addActionListener {
                if (!installationAvailable) {
                    showMessageDialog(
                        panel,
                        "Please start the Burp MCP Server first.",
                        "Burp MCP Server",
                        INFORMATION_MESSAGE
                    )
                    return@addActionListener
                }

                val confirmationText = provider.confirmationText

                if (confirmationText != null) {
                    val result = showConfirmDialog(
                        panel,
                        confirmationText,
                        "Burp MCP Server",
                        YES_NO_OPTION
                    )

                    if (result != YES_OPTION) {
                        return@addActionListener
                    }
                }

                thread {
                    try {
                        val result = provider.install(config)
                        CoroutineScope(Dispatchers.Swing).launch {
                            reinstallNotice.isVisible = false

                            if (result != null) {
                                showMessageDialog(
                                    panel,
                                    result,
                                    "Burp MCP Server",
                                    INFORMATION_MESSAGE
                                )
                            }
                        }
                    } catch (e: Exception) {
                        CoroutineScope(Dispatchers.Swing).launch {
                            showMessageDialog(
                                panel,
                                "Failed to install for ${provider.name}: ${e.message ?: e.javaClass.simpleName}",
                                "${provider.name} install",
                                ERROR_MESSAGE
                            )
                        }
                    }
                }
            }
            installOptions.add(item)
            installOptions.add(createHorizontalStrut(10))
        }

        installOptions.add(
            Anchor(
                text = "Manual install steps",
                url = "https://github.com/PortSwigger/mcp-server?tab=readme-ov-file#manual-installations"
            )
        )
        installOptions.maximumSize = installOptions.preferredSize

        rightPanel.add(installOptions)

        val columnsPanel = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.BOTH
            weighty = 1.0
        }

        c.gridx = 0
        c.gridy = 0
        c.weightx = 0.35
        columnsPanel.add(leftPanel, c)

        c.gridx = 1
        c.weightx = 0.65
        columnsPanel.add(rightPanel, c)

        panel.add(columnsPanel, BorderLayout.CENTER)
    }

    private fun createAutoApprovePanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("Auto-approved HTTP targets")
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        val listModel = DefaultListModel<String>()
        val targetsList = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 4
        }

        updateTargetsList(listModel)
        
        val refreshListener = {
            SwingUtilities.invokeLater {
                updateTargetsList(listModel)
            }
        }
        config.addTargetsChangeListener(refreshListener)

        val scrollPane = JScrollPane(targetsList).apply {
            maximumSize = Dimension(400, 100)
            preferredSize = Dimension(400, 100)
        }

        panel.add(scrollPane)
        panel.add(createVerticalStrut(5))

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        val addButton = JButton("Add").apply {
            addActionListener {
                val input = showInputDialog(
                    panel,
                    "Enter target (hostname or hostname:port):\nExamples: example.com, localhost:8080, *.api.com",
                    "Add HTTP Target",
                    PLAIN_MESSAGE
                )
                
                if (!input.isNullOrBlank()) {
                    val trimmed = input.trim()
                    if (isValidTarget(trimmed)) {
                        addTarget(trimmed)
                    } else {
                        showMessageDialog(
                            panel,
                            "Invalid target format. Use hostname or hostname:port",
                            "Invalid Target",
                            ERROR_MESSAGE
                        )
                    }
                }
            }
        }

        val removeButton = JButton("Remove").apply {
            addActionListener {
                val selectedIndex = targetsList.selectedIndex
                if (selectedIndex >= 0) {
                    removeTarget(selectedIndex, listModel)
                }
            }
        }

        val clearButton = JButton("Clear All").apply {
            addActionListener {
                val result = showConfirmDialog(
                    panel,
                    "Remove all auto-approved targets?",
                    "Clear All Targets",
                    YES_NO_OPTION
                )
                
                if (result == YES_OPTION) {
                    clearAllTargets()
                }
            }
        }

        buttonsPanel.add(addButton)
        buttonsPanel.add(createHorizontalStrut(5))
        buttonsPanel.add(removeButton)
        buttonsPanel.add(createHorizontalStrut(5))
        buttonsPanel.add(clearButton)

        panel.add(buttonsPanel)
        
        return panel
    }

    private fun updateTargetsList(listModel: DefaultListModel<String>) {
        listModel.clear()
        config.getAutoApproveTargetsList().forEach { 
            listModel.addElement(it) 
        }
    }

    private fun isValidTarget(target: String): Boolean {
        if (target.isBlank() || target.length > 255) return false
        
        if (target.startsWith("*.")) {
            val domain = target.substring(2)
            return domain.isNotEmpty() && 
                   domain.length <= 253 && 
                   isValidHostname(domain)
        }
        
        val parts = target.split(":")
        if (parts.size > 2) return false
        
        val hostname = parts[0]
        if (!isValidHostname(hostname)) return false
        
        if (parts.size == 2) {
            val port = parts[1].toIntOrNull()
            if (port == null || port < 1 || port > 65535) return false
        }
        
        return true
    }
    
    private fun isValidHostname(hostname: String): Boolean {
        if (hostname.isEmpty() || hostname.length > 253) return false
        if (hostname.startsWith(".") || hostname.endsWith(".")) return false
        if (hostname.contains("..")) return false
        
        return hostname.matches(Regex("^[a-zA-Z0-9.-]+$")) &&
               hostname.split(".").all { label ->
                   label.isNotEmpty() && 
                   label.length <= 63 &&
                   !label.startsWith("-") && 
                   !label.endsWith("-")
               }
    }

    private fun addTarget(target: String) {
        config.addAutoApproveTarget(target)
    }

    private fun removeTarget(index: Int, listModel: DefaultListModel<String>) {
        if (index >= 0 && index < listModel.size()) {
            val target = listModel.getElementAt(index)
            config.removeAutoApproveTarget(target)
        }
    }

    private fun clearAllTargets() {
        config.clearAutoApproveTargets()
    }
}