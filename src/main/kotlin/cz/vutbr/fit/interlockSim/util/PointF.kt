package cz.vutbr.fit.interlockSim.util

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Floating-point 2D point for sub-pixel positioning.
 *
 * Used by animation system to preserve continuous train positions
 * and avoid rounding artifacts during interpolation.
 *
 * @property x X-coordinate (continuous, can be fractional)
 * @property y Y-coordinate (continuous, can be fractional)
 */
data class PointF(
	val x: Float,
	val y: Float
) {
	/**
	 * Convert to integer Point by rounding coordinates.
	 */
	fun toPoint(): Point = Point(x.roundToInt(), y.roundToInt())

	/**
	 * Convert to integer Point by flooring coordinates.
	 */
	fun toPointFloor(): Point = Point(x.toInt(), y.toInt())

	/**
	 * Calculate Euclidean distance to another point.
	 *
	 * Used for continuity checking in train animation to detect visual jumps
	 * between consecutive frames.
	 *
	 * @param other Target point
	 * @return Euclidean distance in grid cells
	 */
	fun distanceTo(other: PointF): Float {
		val dx = this.x - other.x
		val dy = this.y - other.y
		return sqrt(dx * dx + dy * dy)
	}
}
