package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.Icon

/** A font-independent success indicator that follows the active Burp theme. */
internal class SuccessIcon : Icon {
    override fun getIconWidth() = 32
    override fun getIconHeight() = 32

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Design.Colors.primary
            g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawOval(3, 3, 26, 26)
            g.draw(Path2D.Double().apply {
                moveTo(9.0, 16.0)
                lineTo(14.0, 21.0)
                lineTo(23.0, 11.0)
            })
        } finally {
            g.dispose()
        }
    }
}
