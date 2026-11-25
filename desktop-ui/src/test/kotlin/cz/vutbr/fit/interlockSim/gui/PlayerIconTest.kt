package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.SwingUtilities

/** Tests for [PlayerIcon], the media-player symbols of the run buttons (Issue #1421). */
@DisplayName("PlayerIcon")
class PlayerIconTest {
	@Test
	@DisplayName("apply shows the symbol with no text and keeps the word as accessible name")
	fun applySetsIconAndAccessibleName() {
		SwingUtilities.invokeAndWait {
			val button = JButton("Stop")
			PlayerIcon.apply(button, PlayerIcon.Symbol.STOP, "Stop")

			assertThat(button.text).isNull()
			assertThat(button.icon).isInstanceOf(PlayerIcon::class)
			assertThat((button.icon as PlayerIcon).symbol).isEqualTo(PlayerIcon.Symbol.STOP)
			assertThat(button.accessibleContext.accessibleName).isEqualTo("Stop")
		}
	}

	@Test
	@DisplayName("every symbol paints opaque pixels in the middle of the icon")
	fun everySymbolPaints() {
		PlayerIcon.Symbol.entries.forEach { symbol ->
			val icon = PlayerIcon(symbol, SIZE)
			val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
			val g = image.createGraphics()
			icon.paintIcon(null, g, 0, 0)
			g.dispose()

			val painted =
				(SIZE / 3 until SIZE * 2 / 3).any { x ->
					(SIZE / 3 until SIZE * 2 / 3).any { y -> Color(image.getRGB(x, y), true).alpha > 0 }
				}
			assertThat(painted, "symbol $symbol").isTrue()
		}
	}

	private companion object {
		const val SIZE = 24
	}
}
