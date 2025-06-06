package net.portswigger.mcp.config.components

import javax.swing.JLabel
import javax.swing.UIManager

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