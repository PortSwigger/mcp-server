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
import javax.swing.DefaultListCellRenderer
import javax.swing.Box.*
import javax.swing.JOptionPane.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.concurrent.thread

class ConfigUi(private val config: McpConfig, private val providers: List<Provider>) {

    object M3Colors {
        val primary = Color(0xD86633)
        val onPrimary = Color(0xFFFFFF)
        val surface = Color(0xFFFBFF)
        val onSurface = Color(0x1A1A1A)
        val onSurfaceVariant = Color(0x666666)
        val outline = Color(0xCCCCCC)
        val outlineVariant = Color(0xE0E0E0)
    }

    object M3Typography {
        val headlineMedium = Font("Inter", Font.BOLD, 28)
        val titleMedium = Font("Inter", Font.BOLD, 16)
        val bodyLarge = Font("Inter", Font.PLAIN, 16)
        val bodyMedium = Font("Inter", Font.PLAIN, 14)
        val labelLarge = Font("Inter", Font.BOLD, 14)
        val labelMedium = Font("Inter", Font.BOLD, 12)
    }

    object M3Spacing {
        const val SM = 8
        const val MD = 16
        const val LG = 24
        const val XL = 32
    }

    private fun createM3Card(): JPanel {
        return JPanel().apply {
            background = M3Colors.surface
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(M3Colors.outlineVariant, 1),
                BorderFactory.createEmptyBorder(M3Spacing.MD, M3Spacing.MD, M3Spacing.MD, M3Spacing.MD)
            )
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
    }

    private fun createM3FilledButton(text: String): JButton {
        return JButton(text).apply {
            background = M3Colors.primary
            foreground = M3Colors.onPrimary
            font = M3Typography.labelLarge
            border = BorderFactory.createEmptyBorder(M3Spacing.SM, M3Spacing.MD, M3Spacing.SM, M3Spacing.MD)
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(160, 40)
        }
    }

    private fun createM3OutlinedButton(text: String): JButton {
        return JButton(text).apply {
            background = M3Colors.surface
            foreground = M3Colors.primary
            font = M3Typography.labelLarge
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(M3Colors.outline, 1),
                BorderFactory.createEmptyBorder(M3Spacing.SM, M3Spacing.MD, M3Spacing.SM, M3Spacing.MD)
            )
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(120, 40)
        }
    }

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

    private val enabledCheckBox = JCheckBox("Enabled").apply { 
        alignmentX = LEFT_ALIGNMENT
        font = M3Typography.bodyLarge
        foreground = M3Colors.onSurface
    }
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
            add(JLabel("Burp MCP Server").apply {
                font = M3Typography.headlineMedium
                foreground = M3Colors.onSurface
                alignmentX = CENTER_ALIGNMENT
            })
            add(createVerticalStrut(M3Spacing.MD))
            add(JLabel("Burp MCP Server exposes Burp tooling to AI clients.").apply {
                font = M3Typography.bodyLarge
                foreground = M3Colors.onSurfaceVariant
                alignmentX = CENTER_ALIGNMENT
            })
            add(createVerticalStrut(M3Spacing.MD))
            add(
                Anchor(
                    text = "Learn more about the Model Context Protocol",
                    url = "https://modelcontextprotocol.io/introduction"
                ).apply { alignmentX = CENTER_ALIGNMENT }
            )
        }

        leftPanel.add(headerBox)

        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = M3Colors.surface
            border = BorderFactory.createEmptyBorder(M3Spacing.XL, M3Spacing.XL, M3Spacing.XL, M3Spacing.XL)
        }

        val configEditingToolingCheckBox = JCheckBox("Enable tools that can edit your config").apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = config.configEditingTooling
            font = M3Typography.bodyLarge
            foreground = M3Colors.onSurface
            addItemListener { event -> config.configEditingTooling = event.stateChange == ItemEvent.SELECTED }
        }

        val httpRequestApprovalCheckBox = JCheckBox("Require approval for HTTP requests").apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = config.requireHttpRequestApproval
            font = M3Typography.bodyLarge
            foreground = M3Colors.onSurface
            addItemListener { event -> config.requireHttpRequestApproval = event.stateChange == ItemEvent.SELECTED }
        }

        val mainOptionsPanel = createM3Card().apply {
            alignmentX = LEFT_ALIGNMENT
        }
        
        mainOptionsPanel.add(JLabel("Server Configuration").apply {
            font = M3Typography.titleMedium
            foreground = M3Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        mainOptionsPanel.add(createVerticalStrut(M3Spacing.MD))
        mainOptionsPanel.add(enabledCheckBox)
        mainOptionsPanel.add(createVerticalStrut(M3Spacing.MD))
        mainOptionsPanel.add(configEditingToolingCheckBox)
        mainOptionsPanel.add(createVerticalStrut(M3Spacing.MD))
        mainOptionsPanel.add(httpRequestApprovalCheckBox)
        
        rightPanel.add(mainOptionsPanel)
        rightPanel.add(createVerticalStrut(M3Spacing.LG))
        
        val autoApprovePanel = createAutoApprovePanel()
        rightPanel.add(autoApprovePanel)
        
        rightPanel.add(validationErrorLabel)
        rightPanel.add(createVerticalStrut(15))

        val advancedPanel = createM3Card().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        
        advancedPanel.add(JLabel("Advanced Options").apply {
            font = M3Typography.titleMedium
            foreground = M3Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        advancedPanel.add(createVerticalStrut(M3Spacing.MD))

        JPanel(GridBagLayout()).apply {
            border = BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                "Advanced Options"
            )
            isOpaque = false
        }

        val gbc = GridBagConstraints().apply {
            insets = Insets(8, 10, 8, 10)
            anchor = GridBagConstraints.WEST
        }

        gbc.gridy = 0
        advancedPanel.add(createVerticalStrut(5), gbc)
        
        gbc.gridy = 1
        gbc.gridx = 0
        advancedPanel.add(JLabel("Server host:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        advancedPanel.add(hostField, gbc)

        gbc.gridx = 0
        gbc.gridy = 2
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        advancedPanel.add(JLabel("Server port:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        advancedPanel.add(portField, gbc)
        
        gbc.gridy = 3
        gbc.gridx = 0
        gbc.gridwidth = 2
        advancedPanel.add(createVerticalStrut(5), gbc)

        val advancedWrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(advancedPanel)
            alignmentX = LEFT_ALIGNMENT
        }

        rightPanel.add(advancedWrapper)
        rightPanel.add(createVerticalGlue())
        rightPanel.add(reinstallNotice)
        rightPanel.add(createVerticalStrut(10))

        val installationPanel = createM3Card().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }
        
        installationPanel.add(JLabel("Installation").apply {
            font = M3Typography.titleMedium
            foreground = M3Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        installationPanel.add(createVerticalStrut(M3Spacing.MD))
        
        val installOptions = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }

        providers.forEach { provider ->
            val item = createM3FilledButton(provider.installButtonText).apply {
                preferredSize = Dimension(220, 40)
            }
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
        }

        installationPanel.add(createVerticalStrut(8))
        installationPanel.add(installOptions)
        installationPanel.add(createVerticalStrut(5))
        
        val manualInstallPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }
        manualInstallPanel.add(
            Anchor(
                text = "Manual install steps",
                url = "https://github.com/PortSwigger/mcp-server?tab=readme-ov-file#manual-installations"
            )
        )
        installationPanel.add(manualInstallPanel)
        installationPanel.add(createVerticalStrut(8))
        
        rightPanel.add(installationPanel)

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
        val panel = createM3Card().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        panel.add(JLabel("Auto-Approved HTTP Targets").apply {
            font = M3Typography.titleMedium
            foreground = M3Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        })
        panel.add(createVerticalStrut(M3Spacing.MD))
        
        val descLabel = JLabel("Specify domains and hosts that can be accessed without approval.").apply {
            alignmentX = LEFT_ALIGNMENT
            font = M3Typography.bodyMedium
            foreground = M3Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, 0, M3Spacing.SM, 0)
        }
        val examplesLabel = JLabel("Examples: example.com, localhost:8080, *.api.com").apply {
            alignmentX = LEFT_ALIGNMENT
            font = M3Typography.labelMedium
            foreground = M3Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, 0, M3Spacing.MD, 0)
        }
        panel.add(descLabel)
        panel.add(examplesLabel)
        
        val listModel = DefaultListModel<String>()
        val targetsList = JList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            visibleRowCount = 5
            font = M3Typography.bodyMedium
            background = Color.WHITE
            foreground = M3Colors.onSurface
            border = BorderFactory.createEmptyBorder(M3Spacing.SM, M3Spacing.MD, M3Spacing.SM, M3Spacing.MD)
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    border = BorderFactory.createEmptyBorder(M3Spacing.SM, M3Spacing.MD, M3Spacing.SM, M3Spacing.MD)
                    if (isSelected) {
                        background = Color(0xE3F2FD) // Light blue selection
                        foreground = Color(0x1976D2) // Darker blue text
                    } else {
                        background = if (index % 2 == 0) Color.WHITE else Color(0xFAFAFA) // Alternating rows
                        foreground = M3Colors.onSurface
                    }
                    return this
                }
            }
        }

        updateTargetsList(listModel)
        
        val refreshListener = {
            SwingUtilities.invokeLater {
                updateTargetsList(listModel)
            }
        }
        config.addTargetsChangeListener(refreshListener)

        val scrollPane = JScrollPane(targetsList).apply {
            maximumSize = Dimension(500, 140)
            preferredSize = Dimension(500, 140)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xDDDDDD), 1),
                BorderFactory.createEmptyBorder(1, 1, 1, 1)
            )
            background = Color.WHITE
            viewport.background = Color.WHITE
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }

        // Create a container for the table with better spacing
        val tableContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, M3Spacing.MD, 0)
        }
        tableContainer.add(scrollPane)
        
        panel.add(tableContainer)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, M3Spacing.MD, M3Spacing.SM)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(M3Spacing.SM, 0, 0, 0)
        }

        val addButton = createM3FilledButton("Add").apply {
            preferredSize = Dimension(100, 40)
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

        val removeButton = createM3OutlinedButton("Remove").apply {
            preferredSize = Dimension(100, 40)
            addActionListener {
                val selectedIndex = targetsList.selectedIndex
                if (selectedIndex >= 0) {
                    removeTarget(selectedIndex, listModel)
                }
            }
        }

        val clearButton = createM3OutlinedButton("Clear All").apply {
            preferredSize = Dimension(100, 40)
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
        buttonsPanel.add(removeButton)
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