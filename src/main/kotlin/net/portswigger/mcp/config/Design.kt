package net.portswigger.mcp.config

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*

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
        val warning = Color(0xF57C00)
        val transparent = Color(0, 0, 0, 0)
        val listBackground: Color = Color.WHITE
        val listSelectionBackground = Color(0xE3F2FD)
        val listSelectionForeground = Color(0x1976D2)
        val listAlternatingBackground = Color(0xFAFAFA)
        val listBorder = Color(0xDDDDDD)
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
            background = Colors.transparent
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

    fun createToggleSwitch(initialState: Boolean = false, onToggle: (Boolean) -> Unit): JComponent {
        return ToggleSwitch(initialState, onToggle)
    }
}

class ToggleSwitch(private var isOn: Boolean, private val onToggle: (Boolean) -> Unit) : JComponent() {

    private val trackWidth = 44
    private val trackHeight = 24
    private val thumbSize = 20
    private val padding = 2

    private var animationProgress = if (isOn) 1.0f else 0.0f
    private var animationTimer: Timer? = null

    init {
        preferredSize = Dimension(trackWidth, trackHeight)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                toggle()
            }
        })
    }

    fun setState(newState: Boolean, animate: Boolean = true) {
        if (isOn != newState) {
            isOn = newState
            if (animate) {
                animateToState()
            } else {
                animationProgress = if (isOn) 1.0f else 0.0f
                repaint()
            }
        }
    }

    private fun toggle() {
        isOn = !isOn
        onToggle(isOn)
        animateToState()
    }

    private fun animateToState() {
        animationTimer?.stop()

        val startProgress = animationProgress
        val targetProgress = if (isOn) 1.0f else 0.0f
        val duration = 150
        val startTime = System.currentTimeMillis()

        animationTimer = Timer(16) { _ ->
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toFloat() / duration).coerceIn(0.0f, 1.0f)

            animationProgress = startProgress + (targetProgress - startProgress) * progress

            if (progress >= 1.0f) {
                animationTimer?.stop()
                animationProgress = targetProgress
            }

            repaint()
        }
        animationTimer?.start()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val trackColor = if (isOn) Design.Colors.primary else Design.Colors.outline
        g2.color = trackColor
        val trackRect = RoundRectangle2D.Float(
            0f,
            0f,
            trackWidth.toFloat(),
            trackHeight.toFloat(),
            trackHeight.toFloat(),
            trackHeight.toFloat()
        )
        g2.fill(trackRect)

        val thumbX = padding + animationProgress * (trackWidth - thumbSize - 2 * padding)
        val thumbY = padding.toFloat()

        g2.color = Color(0, 0, 0, 20)
        val shadowRect = RoundRectangle2D.Float(
            thumbX + 1,
            thumbY + 1,
            thumbSize.toFloat(),
            thumbSize.toFloat(),
            thumbSize.toFloat(),
            thumbSize.toFloat()
        )
        g2.fill(shadowRect)

        g2.color = Color.WHITE
        val thumbRect = RoundRectangle2D.Float(
            thumbX,
            thumbY,
            thumbSize.toFloat(),
            thumbSize.toFloat(),
            thumbSize.toFloat(),
            thumbSize.toFloat()
        )
        g2.fill(thumbRect)

        g2.dispose()
    }
}