/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.objects.cells

import cz.vutbr.fit.interlockSim.util.Point
import java.util.Set

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
			assert(from != null)
			val tr = from.clone() as Point
			tr.x += dx
			tr.y += dy
			assert(!from.equals(tr)) { this }
			return tr
		}

		companion object {
			/**
			 * conversion
			 * @param d
			 * @return converted number
			 */
			@JvmStatic
			fun d2r(d: Int): Float = (d + 1) * 0.5f

			/**
			 * conversion
			 * @param r
			 * @return converted number
			 */
			@JvmStatic
			fun r2d(r: Float): Int = (2 * r - 1).toInt()

			/**
			 * This is from d-coordinates conversion
			 * @param dx
			 * @param dy
			 * @return segment
			 */
			@JvmStatic
			fun segmentFor(
				dx: Int,
				dy: Int
			): Segment? =
				if (dx < 0) {
					if (dy < 0) {
						B
					} else if (dy > 0) {
						D
					} else {
						A
					}
				} else if (dx == 0) {
					if (dy < 0) {
						C
					} else if (dy > 0) {
						H
					} else {
						null
					}
				} else if (dx > 0) {
					if (dy < 0) {
						E
					} else if (dy > 0) {
						G
					} else {
						F
					}
				} else {
					null
				}

			/**
			 * if segments can consist in regular cell
			 * @param a
			 * @param b
			 * @return if segments is good pair
			 */
			@JvmStatic
			fun conflict(
				a: Segment?,
				b: Segment?
			): Boolean {
				if (a == b) return true
				if (a == null || b == null) return false
				val dx = a.dx - b.dx
				val dy = a.dy - b.dy
				if (Math.sqrt((dx * dx + dy * dy).toDouble()) <= 0.5) return true
				return false
			}

			/**
			 * @param segment
			 * @return segment in reverse direction
			 */
			@JvmStatic
			fun anti(segment: Segment): Segment {
				val anti = segmentFor(-segment.dx, -segment.dy)
				assert(anti != null)
				return anti!!
			}
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
	fun getSpatialType(): SpatialType

	/**
	 * @return Possible joins
	 */
	fun joins(): Set<Segment>
}
