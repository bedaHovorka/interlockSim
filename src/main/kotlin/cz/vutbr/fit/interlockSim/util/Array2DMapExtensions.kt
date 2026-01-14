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
 * Kotlin-idiomatic extension functions for Array2DMap to support pathfinding algorithms.
 *
 * These extensions provide ordered navigation and spatial queries without requiring
 * Java's NavigableMap interface, making the API multiplatform-friendly and more
 * Kotlin-idiomatic.
 */

/**
 * Returns the first (smallest) point in the map, or null if the map is empty.
 * Points are ordered by row (y) first, then by column (x).
 *
 * Useful for: Starting point for grid traversal in pathfinding algorithms.
 */
fun <V> Array2DMap<V>.firstPoint(): Point? = keys.firstOrNull()

/**
 * Returns the last (largest) point in the map, or null if the map is empty.
 * Points are ordered by row (y) first, then by column (x).
 *
 * Useful for: End point for grid traversal, bounds checking.
 */
fun <V> Array2DMap<V>.lastPoint(): Point? = keys.lastOrNull()

/**
 * Returns the first point-value entry in the map, or null if the map is empty.
 */
fun <V> Array2DMap<V>.firstEntry(): Map.Entry<Point, V>? =
	firstPoint()?.let { point -> entries.find { it.key == point } }

/**
 * Returns the last point-value entry in the map, or null if the map is empty.
 */
fun <V> Array2DMap<V>.lastEntry(): Map.Entry<Point, V>? = lastPoint()?.let { point -> entries.find { it.key == point } }

/**
 * Returns the least point strictly greater than the given point, or null if no such point exists.
 * This is equivalent to NavigableMap's higherKey.
 *
 * Useful for: Advancing to the next cell during grid traversal.
 *
 * @param point the reference point
 * @return the next point after the given point, or null
 */
fun <V> Array2DMap<V>.higherPoint(point: Point): Point? = (keys as? java.util.NavigableSet<Point>)?.higher(point)

/**
 * Returns the greatest point strictly less than the given point, or null if no such point exists.
 * This is equivalent to NavigableMap's lowerKey.
 *
 * Useful for: Moving backwards during grid traversal.
 *
 * @param point the reference point
 * @return the previous point before the given point, or null
 */
fun <V> Array2DMap<V>.lowerPoint(point: Point): Point? = (keys as? java.util.NavigableSet<Point>)?.lower(point)

/**
 * Returns the least point greater than or equal to the given point, or null if no such point exists.
 * This is equivalent to NavigableMap's ceilingKey.
 *
 * Useful for: Finding the starting point for a range query in pathfinding.
 *
 * @param point the reference point
 * @return the ceiling point, or null
 */
fun <V> Array2DMap<V>.ceilingPoint(point: Point): Point? = (keys as? java.util.NavigableSet<Point>)?.ceiling(point)

/**
 * Returns the greatest point less than or equal to the given point, or null if no such point exists.
 * This is equivalent to NavigableMap's floorKey.
 *
 * Useful for: Finding the ending point for a range query.
 *
 * @param point the reference point
 * @return the floor point, or null
 */
fun <V> Array2DMap<V>.floorPoint(point: Point): Point? = (keys as? java.util.NavigableSet<Point>)?.floor(point)

/**
 * Returns a view of the portion of this map whose points range from [fromPoint] (inclusive)
 * to [toPoint] (exclusive).
 *
 * Useful for: Limiting pathfinding search to a specific rectangular region.
 *
 * @param fromPoint low endpoint (inclusive) of the points in the returned map
 * @param toPoint high endpoint (exclusive) of the points in the returned map
 * @return a view of the specified range within this map
 */
fun <V> Array2DMap<V>.subMap(
	fromPoint: Point,
	toPoint: Point
): Map<Point, V> {
	val comparator = Array2DMap.POINT_COMPARATOR
	val subKeys =
		(keys as? java.util.SortedSet<Point>)?.subSet(fromPoint, toPoint)
			?: keys.filter { comparator.compare(it, fromPoint) >= 0 && comparator.compare(it, toPoint) < 0 }.toSet()
	return subKeys.associateWith { point: Point -> this[point]!! }
}

/**
 * Returns a view of the portion of this map whose points are strictly less than [toPoint].
 *
 * Useful for: Getting all cells before a certain point in grid order.
 *
 * @param toPoint high endpoint (exclusive) of the points in the returned map
 * @return a view of the portion of this map whose points are less than toPoint
 */
fun <V> Array2DMap<V>.headMap(toPoint: Point): Map<Point, V> {
	val comparator = Array2DMap.POINT_COMPARATOR
	val headKeys =
		(keys as? java.util.SortedSet<Point>)?.headSet(toPoint)
			?: keys.filter { comparator.compare(it, toPoint) < 0 }.toSet()
	return headKeys.associateWith { point: Point -> this[point]!! }
}

/**
 * Returns a view of the portion of this map whose points are greater than or equal to [fromPoint].
 *
 * Useful for: Getting all cells from a certain point onwards in grid order.
 *
 * @param fromPoint low endpoint (inclusive) of the points in the returned map
 * @return a view of the portion of this map whose points are greater than or equal to fromPoint
 */
fun <V> Array2DMap<V>.tailMap(fromPoint: Point): Map<Point, V> {
	val comparator = Array2DMap.POINT_COMPARATOR
	val tailKeys =
		(keys as? java.util.SortedSet<Point>)?.tailSet(fromPoint)
			?: keys.filter { comparator.compare(it, fromPoint) >= 0 }.toSet()
	return tailKeys.associateWith { point: Point -> this[point]!! }
}

/**
 * Returns the 4-connected neighbors (up, down, left, right) of the given point that exist in the map.
 *
 * Useful for: A* pathfinding, Dijkstra's algorithm - getting adjacent cells to explore.
 *
 * @param point the center point
 * @return sequence of neighboring points that exist in this map
 */
fun <V> Array2DMap<V>.neighbors4(point: Point): Sequence<Point> =
	sequence {
		val candidates =
			listOf(
				Point(point.x, point.y - 1), // up
				Point(point.x, point.y + 1), // down
				Point(point.x - 1, point.y), // left
				Point(point.x + 1, point.y) // right
			)
		for (candidate in candidates) {
			if (containsKey(candidate)) {
				yield(candidate)
			}
		}
	}

/**
 * Returns the 8-connected neighbors (including diagonals) of the given point that exist in the map.
 *
 * Useful for: Pathfinding with diagonal movement allowed.
 *
 * @param point the center point
 * @return sequence of neighboring points that exist in this map
 */
fun <V> Array2DMap<V>.neighbors8(point: Point): Sequence<Point> =
	sequence {
		for (dy in -1..1) {
			for (dx in -1..1) {
				if (dx == 0 && dy == 0) continue // skip self
				val candidate = Point(point.x + dx, point.y + dy)
				if (containsKey(candidate)) {
					yield(candidate)
				}
			}
		}
	}

/**
 * Returns the 4-connected neighbor entries (up, down, left, right) of the given point.
 *
 * Useful for: Pathfinding with access to both position and value.
 *
 * @param point the center point
 * @return sequence of neighboring point-value pairs
 */
fun <V> Array2DMap<V>.neighborEntries4(point: Point): Sequence<Pair<Point, V>> =
	neighbors4(point).map { p -> p to this[p]!! }

/**
 * Returns the 8-connected neighbor entries (including diagonals) of the given point.
 *
 * Useful for: Pathfinding with diagonal movement and access to values.
 *
 * @param point the center point
 * @return sequence of neighboring point-value pairs
 */
fun <V> Array2DMap<V>.neighborEntries8(point: Point): Sequence<Pair<Point, V>> =
	neighbors8(point).map { p -> p to this[p]!! }

/**
 * Returns all points within a Manhattan distance of [distance] from the given point.
 *
 * Useful for: Finding all cells within a certain movement cost (e.g., train reach).
 *
 * @param point the center point
 * @param distance the maximum Manhattan distance
 * @return sequence of points within the distance that exist in this map
 */
fun <V> Array2DMap<V>.pointsWithinManhattan(
	point: Point,
	distance: Int
): Sequence<Point> =
	sequence {
		for (dy in -distance..distance) {
			val remainingDist = distance - kotlin.math.abs(dy)
			for (dx in -remainingDist..remainingDist) {
				val candidate = Point(point.x + dx, point.y + dy)
				if (containsKey(candidate)) {
					yield(candidate)
				}
			}
		}
	}

/**
 * Returns all points in a rectangular region defined by the given bounds.
 *
 * Useful for: Region-based queries in pathfinding, checking occupancy in an area.
 *
 * @param minX minimum x coordinate (inclusive)
 * @param minY minimum y coordinate (inclusive)
 * @param maxX maximum x coordinate (inclusive)
 * @param maxY maximum y coordinate (inclusive)
 * @return sequence of points in the region that exist in this map
 */
fun <V> Array2DMap<V>.pointsInRegion(
	minX: Int,
	minY: Int,
	maxX: Int,
	maxY: Int
): Sequence<Point> =
	sequence {
		val from = Point(minX, minY)
		val to = Point(maxX + 1, maxY + 1)
		val comparator = Array2DMap.POINT_COMPARATOR
		val rangeKeys =
			(keys as? java.util.SortedSet<Point>)?.subSet(from, to)
				?: keys.filter { comparator.compare(it, from) >= 0 && comparator.compare(it, to) < 0 }
		for (point in rangeKeys) {
			if (point.x <= maxX && point.y <= maxY) {
				yield(point)
			}
		}
	}
