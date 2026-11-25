package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Rectangle

/**
 * Pure tests for [WindowGeometry] (Issue #1421): the start size of the main window comes from the
 * usable screen area, with a lower and an upper limit.
 */
@DisplayName("WindowGeometry")
class WindowGeometryTest {
	@Test
	@DisplayName("no screen (headless) gives the fixed fallback size")
	fun noScreenGivesFallback() {
		assertThat(WindowGeometry.initialBounds(null))
			.isEqualTo(Rectangle(0, 0, WindowGeometry.MIN_WIDTH, WindowGeometry.MIN_HEIGHT))
	}

	@Test
	@DisplayName("a large screen gets 85 percent of its size, centered")
	fun largeScreenGetsFractionCentered() {
		val bounds = WindowGeometry.initialBounds(Rectangle(0, 0, 3840, 2340))

		assertThat(bounds.width).isEqualTo(3264)
		assertThat(bounds.height).isEqualTo(1989)
		assertThat(bounds.x).isEqualTo((3840 - 3264) / 2)
		assertThat(bounds.y).isEqualTo((2340 - 1989) / 2)
	}

	@Test
	@DisplayName("a middle-size screen is lifted to the lower limit")
	fun middleScreenUsesLowerLimit() {
		val bounds = WindowGeometry.initialBounds(Rectangle(0, 0, 1100, 900))

		assertThat(bounds.width).isEqualTo(WindowGeometry.MIN_WIDTH)
		assertThat(bounds.height).isEqualTo(WindowGeometry.MIN_HEIGHT)
	}

	@Test
	@DisplayName("a screen smaller than the lower limit caps the window at the screen")
	fun smallScreenCapsAtScreen() {
		val bounds = WindowGeometry.initialBounds(Rectangle(0, 24, 800, 576))

		assertThat(bounds).isEqualTo(Rectangle(0, 24, 800, 576))
	}

	@Test
	@DisplayName("the screen origin is kept (second monitor, top panel)")
	fun screenOriginIsKept() {
		val bounds = WindowGeometry.initialBounds(Rectangle(1920, 30, 2560, 1050))

		assertThat(bounds.x).isEqualTo(1920 + (2560 - bounds.width) / 2)
		assertThat(bounds.y).isEqualTo(30 + (1050 - bounds.height) / 2)
	}

	@Test
	@DisplayName("saved bounds are usable only when enough of them lies on a screen")
	fun savedBoundsUsableOnlyOnAScreen() {
		val screens = listOf(Rectangle(0, 0, 1920, 1200))

		assertThat(WindowGeometry.isOnScreen(Rectangle(100, 100, 1200, 900), screens)).isTrue()
		assertThat(WindowGeometry.isOnScreen(Rectangle(1900, 100, 1200, 900), screens)).isFalse()
		assertThat(WindowGeometry.isOnScreen(Rectangle(4000, 100, 1200, 900), screens)).isFalse()
		assertThat(WindowGeometry.isOnScreen(Rectangle(100, 100, 0, 900), screens)).isFalse()
	}
}
