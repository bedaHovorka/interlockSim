/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.util

/**
 * Simple 2D point class to replace java.awt.Point dependency in non-GUI code.
 * This class represents a location in (x,y) coordinate space, specified in integer precision.
 * This is an immutable value class.
 */
data class Point(
	/**
	 * The X coordinate of this Point.
	 */
	val x: Int = 0,
	
	/**
	 * The Y coordinate of this Point.
	 */
	val y: Int = 0
) : Cloneable {
	
	/**
	 * Constructs and initializes a point at the origin (0, 0) of the coordinate space.
	 */
	constructor() : this(0, 0)
	
	/**
	 * Creates a copy of this point.
	 * @return a clone of this Point object.
	 */
	public override fun clone(): Point {
		return Point(x, y)
	}
	
	/**
	 * Returns the distance from this Point to a specified Point.
	 * @param pt the specified point to be measured against this Point
	 * @return the distance between this Point and the specified Point.
	 */
	fun distance(pt: Point): Double {
		val dx = (x - pt.x).toDouble()
		val dy = (y - pt.y).toDouble()
		return kotlin.math.sqrt(dx * dx + dy * dy)
	}
	
	/**
	 * Returns a string representation of this point and its location in the (x,y) coordinate space.
	 * @return a string representation of this point.
	 */
	override fun toString(): String {
		return "Point(x=$x, y=$y)"
	}
}
