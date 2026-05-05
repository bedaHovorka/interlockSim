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
import cz.vutbr.fit.interlockSim.util.PointF
import io.github.oshai.kotlinlogging.KotlinLogging
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

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
 * 1. AnimationController captures state from SimulationContext (kDisco thread)
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
	private val previousTrainLocations = mutableMapOf<Int, PointF>()
	private val previousTrainHeadings = mutableMapOf<Int, Double>()
	private val baseTrainShapeCache = mutableMapOf<TrainShapeKey, Shape>()
	private val rotatedTrainShapeCache = mutableMapOf<TrainRotationKey, Shape>()
	private val trainTranslationTransform = AffineTransform()

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
		drawAnimatedInOut(g, cell.direction())
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
		drawAnimatedInOut(g, cell.staticRef.direction())
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
		val activeSegments =
			if (capturedState != null) {
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

	private fun drawAnimatedInOut(
		g: Graphics2D,
		direction: Cell.Segment
	) {
		// Keep the entry/exit connection visible without reusing the legacy center circle
		// that looked like the previous train marker in animated mode.
		g.color = AnimationColors.DEFAULT_TRACK
		drawSegments(g, direction)
	}

	/**
	 * Draw a train overlay on the canvas.
	 *
	 * Trains are rendered as directional locomotive-like markers with white ID numbers
	 * and black borders overlaid on top of the grid cells. This method should be called
	 * after all grid cells have been rendered.
	 *
	 * ## Visual Design
	 *
	 * - **Train body:** Locomotive silhouette with cab, body, and pointed nose aligned to train movement
	 * - **Origin-based colors:** Blue for trains from InOut B, orange for trains from InOut A
	 * - **Border:** Black stroke with width derived from train height and clamped for visibility
	 * - **Train ID:** White text centered in the locomotive body
	 * - **Multiple trains:** Positioned at different grid locations (no overlap if on different sections)
	 * - **Color persistence:** Trains maintain their origin color throughout their entire journey
	 *
	 * ## Coordinate System
	 *
	 * The graphics context should already be translated so that (0,0) represents
	 * the pixel coordinates for grid cell (0,0). Train grid locations from
	 * [TrainState.frontGridLocation] are converted to pixel coordinates using
	 * cell width and height.
	 *
	 * @param g Graphics context for rendering (must be Graphics2D)
	 * @param trainState Immutable train state snapshot with position, ID, and direction
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

		// Convert grid coordinates to pixel coordinates (center of cell).
		// PointF provides continuous coordinates, round to nearest pixel for rendering.
		val cellWidthHalf = cellWidth / 2.0
		val cellHeightHalf = cellHeight / 2.0
		val pixelX = (gridLocation.x * cellWidth + cellWidthHalf).roundToInt()
		val pixelY = (gridLocation.y * cellHeight + cellHeightHalf).roundToInt()
		val minCellSize = minOf(cellWidth, cellHeight).coerceAtLeast(1)
		val trainHeight = maxOf(MIN_TRAIN_HEIGHT_PIXELS, (minCellSize * TRAIN_HEIGHT_CELL_RATIO).roundToInt())
		val bodyLength =
			maxOf(
				trainHeight + MIN_BODY_LENGTH_EXTRA_PIXELS,
				(minCellSize * BODY_LENGTH_CELL_RATIO).roundToInt()
			)
		val noseLength = maxOf(MIN_NOSE_LENGTH_PIXELS, trainHeight / 2)
		val borderWidth = maxOf(1, trainHeight / 5)
		val heading = resolveTrainHeading(trainState.trainNumber, gridLocation)
		val trainShape = createTrainShape(pixelX, pixelY, bodyLength, trainHeight, noseLength, heading)

		// Select body color based on origin InOut
		val bodyColor =
			if (trainState.travelingRight) {
				AnimationColors.TRAIN_FROM_B // Blue (InOut B)
			} else {
				AnimationColors.TRAIN_FROM_A // Orange (InOut A)
			}

		// Draw train body.
		val oldStroke = g.stroke
		val oldFont = g.font
		val oldAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
		g.color = bodyColor
		g.fill(trainShape)

		// Draw black border.
		g.color = AnimationColors.TRAIN_BORDER
		g.stroke = BasicStroke(borderWidth.toFloat())
		g.draw(trainShape)

		// Draw train ID centered in the body while keeping text horizontal for readability.
		g.color = AnimationColors.TRAIN_ID
		g.font = g.font.deriveFont(maxOf(8f, trainHeight * 0.8f))
		val idText = trainState.trainNumber.toString()
		val fontMetrics = g.fontMetrics
		val textWidth = fontMetrics.stringWidth(idText)
		val textHeight = fontMetrics.ascent
		val textOffset = bodyLength * BODY_TEXT_OFFSET_RATIO + noseLength * NOSE_TEXT_OFFSET_RATIO
		val textCenterX = pixelX - cos(heading) * textOffset
		val textCenterY = pixelY - sin(heading) * textOffset

		g.drawString(
			idText,
			(textCenterX - textWidth / 2).roundToInt(),
			(textCenterY + textHeight / 2 - 1).roundToInt()
		)

		g.stroke = oldStroke
		g.font = oldFont
		val antialiasingToRestore = oldAntialiasing ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antialiasingToRestore)
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
		previousTrainLocations.keys.retainAll(state.trainStates.keys)
		previousTrainHeadings.keys.retainAll(state.trainStates.keys)

		// Render each train
		for ((_, trainState) in state.trainStates) {
			drawTrain(g, trainState, cellWidth, cellHeight)
		}
	}

	private fun resolveTrainHeading(
		trainNumber: Int,
		currentLocation: PointF
	): Double {
		val previousLocation = previousTrainLocations[trainNumber]
		val heading =
			previousLocation?.let { inferHeading(it, currentLocation) }
				?: previousTrainHeadings[trainNumber]
				?: DEFAULT_TRAIN_HEADING

		previousTrainLocations[trainNumber] = currentLocation
		previousTrainHeadings[trainNumber] = heading

		return heading
	}

	private fun inferHeading(
		previousLocation: PointF,
		currentLocation: PointF
	): Double? {
		val dx = (currentLocation.x - previousLocation.x).toDouble()
		val dy = (currentLocation.y - previousLocation.y).toDouble()
		if (abs(dx) < HEADING_EPSILON && abs(dy) < HEADING_EPSILON) {
			return null
		}

		return snapHeadingToNearestCompassDirection(atan2(dy, dx))
	}

	private fun snapHeadingToNearestCompassDirection(angle: Double): Double =
		round(angle / SEGMENT_ANGLE_STEP) * SEGMENT_ANGLE_STEP

	private fun createTrainShape(
		pixelX: Int,
		pixelY: Int,
		bodyLength: Int,
		trainHeight: Int,
		noseLength: Int,
		heading: Double
	): Shape {
		val rotatedShape = getRotatedTrainShape(bodyLength, trainHeight, noseLength, heading)
		trainTranslationTransform.setToIdentity()
		trainTranslationTransform.translate(pixelX.toDouble(), pixelY.toDouble())
		return trainTranslationTransform.createTransformedShape(rotatedShape)
	}

	private fun getRotatedTrainShape(
		bodyLength: Int,
		trainHeight: Int,
		noseLength: Int,
		heading: Double
	): Shape {
		val shapeKey = TrainShapeKey(bodyLength, trainHeight, noseLength)
		val rotationKey = TrainRotationKey(shapeKey, heading)
		return rotatedTrainShapeCache.getOrPut(rotationKey) {
			val baseShape = getBaseTrainShape(shapeKey)
			AffineTransform.getRotateInstance(heading).createTransformedShape(baseShape)
		}
	}

	private fun getBaseTrainShape(shapeKey: TrainShapeKey): Shape =
		baseTrainShapeCache.getOrPut(shapeKey) {
			val halfHeight = shapeKey.trainHeight / 2.0
			val cabHeight = shapeKey.trainHeight * CAB_HEIGHT_RATIO
			val cabHalfHeight = cabHeight / 2.0
			// bodyLength represents the full rectangular locomotive body behind the nose.
			// Split that footprint into a shorter, lower rear cab and a longer, taller main body section.
			val cabLength = shapeKey.bodyLength * CAB_LENGTH_RATIO
			val rearX = -(shapeKey.bodyLength + shapeKey.noseLength).toDouble()
			val cabFrontX = rearX + cabLength
			val noseBaseX = -shapeKey.noseLength.toDouble()

			Path2D.Double().apply {
				moveTo(rearX, -cabHalfHeight)
				lineTo(cabFrontX, -cabHalfHeight)
				lineTo(cabFrontX, -halfHeight)
				lineTo(noseBaseX, -halfHeight)
				lineTo(0.0, 0.0)
				lineTo(noseBaseX, halfHeight)
				lineTo(cabFrontX, halfHeight)
				lineTo(cabFrontX, cabHalfHeight)
				lineTo(rearX, cabHalfHeight)
				closePath()
			}
		}

	private data class TrainShapeKey(
		val bodyLength: Int,
		val trainHeight: Int,
		val noseLength: Int
	)

	private data class TrainRotationKey(
		val shapeKey: TrainShapeKey,
		val heading: Double
	)

	private companion object {
		const val HEADING_EPSILON = 0.001
		const val DEFAULT_TRAIN_HEADING = 0.0
		const val SEGMENT_ANGLE_STEP = PI / 4.0
		const val TRAIN_HEIGHT_CELL_RATIO = 0.55
		const val BODY_LENGTH_CELL_RATIO = 0.9
		const val MIN_TRAIN_HEIGHT_PIXELS = 8
		const val MIN_BODY_LENGTH_EXTRA_PIXELS = 6
		const val MIN_NOSE_LENGTH_PIXELS = 4
		const val BODY_TEXT_OFFSET_RATIO = 0.55
		const val NOSE_TEXT_OFFSET_RATIO = 0.2
		// Geometry ratios chosen by visual tuning so the marker reads as a locomotive at 16-20 px cell sizes.
		const val CAB_LENGTH_RATIO = 0.32 // Rear cab takes roughly one third of the rectangular body length.
		const val CAB_HEIGHT_RATIO = 0.6 // Reduced cab height keeps a stronger visible step from cab to main body.
	}
}
