/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.animation

import cz.vutbr.fit.interlockSim.objects.core.Cell
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Generates directional train sprite icons for animation rendering.
 *
 * Creates locomotive-style sprites showing direction of travel with arrow indicators.
 * Sprites are generated programmatically at initialization and cached for performance.
 *
 * ## Sprite Design
 *
 * - **Size:** 20×12 pixels (rectangle showing train length)
 * - **Arrow:** Directional triangle indicator for 8 possible segments
 * - **Colors:** Blue (#0000FF) for InOut B, Orange (#FF8C00) for InOut A
 * - **Border:** Black 2px stroke for visibility
 * - **Number space:** Reserved area for white train ID text overlay
 *
 * ## Supported Directions
 *
 * Generates sprites for all 8 Cell.Segment directions:
 * - A: Left (→)
 * - B: Left-Top diagonal (↗)
 * - C: Top (↑)
 * - D: Left-Bottom diagonal (↘)
 * - E: Right-Top diagonal (↖)
 * - F: Right (←)
 * - G: Right-Bottom diagonal (↙)
 * - H: Bottom (↓)
 *
 * ## Color Variants
 *
 * Two color variants per direction (16 total sprites):
 * - Blue: Trains from InOut B
 * - Orange: Trains from InOut A
 *
 * ## Performance
 *
 * All sprites pre-generated at initialization and cached in memory.
 * Rendering uses fast BufferedImage.drawImage() with no runtime generation overhead.
 *
 * @since 2026-02-06 (Issue #297 - Train visual enhancement)
 */
object TrainIconGenerator {
	/** Sprite width in pixels */
	private const val SPRITE_WIDTH = 20

	/** Sprite height in pixels */
	private const val SPRITE_HEIGHT = 12

	/** Border stroke width */
	private const val BORDER_WIDTH = 2

	/**
	 * Cache of pre-generated train sprites.
	 *
	 * Key: (segment, isBlue) pair
	 * Value: BufferedImage sprite
	 */
	private val spriteCache = mutableMapOf<Pair<Cell.Segment, Boolean>, BufferedImage>()

	/**
	 * Initialize sprite cache by generating all variants.
	 *
	 * Must be called once before first use (typically during AnimatedSimulationCellRenderer init).
	 * Generates 16 sprites: 8 directions × 2 colors.
	 */
	fun initialize() {
		// Generate all segment × color combinations
		for (segment in Cell.Segment.values()) {
			for (isBlue in listOf(true, false)) {
				val sprite = generateSprite(segment, isBlue)
				spriteCache[Pair(segment, isBlue)] = sprite
			}
		}
	}

	/**
	 * Get cached sprite for a specific direction and color.
	 *
	 * @param segment Travel direction segment
	 * @param isBlue true for blue (InOut B), false for orange (InOut A)
	 * @return Cached BufferedImage sprite, or null if not initialized
	 */
	fun getSprite(
		segment: Cell.Segment,
		isBlue: Boolean
	): BufferedImage? = spriteCache[Pair(segment, isBlue)]

	/**
	 * Generate a train sprite for a specific direction and color.
	 *
	 * Creates a rectangular train body with directional arrow indicator.
	 * Arrow points in the direction indicated by segment's dx/dy values.
	 *
	 * @param segment Travel direction segment
	 * @param isBlue true for blue color, false for orange
	 * @return Generated BufferedImage sprite
	 */
	private fun generateSprite(
		segment: Cell.Segment,
		isBlue: Boolean
	): BufferedImage {
		val image = BufferedImage(SPRITE_WIDTH, SPRITE_HEIGHT, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()

		// Enable anti-aliasing for smooth rendering
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

		// Select body color
		val bodyColor =
			if (isBlue) {
				AnimationColors.TRAIN_FROM_B // Blue
			} else {
				AnimationColors.TRAIN_FROM_A // Orange
			}

		// Draw train body (filled rectangle)
		g.color = bodyColor
		g.fillRect(0, 0, SPRITE_WIDTH, SPRITE_HEIGHT)

		// Draw directional arrow
		drawArrow(g, segment)

		// Draw black border
		g.color = AnimationColors.TRAIN_BORDER
		g.stroke = BasicStroke(BORDER_WIDTH.toFloat())
		g.drawRect(0, 0, SPRITE_WIDTH - 1, SPRITE_HEIGHT - 1)

		g.dispose()
		return image
	}

	/**
	 * Draw directional arrow on sprite.
	 *
	 * Draws a white triangle pointing in the direction indicated by segment.
	 * Arrow positioned to show direction of travel while leaving space for train number.
	 *
	 * @param g Graphics context
	 * @param segment Direction segment (determines arrow orientation)
	 */
	private fun drawArrow(
		g: Graphics2D,
		segment: Cell.Segment
	) {
		g.color = Color.WHITE

		// Center of sprite
		val cx = SPRITE_WIDTH / 2
		val cy = SPRITE_HEIGHT / 2

		// Arrow size
		val arrowSize = 5

		// Calculate arrow points based on segment direction
		// Arrow points in direction of (dx, dy)
		val (xPoints, yPoints) =
			when (segment) {
				// Horizontal directions
				Cell.Segment.A -> { // Left (-1, 0)
					Triple(cx - arrowSize, cy, cx + arrowSize) to
						Triple(cy, cy - arrowSize, cy)
				}
				Cell.Segment.F -> { // Right (1, 0)
					Triple(cx - arrowSize, cy, cx + arrowSize) to
						Triple(cy, cy - arrowSize, cy)
				}

				// Vertical directions
				Cell.Segment.C -> { // Top (0, -1)
					Triple(cx, cx - arrowSize, cx + arrowSize) to
						Triple(cy - arrowSize, cy + arrowSize, cy + arrowSize)
				}
				Cell.Segment.H -> { // Bottom (0, 1)
					Triple(cx, cx - arrowSize, cx + arrowSize) to
						Triple(cy + arrowSize, cy - arrowSize, cy - arrowSize)
				}

				// Diagonal directions
				Cell.Segment.B -> { // Left-Top (-1, -1)
					Triple(cx - arrowSize, cx, cx + arrowSize) to
						Triple(cy, cy - arrowSize, cy)
				}
				Cell.Segment.D -> { // Left-Bottom (-1, 1)
					Triple(cx - arrowSize, cx, cx + arrowSize) to
						Triple(cy, cy + arrowSize, cy)
				}
				Cell.Segment.E -> { // Right-Top (1, -1)
					Triple(cx - arrowSize, cx, cx + arrowSize) to
						Triple(cy, cy - arrowSize, cy)
				}
				Cell.Segment.G -> { // Right-Bottom (1, 1)
					Triple(cx - arrowSize, cx, cx + arrowSize) to
						Triple(cy, cy + arrowSize, cy)
				}
			}

		// Draw filled triangle
		g.fillPolygon(
			intArrayOf(xPoints.first, xPoints.second, xPoints.third),
			intArrayOf(yPoints.first, yPoints.second, yPoints.third),
			3
		)
	}
}
