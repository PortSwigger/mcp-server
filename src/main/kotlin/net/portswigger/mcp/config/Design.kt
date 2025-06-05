package net.portswigger.mcp.config

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Shared Design constants and utilities for consistent theming across the application
 */
object Design {

    object Colors {
        val primary = Color(0xD86633)
        val onPrimary = Color(0xFFFFFF)
        val surface = Color(0xFFFBFF)
        val onSurface = Color(0x1A1A1A)
        val onSurfaceVariant = Color(0x666666)
        val outline = Color(0xCCCCCC)
        val outlineVariant = Color(0xE0E0E0)
        val error = Color(0xB3261E)
    }

    object Typography {
        val headlineMedium = Font("Inter", Font.BOLD, 28)
        val titleMedium = Font("Inter", Font.BOLD, 16)
        val bodyLarge = Font("Inter", Font.PLAIN, 16)
        val bodyMedium = Font("Inter", Font.PLAIN, 14)
        val labelLarge = Font("Inter", Font.BOLD, 14)
        val labelMedium = Font("Inter", Font.BOLD, 12)
    }

    object Spacing {
        const val SM = 8
        const val MD = 16
        const val LG = 24
        const val XL = 32
    }

    fun createFilledButton(text: String, customSize: Dimension? = null): JButton {
        return JButton(text).apply {
            background = Colors.primary
            foreground = Colors.onPrimary
            font = Typography.labelLarge
            border = BorderFactory.createEmptyBorder(Spacing.SM + 2, Spacing.LG, Spacing.SM + 2, Spacing.LG)
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = customSize ?: Dimension(160, 40)
            minimumSize = Dimension(80, 40)
        }
    }

    fun createOutlinedButton(text: String, customSize: Dimension? = null): JButton {
        return JButton(text).apply {
            background = Colors.surface
            foreground = Colors.primary
            font = Typography.labelLarge
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Colors.outline, 1),
                BorderFactory.createEmptyBorder(Spacing.SM + 1, Spacing.LG - 1, Spacing.SM + 1, Spacing.LG - 1)
            )
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = customSize ?: Dimension(120, 40)
            minimumSize = Dimension(80, 40)
        }
    }

    fun createTextButton(text: String, customSize: Dimension? = null): JButton {
        return JButton(text).apply {
            background = Color(0, 0, 0, 0)
            foreground = Colors.primary
            font = Typography.labelLarge
            border = BorderFactory.createEmptyBorder(Spacing.SM + 2, Spacing.LG, Spacing.SM + 2, Spacing.LG)
            isFocusPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = customSize ?: Dimension(100, 40)
            minimumSize = Dimension(80, 40)
        }
    }

    fun createCard(): JPanel {
        return JPanel().apply {
            background = Colors.surface
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Colors.outlineVariant, 1),
                BorderFactory.createEmptyBorder(Spacing.MD, Spacing.MD, Spacing.MD, Spacing.MD)
            )
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
    }
}