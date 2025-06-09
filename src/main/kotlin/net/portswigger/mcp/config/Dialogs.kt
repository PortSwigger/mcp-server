package net.portswigger.mcp.config

import burp.api.montoya.MontoyaApi
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.ui.editor.EditorOptions
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

object Dialogs {

    private fun wrapText(text: String, maxWidth: Int = 50): String {
        if (text.length <= maxWidth) return text

        val words = text.split(" ")
        val result = StringBuilder()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.length + word.length + 1 <= maxWidth) {
                if (currentLine.isNotEmpty()) currentLine.append(" ")
                currentLine.append(word)
            } else {
                if (result.isNotEmpty()) result.append("\n")
                result.append(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }

        if (currentLine.isNotEmpty()) {
            if (result.isNotEmpty()) result.append("\n")
            result.append(currentLine.toString())
        }

        return result.toString()
    }

    private fun createDialog(parent: Component?): JDialog {
        val parentWindow = SwingUtilities.getWindowAncestor(parent)
        return JDialog(parentWindow, "", Dialog.ModalityType.APPLICATION_MODAL).apply {
            background = Design.Colors.surface
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            isResizable = true

            val escapeAction = object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) {
                    dispose()
                }
            }

            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape"
            )
            rootPane.actionMap.put("escape", escapeAction)
        }
    }

    fun showMessageDialog(
        parent: Component?, message: String, messageType: Int
    ) {
        val dialog = createDialog(parent)

        val iconLabel = when (messageType) {
            JOptionPane.ERROR_MESSAGE -> JLabel("⚠").apply {
                font = Design.Typography.headlineMedium
                foreground = Design.Colors.error
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(40, 40)
            }

            JOptionPane.WARNING_MESSAGE -> JLabel("⚠").apply {
                font = Design.Typography.headlineMedium
                foreground = Design.Colors.warning
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(40, 40)
            }

            JOptionPane.INFORMATION_MESSAGE -> JLabel("ⓘ").apply {
                font = Design.Typography.headlineMedium
                foreground = Design.Colors.primary
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(40, 40)
            }

            else -> null
        }

        val messageLabel = JLabel(wrapText(message)).apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            horizontalAlignment = SwingConstants.CENTER
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Design.Colors.surface
            border = EmptyBorder(Design.Spacing.XL, Design.Spacing.XL, Design.Spacing.LG, Design.Spacing.XL)
        }

        if (iconLabel != null) {
            iconLabel.alignmentX = Component.CENTER_ALIGNMENT
            contentPanel.add(iconLabel)
            contentPanel.add(Box.createVerticalStrut(Design.Spacing.MD))
        }

        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(messageLabel)
        contentPanel.add(Box.createVerticalStrut(Design.Spacing.LG))

        val okButton = Design.createFilledButton("OK").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener {
                dialog.dispose()
            }
        }

        contentPanel.add(okButton)

        dialog.contentPane = contentPanel
        dialog.pack()
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true
    }

    fun showConfirmDialog(
        parent: Component?, message: String, optionType: Int
    ): Int {
        val dialog = createDialog(parent)
        var result = JOptionPane.CANCEL_OPTION

        val messageLabel = JLabel(message).apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            horizontalAlignment = SwingConstants.CENTER
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Design.Colors.surface
            border = EmptyBorder(Design.Spacing.XL, Design.Spacing.XL, Design.Spacing.LG, Design.Spacing.XL)
        }

        messageLabel.alignmentX = Component.CENTER_ALIGNMENT
        contentPanel.add(messageLabel)
        contentPanel.add(Box.createVerticalStrut(Design.Spacing.LG))

        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER, Design.Spacing.MD, 0)).apply {
            background = Design.Colors.surface
            alignmentX = Component.CENTER_ALIGNMENT
        }

        when (optionType) {
            JOptionPane.YES_NO_OPTION -> {
                val noButton = Design.createOutlinedButton("No").apply {
                    addActionListener {
                        result = JOptionPane.NO_OPTION
                        dialog.dispose()
                    }
                }
                val yesButton = Design.createFilledButton("Yes").apply {
                    addActionListener {
                        result = JOptionPane.YES_OPTION
                        dialog.dispose()
                    }
                }
                buttonPanel.add(noButton)
                buttonPanel.add(yesButton)
            }

            JOptionPane.OK_CANCEL_OPTION -> {
                val cancelButton = Design.createOutlinedButton("Cancel").apply {
                    addActionListener {
                        result = JOptionPane.CANCEL_OPTION
                        dialog.dispose()
                    }
                }
                val okButton = Design.createFilledButton("OK").apply {
                    addActionListener {
                        result = JOptionPane.OK_OPTION
                        dialog.dispose()
                    }
                }
                buttonPanel.add(cancelButton)
                buttonPanel.add(okButton)
            }
        }

        contentPanel.add(buttonPanel)

        dialog.contentPane = contentPanel
        dialog.pack()
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true

        return result
    }

    fun showInputDialog(
        parent: Component?, message: String
    ): String? {
        val dialog = createDialog(parent)
        var result: String? = null

        val messageLabel = JLabel(message).apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
        }

        val inputField = JTextField(20).apply {
            font = Design.Typography.bodyLarge
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Design.Colors.outline, 1), BorderFactory.createEmptyBorder(
                    Design.Spacing.SM, Design.Spacing.MD, Design.Spacing.SM, Design.Spacing.MD
                )
            )
            background = Design.Colors.listBackground
            foreground = Design.Colors.onSurface
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = Design.Colors.surface
            border = EmptyBorder(Design.Spacing.XL, Design.Spacing.XL, Design.Spacing.LG, Design.Spacing.XL)
        }

        messageLabel.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(messageLabel)
        contentPanel.add(Box.createVerticalStrut(Design.Spacing.MD))

        inputField.alignmentX = Component.LEFT_ALIGNMENT
        contentPanel.add(inputField)
        contentPanel.add(Box.createVerticalStrut(Design.Spacing.LG))

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, Design.Spacing.MD, 0)).apply {
            background = Design.Colors.surface
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val cancelButton = Design.createOutlinedButton("Cancel").apply {
            addActionListener {
                result = null
                dialog.dispose()
            }
        }

        val okButton = Design.createFilledButton("OK").apply {
            addActionListener {
                result = inputField.text?.takeIf { it.isNotBlank() }
                dialog.dispose()
            }
        }

        val enterAction = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                result = inputField.text?.takeIf { it.isNotBlank() }
                dialog.dispose()
            }
        }

        inputField.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter"
        )
        inputField.actionMap.put("enter", enterAction)

        buttonPanel.add(cancelButton)
        buttonPanel.add(okButton)
        contentPanel.add(buttonPanel)

        dialog.contentPane = contentPanel
        dialog.pack()
        dialog.setLocationRelativeTo(parent)

        SwingUtilities.invokeLater {
            inputField.requestFocusInWindow()
        }

        dialog.isVisible = true

        return result
    }

    fun showOptionDialog(
        parent: Component?,
        message: String,
        options: Array<String>,
        requestContent: String? = null,
        api: MontoyaApi? = null
    ): Int {
        val dialog = createDialog(parent)
        var result = -1

        val messageArea = JTextArea(message).apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            background = Design.Colors.surface
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            columns = 30
            rows = 0
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val contentPanel = JPanel().apply {
            background = Design.Colors.surface
        }

        if (!requestContent.isNullOrBlank()) {
            contentPanel.layout = BorderLayout()

            val leftPanel = JPanel().apply {
                layout = BorderLayout()
                background = Design.Colors.surface
                minimumSize = Dimension(400, 300)
            }

            val requestComponent = if (api != null) {
                try {
                    val httpRequestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.READ_ONLY)
                    httpRequestEditor.request = HttpRequest.httpRequest(requestContent)
                    httpRequestEditor.uiComponent().apply {
                        minimumSize = Dimension(400, 200)
                    }
                } catch (_: Exception) {
                    JTextArea(requestContent).apply {
                        font = Design.Typography.bodyMedium
                        foreground = Design.Colors.onSurface
                        background = Design.Colors.listBackground
                        isEditable = false
                        lineWrap = false
                        tabSize = 4
                    }.let { textArea ->
                        JScrollPane(textArea).apply {
                            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                            border = BorderFactory.createLineBorder(Design.Colors.outline, 1)
                            minimumSize = Dimension(400, 200)
                        }
                    }
                }
            } else {
                JTextArea(requestContent).apply {
                    font = Design.Typography.bodyMedium
                    foreground = Design.Colors.onSurface
                    background = Design.Colors.listBackground
                    isEditable = false
                    lineWrap = false
                    tabSize = 4
                }.let { textArea ->
                    JScrollPane(textArea).apply {
                        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                        border = BorderFactory.createLineBorder(Design.Colors.outline, 1)
                        minimumSize = Dimension(400, 200)
                    }
                }
            }

            leftPanel.add(requestComponent, BorderLayout.CENTER)

            val rightPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = Design.Colors.surface
                minimumSize = Dimension(400, 300)
                maximumSize = Dimension(400, Int.MAX_VALUE)
                preferredSize = Dimension(400, 400)
                border = EmptyBorder(Design.Spacing.XL, Design.Spacing.LG, Design.Spacing.XL, Design.Spacing.XL)
            }

            messageArea.alignmentX = Component.CENTER_ALIGNMENT
            rightPanel.add(messageArea)
            rightPanel.add(Box.createVerticalStrut(Design.Spacing.LG))
            rightPanel.add(Box.createVerticalGlue())

            val buttonPanel = JPanel().apply {
                layout = GridLayout(2, 2, Design.Spacing.SM, Design.Spacing.SM)
                background = Design.Colors.surface
                alignmentX = Component.CENTER_ALIGNMENT
                preferredSize = Dimension(390, 80)
            }

            options.forEachIndexed { index, option ->
                val button = when (index) {
                    0 -> Design.createFilledButton(option)
                    1, 2 -> Design.createOutlinedButton(option)
                    3 -> Design.createOutlinedButton(option).apply {
                        foreground = Design.Colors.error
                        border = BorderFactory.createLineBorder(Design.Colors.error, 1)
                    }

                    else -> Design.createOutlinedButton(option)
                }.apply {
                    preferredSize = Dimension(190, 32)
                    font = font.deriveFont(10f)
                    addActionListener {
                        result = index
                        dialog.dispose()
                    }
                }
                buttonPanel.add(button)
            }

            rightPanel.add(buttonPanel)

            contentPanel.add(leftPanel, BorderLayout.CENTER)
            contentPanel.add(rightPanel, BorderLayout.EAST)
        } else {
            contentPanel.layout = BoxLayout(contentPanel, BoxLayout.Y_AXIS)
            contentPanel.border =
                EmptyBorder(Design.Spacing.XL, Design.Spacing.XL, Design.Spacing.XL, Design.Spacing.XL)

            messageArea.alignmentX = Component.CENTER_ALIGNMENT
            contentPanel.add(messageArea)
            contentPanel.add(Box.createVerticalStrut(Design.Spacing.XL))

            val buttonPanel = JPanel().apply {
                layout = GridLayout(2, 2, Design.Spacing.SM, Design.Spacing.SM)
                background = Design.Colors.surface
                alignmentX = Component.CENTER_ALIGNMENT
                preferredSize = Dimension(370, 80)
            }

            options.forEachIndexed { index, option ->
                val button = when (index) {
                    0 -> Design.createFilledButton(option)
                    1, 2 -> Design.createOutlinedButton(option)
                    else -> Design.createTextButton(option)
                }.apply {
                    preferredSize = Dimension(180, 32)
                    font = font.deriveFont(10f)
                    addActionListener {
                        result = index
                        dialog.dispose()
                    }
                }
                buttonPanel.add(button)
            }

            contentPanel.add(buttonPanel)
        }

        dialog.contentPane = contentPanel

        if (requestContent.isNullOrBlank()) {
            dialog.preferredSize = Dimension(420, 350)
        } else {
            dialog.preferredSize = Dimension(860, 400)
        }

        dialog.pack()

        if (parent != null && parent.isDisplayable) {
            dialog.setLocationRelativeTo(parent)

            dialog.isAlwaysOnTop = true
            dialog.toFront()
            dialog.requestFocus()
        } else {
            val screenSize = Toolkit.getDefaultToolkit().screenSize
            val dialogSize = dialog.size
            dialog.setLocation(
                (screenSize.width - dialogSize.width) / 2, (screenSize.height - dialogSize.height) / 2
            )
        }

        dialog.isVisible = true

        SwingUtilities.invokeLater {
            dialog.isAlwaysOnTop = false
            dialog.toFront()
        }

        return result
    }
}