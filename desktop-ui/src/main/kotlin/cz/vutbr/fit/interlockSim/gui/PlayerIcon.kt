package cz.vutbr.fit.interlockSim.gui

import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.UIManager

/**
 * Media-player symbol for the run buttons (Issue #1421): play, pause or stop.
 *
 * Drawn as vector shapes, not font glyphs: common Linux Java fonts lack the pause glyph, and shapes
 * stay sharp at any screen scale. The icon takes the button's text colour, or the look-and-feel's
 * disabled text colour while the button is disabled.
 */
internal class PlayerIcon(
	val symbol: Symbol,
	private val size: Int
) : Icon {
	/** The three player symbols. */
	enum class Symbol { PLAY, PAUSE, STOP }

	override fun getIconWidth(): Int = size

	override fun getIconHeight(): Int = size

	override fun paintIcon(
		c: Component?,
		g: Graphics,
		x: Int,
		y: Int
	) {
		val g2 = g.create() as Graphics2D
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			g2.color = colorFor(c)
			g2.translate(x, y)
			val s = size.toDouble()
			when (symbol) {
				Symbol.PLAY ->
					g2.fill(
						Path2D.Double().apply {
							moveTo(s * EDGE_LEFT, s * EDGE_TOP)
							lineTo(s * EDGE_RIGHT, s * HALF)
							lineTo(s * EDGE_LEFT, s * EDGE_BOTTOM)
							closePath()
						}
					)
				Symbol.PAUSE -> {
					val barWidth = s * BAR_WIDTH
					val barHeight = s * (EDGE_BOTTOM - EDGE_TOP)
					g2.fill(Rectangle2D.Double(s * EDGE_LEFT, s * EDGE_TOP, barWidth, barHeight))
					g2.fill(Rectangle2D.Double(s * EDGE_RIGHT - barWidth, s * EDGE_TOP, barWidth, barHeight))
				}
				Symbol.STOP -> {
					val side = s * (EDGE_BOTTOM - EDGE_TOP)
					g2.fill(Rectangle2D.Double(s * EDGE_TOP, s * EDGE_TOP, side, side))
				}
			}
		} finally {
			g2.dispose()
		}
	}

	private fun colorFor(c: Component?): Color =
		if (c == null || c.isEnabled) {
			c?.foreground ?: Color.BLACK
		} else {
			UIManager.getColor("Button.disabledText") ?: Color.GRAY
		}

	companion object {
		private const val EDGE_LEFT = 0.2
		private const val EDGE_RIGHT = 0.85
		private const val EDGE_TOP = 0.15
		private const val EDGE_BOTTOM = 0.85
		private const val HALF = 0.5
		private const val BAR_WIDTH = 0.22

		/** Icon size relative to the button font, so the symbol matches the text around it. */
		private const val FONT_SCALE = 1.25

		/**
		 * Shows [symbol] on [button] as its only content: no text, [label] as the accessible name, so
		 * screen readers and tests still read "Stop", "Pause" or "Resume".
		 */
		fun apply(
			button: JButton,
			symbol: Symbol,
			label: String
		) {
			button.text = null
			button.icon = PlayerIcon(symbol, (button.font.size2D * FONT_SCALE).toInt())
			button.accessibleContext.accessibleName = label
		}
	}
}
