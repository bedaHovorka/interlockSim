/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.gui.gridcanvas

import cz.vutbr.fit.interlockSim.gui.animation.AnimationColors
import cz.vutbr.fit.interlockSim.gui.animation.AnimationController
import cz.vutbr.fit.interlockSim.gui.animation.TrainState
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.Cell
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.Graphics2D

private val logger = KotlinLogging.logger {}

/**
 * Cell renderer for animated simulation with state-based coloring.
 *
 * Extends [SimulationCellRenderer] to add visual animation state from [AnimationController],
 * rendering railway track blocks and semaphore signals with colors based on their current state:
 *
 * ## Track Block Colors (standard railway convention)
 * - **FREE** → Gray (0x808080) - Available for path setup
 * - **RESERVED** → Yellow (0xFFFF00) - Path set up, train approaching
 * - **OCCUPIED** → Red (0xFF0000) - Train currently on block
 *
 * ## Semaphore Signal Colors (standard railway convention)
 * - **STOP** (Hp0) → Red (0xFF0000) - Train must stop
 * - **S40** (Hp2) → Yellow (0xFFFF00) - Proceed at 40 km/h
 * - **All other allowing signals** (S30, S60, S80, S100, FREE) → Green (0x00FF00) - Proceed
 *
 * ## Architecture
 *
 * This renderer overrides the draw() methods for [TrackBlockPart] and [DynamicRailSemaphore]
 * to inject state-based colors before delegating to the parent class for geometry rendering.
 *
 * The rendering pipeline:
 * ```
 * 1. AnimationController captures state from SimulationContext (jDisco thread)
 * 2. State marshaled to EDT via SwingUtilities.invokeLater
 * 3. Swing Timer triggers repaint() at 30 FPS (on EDT)
 * 4. This renderer queries AnimationController.getCurrentState() (on EDT)
 * 5. Color set via Graphics2D.color based on state
 * 6. Parent renderer draws geometry with the set color
 * ```
 *
 * ## Thread Safety
 *
 * All state reads happen on Swing EDT:
 * - [AnimationController.getCurrentState] is EDT-confined
 * - Graphics2D operations are inherently EDT-only
 * - No synchronization needed (single-threaded rendering)
 *
 * ## Performance
 *
 * - **Color lookup:** O(1) HashMap operations per cell
 * - **Frame budget:** 33ms for 30 FPS
 * - **Expected overhead:** ~20μs for 100 cells (0.06% of frame budget)
 *
 * ## Usage
 *
 * ```kotlin
 * val animationController = AnimationController(simulationContext, canvas)
 * val renderer = AnimatedSimulationCellRenderer(cellWidth, cellHeight, animationController)
 * canvas.setCellRenderer(renderer)
 * animationController.start()
 * ```
 *
 * @property cellWidth Width of each grid cell in pixels
 * @property cellHeight Height of each grid cell in pixels
 * @property animationController Controller providing current animation state
 *
 * @see AnimationController
 * @see AnimationColors
 * @see SimulationCellRenderer
 *
 * @since 2026-01-22 (Issue #202)
 */
class AnimatedSimulationCellRenderer(
	cellWidth: Int,
	cellHeight: Int,
	private val animationController: AnimationController
) : SimulationCellRenderer(cellWidth, cellHeight) {

	/**
	 * Render track block part with occupancy state coloring.
	 *
	 * Queries the current animation state to determine the track block's state
	 * (FREE/RESERVED/OCCUPIED) and sets the graphics color accordingly before
	 * delegating to the parent renderer for geometry drawing.
	 *
	 * **Fallback behavior:** If the track block state is not available in the
	 * animation state (e.g., during initialization or for untracked blocks),
	 * uses [AnimationColors.DEFAULT_TRACK] (light gray).
	 *
	 * @param g Graphics context for rendering
	 * @param cell Track block part cell to render
	 */
	override fun draw(
		g: Graphics2D,
		cell: TrackBlockPart
	) {
		val state = animationController.getCurrentState()
		val trackBlock = cell.getTrackBlock()
		val trackState = state.trackStates[trackBlock]

		// Set color based on track state (or default if not available)
		g.color = trackState?.let {
			AnimationColors.forTrackState(it.state)
		} ?: AnimationColors.DEFAULT_TRACK

		// Delegate to parent for geometry rendering
		super.draw(g, cell)
	}

	/**
	 * Render semaphore signal with signal state coloring.
	 *
	 * Queries the current animation state to determine the semaphore's signal
	 * (STOP/S40/allowing) and sets the graphics color accordingly before
	 * delegating to the parent renderer for geometry drawing.
	 *
	 * **Fallback behavior:** If the signal state is not available in the
	 * animation state (e.g., during initialization or for untracked semaphores),
	 * uses [AnimationColors.DEFAULT_SIGNAL] (light gray).
	 *
	 * @param g Graphics context for rendering
	 * @param cell Dynamic rail semaphore cell to render
	 */
	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSemaphore
	) {
		val state = animationController.getCurrentState()
		val staticSemaphore = cell.staticRef
		val signalState = state.signalStates[staticSemaphore]

		// Set color based on signal state (or default if not available)
		g.color = signalState?.let {
			AnimationColors.forSignal(it.signal)
		} ?: AnimationColors.DEFAULT_SIGNAL

		// Delegate to parent for geometry rendering
		super.draw(g, cell)
	}

	/**
	 * Render static InOut (entry/exit point) with light gray color.
	 *
	 * InOut cells represent connections to the external railway network.
	 * Rendered in light gray for consistency.
	 *
	 * @param g Graphics context for rendering
	 * @param cell Static InOut cell to render
	 */
	override fun draw(
		g: Graphics2D,
		cell: InOut
	) {
		// Set color to light gray for InOut cells
		g.color = AnimationColors.DEFAULT_TRACK

		// Delegate to parent for geometry rendering
		super.draw(g, cell)
	}

	/**
	 * Render InOut (entry/exit point) with light gray color.
	 *
	 * InOut cells represent connections to the external railway network
	 * and do not have dynamic state (no occupation tracking). They are
	 * rendered in light gray to distinguish them from track blocks.
	 *
	 * @param g Graphics context for rendering
	 * @param cell Dynamic InOut cell to render
	 */
	override fun draw(
		g: Graphics2D,
		cell: DynamicInOut
	) {
		// Set color to light gray for InOut cells
		g.color = AnimationColors.DEFAULT_TRACK

		// Delegate to parent for geometry rendering
		super.draw(g, cell)
	}

	/**
	 * Render railway switch showing the historically active direction from captured state.
	 *
	 * Uses the switch configuration from the current animation state snapshot rather than
	 * the live cell state. This ensures that during playback, switches display their
	 * historical positions (MAIN or BRANCH) as they were during the recorded simulation.
	 *
	 * Only the active path (based on captured conf) is drawn. Inactive directions
	 * are not rendered, providing a clear indication of which route was set through
	 * the switch at that moment in time.
	 *
	 * **Fallback behavior:** If switch state is not available in the animation state
	 * (e.g., during initialization), falls back to using the current cell configuration.
	 *
	 * @param g Graphics context for rendering
	 * @param cell Dynamic rail switch cell to render
	 */
	override fun draw(
		g: Graphics2D,
		cell: DynamicRailSwitch
	) {
		val state = animationController.getCurrentState()
		val staticSwitch = cell.staticRef
		val capturedState = state.switchStates[staticSwitch]

		// Use captured state if available, otherwise fall back to current state
		val activeSegments = if (capturedState != null) {
			// Use historical state from animation
			getActiveSegmentsForConf(staticSwitch, capturedState.conf)
		} else {
			// Fall back to current state (should not happen during playback)
			logger.trace { "No captured state for switch ${staticSwitch.getName()}, using current conf" }
			cell.getActiveSegments()
		}

		// Draw only the active direction (inherits graphics context color)
		drawSegments(g, *activeSegments.toTypedArray())
	}

	/**
	 * Get active segments for a railway switch with a specific configuration.
	 *
	 * Queries the static switch topology to find which segments are connected
	 * based on the given configuration (MAIN or BRANCH).
	 *
	 * This is similar to [DynamicRailSwitch.getActiveSegments] but takes an
	 * explicit configuration parameter rather than using the cell's current state.
	 *
	 * @param railSwitch Static switch configuration
	 * @param conf Configuration to query (MAIN or BRANCH)
	 * @return Set of 2 segments forming the active path for the given configuration
	 * @throws IllegalStateException if no segments found for the configuration
	 */
	private fun getActiveSegmentsForConf(
		railSwitch: RailSwitch,
		conf: RailSwitch.Conf
	): Set<Cell.Segment> {
		// Iterate through all segments that join in this switch
		for (segment in railSwitch.joins()) {
			// Get all edges connected to this segment
			val joinedEdges = railSwitch.confs.getJoinedNodesAndEdges(segment)

			// Search for the edge with value matching the given conf
			// Use explicit cast similar to DynamicRailSwitch.getActiveSegments()
			for (e in (joinedEdges as Map<*, *>).entries) {
				@Suppress("UNCHECKED_CAST")
				val entry = e as Map.Entry<Cell.Segment, RailSwitch.Conf>
				if (entry.value == conf) {
					return setOf(segment, entry.key)
				}
			}
		}

		// This should never happen if the switch is properly initialized
		throw IllegalStateException(
			"No segments found for configuration $conf in switch ${railSwitch.getName()}. " +
				"This indicates a corrupted switch topology."
		)
	}

	/**
	 * Draw a train overlay on the canvas.
	 *
	 * Trains are rendered as blue circles with white ID numbers overlaid on top
	 * of the grid cells. This method should be called after all grid cells have
	 * been rendered.
	 *
	 * ## Visual Design
	 *
	 * - **Train body:** Blue circle (6x6 pixels) at interpolated grid position
	 * - **Train ID:** White text centered in the circle
	 * - **Multiple trains:** Positioned at different grid locations (no overlap if on different sections)
	 *
	 * ## Coordinate System
	 *
	 * The graphics context should already be translated so that (0,0) represents
	 * the pixel coordinates for grid cell (0,0). Train grid locations from
	 * [TrainState.frontGridLocation] are converted to pixel coordinates using
	 * cell width and height.
	 *
	 * @param g Graphics context for rendering (must be Graphics2D)
	 * @param trainState Immutable train state snapshot with position and ID
	 * @param cellWidth Width of each grid cell in pixels
	 * @param cellHeight Height of each grid cell in pixels
	 */
	fun drawTrain(
		g: Graphics2D,
		trainState: TrainState,
		cellWidth: Int,
		cellHeight: Int
	) {
		val gridLocation = trainState.frontGridLocation ?: return

		// Convert grid coordinates to pixel coordinates (center of cell)
		val pixelX = gridLocation.x * cellWidth + cellWidth / 2
		val pixelY = gridLocation.y * cellHeight + cellHeight / 2

		// Train body: blue circle
		g.color = AnimationColors.TRAIN_BODY
		val trainSize = 6 // 6x6 pixel circle
		g.fillOval(
			pixelX - trainSize / 2,
			pixelY - trainSize / 2,
			trainSize,
			trainSize
		)

		// Train ID: white text
		g.color = AnimationColors.TRAIN_ID
		val idText = trainState.trainNumber.toString()
		val fontMetrics = g.fontMetrics
		val textWidth = fontMetrics.stringWidth(idText)
		val textHeight = fontMetrics.ascent

		// Center text in the circle
		g.drawString(
			idText,
			pixelX - textWidth / 2,
			pixelY + textHeight / 2 - 1 // Adjust for baseline
		)
	}

	/**
	 * Draw all trains as overlays on the canvas.
	 *
	 * Renders all trains from the current animation state on top of the grid cells.
	 * This method should be called after all grid cells have been rendered.
	 *
	 * ## Multiple Train Handling
	 *
	 * Trains at different grid locations render without overlap. Trains at the
	 * same grid location (rare, usually during transition between cells) will
	 * overlap, with the last-rendered train on top.
	 *
	 * @param g Graphics context for rendering (must be Graphics2D)
	 * @param cellWidth Width of each grid cell in pixels
	 * @param cellHeight Height of each grid cell in pixels
	 */
	fun drawAllTrains(
		g: Graphics2D,
		cellWidth: Int,
		cellHeight: Int
	) {
		val state = animationController.getCurrentState()

		// Render each train
		for ((_, trainState) in state.trainStates) {
			drawTrain(g, trainState, cellWidth, cellHeight)
		}
	}
}
