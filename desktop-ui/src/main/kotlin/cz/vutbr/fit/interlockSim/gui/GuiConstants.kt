/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui

import java.awt.Color

/**
 * GUI rendering and interaction constants.
 *
 * These constants define visual dimensions, scrolling behavior, and UI element sizing
 * for the railway network editor and simulation visualizer.
 *
 * @since 0.1-bachelor
 */

/**
 * Grid rendering dimensions.
 */
object GridDimensions {
	/** Grid cell width in pixels */
	const val CELL_WIDTH = 16

	/** Grid cell height in pixels */
	const val CELL_HEIGHT = 16

	/** Maximum scroll unit increment in pixels */
	const val MAX_UNIT_INCREMENT = 35
}

/**
 * Icon sizing for UI elements.
 */
object IconSizes {
	/** Standard icon size for toolbar actions (pixels) */
	const val ACTION_ICON_SIZE = 20
}

/**
 * Status bar badge colors.
 */
object StatusBarColors {
	/** Distinct color for the paused-state badge shown next to the speed indicator. */
	val PAUSED_BADGE_COLOR: Color = Color(0xFF, 0x8C, 0x00)
}
