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

import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.util.DynamicWrapperUtils
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.math.roundToInt

/**
 * Utility for calculating train grid positions via linear interpolation.
 *
 * Trains move continuously along track sections between grid cells (PathSeparators).
 * This calculator interpolates the train's position along a track section to determine
 * the grid coordinates for rendering.
 *
 * ## Position Calculation Algorithm
 *
 * Given:
 * - Current [TrackSection] the train is on
 * - Distance traveled along that section (in meters)
 * - Section length (in meters)
 *
 * Calculate:
 * 1. **Progress ratio:** `ratio = distance / sectionLength`
 * 2. **Start point:** Grid coordinates of section's start PathSeparator
 * 3. **End point:** Grid coordinates of section's end PathSeparator
 * 4. **Interpolated position:** `gridPos = start + (end - start) * ratio`
 *
 * ## Performance Optimization
 *
 * Uses pre-built cache from DefaultSimulationContext for O(1) PathSeparator position lookups,
 * avoiding O(n²) grid scans at 30 FPS rendering rate (2,500× faster for 50×50 grid).
 *
 * ## Grid Coordinate System
 *
 * - **Origin:** Top-left corner (0, 0)
 * - **X-axis:** Increases rightward
 * - **Y-axis:** Increases downward
 * - **Coordinates:** Integer grid cell indices
 *
 * ## Usage
 *
 * ```kotlin
 * val calculator = TrainPositionCalculator(
 *     simulationContext,
 *     simulationContext.getSeparatorPositionCache()
 * )
 * val gridLocation = calculator.calculateTrainGridLocation(
 *     currentSection = train.getCurrentSection(),
 *     distanceAlongSection = 45.0 // meters
 * )
 * // gridLocation is a Point with interpolated coordinates
 * ```
 *
 * ## Thread Safety
 *
 * This calculator accesses simulation state and must be called from a thread-safe context
 * (typically after marshaling to EDT via SwingUtilities.invokeLater).
 *
 * @property context Simulation context for accessing grid and network data
 * @property separatorPositionCache Cache mapping PathSeparators to grid Points for O(1) lookups
 *
 * @see TrainState
 * @see AnimationStateCapture
 *
 * @since 2026-01-22 (Issue #203)
 */
class TrainPositionCalculator(
	private val context: SimulationContext,
	private val separatorPositionCache: Map<PathSeparator, Point>
) {

	/**
	 * Calculate train's grid location via linear interpolation along a track section.
	 *
	 * Interpolates between the two endpoints of the track section based on the
	 * distance traveled. Since trains always travel from the start of a section
	 * toward the end, we use the two endpoints and the progress ratio.
	 *
	 * ## Algorithm
	 *
	 * 1. Get both endpoints of the track section
	 * 2. Calculate progress ratio: `ratio = distance / sectionLength`
	 * 3. Interpolate position: `position = end1 + (end2 - end1) * ratio`
	 *
	 * **Note:** The direction (which end is "start" vs "end") is determined by
	 * the train's movement through the railway network, but for grid interpolation
	 * purposes, we can use either end as the base since the ratio indicates progress.
	 *
	 * ## Edge Cases
	 *
	 * - **Progress > section length:** Clamps ratio to 1.0 (end of section)
	 * - **Progress < 0:** Clamps ratio to 0.0 (start of section)
	 * - **Zero-length section:** Returns first endpoint position
	 * - **Null section:** Returns null (cannot calculate)
	 *
	 * ## Coordinate Rounding
	 *
	 * Grid coordinates are rounded to nearest integer for pixel-perfect rendering.
	 *
	 * @param currentSection Track section the train is currently on
	 * @param distanceAlongSection Distance traveled along section in meters
	 * @return Grid coordinates for train rendering, or null if position cannot be calculated
	 */
	fun calculateTrainGridLocation(
		currentSection: TrackSection?,
		distanceAlongSection: Double
	): Point? {
		// Cannot calculate position without section
		if (currentSection == null) {
			return null
		}

		val sectionLength = currentSection.length()

		// Get both endpoints of the section
		val ends = currentSection.ends()
		if (ends.size != 2) {
			return null // Invalid track section
		}

		val end1Pos = getGridPosition(ends[0]) ?: return null
		val end2Pos = getGridPosition(ends[1]) ?: return null

		// Handle zero-length sections
		if (sectionLength <= 0.0) {
			return end1Pos
		}

		// Calculate progress ratio (clamped to [0.0, 1.0])
		val ratio = (distanceAlongSection / sectionLength).coerceIn(0.0, 1.0)

		// Linear interpolation from end1 to end2
		val interpolatedX = end1Pos.x + (end2Pos.x - end1Pos.x) * ratio
		val interpolatedY = end1Pos.y + (end2Pos.y - end1Pos.y) * ratio

		// Round to nearest integer for grid coordinates
		return Point(interpolatedX.roundToInt(), interpolatedY.roundToInt())
	}

	/**
	 * Get grid position of a PathSeparator.
	 *
	 * Uses pre-built cache from DefaultSimulationContext for O(1) lookup
	 * instead of O(n²) grid scan. This method is called twice per train per frame
	 * at 30 FPS, so performance is critical (2,500× faster for 50×50 grid).
	 *
	 * **Dynamic Wrapper Handling:** The separator parameter may be a dynamic wrapper
	 * (DynamicRailSemaphore, DynamicInOut). This method unwraps to static reference
	 * before cache lookup.
	 *
	 * @param separator PathSeparator to locate in grid (can be dynamic or static)
	 * @return Grid coordinates, or null if not found
	 */
	private fun getGridPosition(separator: PathSeparator): Point? {
		// Unwrap dynamic wrapper to static reference for cache lookup
		val staticSeparator = DynamicWrapperUtils.unwrapToStatic(separator)
		val result = separatorPositionCache[staticSeparator]

		// Fallback: If not in cache, scan grid (should not happen after optimization)
		if (result == null) {
			val grid = context.getRailWayNetGrid()
			for (x in 0 until grid.getCols()) {
				for (y in 0 until grid.getRows()) {
					val cell = grid.getCellAt(x, y)

					// Direct identity match
					if (cell === staticSeparator) {
						return Point(x, y)
					}

					// Also check if this is a PathSeparator that equals the target
					if (cell is PathSeparator && staticSeparator is PathSeparator) {
						val unwrappedCell = DynamicWrapperUtils.unwrapToStatic(cell)
						if (unwrappedCell === staticSeparator) {
							return Point(x, y)
						}
					}
				}
			}
		}

		return result
	}
}
