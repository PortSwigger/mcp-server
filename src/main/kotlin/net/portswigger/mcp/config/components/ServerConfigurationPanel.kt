package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.ToggleSwitch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Frame
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalStrut
import javax.swing.table.DefaultTableModel

class ServerConfigurationPanel(
    private val config: McpConfig,
    private val enabledToggle: ToggleSwitch,
    private val validationErrorLabel: WarningLabel
) : JPanel() {

    private lateinit var alwaysAllowHttpHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowWebSocketHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowOrganizerCheckBox: JCheckBox

    private lateinit var dedupStatusLabel: JLabel
    private lateinit var dedupViewButton: JButton
    private var dedupTimer: Timer? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        updateColors()
        alignmentX = LEFT_ALIGNMENT

        buildPanel()
    }

    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    private fun updateColors() {
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD)
        )
    }

    private fun buildPanel() {
        add(Design.createSectionLabel("Server Configuration"))
        add(createVerticalStrut(Design.Spacing.MD))

        val enabledPanel = createEnabledPanel()
        add(enabledPanel)
        add(createVerticalStrut(Design.Spacing.MD))

        val configEditingToolingCheckBox = createCheckBoxWithSubtitle(
            "Enable tools that can edit your config",
            "WARNING: Can execute code",
            config.configEditingTooling
        ) { config.configEditingTooling = it }
        add(configEditingToolingCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val httpRequestApprovalCheckBox = createStandardCheckBox(
            "Require approval for HTTP requests", config.requireHttpRequestApproval
        ) { config.requireHttpRequestApproval = it }
        add(httpRequestApprovalCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val dataAccessApprovalCheckBox = createDataAccessApprovalCheckBox()
        add(dataAccessApprovalCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowHttpHistoryCheckBox = createIndentedCheckBox(
            "Always allow HTTP history access", config.alwaysAllowHttpHistory, config.requireDataAccessApproval
        ) { config.alwaysAllowHttpHistory = it }
        add(alwaysAllowHttpHistoryCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowWebSocketHistoryCheckBox = createIndentedCheckBox(
            "Always allow WebSocket history access",
            config.alwaysAllowWebSocketHistory,
            config.requireDataAccessApproval
        ) { config.alwaysAllowWebSocketHistory = it }
        add(alwaysAllowWebSocketHistoryCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowOrganizerCheckBox = createIndentedCheckBox(
            "Always allow Organizer access",
            config.alwaysAllowOrganizer,
            config.requireDataAccessApproval
        ) { config.alwaysAllowOrganizer = it }
        add(alwaysAllowOrganizerCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val filterConfigCredentialsCheckBox = createCheckBoxWithSubtitle(
            "Filter config credentials",
            "Hides sensitive data in config files (Platform Authentication, socks proxy, etc.)",
            config.filterConfigCredentials
        ) { config.filterConfigCredentials = it }
        add(filterConfigCredentialsCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val tokenDeduplicationCheckBox = createCheckBoxWithSubtitle(
            "Deduplicate session headers (token optimisation)",
            "Saves ~60–78% of tokens on authenticated sessions by replacing repeated headers with compact references",
            config.tokenDeduplicationEnabled
        ) { config.tokenDeduplicationEnabled = it }
        add(tokenDeduplicationCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        // Option C — status line
        dedupStatusLabel = JLabel("No session captured yet").apply {
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = Color.GRAY
            border = BorderFactory.createEmptyBorder(0, 20, 0, 0)
            alignmentX = LEFT_ALIGNMENT
        }
        add(dedupStatusLabel)
        add(createVerticalStrut(Design.Spacing.SM))

        // Option B — View Variables button
        dedupViewButton = JButton("View Session Variables").apply {
            font = font.deriveFont(11f)
            alignmentX = LEFT_ALIGNMENT
            addActionListener { showSessionVariablesDialog() }
        }
        val dedupButtonRow = JPanel(FlowLayout(FlowLayout.LEFT, 20, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(dedupViewButton)
        }
        add(dedupButtonRow)
        add(createVerticalStrut(Design.Spacing.SM))

        // Start polling timer to refresh status label every 1.5s
        startDedupTimer()

        add(validationErrorLabel)
    }

    private fun createEnabledPanel(): JPanel {
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
        return enabledPanel
    }

    private fun createDataAccessApprovalCheckBox(): JCheckBox {
        return createStandardCheckBox(
            "Require approval for project data access", config.requireDataAccessApproval
        ) { enabled ->
            config.requireDataAccessApproval = enabled
            if (!enabled) {
                config.alwaysAllowHttpHistory = false
                config.alwaysAllowWebSocketHistory = false
                config.alwaysAllowOrganizer = false
                alwaysAllowHttpHistoryCheckBox.isSelected = false
                alwaysAllowWebSocketHistoryCheckBox.isSelected = false
                alwaysAllowOrganizerCheckBox.isSelected = false
            }
            alwaysAllowHttpHistoryCheckBox.isEnabled = enabled
            alwaysAllowWebSocketHistoryCheckBox.isEnabled = enabled
            alwaysAllowOrganizerCheckBox.isEnabled = enabled
        }
    }

    fun updateDataAccessCheckboxes() {
        SwingUtilities.invokeLater {
            alwaysAllowHttpHistoryCheckBox.isSelected = config.alwaysAllowHttpHistory
            alwaysAllowWebSocketHistoryCheckBox.isSelected = config.alwaysAllowWebSocketHistory
            alwaysAllowOrganizerCheckBox.isSelected = config.alwaysAllowOrganizer
        }
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

    private fun createCheckBoxWithSubtitle(
        mainText: String, subtitleText: String, initialValue: Boolean, onChange: (Boolean) -> Unit
    ): JPanel {
        val checkBox = JCheckBox(mainText).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }

        val subtitleLabel = JLabel(subtitleText).apply {
            font = Design.Typography.labelMedium
            foreground = Design.Colors.onSurfaceVariant
        }

        val subtitlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(createHorizontalStrut(20))
            add(subtitleLabel)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(checkBox)
            add(subtitlePanel)
        }
    }


    private fun startDedupTimer() {
        dedupTimer?.stop()
        dedupTimer = Timer(1500) { refreshDedupStatus() }.apply {
            isRepeats = true
            start()
        }
    }

    fun stopDedupTimer() {
        dedupTimer?.stop()
        dedupTimer = null
    }

    private fun refreshDedupStatus() {
        val ctx = config.sessionContext
        val label = when {
            ctx == null -> "Deduplication disabled"
            ctx.isEmpty() -> "Waiting for first history request…"
            else -> {
                val count = ctx.snapshot().size
                val savings = ctx.estimatedTokenSavingsPerRequest()
                "$count variable${if (count != 1) "s" else ""} captured · ~$savings tokens saved per request"
            }
        }
        SwingUtilities.invokeLater {
            if (::dedupStatusLabel.isInitialized) dedupStatusLabel.text = label
        }
    }

    private fun showSessionVariablesDialog() {
        val ctx = config.sessionContext
        val parent = SwingUtilities.getWindowAncestor(this) as? Frame
        val dialog = JDialog(parent, "Session Variables", true)
        dialog.layout = BorderLayout(10, 10)
        (dialog.rootPane.border as? javax.swing.border.Border)?.let {}
        dialog.rootPane.border = BorderFactory.createEmptyBorder(12, 12, 12, 12)

        if (ctx == null || ctx.isEmpty()) {
            val msg = JLabel(
                if (ctx == null) "Deduplication is disabled. Enable it and call get_proxy_http_history to capture variables."
                else "No variables captured yet. Call get_proxy_http_history with deduplication enabled."
            ).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = Color.GRAY
            }
            dialog.add(msg, BorderLayout.CENTER)
        } else {
            val snapshot = ctx.snapshot().entries.sortedBy { it.key }
            val columnNames = arrayOf("Variable", "Value")
            val tableModel = DefaultTableModel(columnNames, 0).apply {
                snapshot.forEach { (k, v) ->
                    val display = if (v.length > 120) "${v.take(80)}…  [${v.length} chars total]" else v
                    addRow(arrayOf(k, display))
                }
            }
            val table = JTable(tableModel).apply {
                isEnabled = false
                fillsViewportHeight = true
                columnModel.getColumn(0).preferredWidth = 100
                columnModel.getColumn(0).maxWidth = 120
                columnModel.getColumn(1).preferredWidth = 480
                rowHeight = 22
                tableHeader.resizingAllowed = true
            }

            val savingsLabel = JLabel(
                "${snapshot.size} variables · ~${ctx.estimatedTokenSavingsPerRequest()} tokens saved per request"
            ).apply {
                font = font.deriveFont(Font.ITALIC, 11f)
                foreground = Color.GRAY
                border = BorderFactory.createEmptyBorder(6, 2, 6, 2)
            }

            val centerPanel = JPanel(BorderLayout(0, 4)).apply {
                add(JScrollPane(table), BorderLayout.CENTER)
                add(savingsLabel, BorderLayout.SOUTH)
            }
            dialog.add(centerPanel, BorderLayout.CENTER)
        }

        // Button row
        val clearBtn = JButton("Clear Session").apply {
            addActionListener {
                ctx?.reset()
                dialog.dispose()
            }
        }
        val closeBtn = JButton("Close").apply {
            addActionListener { dialog.dispose() }
        }
        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        if (ctx != null && !ctx.isEmpty()) btnPanel.add(clearBtn)
        btnPanel.add(closeBtn)
        dialog.add(btnPanel, BorderLayout.SOUTH)

        dialog.setSize(640, 380)
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true

        // Refresh status after dialog closes (user may have cleared)
        refreshDedupStatus()
    }
}