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

    private val enabledToggle: ToggleSwitch = Design.createToggleSwitch(false) { enabled ->
        if (suppressToggleEvents) return@createToggleSwitch

        if (enabled) {
            getValidationError()?.let { error ->
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

    private lateinit var alwaysAllowHttpHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowWebSocketHistoryCheckBox: JCheckBox

    private var toggleListener: ((Boolean) -> Unit)? = null
    private var suppressToggleEvents: Boolean = false

    init {
        enabledToggle.setState(config.enabled, animate = false)
        hostField.text = config.host
        portField.text = config.port.toString()

        buildUi()

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
                        panel, "Failed to start Burp MCP Server: $friendlyMessage", "Error", ERROR_MESSAGE
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

        val configEditingToolingCheckBox = createStandardCheckBox(
            "Enable tools that can edit your config", config.configEditingTooling
        ) { config.configEditingTooling = it }

        val httpRequestApprovalCheckBox = createStandardCheckBox(
            "Require approval for HTTP requests", config.requireHttpRequestApproval
        ) { config.requireHttpRequestApproval = it }

        val historyAccessApprovalCheckBox = createStandardCheckBox(
            "Require approval for history access", config.requireHistoryAccessApproval
        ) { enabled ->
            config.requireHistoryAccessApproval = enabled
            if (!enabled) {
                config.alwaysAllowHttpHistory = false
                config.alwaysAllowWebSocketHistory = false
                alwaysAllowHttpHistoryCheckBox.isSelected = false
                alwaysAllowWebSocketHistoryCheckBox.isSelected = false
            }
            alwaysAllowHttpHistoryCheckBox.isEnabled = enabled
            alwaysAllowWebSocketHistoryCheckBox.isEnabled = enabled
        }

        alwaysAllowHttpHistoryCheckBox = createIndentedCheckBox(
            "Always allow HTTP history access", config.alwaysAllowHttpHistory, config.requireHistoryAccessApproval
        ) { config.alwaysAllowHttpHistory = it }

        alwaysAllowWebSocketHistoryCheckBox = createIndentedCheckBox(
            "Always allow WebSocket history access",
            config.alwaysAllowWebSocketHistory,
            config.requireHistoryAccessApproval
        ) { config.alwaysAllowWebSocketHistory = it }

        val mainOptionsPanel = Design.createCard().apply {
            alignmentX = LEFT_ALIGNMENT
        }

        mainOptionsPanel.add(createSectionLabel("Server Configuration"))
        mainOptionsPanel.add(createVerticalStrut(Design.Spacing.MD))

        val enabledPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        enabledPanel.add(JLabel("Enabled").apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
        })
        enabledPanel.add(createHorizontalStrut(Design.Spacing.MD))
        enabledPanel.add(enabledToggle)

        mainOptionsPanel.add(enabledPanel)
        mainOptionsPanel.add(createVerticalStrut(Design.Spacing.MD))
        mainOptionsPanel.add(configEditingToolingCheckBox)
        mainOptionsPanel.add(createVerticalStrut(Design.Spacing.MD))
        mainOptionsPanel.add(httpRequestApprovalCheckBox)
        mainOptionsPanel.add(createVerticalStrut(Design.Spacing.MD))
        mainOptionsPanel.add(historyAccessApprovalCheckBox)
        mainOptionsPanel.add(createVerticalStrut(Design.Spacing.SM))
        mainOptionsPanel.add(alwaysAllowHttpHistoryCheckBox)
        mainOptionsPanel.add(createVerticalStrut(Design.Spacing.SM))
        mainOptionsPanel.add(alwaysAllowWebSocketHistoryCheckBox)

        rightPanelContent.add(mainOptionsPanel)
        rightPanelContent.add(createVerticalStrut(Design.Spacing.LG))

        val autoApprovePanel = createAutoApprovePanel()
        rightPanelContent.add(autoApprovePanel)

        rightPanelContent.add(validationErrorLabel)
        rightPanelContent.add(createVerticalStrut(15))

        val advancedPanel = Design.createCard().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        advancedPanel.add(createSectionLabel("Advanced Options"))
        advancedPanel.add(createVerticalStrut(Design.Spacing.MD))

        val formPanel = createFormPanel(
            "Server host:" to hostField, "Server port:" to portField
        )

        advancedPanel.add(formPanel)

        rightPanelContent.add(advancedPanel)
        rightPanelContent.add(createVerticalGlue())
        rightPanelContent.add(reinstallNotice)
        rightPanelContent.add(createVerticalStrut(10))

        val installationPanel = Design.createCard().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        installationPanel.add(createSectionLabel("Installation"))
        installationPanel.add(createVerticalStrut(Design.Spacing.SM))

        val installOptions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, Design.Spacing.SM, Design.Spacing.SM)).apply {
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
        }

        providers.forEach { provider ->
            val item = Design.createFilledButton(provider.installButtonText).apply {
                preferredSize = Dimension(260, 40)
                minimumSize = Dimension(200, 40)
            }
            item.addActionListener {
                val confirmationText = provider.confirmationText

                if (confirmationText != null) {
                    val result = Dialogs.showConfirmDialog(
                        panel, confirmationText, "Burp MCP Server", YES_NO_OPTION
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
                                Dialogs.showMessageDialog(
                                    panel, result, "Burp MCP Server", INFORMATION_MESSAGE
                                )
                            }
                        }
                    } catch (e: Exception) {
                        CoroutineScope(Dispatchers.Swing).launch {
                            Dialogs.showMessageDialog(
                                panel,
                                "Failed to install for ${provider.name}: ${e.message ?: e.javaClass.simpleName}",
                                "${provider.name} install",
                                ERROR_MESSAGE
                            )
                        }
                    }
                }
            }
            buttonRow.add(item)
        }

        installOptions.add(buttonRow)
        installationPanel.add(installOptions)
        installationPanel.add(createVerticalStrut(Design.Spacing.SM))

        val manualInstallPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
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

        rightPanelContent.add(installationPanel)

        val columnsPanel = ResponsiveColumnsPanel(leftPanel, rightPanel)
        panel.add(columnsPanel, BorderLayout.CENTER)
    }

    private fun createAutoApprovePanel(): JPanel {
        val panel = Design.createCard().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
        }

        panel.add(createSectionLabel("Auto-Approved HTTP Targets"))
        panel.add(createVerticalStrut(Design.Spacing.MD))

        val descLabel = JLabel("Specify domains and hosts that can be accessed without approval.").apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, 0, Design.Spacing.SM, 0)
        }
        val examplesLabel = JLabel("Examples: example.com, localhost:8080, *.api.com").apply {
            alignmentX = LEFT_ALIGNMENT
            font = Design.Typography.labelMedium
            foreground = Design.Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, 0, Design.Spacing.MD, 0)
        }
        panel.add(descLabel)
        panel.add(examplesLabel)

        val listModel = DefaultListModel<String>()

        val targetsList = object : JList<String>(listModel) {
            private var rolloverIndex = -1

            init {
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                visibleRowCount = 5
                font = Design.Typography.bodyMedium
                background = Design.Colors.listBackground
                foreground = Design.Colors.onSurface
                border = BorderFactory.createEmptyBorder(
                    Design.Spacing.SM, Design.Spacing.MD, Design.Spacing.SM, Design.Spacing.MD
                )
                cellRenderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                    ): Component {
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        border = BorderFactory.createEmptyBorder(
                            Design.Spacing.SM, Design.Spacing.MD, Design.Spacing.SM, Design.Spacing.MD
                        )

                        val isRollover = index == rolloverIndex && !isSelected

                        if (isSelected) {
                            background = Design.Colors.listSelectionBackground
                            foreground = Design.Colors.listSelectionForeground
                        } else if (isRollover) {
                            background = Design.Colors.listHoverBackground
                            foreground = Design.Colors.onSurface
                        } else {
                            background =
                                if (index % 2 == 0) Design.Colors.listBackground else Design.Colors.listAlternatingBackground
                            foreground = Design.Colors.onSurface
                        }
                        return this
                    }
                }

                addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                    override fun mouseMoved(e: java.awt.event.MouseEvent) {
                        try {
                            val index = locationToIndex(e.point)
                            val newRolloverIndex = if (index >= 0 && index < model.size && getCellBounds(
                                    index,
                                    index
                                )?.contains(e.point) == true
                            ) {
                                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                                index
                            } else {
                                cursor = Cursor.getDefaultCursor()
                                -1
                            }

                            if (rolloverIndex != newRolloverIndex) {
                                rolloverIndex = newRolloverIndex
                                repaint()
                            }
                        } catch (_: Exception) {
                            rolloverIndex = -1
                            cursor = Cursor.getDefaultCursor()
                        }
                    }
                })

                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseExited(e: java.awt.event.MouseEvent) {
                        if (rolloverIndex != -1) {
                            rolloverIndex = -1
                            cursor = Cursor.getDefaultCursor()
                            repaint()
                        }
                    }
                })

                addKeyListener(object : java.awt.event.KeyAdapter() {
                    override fun keyPressed(e: java.awt.event.KeyEvent) {
                        when (e.keyCode) {
                            java.awt.event.KeyEvent.VK_DELETE, java.awt.event.KeyEvent.VK_BACK_SPACE -> {
                                if (selectedIndex >= 0 && selectedIndex < model.size) {
                                    try {
                                        removeTarget(selectedIndex, listModel)
                                        e.consume()
                                    } catch (ex: Exception) {
                                        ex.printStackTrace()
                                    }
                                }
                            }
                        }
                    }
                })

                isFocusable = true
            }
        }

        updateTargetsList(listModel)

        val refreshListener = {
            SwingUtilities.invokeLater {
                updateTargetsList(listModel)
            }
        }
        config.addTargetsChangeListener(refreshListener)

        val historyAccessRefreshListener = {
            SwingUtilities.invokeLater {
                alwaysAllowHttpHistoryCheckBox.isSelected = config.alwaysAllowHttpHistory
                alwaysAllowWebSocketHistoryCheckBox.isSelected = config.alwaysAllowWebSocketHistory
            }
        }
        config.addHistoryAccessChangeListener(historyAccessRefreshListener)

        val scrollPane = JScrollPane(targetsList).apply {
            maximumSize = Dimension(Int.MAX_VALUE, 220)
            preferredSize = Dimension(400, 220)
            minimumSize = Dimension(250, 150)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Design.Colors.listBorder, 1), BorderFactory.createEmptyBorder(1, 1, 1, 1)
            )
            background = Design.Colors.listBackground
            viewport.background = Design.Colors.listBackground
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }

        val tableContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(0, 0, Design.Spacing.MD, 0)
        }
        tableContainer.add(scrollPane)

        panel.add(tableContainer)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, Design.Spacing.SM, Design.Spacing.SM)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(Design.Spacing.SM, 0, 0, 0)
        }

        val addButton = Design.createFilledButton("Add").apply {
            preferredSize = Dimension(100, 40)
            minimumSize = Dimension(80, 40)
            addActionListener {
                val input = Dialogs.showInputDialog(
                    panel,
                    "Enter target (hostname or hostname:port):\nExamples: example.com, localhost:8080, *.api.com",
                    "Add HTTP Target"
                )

                if (!input.isNullOrBlank()) {
                    val trimmed = input.trim()
                    if (isValidTarget(trimmed)) {
                        addTarget(trimmed)
                    } else {
                        Dialogs.showMessageDialog(
                            panel,
                            "Invalid target format. Use hostname, hostname:port, or wildcard (*.domain)",
                            "Invalid Target",
                            ERROR_MESSAGE
                        )
                    }
                }
            }
        }

        val removeButton = Design.createOutlinedButton("Remove").apply {
            preferredSize = Dimension(120, 40)
            minimumSize = Dimension(80, 40)
            addActionListener {
                val selectedIndex = targetsList.selectedIndex
                if (selectedIndex >= 0) {
                    removeTarget(selectedIndex, listModel)
                }
            }
        }

        val clearButton = Design.createOutlinedButton("Clear All").apply {
            preferredSize = Dimension(120, 40)
            minimumSize = Dimension(80, 40)
            addActionListener {
                val result = Dialogs.showConfirmDialog(
                    panel, "Remove all auto-approved targets?", "Clear All Targets", YES_NO_OPTION
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

        if (target.contains("..") || target.contains("//") || target.contains("@") || target.contains(" ") || target.contains(
                "\t"
            ) || target.contains("\n") || target.contains("\r")
        ) return false

        if (target.startsWith("*.")) {
            val domain = target.substring(2)
            if (domain.isEmpty() || domain.length > 253) return false

            return isValidHostname(domain)
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

        if (isValidIPv4(hostname)) return true

        if (isValidIPv6(hostname)) return true

        if (hostname.startsWith(".") || hostname.endsWith(".")) return false
        if (hostname.contains("..")) return false

        return hostname.matches(Regex("^[a-zA-Z0-9.-]+$")) && hostname.split(".").all { label ->
            label.isNotEmpty() && label.length <= 63 && !label.startsWith("-") && !label.endsWith("-")
        }
    }

    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in 0..255 && (num == 0 || !part.startsWith("0"))
        }
    }

    private fun isValidIPv6(ip: String): Boolean {
        val address = if (ip.startsWith("[") && ip.endsWith("]")) {
            ip.substring(1, ip.length - 1)
        } else {
            ip
        }

        if (address.contains(":::")) return false

        val parts = if (address.contains("::")) {
            val splitParts = address.split("::")
            if (splitParts.size > 2) return false

            val leftParts = splitParts[0].split(":").filter { it.isNotEmpty() }
            val rightParts = if (splitParts.size == 2) {
                splitParts[1].split(":").filter { it.isNotEmpty() }
            } else emptyList()

            if (leftParts.size + rightParts.size > 8) return false
            leftParts + rightParts
        } else {
            val normalParts = address.split(":")
            if (normalParts.size != 8) return false
            normalParts
        }

        return parts.all { part ->
            part.length <= 4 && part.matches(Regex("^[0-9a-fA-F]+$"))
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

    private fun createStandardCheckBox(
        text: String, initialValue: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createIndentedCheckBox(
        text: String, initialValue: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            isEnabled = enabled
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, Design.Spacing.LG, 0, 0)
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createFormPanel(vararg fields: Pair<String, JComponent>): JPanel {
        val formPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, Design.Spacing.MD)
            anchor = GridBagConstraints.WEST
        }

        fields.forEachIndexed { index, (labelText, field) ->
            gbc.gridx = 0
            gbc.gridy = index
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            formPanel.add(JLabel(labelText).apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurface
            }, gbc)

            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            gbc.insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, 0)

            if (field is JTextField) {
                field.preferredSize = Dimension(200, 32)
                field.font = Design.Typography.bodyLarge
            }

            formPanel.add(field, gbc)

            gbc.insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, Design.Spacing.MD)
        }

        return formPanel
    }

    private fun createSectionLabel(text: String): JLabel {
        return JLabel(text).apply {
            font = Design.Typography.titleMedium
            foreground = Design.Colors.onSurface
            alignmentX = LEFT_ALIGNMENT
        }
    }
}

class ResponsiveColumnsPanel(private val leftPanel: JPanel, private val rightPanel: JScrollPane) : JPanel() {
    private val minWidthForTwoColumns = 900
    private val minWidthForLargePadding = 700
    private var lastLayout = Layout.SINGLE_COLUMN
    private var lastPaddingSize = PaddingSize.SMALL

    enum class Layout { SINGLE_COLUMN, TWO_COLUMNS }
    enum class PaddingSize { SMALL, LARGE }

    init {
        updateLayout()
    }

    override fun doLayout() {
        super.doLayout()
        val currentLayout = if (width >= minWidthForTwoColumns) Layout.TWO_COLUMNS else Layout.SINGLE_COLUMN
        val currentPaddingSize = if (width >= minWidthForLargePadding) PaddingSize.LARGE else PaddingSize.SMALL

        if (currentLayout != lastLayout || currentPaddingSize != lastPaddingSize) {
            lastLayout = currentLayout
            lastPaddingSize = currentPaddingSize
            updateLayout()
        }
    }

    private fun updateLayout() {
        removeAll()

        val padding = when (lastPaddingSize) {
            PaddingSize.LARGE -> Design.Spacing.LG
            PaddingSize.SMALL -> Design.Spacing.SM
        }

        if (rightPanel.viewport.view is JPanel) {
            val contentPanel = rightPanel.viewport.view as JPanel
            contentPanel.border = BorderFactory.createEmptyBorder(padding, padding, padding, padding)
        }

        when (lastLayout) {
            Layout.TWO_COLUMNS -> {
                layout = GridBagLayout()
                val c = GridBagConstraints().apply {
                    fill = GridBagConstraints.BOTH
                    weighty = 1.0
                }

                c.gridx = 0
                c.gridy = 0
                c.weightx = 0.35
                add(leftPanel, c)

                c.gridx = 1
                c.weightx = 0.65
                add(rightPanel, c)
            }

            Layout.SINGLE_COLUMN -> {
                layout = BorderLayout()
                val singleColumnPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    background = Design.Colors.surface
                }

                val headerWrapper = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(padding, padding, Design.Spacing.MD, padding)
                    add(leftPanel, BorderLayout.CENTER)
                }

                singleColumnPanel.add(headerWrapper)

                val scrollWrapper = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(rightPanel, BorderLayout.CENTER)
                }
                singleColumnPanel.add(scrollWrapper)

                add(singleColumnPanel, BorderLayout.CENTER)
            }
        }

        revalidate()
        repaint()
    }
}