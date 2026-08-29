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
import cz.vutbr.fit.interlockSim.util.PointF
import kotlin.math.atan2

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
 *     simulationContext.separatorPositionCache
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
	 * Uses the train's entry separator to determine interpolation direction, ensuring
	 * monotonic progression from entry → exit as distance increases. This prevents
	 * visual oscillation that occurs when using unordered endpoints.
	 *
	 * ## Algorithm
	 *
	 * 1. Get entry separator from train (where it entered the section)
	 * 2. Get exit separator via section.getSecondEnd(entry)
	 * 3. Calculate progress ratio: `ratio = distance / sectionLength`
	 * 4. Interpolate position: `position = entry + (exit - entry) * ratio`
	 *
	 * This ensures ratio always increases from 0.0 (at entry) to 1.0 (at exit).
	 *
	 * ## Sub-Pixel Rendering
	 *
	 * Returns continuous floating-point coordinates (PointF) instead of discrete grid cells.
	 * This preserves sub-cell positioning and eliminates visual "jumping" artifacts that
	 * occur when rounding to nearest integer at the grid level. Rounding is deferred to
	 * the rendering layer (pixel level) for smooth train movement.
	 *
	 * ## Edge Cases
	 *
	 * - **No entry separator:** Returns null (cannot determine direction)
	 * - **Progress > section length:** Clamps ratio to 1.0 (end of section)
	 * - **Progress < 0:** Clamps ratio to 0.0 (start of section)
	 * - **Zero-length section:** Returns entry position
	 * - **Null section:** Returns null (cannot calculate)
	 *
	 * @param train Train object to query for entry separator
	 * @param currentSection Track section the train is currently on
	 * @param distanceAlongSection Distance traveled along section in meters
	 * @return Continuous grid coordinates for train rendering, or null if position cannot be calculated
	 */
	fun calculateTrainGridLocation(
		train: cz.vutbr.fit.interlockSim.sim.Train,
		currentSection: TrackSection?,
		distanceAlongSection: Double
	): PointF? {
		// Cannot calculate position without section
		if (currentSection == null) {
			return null
		}

		val sectionLength = currentSection.length()

		// TRANSITION WINDOW FIX (Issue #289):
		// When distanceAlongSection > sectionLength, the train is in a transition window where
		// Train.onNext=true caused getFrontSection() to return the NEXT section before the
		// position was adjusted. The distance is still measured in the OLD section's coordinates.
		// Solution: Clamp to section exit (ratio = 1.0) to prevent visual jumping.
		//
		// This handles the race condition without modifying Train.kt:
		// - Before: section=B, distance=100m (from section A), ratio=100/50=2.0→1.0, renders at exit of B (WRONG)
		// - Fixed: section=B, distance=100m, detected as inconsistent, clamp to exit of B (prevents forward jump)
		if (distanceAlongSection > sectionLength && sectionLength > 0.0) {
			// Distance exceeds section length - train is in transition window
			// Return position at section exit to prevent jumping
			val entrySeparator = train.trainEntrySeparator
			val ends = currentSection.ends()
			if (ends.isEmpty()) {
				return null
			}

			// Use trainEntrySeparator to find the computed exit separator; fall back to arbitrary ends if unavailable (e.g. at spawn).
			val computedExitSeparator = if (entrySeparator != null) currentSection.getSecondEnd(entrySeparator) else null

			// Return null when the entry end has no grid position.
			if (getGridPosition(entrySeparator ?: ends[0]) == null) {
				return null
			}
			val exitPos = getGridPosition(computedExitSeparator ?: ends.getOrElse(1) { ends[0] }) ?: return null

			// Return exit position (ratio = 1.0)
			return PointF(exitPos.x.toFloat(), exitPos.y.toFloat())
		}

		// NORMAL CASE: Calculate interpolated position using entry separator
		val (entryPos, exitPos) = resolveEntryExitPositions(train, currentSection) ?: return null

		// Handle zero-length sections
		if (sectionLength <= 0.0) {
			return PointF(entryPos.x.toFloat(), entryPos.y.toFloat())
		}

		// Calculate progress ratio (clamped to [0.0, 1.0])
		val ratio = (distanceAlongSection / sectionLength).coerceIn(0.0, 1.0)

		// Linear interpolation from entry to exit (ratio=0 → entry, ratio=1 → exit)
		val ratioF = ratio.toFloat()
		val x = entryPos.x.toFloat() + (exitPos.x.toFloat() - entryPos.x.toFloat()) * ratioF
		val y = entryPos.y.toFloat() + (exitPos.y.toFloat() - entryPos.y.toFloat()) * ratioF
		return PointF(x, y)
	}

	/**
	 * Calculate the train's heading (nose direction) from the current section's travel direction.
	 *
	 * The heading is derived from the authoritative entry → exit direction of the track
	 * section the train front occupies, NOT from frame-to-frame position deltas. Since a
	 * train always advances from its entry separator toward the opposite end, this direction
	 * is always "forward" and never reverses at a block boundary. This eliminates the visual
	 * front/tail swap that occurred when the renderer inferred heading from position deltas
	 * that momentarily became zero or slightly negative at segment crossings.
	 *
	 * @param train Train object to query for entry separator
	 * @param currentSection Track section the train front is currently on
	 * @return Heading angle in radians (atan2 of exit − entry), or null if not resolvable
	 *         (e.g. no entry separator yet, missing grid positions, or a zero-length step)
	 */
	fun calculateTrainHeadingRadians(
		train: cz.vutbr.fit.interlockSim.sim.Train,
		currentSection: TrackSection?
	): Double? {
		if (currentSection == null) {
			return null
		}
		val (entryPos, exitPos) = resolveEntryExitPositions(train, currentSection) ?: return null
		val dx = (exitPos.x - entryPos.x).toDouble()
		val dy = (exitPos.y - entryPos.y).toDouble()
		if (dx == 0.0 && dy == 0.0) {
			return null
		}
		return atan2(dy, dx)
	}

	/**
	 * Resolve the entry and exit grid positions of the train on its current section.
	 *
	 * Uses the train's entry separator to orient interpolation so it always progresses
	 * from entry (ratio 0.0) to exit (ratio 1.0). Falls back to the section's declared
	 * end order when the entry separator is unavailable (e.g. at spawn).
	 *
	 * @return Pair of (entryPos, exitPos) grid positions, or null if positions cannot be resolved
	 */
	private fun resolveEntryExitPositions(
		train: cz.vutbr.fit.interlockSim.sim.Train,
		currentSection: TrackSection
	): Pair<Point, Point>? {
		val ends = currentSection.ends()
		if (ends.size < 2) {
			// Section has less than 2 ends - can't interpolate
			return null
		}

		val entrySeparator = train.trainEntrySeparator
		if (entrySeparator == null) {
			// No entry separator available yet - use arbitrary order
			val end0Pos = getGridPosition(ends[0]) ?: return null
			val end1Pos = getGridPosition(ends[1]) ?: return null
			return Pair(end0Pos, end1Pos)
		}

		// Use identity-based comparison (===) to determine interpolation direction.
		// Unwrap dynamic wrappers to static refs, then compare with === to find which
		// end the train entered from — that end becomes the start of interpolation.
		val entryStatic = DynamicWrapperUtils.unwrapToStatic(entrySeparator)
		val end1Static = DynamicWrapperUtils.unwrapToStatic(ends[1])
		val end0GridPos = getGridPosition(ends[0])
		val end1GridPos = getGridPosition(ends[1])

		return if (entryStatic === end1Static) {
			// entrySeparator matches ends[1] - use reversed order
			Pair(end1GridPos ?: return null, end0GridPos ?: return null)
		} else {
			// entrySeparator matches ends[0] (or unmatched fallback) - use normal order
			Pair(end0GridPos ?: return null, end1GridPos ?: return null)
		}
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
	 * **Visibility:** Internal to allow AnimationStateCapture to calculate train direction.
	 *
	 * @param separator PathSeparator to locate in grid (can be dynamic or static)
	 * @return Grid coordinates, or null if not found
	 */
	internal fun getGridPosition(separator: PathSeparator): Point? {
		// Unwrap dynamic wrapper to static reference for cache lookup
		val staticSeparator = DynamicWrapperUtils.unwrapToStatic(separator)
		val result = separatorPositionCache[staticSeparator]

		// Fallback: If not in cache, scan grid (should not happen after optimization)
		if (result == null) {
			val grid = context.getRailWayNetGrid()
			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
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
