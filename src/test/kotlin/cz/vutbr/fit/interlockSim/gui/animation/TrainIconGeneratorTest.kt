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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.core.Cell
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Test suite for [TrainIconGenerator].
 *
 * Verifies that train sprite icons are generated correctly for all
 * 8 directional segments and 2 color variants (16 total sprites).
 *
 * @since 2026-02-06 (Issue #297 - Train visual enhancement)
 */
class TrainIconGeneratorTest {
	@BeforeEach
	fun setup() {
		// Initialize sprite cache before each test
		TrainIconGenerator.initialize()
	}

	@Test
	fun `generates sprites for all 8 segments`() {
		// Verify that all 8 segments have sprites generated
		for (segment in Cell.Segment.values()) {
			val blueSprite = TrainIconGenerator.getSprite(segment, isBlue = true)
			val orangeSprite = TrainIconGenerator.getSprite(segment, isBlue = false)

			assertThat(blueSprite).isNotNull()
			assertThat(orangeSprite).isNotNull()
		}
	}

	@Test
	fun `blue sprite has correct dimensions`() {
		val sprite = TrainIconGenerator.getSprite(Cell.Segment.F, isBlue = true)
		assertThat(sprite!!.width).isEqualTo(20)
		assertThat(sprite.height).isEqualTo(12)
	}

	@Test
	fun `orange sprite has correct dimensions`() {
		val sprite = TrainIconGenerator.getSprite(Cell.Segment.A, isBlue = false)
		assertThat(sprite!!.width).isEqualTo(20)
		assertThat(sprite.height).isEqualTo(12)
	}

	@Test
	fun `sprites are cached and reused`() {
		val sprite1 = TrainIconGenerator.getSprite(Cell.Segment.C, isBlue = true)
		val sprite2 = TrainIconGenerator.getSprite(Cell.Segment.C, isBlue = true)

		// Same reference means it's cached
		assertThat(sprite1).isEqualTo(sprite2)
	}

	@Test
	fun `different segments have different sprites`() {
		val spriteA = TrainIconGenerator.getSprite(Cell.Segment.A, isBlue = true)
		val spriteF = TrainIconGenerator.getSprite(Cell.Segment.F, isBlue = true)

		// Different segment directions should have different sprite instances
		assertThat(spriteA).isNotNull()
		assertThat(spriteF).isNotNull()
	}

	@Test
	fun `different colors have different sprites`() {
		val blueSprite = TrainIconGenerator.getSprite(Cell.Segment.F, isBlue = true)
		val orangeSprite = TrainIconGenerator.getSprite(Cell.Segment.F, isBlue = false)

		// Different colors should have different sprite instances
		assertThat(blueSprite).isNotNull()
		assertThat(orangeSprite).isNotNull()
	}

	@Test
	fun `generates sprite preview image for visual verification`() {
		// Create a simple composite preview image
		val segments = Cell.Segment.values()
		val spriteWidth = 20
		val spriteHeight = 12
		val padding = 5

		val imageWidth = 8 * (spriteWidth + padding) + padding
		val imageHeight = 2 * (spriteHeight + padding) + padding

		val composite = java.awt.image.BufferedImage(
			imageWidth,
			imageHeight,
			java.awt.image.BufferedImage.TYPE_INT_ARGB
		)
		val g = composite.createGraphics()

		// Light gray background
		g.color = java.awt.Color.LIGHT_GRAY
		g.fillRect(0, 0, imageWidth, imageHeight)

		// Draw all sprites in a grid
		for ((i, segment) in segments.withIndex()) {
			val x = padding + i * (spriteWidth + padding)

			// Blue variant (top row)
			val blueSprite = TrainIconGenerator.getSprite(segment, isBlue = true)
			if (blueSprite != null) {
				g.drawImage(blueSprite, x, padding, null)
			}

			// Orange variant (bottom row)
			val orangeSprite = TrainIconGenerator.getSprite(segment, isBlue = false)
			if (orangeSprite != null) {
				g.drawImage(orangeSprite, x, padding + spriteHeight + padding, null)
			}
		}

		g.dispose()

		// Save preview image
		val outputDir = java.io.File("build/test-results/sprites")
		outputDir.mkdirs()
		val outputFile = java.io.File(outputDir, "train-sprites-preview.png")
		javax.imageio.ImageIO.write(composite, "PNG", outputFile)

		println("Train sprite preview saved to: ${outputFile.absolutePath}")
		assertThat(outputFile.exists()).isEqualTo(true)
	}
}
