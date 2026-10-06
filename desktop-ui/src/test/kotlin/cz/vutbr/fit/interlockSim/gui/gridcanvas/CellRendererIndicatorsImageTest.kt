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
import assertk.assertions.isEmpty
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.gui.animation.AnimationColors
import cz.vutbr.fit.interlockSim.gui.animation.AnimationController
import cz.vutbr.fit.interlockSim.gui.animation.AnimationState
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.allPixels
import cz.vutbr.fit.interlockSim.testutil.createDynamicInOut
import cz.vutbr.fit.interlockSim.testutil.createDynamicSwitch
import cz.vutbr.fit.interlockSim.testutil.exactColorPixels
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the Issue #1008 indicators off-screen at the canvas's default 16 px cell size on its black
 * background, with [SimulationCellRenderer] and with [AnimatedSimulationCellRenderer]:
 * every simple switch type in both HORIZONTAL and VERTICAL orientation, in MAIN and BRANCH, unlocked
 * and locked, plus an occupied and a free InOut.
 *
 * Checks: a locked switch shows [AnimationColors.SWITCH_LOCKED] pixels and an unlocked one none; no
 * padlock pixel lies on, or next to, a pixel that either switch configuration draws as track (so the
 * trunk and both legs keep a one-pixel gap); only the occupied InOut shows the occupancy tint.
 *
 * When the system property [PNG_PATH_PROPERTY] names a file, the whole sheet is also written there,
 * scaled up [PNG_SCALE] times with nearest-neighbour sampling, for a visual review. Unset, nothing
 * is written. Sheet layout per renderer (static block on top, animated below): one row per
 * orientation and lock state (HORIZONTAL unlocked, HORIZONTAL locked, VERTICAL unlocked, VERTICAL
 * locked), columns = each switch type in MAIN then BRANCH; a last row holds the occupied and the
 * free InOut.
 */
@DisplayName("Issue #1008 indicators render off-screen")
class CellRendererIndicatorsImageTest {
	private companion object {
		const val PNG_PATH_PROPERTY = "interlockSim.rendererIndicators.pngPath"
		const val CELL = 16
		const val PNG_SCALE = 8
		const val GAP = 4
		val ORIENTATIONS = listOf(Cell.SpatialType.HORIZONTAL, Cell.SpatialType.VERTICAL)
		val TYPES = RailSwitch.Type.entries.filter { it.kind == RailSwitch.Kind.SIMPLE }
		val CONFS = listOf(RailSwitch.Conf.MAIN, RailSwitch.Conf.BRANCH)
		val COLUMNS = TYPES.size * CONFS.size
		val ROWS_PER_RENDERER = ORIENTATIONS.size * 2 + 1
	}

	private val controller = mockk<AnimationController>().also { every { it.currentState } returns AnimationState.EMPTY }
	private val renderers: List<CellRenderer> =
		listOf(SimulationCellRenderer(CELL, CELL), AnimatedSimulationCellRenderer(CELL, CELL, controller))

	private val sheet =
		BufferedImage(COLUMNS * (CELL + GAP), renderers.size * ROWS_PER_RENDERER * (CELL + GAP), BufferedImage.TYPE_INT_RGB)

	private fun newSwitch(
		spatialType: Cell.SpatialType,
		type: RailSwitch.Type,
		conf: RailSwitch.Conf,
		locked: Boolean
	): DynamicRailSwitch =
		createDynamicSwitch(spatialType, type).apply {
			if (conf != this.conf) changeConf()
			if (locked) lock()
		}

	/** Renders [cell] alone into a fresh black cell image and copies it onto the sheet at ([column], [row]). */
	private fun render(
		renderer: CellRenderer,
		cell: Cell,
		column: Int,
		row: Int
	): BufferedImage {
		val image = BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_RGB)
		val g = image.createGraphics()
		try {
			g.color = Color.BLACK
			g.fillRect(0, 0, CELL, CELL)
			g.color = AnimationColors.DEFAULT_TRACK
			renderer.draw(g, cell)
		} finally {
			g.dispose()
		}
		val sheetGraphics = sheet.createGraphics()
		try {
			sheetGraphics.drawImage(image, column * (CELL + GAP), row * (CELL + GAP), null)
		} finally {
			sheetGraphics.dispose()
		}
		return image
	}

	@Test
	fun `padlocks keep clear of every track pixel and only the occupied InOut is tinted`() {
		renderers.forEachIndexed { block, renderer ->
			val firstRow = block * ROWS_PER_RENDERER
			ORIENTATIONS.forEachIndexed { o, spatialType ->
				TYPES.forEachIndexed { t, type ->
					checkSwitch(renderer, spatialType, type, firstRow + 2 * o, t * CONFS.size)
				}
			}
			val inOutRow = firstRow + ORIENTATIONS.size * 2
			val occupied = render(renderer, createDynamicInOut(occupied = true), 0, inOutRow)
			val free = render(renderer, createDynamicInOut(), 1, inOutRow)
			assertThat(exactColorPixels(occupied, AnimationColors.TRACK_OCCUPIED)).isNotEmpty()
			assertThat(exactColorPixels(free, AnimationColors.TRACK_OCCUPIED)).isEmpty()
		}

		System.getProperty(PNG_PATH_PROPERTY)?.let { writeScaled(File(it)) }
	}

	private fun checkSwitch(
		renderer: CellRenderer,
		spatialType: Cell.SpatialType,
		type: RailSwitch.Type,
		unlockedRow: Int,
		firstColumn: Int
	) {
		val track = mutableSetOf<Pair<Int, Int>>()
		CONFS.forEachIndexed { c, conf ->
			val unlocked = render(renderer, newSwitch(spatialType, type, conf, locked = false), firstColumn + c, unlockedRow)
			assertThat(exactColorPixels(unlocked, AnimationColors.SWITCH_LOCKED)).isEmpty()
			track += nonBackgroundPixels(unlocked)
		}
		CONFS.forEachIndexed { c, conf ->
			val locked = render(renderer, newSwitch(spatialType, type, conf, locked = true), firstColumn + c, unlockedRow + 1)
			val mark = exactColorPixels(locked, AnimationColors.SWITCH_LOCKED)
			val label = "${renderer::class.simpleName} $spatialType $type $conf"
			assertThat(mark, label).isNotEmpty()
			// No padlock pixel on, or 8-adjacent to, a pixel either configuration draws as track.
			val touching = mark.filter { (x, y) -> (-1..1).any { dx -> (-1..1).any { dy -> (x + dx to y + dy) in track } } }
			assertThat(touching, label).isEmpty()
			// The trunk itself (rows 7-8 for HORIZONTAL, columns 7-8 for VERTICAL) carries no padlock pixel.
			val onTrunk =
				mark.filter { (x, y) -> (if (spatialType == Cell.SpatialType.HORIZONTAL) y else x) in CELL / 2 - 1..CELL / 2 }
			assertThat(onTrunk, label).isEmpty()
			// Draws nothing besides the track and the mark.
			assertThat(nonBackgroundPixels(locked) - mark.toSet() - track, label).isEmpty()
		}
	}

	private fun nonBackgroundPixels(image: BufferedImage): Set<Pair<Int, Int>> =
		allPixels(image).filter { (x, y) -> image.getRGB(x, y) != Color.BLACK.rgb }.toSet()

	private fun writeScaled(target: File) {
		val scaled = BufferedImage(sheet.width * PNG_SCALE, sheet.height * PNG_SCALE, BufferedImage.TYPE_INT_RGB)
		val g = scaled.createGraphics()
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
			g.drawImage(sheet, 0, 0, scaled.width, scaled.height, null)
		} finally {
			g.dispose()
		}
		target.parentFile?.mkdirs()
		ImageIO.write(scaled, "png", target)
	}
}
