package net.portswigger.mcp.config

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

    private fun createDialog(parent: Component?, title: String): JDialog {
        val parentWindow = SwingUtilities.getWindowAncestor(parent)
        return JDialog(parentWindow, title, Dialog.ModalityType.APPLICATION_MODAL).apply {
            background = Design.Colors.surface
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

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
        parent: Component?, message: String, title: String, messageType: Int
    ) {
        val dialog = createDialog(parent, title)

        val iconLabel = when (messageType) {
            JOptionPane.ERROR_MESSAGE -> JLabel("⚠").apply {
                font = Font("SF Pro Display", Font.PLAIN, 24)
                foreground = Design.Colors.error
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(40, 40)
            }

            JOptionPane.WARNING_MESSAGE -> JLabel("⚠").apply {
                font = Font("SF Pro Display", Font.PLAIN, 24)
                foreground = Design.Colors.warning
                horizontalAlignment = SwingConstants.CENTER
                preferredSize = Dimension(40, 40)
            }

            JOptionPane.INFORMATION_MESSAGE -> JLabel("ⓘ").apply {
                font = Font("SF Pro Display", Font.PLAIN, 24)
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
        parent: Component?, message: String, title: String, optionType: Int
    ): Int {
        val dialog = createDialog(parent, title)
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
        parent: Component?, message: String, title: String
    ): String? {
        val dialog = createDialog(parent, title)
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

        val cancelButton = Design.createTextButton("Cancel").apply {
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
        parent: Component?, message: String, title: String, options: Array<String>
    ): Int {
        val dialog = createDialog(parent, title)
        var result = -1

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

        val buttonPanel = JPanel().apply {
            layout = GridLayout(0, 1, 0, Design.Spacing.SM)
            background = Design.Colors.surface
            alignmentX = Component.CENTER_ALIGNMENT
        }

        options.forEachIndexed { index, option ->
            val button = when (index) {
                0 -> Design.createFilledButton(option)
                options.size - 1 -> Design.createOutlinedButton(option)
                else -> Design.createTextButton(option)
            }.apply {
                preferredSize = Dimension(200, 40)
                addActionListener {
                    result = index
                    dialog.dispose()
                }
            }
            buttonPanel.add(button)
        }

        contentPanel.add(buttonPanel)

        dialog.contentPane = contentPanel
        dialog.pack()
        dialog.setLocationRelativeTo(parent)
        dialog.isVisible = true

        return result
    }
}