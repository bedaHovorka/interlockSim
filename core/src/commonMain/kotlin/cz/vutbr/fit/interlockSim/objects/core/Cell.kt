/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.core

import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.util.Point
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Cell in Grid
 *
 */
interface Cell {
	/**
	 * Topology joins with other cells
	 *
	 * for drawing cells - "Segment analogy from displays"
	 */
	enum class Segment(
		val dx: Int,
		val dy: Int
	) {
		/**
		 * left middle
		 */
		A(-1, 0),

		/**
		 * left top
		 */
		B(-1, -1),

		/**
		 * center top
		 */
		C(0, -1),

		/**
		 * left bottom
		 */
		D(-1, 1),

		/**
		 * right top
		 */
		E(1, -1),

		/**
		 * right middle
		 */
		F(1, 0),

		/**
		 * right bottom
		 */
		G(1, 1),

		/**
		 * center bottom
		 */
		H(0, 1);

		/**
		 * @return 0 - left, 0.5f - center, 1 - right
		 */
		fun getRx(): Float = d2r(dx)

		/**
		 * @return 0 - top, 0.5f - middle, 1 - bottom
		 */
		fun getRy(): Float = d2r(dy)

		/**
		 * @param from
		 * @return neighbour Point
		 */
		fun transform(from: Point): Point {
			val tr = Point(from.x + dx, from.y + dy)
			requireValidState(from != tr) { "Transformed point must be different from original: segment=$this" }
			return tr
		}
	}

	/**
	 * "Natoceni bunky" - "pro prehlednednejsi vytvareni bunek"
	 */
	enum class SpatialType(
		val segments: Array<Segment>
	) {
		// !!! prvni segment pro false orientaci !!!

		/**
		 *
		 */
		VERTICAL(arrayOf(Segment.H, Segment.C)),

		/**
		 *
		 */
		HORIZONTAL(arrayOf(Segment.F, Segment.A)),

		/**
		 *
		 */
		DIAGONAL1(arrayOf(Segment.E, Segment.D)), //  /

		/**
		 *
		 */
		DIAGONAL2(arrayOf(Segment.G, Segment.B)) //  \
	}

	/**
	 *
	 * @return {@link SpatialType}
	 */
	fun getSpatialType(): SpatialType?

	/**
	 * @return Possible joins
	 */
	fun joins(): Set<Segment>
}

/**
 * conversion
 * @param d
 * @return converted number
 */
fun d2r(d: Int): Float = (d + 1) * 0.5f

/**
 * conversion
 * @param r
 * @return converted number
 */
fun r2d(r: Float): Int = (2 * r - 1).toInt()

/**
 * This is from d-coordinates conversion
 * @param dx
 * @param dy
 * @return segment
 */
fun segmentFor(
	dx: Int,
	dy: Int
): Cell.Segment? =
	when (dx.sign to dy.sign) {
		-1 to -1 -> Cell.Segment.B
		-1 to 0 -> Cell.Segment.A
		-1 to 1 -> Cell.Segment.D
		0 to -1 -> Cell.Segment.C
		0 to 0 -> null
		0 to 1 -> Cell.Segment.H
		1 to -1 -> Cell.Segment.E
		1 to 0 -> Cell.Segment.F
		1 to 1 -> Cell.Segment.G
		else -> null // unreachable: Int.sign is -1, 0, or 1
	}

/**
 * if segments can consist in regular cell
 * @param a
 * @param b
 * @return if segments is good pair
 */
fun conflict(
	a: Cell.Segment?,
	b: Cell.Segment?
): Boolean {
	if (a == b) return true
	if (a == null || b == null) return false
	val dx = a.dx - b.dx
	val dy = a.dy - b.dy
	if (sqrt((dx * dx + dy * dy).toDouble()) <= 0.5) return true
	return false
}

/**
 * @param segment
 * @return segment in reverse direction
 */
fun anti(segment: Cell.Segment): Cell.Segment {
	val anti = segmentFor(-segment.dx, -segment.dy)
	requireValidState(anti != null) { "Could not find anti-segment for $segment" }
	return anti
}
