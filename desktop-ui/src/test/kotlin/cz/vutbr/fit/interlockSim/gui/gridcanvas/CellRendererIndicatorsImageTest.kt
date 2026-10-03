/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Off-screen render of the Issue #1008 indicators.
 */
package cz.vutbr.fit.interlockSim.gui.gridcanvas

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.gui.animation.AnimationColors
import cz.vutbr.fit.interlockSim.gui.animation.AnimationController
import cz.vutbr.fit.interlockSim.gui.animation.AnimationState
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders a locked switch, an unlocked switch, an occupied InOut, a free InOut and a locked switch
 * set to its branch (the diagonal leg runs towards the padlock corner) off-screen at
 * the canvas's default 16 px cell size — the top row with [SimulationCellRenderer], the bottom row
 * with [AnimatedSimulationCellRenderer] — on the canvas's black background, and checks that only
 * the locked switch shows the padlock colour and only the occupied InOut the occupancy tint.
 *
 * When the system property [PNG_PATH_PROPERTY] names a file, the image is also written there,
 * scaled up [PNG_SCALE] times with nearest-neighbour sampling, for a visual review. Unset, nothing
 * is written.
 */
@DisplayName("Issue #1008 indicators render off-screen")
class CellRendererIndicatorsImageTest {
	private companion object {
		const val PNG_PATH_PROPERTY = "interlockSim.rendererIndicators.pngPath"
		const val CELL = 16
		const val COLUMNS = 5
		const val ROWS = 2
		const val PNG_SCALE = 8
		const val LOCKED_SWITCH = 0
		const val UNLOCKED_SWITCH = 1
		const val OCCUPIED_IN_OUT = 2
		const val FREE_IN_OUT = 3
		const val LOCKED_BRANCH_SWITCH = 4
	}

	private fun newSwitch(): DynamicRailSwitch =
		DynamicRailSwitch(RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE))

	private fun newInOut(occupied: Boolean): DynamicInOut {
		val staticInOut = InOut("Entry", true, Cell.SpatialType.HORIZONTAL)
		val inOut =
			DynamicInOut(
				staticInOut,
				createDynamicInstance(staticInOut.inSemaphore),
				createDynamicInstance(staticInOut.outSemaphore)
			)
		if (!occupied) return inOut
		// setOccupied is internal to :core (InOutWorker owns it), so the GUI test stubs the getter.
		return spyk(inOut).also { every { it.occupied } returns true }
	}

	private fun cells(): List<Cell> =
		listOf(
			newSwitch().apply { lock() },
			newSwitch(),
			newInOut(true),
			newInOut(false),
			newSwitch().apply {
				changeConf()
				lock()
			}
		)

	@Test
	fun `only the locked switch shows the padlock and only the occupied InOut the tint`() {
		val controller = mockk<AnimationController>()
		every { controller.currentState } returns AnimationState.EMPTY
		val renderers =
			listOf(
				SimulationCellRenderer(CELL, CELL),
				AnimatedSimulationCellRenderer(CELL, CELL, controller)
			)

		val image = BufferedImage(COLUMNS * CELL, ROWS * CELL, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		try {
			g.color = Color.BLACK
			g.fillRect(0, 0, image.width, image.height)
			renderers.forEachIndexed { row, renderer ->
				cells().forEachIndexed { column, cell ->
					g.translate(column * CELL, row * CELL)
					g.clipRect(0, 0, CELL, CELL)
					g.color = AnimationColors.DEFAULT_TRACK
					renderer.draw(g, cell)
					g.clip = null
					g.translate(-column * CELL, -row * CELL)
				}
			}
		} finally {
			g.dispose()
		}

		for (row in 0 until ROWS) {
			assertThat(count(image, row, LOCKED_SWITCH, AnimationColors.SWITCH_LOCKED)).isGreaterThan(0)
			assertThat(count(image, row, UNLOCKED_SWITCH, AnimationColors.SWITCH_LOCKED)).isEqualTo(0)
			assertThat(count(image, row, OCCUPIED_IN_OUT, AnimationColors.TRACK_OCCUPIED)).isGreaterThan(0)
			assertThat(count(image, row, FREE_IN_OUT, AnimationColors.TRACK_OCCUPIED)).isEqualTo(0)
			assertThat(count(image, row, LOCKED_BRANCH_SWITCH, AnimationColors.SWITCH_LOCKED)).isGreaterThan(0)
		}

		System.getProperty(PNG_PATH_PROPERTY)?.let { writeScaled(image, File(it)) }
	}

	private fun count(
		image: BufferedImage,
		row: Int,
		column: Int,
		color: Color
	): Int {
		var matches = 0
		for (y in row * CELL until (row + 1) * CELL) {
			for (x in column * CELL until (column + 1) * CELL) {
				if (image.getRGB(x, y) == color.rgb) matches++
			}
		}
		return matches
	}

	private fun writeScaled(
		image: BufferedImage,
		target: File
	) {
		val scaled = BufferedImage(image.width * PNG_SCALE, image.height * PNG_SCALE, BufferedImage.TYPE_INT_RGB)
		val g = scaled.createGraphics()
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
			g.drawImage(image, 0, 0, scaled.width, scaled.height, null)
		} finally {
			g.dispose()
		}
		target.parentFile?.mkdirs()
		ImageIO.write(scaled, "png", target)
	}
}
