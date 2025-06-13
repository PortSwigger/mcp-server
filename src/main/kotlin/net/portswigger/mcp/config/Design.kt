package net.portswigger.mcp.config

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared Design constants and utilities for consistent theming across the application
 */
object Design {

    object Colors {
        val primary: Color get() = UIManager.getColor("Burp.primaryButtonBackground") ?: Color(0xD86633)
        val onPrimary: Color get() = UIManager.getColor("Burp.primaryButtonForeground") ?: Color.WHITE
        val surface: Color get() = UIManager.getColor("Panel.background") ?: Color(0xFFFBFF)
        val onSurface: Color get() = UIManager.getColor("Label.foreground") ?: Color(0x1A1A1A)
        val onSurfaceVariant: Color get() = UIManager.getColor("Label.disabledForeground") ?: Color(0x666666)
        val outline: Color get() = UIManager.getColor("Component.borderColor") ?: Color(0xCCCCCC)
        val outlineVariant: Color get() = UIManager.getColor("Separator.foreground") ?: Color(0xE0E0E0)
        val error: Color get() = UIManager.getColor("Burp.errorColor") ?: Color(0xB3261E)
        val warning: Color get() = UIManager.getColor("Burp.warningColor") ?: Color(0xF57C00)
        val transparent = Color(0, 0, 0, 0)
        val listBackground: Color get() = UIManager.getColor("List.background") ?: Color.WHITE
        val listSelectionBackground: Color get() = UIManager.getColor("List.selectionBackground") ?: Color(0xE3F2FD)
        val listSelectionForeground: Color get() = UIManager.getColor("List.selectionForeground") ?: Color(0x1976D2)
        val listHoverBackground: Color get() = UIManager.getColor("List.hoverBackground") ?: Color(0xF0F8FF)
        val listAlternatingBackground: Color get() = UIManager.getColor("List.alternateRowColor") ?: Color(0xFAFAFA)
        val listBorder: Color get() = UIManager.getColor("List.border") ?: Color(0xDDDDDD)
    }

    object Typography {
        private val baseFont: Font get() = UIManager.getFont("Label.font") ?: Font("Inter", Font.PLAIN, 14)
        private val baseSize: Int get() = baseFont.size

        val headlineMedium: Font get() = baseFont.deriveFont(Font.BOLD, (baseSize * 2.0f))
        val titleMedium: Font get() = baseFont.deriveFont(Font.BOLD, (baseSize * 1.14f))
        val bodyLarge: Font get() = baseFont.deriveFont(Font.PLAIN, (baseSize * 1.14f))
        val bodyMedium: Font get() = baseFont.deriveFont(Font.PLAIN, baseSize.toFloat())
        val labelLarge: Font get() = baseFont.deriveFont(Font.BOLD, baseSize.toFloat())
        val labelMedium: Font get() = baseFont.deriveFont(Font.BOLD, (baseSize * 0.86f))
    }

    object Spacing {
        private val baseSize: Int get() = (UIManager.getFont("Label.font")?.size ?: 14)
        private val scaleFactor: Float get() = baseSize / 14f

        val SM: Int get() = (8 * scaleFactor).toInt().coerceAtLeast(4)
        val MD: Int get() = (16 * scaleFactor).toInt().coerceAtLeast(8)
        val LG: Int get() = (24 * scaleFactor).toInt().coerceAtLeast(12)
        val XL: Int get() = (32 * scaleFactor).toInt().coerceAtLeast(16)
    }

    private fun calculateTextFitSize(button: JButton): Dimension {
        val font = Typography.labelLarge
        val metrics = button.getFontMetrics(font)
        val textWidth = metrics.stringWidth(button.text)
        val textHeight = metrics.height

        val horizontalPadding = Spacing.LG * 2
        val verticalPadding = Spacing.SM * 2 + 4

        val minWidth = textWidth + horizontalPadding
        val minHeight = textHeight + verticalPadding

        return Dimension(
            minWidth.coerceAtLeast(80),
            minHeight.coerceAtLeast(40)
        )
    }

    private fun applyButtonBaseStyle(button: JButton, customSize: Dimension?) {
        button.apply {
            font = Typography.labelLarge
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            val textFitSize = calculateTextFitSize(this)
            minimumSize = textFitSize
            preferredSize = customSize ?: textFitSize
        }
    }

    fun createFilledButton(text: String, customSize: Dimension? = null): JButton {
        return object : JButton(text) {
            init {
                updateColorsAndSizing()
                applyButtonBaseStyle(this, customSize)
            }

            override fun updateUI() {
                super.updateUI()
                updateColorsAndSizing()
                applyButtonBaseStyle(this, customSize)
            }

            private fun updateColorsAndSizing() {
                background = Colors.primary
                foreground = Colors.onPrimary
                border = BorderFactory.createEmptyBorder(Spacing.SM + 2, Spacing.LG, Spacing.SM + 2, Spacing.LG)
            }
        }
    }

    fun createOutlinedButton(text: String, customSize: Dimension? = null): JButton {
        return object : JButton(text) {
            init {
                updateColorsAndSizing()
                applyButtonBaseStyle(this, customSize)
            }

            override fun updateUI() {
                super.updateUI()
                updateColorsAndSizing()
                applyButtonBaseStyle(this, customSize)
            }

            private fun updateColorsAndSizing() {
                background = Colors.surface
                foreground = Colors.primary
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Colors.outline, 1),
                    BorderFactory.createEmptyBorder(Spacing.SM + 1, Spacing.LG - 1, Spacing.SM + 1, Spacing.LG - 1)
                )
            }
        }
    }

    fun createTextButton(text: String, customSize: Dimension? = null): JButton {
        return object : JButton(text) {
            init {
                updateColorsAndSizing()
                isContentAreaFilled = false
                applyButtonBaseStyle(this, customSize)
            }

            override fun updateUI() {
                super.updateUI()
                updateColorsAndSizing()
                applyButtonBaseStyle(this, customSize)
            }

            private fun updateColorsAndSizing() {
                background = Colors.transparent
                foreground = Colors.primary
                border = BorderFactory.createEmptyBorder(Spacing.SM + 2, Spacing.LG, Spacing.SM + 2, Spacing.LG)
            }
        }
    }

    fun createToggleSwitch(initialState: Boolean = false, onToggle: (Boolean) -> Unit): ToggleSwitch {
        return ToggleSwitch(initialState, onToggle)
    }

    fun createSectionLabel(text: String): JLabel {
        return object : JLabel(text) {
            init {
                updateColors()
                alignmentX = LEFT_ALIGNMENT
            }

            override fun updateUI() {
                super.updateUI()
                updateColors()
            }

            private fun updateColors() {
                font = Typography.titleMedium
                foreground = Colors.onSurface
            }
        }
    }
}

class ToggleSwitch(private var isOn: Boolean, private val onToggle: (Boolean) -> Unit) : JComponent() {

    companion object {
        private const val TRACK_WIDTH = 44
        private const val TRACK_HEIGHT = 24
        private const val THUMB_SIZE = 20
        private const val PADDING = 2
        private const val ANIMATION_DURATION = 150
        private const val TIMER_DELAY = 16
        private const val SPARKLE_DURATION = 800
        private const val SPARKLE_COUNT = 8
        private const val SPARKLE_MARGIN = 8
        private const val COMPONENT_WIDTH = TRACK_WIDTH + (SPARKLE_MARGIN * 2)
        private const val COMPONENT_HEIGHT = TRACK_HEIGHT + (SPARKLE_MARGIN * 2)
    }

    private var animationProgress = if (isOn) 1.0f else 0.0f
    private var animationTimer: Timer? = null
    private var sparkles = mutableListOf<Sparkle>()
    private var sparkleTimer: Timer? = null

    private data class Sparkle(
        var x: Float,
        var y: Float,
        var size: Float,
        var opacity: Float,
        var life: Float,
        val maxLife: Float,
        val velocityX: Float,
        val velocityY: Float,
        val rotation: Float,
        val rotationSpeed: Float
    )

    init {
        preferredSize = Dimension(COMPONENT_WIDTH, COMPONENT_HEIGHT)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                e?.let { event ->
                    val toggleBounds = Rectangle(
                        SPARKLE_MARGIN,
                        SPARKLE_MARGIN,
                        TRACK_WIDTH,
                        TRACK_HEIGHT
                    )
                    if (toggleBounds.contains(event.point)) {
                        toggle()
                    }
                }
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
        val startTime = System.currentTimeMillis()

        animationTimer = Timer(TIMER_DELAY) { _ ->
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toFloat() / ANIMATION_DURATION).coerceIn(0.0f, 1.0f)

            animationProgress = startProgress + (targetProgress - startProgress) * progress

            if (progress >= 1.0f) {
                animationTimer?.stop()
                animationProgress = targetProgress
                if (isOn) {
                    triggerSparkles()
                }
            }

            repaint()
        }
        animationTimer?.start()
    }

    private fun triggerSparkles() {
        sparkles.clear()
        sparkleTimer?.stop()

        val trackX = SPARKLE_MARGIN.toFloat()
        val trackY = SPARKLE_MARGIN.toFloat()
        val thumbX = trackX + PADDING + 1.0f * (TRACK_WIDTH - THUMB_SIZE - 2 * PADDING)
        val thumbY = trackY + PADDING
        val thumbCenterX = thumbX + THUMB_SIZE / 2f
        val thumbCenterY = thumbY + THUMB_SIZE / 2f

        for (i in 0 until SPARKLE_COUNT) {
            val angle = (i * 360f / SPARKLE_COUNT) * Math.PI / 180f

            val maxSparkleSize = 5f
            val safetyBuffer = 2f
            val distanceToRightEdge = COMPONENT_WIDTH - thumbCenterX - maxSparkleSize - safetyBuffer
            val distanceToBottomEdge = COMPONENT_HEIGHT - thumbCenterY - maxSparkleSize - safetyBuffer
            val distanceToLeftEdge = thumbCenterX - maxSparkleSize - safetyBuffer
            val distanceToTopEdge = thumbCenterY - maxSparkleSize - safetyBuffer

            val maxSafeDistance =
                minOf(distanceToRightEdge, distanceToBottomEdge, distanceToLeftEdge, distanceToTopEdge)
            val constrainedMaxDistance = maxSafeDistance.coerceAtLeast(4f)

            val distance = 4f + Math.random().toFloat() * (constrainedMaxDistance - 4f)
            val sparkleX = thumbCenterX + cos(angle).toFloat() * distance
            val sparkleY = thumbCenterY + sin(angle).toFloat() * distance

            sparkles.add(
                Sparkle(
                    x = sparkleX,
                    y = sparkleY,
                    size = 2f + Math.random().toFloat() * 3f,
                    opacity = 1f,
                    life = 0f,
                    maxLife = SPARKLE_DURATION.toFloat() + Math.random().toFloat() * 200f,
                    velocityX = (Math.random().toFloat() - 0.5f) * 0.5f,
                    velocityY = (Math.random().toFloat() - 0.5f) * 0.5f,
                    rotation = Math.random().toFloat() * 360f,
                    rotationSpeed = (Math.random().toFloat() - 0.5f) * 5f
                )
            )
        }

        startSparkleAnimation()
    }

    private fun startSparkleAnimation() {
        sparkleTimer = Timer(TIMER_DELAY) { _ ->
            var activeSparkles = false

            for (sparkle in sparkles) {
                sparkle.life += TIMER_DELAY
                sparkle.x += sparkle.velocityX
                sparkle.y += sparkle.velocityY

                val lifeRatio = sparkle.life / sparkle.maxLife
                sparkle.opacity = (1f - lifeRatio).coerceIn(0f, 1f)
                sparkle.size = sparkle.size * 0.998f

                if (sparkle.life < sparkle.maxLife) {
                    activeSparkles = true
                }
            }

            if (!activeSparkles) {
                sparkleTimer?.stop()
                sparkles.clear()
            }

            repaint()
        }
        sparkleTimer?.start()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val trackX = SPARKLE_MARGIN.toFloat()
        val trackY = SPARKLE_MARGIN.toFloat()

        g2.color = if (isOn) Design.Colors.primary else Design.Colors.outline
        g2.fill(createRoundRect(trackX, trackY, TRACK_WIDTH.toFloat(), TRACK_HEIGHT.toFloat(), TRACK_HEIGHT.toFloat()))

        val thumbX = trackX + PADDING + animationProgress * (TRACK_WIDTH - THUMB_SIZE - 2 * PADDING)
        val thumbY = trackY + PADDING

        g2.color = Color(0, 0, 0, 20)
        g2.fill(
            createRoundRect(
                thumbX + 1,
                thumbY + 1,
                THUMB_SIZE.toFloat(),
                THUMB_SIZE.toFloat(),
                THUMB_SIZE.toFloat()
            )
        )

        g2.color = Color.WHITE
        g2.fill(createRoundRect(thumbX, thumbY, THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat(), THUMB_SIZE.toFloat()))

        for (sparkle in sparkles) {
            if (sparkle.opacity > 0) {
                val alpha = (sparkle.opacity * 255).toInt().coerceIn(0, 255)
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, sparkle.opacity)

                val sparkleSize = sparkle.size
                val cx = sparkle.x
                val cy = sparkle.y

                g2.color = Color(255, 215, 0, alpha)
                g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                g2.drawLine(
                    (cx - sparkleSize).toInt(),
                    cy.toInt(),
                    (cx + sparkleSize).toInt(),
                    cy.toInt()
                )

                g2.drawLine(
                    cx.toInt(),
                    (cy - sparkleSize).toInt(),
                    cx.toInt(),
                    (cy + sparkleSize).toInt()
                )

                val diagSize = sparkleSize * 0.7f
                g2.drawLine(
                    (cx - diagSize).toInt(),
                    (cy - diagSize).toInt(),
                    (cx + diagSize).toInt(),
                    (cy + diagSize).toInt()
                )
                g2.drawLine(
                    (cx - diagSize).toInt(),
                    (cy + diagSize).toInt(),
                    (cx + diagSize).toInt(),
                    (cy - diagSize).toInt()
                )
            }
        }

        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f)

        g2.dispose()
    }

    override fun updateUI() {
        super.updateUI()
        repaint() // Repaint to use updated theme colors
    }

    private fun createRoundRect(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        arcSize: Float
    ): RoundRectangle2D.Float {
        return RoundRectangle2D.Float(x, y, width, height, arcSize, arcSize)
    }
}