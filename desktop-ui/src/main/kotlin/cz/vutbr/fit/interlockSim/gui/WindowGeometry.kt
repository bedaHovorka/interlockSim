package cz.vutbr.fit.interlockSim.gui

import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/**
 * Start size and position of the main window (Issue #1421).
 *
 * Pure functions on screen rectangles, so the rules are testable without a display. [Frame] feeds
 * them the usable screen area from [GraphicsEnvironment.getMaximumWindowBounds] (the screen minus
 * desktop panels), or `null` when the JVM is headless.
 */
internal object WindowGeometry {
	/** Lower limit of the start width; also the headless fallback width. */
	const val MIN_WIDTH: Int = 1024

	/**
	 * Lower limit of the start height; also the headless fallback height. It leaves room for the
	 * north controls, the bottom tabs and the status bar around a usable canvas (PR #801 review).
	 */
	const val MIN_HEIGHT: Int = 818

	/** Share of the usable screen, in percent, that the window takes at its first start. */
	const val SCREEN_PERCENT: Int = 85

	/** Smallest width and height of saved bounds that must lie on one screen to be reused. */
	const val MIN_VISIBLE: Int = 200

	/**
	 * Start bounds on the usable [screen] area: [SCREEN_PERCENT] of it, not smaller than
	 * [MIN_WIDTH] x [MIN_HEIGHT], not larger than the screen, centered on it. A `null` screen
	 * (headless) gives `0, 0, MIN_WIDTH, MIN_HEIGHT`.
	 */
	fun initialBounds(screen: Rectangle?): Rectangle {
		if (screen == null) return Rectangle(0, 0, MIN_WIDTH, MIN_HEIGHT)
		val width = (screen.width * SCREEN_PERCENT / PERCENT).coerceAtLeast(MIN_WIDTH).coerceAtMost(screen.width)
		val height = (screen.height * SCREEN_PERCENT / PERCENT).coerceAtLeast(MIN_HEIGHT).coerceAtMost(screen.height)
		return Rectangle(screen.x + (screen.width - width) / 2, screen.y + (screen.height - height) / 2, width, height)
	}

	/**
	 * `true` when at least [MIN_VISIBLE] x [MIN_VISIBLE] pixels of [bounds] lie on one of the
	 * [screens]. Saved bounds from a monitor that is now gone fail this check, so the window never
	 * opens where the user cannot see it.
	 */
	fun isOnScreen(
		bounds: Rectangle,
		screens: List<Rectangle>
	): Boolean {
		if (bounds.width <= 0 || bounds.height <= 0) return false
		return screens.any { screen ->
			val visible = screen.intersection(bounds)
			visible.width >= MIN_VISIBLE && visible.height >= MIN_VISIBLE
		}
	}

	/** Usable screen area of the default screen, or `null` when the JVM is headless. */
	fun usableScreen(): Rectangle? =
		if (GraphicsEnvironment.isHeadless()) {
			null
		} else {
			GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
		}

	/** Bounds of every connected screen; empty when the JVM is headless. */
	fun screenBounds(): List<Rectangle> =
		if (GraphicsEnvironment.isHeadless()) {
			emptyList()
		} else {
			GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
		}

	private const val PERCENT = 100
}
