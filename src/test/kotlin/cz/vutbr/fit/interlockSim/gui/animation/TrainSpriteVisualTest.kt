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
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Visual test utility for generating sprite preview images.
 *
 * Creates a composite image showing all 16 train sprite variants
 * (8 directions × 2 colors) for visual verification.
 *
 * @since 2026-02-06 (Issue #297 - Train visual enhancement)
 */
object TrainSpriteVisualTest {
	@JvmStatic
	fun main(args: Array<String>) {
		// Initialize sprite cache
		TrainIconGenerator.initialize()

		// Create composite image showing all sprites
		val segments = Cell.Segment.values()
		val gridWidth = 8 // 8 segments
		val gridHeight = 2 // 2 colors (blue, orange)

		val spriteWidth = 20
		val spriteHeight = 12
		val padding = 10
		val labelHeight = 20

		val imageWidth = gridWidth * (spriteWidth + padding) + padding
		val imageHeight = gridHeight * (spriteHeight + padding + labelHeight) + padding

		val composite = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
		val g = composite.createGraphics()

		// Enable anti-aliasing
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

		// Background
		g.color = Color.LIGHT_GRAY
		g.fillRect(0, 0, imageWidth, imageHeight)

		// Draw sprites
		for ((segmentIndex, segment) in segments.withIndex()) {
			// Blue variant (top row)
			val blueSprite = TrainIconGenerator.getSprite(segment, isBlue = true)
			val blueX = padding + segmentIndex * (spriteWidth + padding)
			val blueY = padding
			if (blueSprite != null) {
				g.drawImage(blueSprite, blueX, blueY, null)
			}

			// Label for blue variant
			g.color = Color.BLACK
			val blueLabelY = blueY + spriteHeight + 12
			g.drawString("${segment.name} (Blue)", blueX, blueLabelY)

			// Orange variant (bottom row)
			val orangeSprite = TrainIconGenerator.getSprite(segment, isBlue = false)
			val orangeX = blueX
			val orangeY = blueY + spriteHeight + padding + labelHeight
			if (orangeSprite != null) {
				g.drawImage(orangeSprite, orangeX, orangeY, null)
			}

			// Label for orange variant
			val orangeLabelY = orangeY + spriteHeight + 12
			g.drawString("${segment.name} (Orange)", orangeX, orangeLabelY)
		}

		g.dispose()

		// Save to file
		val outputDir = File("build/test-results/sprites")
		outputDir.mkdirs()
		val outputFile = File(outputDir, "train-sprites-preview.png")
		ImageIO.write(composite, "PNG", outputFile)

		println("Train sprite preview saved to: ${outputFile.absolutePath}")

		// Also save individual sprites
		for (segment in segments) {
			for ((colorName, isBlue) in listOf("blue" to true, "orange" to false)) {
				val sprite = TrainIconGenerator.getSprite(segment, isBlue)
				if (sprite != null) {
					val file = File(outputDir, "train-${segment.name.lowercase()}-$colorName.png")
					ImageIO.write(sprite, "PNG", file)
				}
			}
		}

		println("Individual sprite images saved to: ${outputDir.absolutePath}")
	}
}
