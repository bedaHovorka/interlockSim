/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.exceptions.requireValidArgument
import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.cells.anti
import cz.vutbr.fit.interlockSim.objects.cells.conflict
import cz.vutbr.fit.interlockSim.objects.cells.segmentFor
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.HashMapGraph
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.putMulti
import cz.vutbr.fit.interlockSim.util.valuesMulti
import io.github.oshai.kotlinlogging.KotlinLogging
import java.beans.PropertyChangeSupport
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * Default implementation of {@link EditingContext}.
 *
 * Handles mutable railway network editing operations:
 * - Grid operations: adding, removing, moving cells
 * - Track block management: joining cells, creating track sections
 * - Graph management: maintaining track block connectivity
 * - Configuration: max speed, track length, naming conventions
 *
 * This class contains only editing-related functionality. It does NOT include
 * simulation-specific operations (running simulations, train management, etc.).
 * For simulation capabilities, see {@link DefaultSimulationContext}.
 *
 * ## Thread Safety
 *
 * **This class is NOT thread-safe.**
 *
 * DefaultEditingContext maintains mutable state including:
 * - Railway network grid (DefaultRailWayNetGrid)
 * - Graph structure (ExtendedUnorientedGraph)
 * - Property change listeners (PropertyChangeSupport)
 * - Track block mappings
 *
 * These data structures are not synchronized and will experience race conditions
 * if accessed concurrently from multiple threads.
 *
 * ### Design Decision
 *
 * Thread safety is intentionally NOT implemented because:
 * 1. Railway network editing is inherently sequential
 * 2. GUI editors operate in a single thread (Swing EDT)
 * 3. No current use cases require concurrent editing
 * 4. Thread-safety would add complexity and performance overhead
 *
 * ### Usage
 *
 * - Access DefaultEditingContext only from the editing thread
 * - Do not share instances across thread boundaries
 * - Use external synchronization if multi-threaded access is unavoidable
 *
 * @see EditingContext
 * @see DefaultSimulationContext
 * @see Context
 * @see javax.annotation.concurrent.NotThreadSafe
 */
open class DefaultEditingContext(
	cols: Int,
	rows: Int
) : EditingContext {
	/**
	 * PropertyChangeSupport for notifying listeners of context changes.
	 * Replaces deprecated Observable pattern (Java 21 migration).
	 */
	private val changeSupport: PropertyChangeSupport = PropertyChangeSupport(this)

	/**
	 * Graph representing track blocks and their connections
	 */
	private val extendedUnorientedGraph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> =
		HashMapGraph<Point, TrackBlock, Segment>()

	/**
	 * Maps track blocks to their line cell keys for removal tracking
	 */
	private val linesKeys: MutableMap<TrackBlock, Set<Point>> = IdentityHashMap<TrackBlock, Set<Point>>()

	/**
	 * List of entry/exit points in the railway network
	 */
	protected var inouts: MutableList<InOut> = mutableListOf()

	/**
	 * Current maximum speed for path elements
	 */
	override var currentMaxSpeed: Double = PathElement.COMMON_MAX_SPEED

	/**
	 * Current track length for new track elements
	 */
	override var currentTrackLength: Double = Track.COMMON_TRACK_LENGTH

	/**
	 * Railway network grid structure
	 */
	private val railwayNetGrid: DefaultRailWayNetGrid = DefaultRailWayNetGrid(cols, rows)

	/**
	 * Name string for train generation (backing field for currentNameString)
	 */
	private var nameString: String? = null

	companion object {
		/**
		 * Logger for general class operations.
		 */
		private val logger = KotlinLogging.logger {}

		/**
		 * Constant - square root of 2
		 * 1.4142135623730951
		 */
		const val SQRT2: Double = 1.4142135623730951
	}

	init {
		logger.debug { "Initialized railway network grid: ${cols}x$rows cells" }
	}

	override fun getRailWayNetGrid(): RailwayNetGrid<NodeCell> {
		// The grid internally stores Cell (NodeCell + TrackBlockPart), but we only expose NodeCell
		// This is type-safe because all NodeCell operations go through putCell/removeCell/moveCell
		@Suppress("UNCHECKED_CAST")
		return railwayNetGrid as RailwayNetGrid<NodeCell>
	}

	/**
	 * Add a listener for context changes.
	 * Replaces deprecated addObserver(Observer) method.
	 *
	 * @param listener the listener to add
	 */
	@Synchronized
	override fun addPropertyChangeListener(listener: java.beans.PropertyChangeListener) {
		changeSupport.addPropertyChangeListener(listener)
	}

	/**
	 * Remove a listener for context changes.
	 * Replaces deprecated deleteObserver(Observer) method.
	 *
	 * @param listener the listener to remove
	 */
	@Synchronized
	override fun removePropertyChangeListener(listener: java.beans.PropertyChangeListener) {
		changeSupport.removePropertyChangeListener(listener)
	}

	/**
	 * Swap X and Y coordinates of a point (used in Bresenham algorithm)
	 */
	private fun swapXY(p: Point): Point = Point(p.y, p.x)

	/**
	 * Data class holding a segment transportation between two nodes
	 */
	private inner class Tranporter(
		private val p1: Point,
		private val p2: Point,
		private val s1: Segment,
		private val s2: Segment
	) {
		fun getP1(): Point = p1

		fun getP2(): Point = p2

		fun getS1(): Segment = s1

		fun getS2(): Segment = s2
	}

	/**
	 * Find track line parts between two nodes, applying Bresenham algorithm
	 * to locate intermediate cells
	 */
	private fun findTrackLineParts(
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	): Map<Point, TrackBlockPart>? {
		// Using Kotlin-idiomatic TreeMap with extension functions
		// Replaces TreeMultiMap with standard library + extensions
		val distanceMap = TreeMap<Double, MutableSet<Tranporter>>()

		if (key1.distance(key2) <= SQRT2) return null

		val nodecell1: NodeCell = CellUtilities.assertNodeCell(getGrid().get(key1)!!)
		val nodecell2: NodeCell = CellUtilities.assertNodeCell(getGrid().get(key2)!!)

		for (s1: Segment in nodecell1.joins()) {
			val p1 = s1.transform(key1)
			if (used(p1)) continue
			for (s2: Segment in nodecell2.joins()) {
				if (s1 == s2) continue // stejne segmenty se nepropoji
				val p2 = s2.transform(key2)
				if (used(p2)) continue
				val distance = p1.distance(p2)
				// if (distance <= 1) continue;
				distanceMap.putMulti(distance, Tranporter(p1, p2, s1, s2))
			}
		}

		for (t in distanceMap.valuesMulti()) {
			val tryJoin = tryJoin(t, key1, key2, trackBlock)
			if (tryJoin != null) return tryJoin
		}
		return null
	}

	/**
	 * Join two nodes with a track block via hard connection
	 * If nodes are far apart, finds intermediate cells using Bresenham
	 *
	 * @param s1 edge join to node 1
	 * @param s2 edge join to node 2
	 * @param key1 location of node 1
	 * @param key2 location of node 2
	 * @param trackBlock edge object
	 * @return success of inserting
	 */
	fun hardJoin(
		s1: Segment,
		s2: Segment,
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	): Boolean {
		if (key1.distance(key2) > SQRT2) {
			val blockEndFrom = s1.transform(key1)
			val blockEndTo = s2.transform(key2)
			val result = tryJoin(blockEndFrom, blockEndTo, s1, s2, key1, key2, trackBlock)
			if (result == null) {
				// If tryJoin failed, still add the block directly for now
				extendedUnorientedGraph.put(key1, s1, key2, s2, trackBlock)
			}
			return true
		}
		extendedUnorientedGraph.put(key1, s1, key2, s2, trackBlock)
		return true
	}

	/**
	 * Try to join nodes using transported segment information
	 */
	private fun tryJoin(
		t: Tranporter,
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	): Map<Point, TrackBlockPart>? = tryJoin(t.getP1(), t.getP2(), t.getS1(), t.getS2(), key1, key2, trackBlock)

	/**
	 * Try to join nodes at specific intermediate points
	 */
	private fun tryJoin(
		pi1: Point,
		pi2: Point,
		s1: Segment,
		s2: Segment,
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	): Map<Point, TrackBlockPart>? {
		val p1 = pi1
		val p2 = pi2

		val keys: MutableList<Point> = mutableListOf()
		val b = bresenham(key1, key2, p1, p2, keys)
		if (keys.isEmpty()) return null

		val builtPath =
			if (b) {
				buildPath(keys as List<Point>, key2, key1, trackBlock)
			} else {
				buildPath(keys as List<Point>, key1, key2, trackBlock)
			}

		if (builtPath != null && builtPath.isNotEmpty()) {
			@Suppress("UNCHECKED_CAST")
			val mapToAdd = builtPath as MutableMap<Point, TrackBlockPart>
			getGrid().putMap(mapToAdd)
			linesKeys[trackBlock] = mapToAdd.keys.toSet()
			requireValidState(!extendedUnorientedGraph.contains(key1, key2)) {
				"Graph already contains edge between ($key1, $key2)"
			}
			extendedUnorientedGraph.put(key1, s1, key2, s2, trackBlock)
			return mapToAdd
		}
		return null
	}

	/**
	 * Build a path of track block parts from Bresenham-generated points
	 */
	private fun buildPath(
		bresenham: List<Point>,
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	): MutableMap<Point, TrackBlockPart>? {
		requireValidArgument(bresenham.isNotEmpty()) {
			"Bresenham path list cannot be empty"
		}
		val map: MutableMap<Point, TrackBlockPart> = linkedMapOf()
		var from = key1
		var middle = bresenham[0]
		var part: TrackBlockPart?

		if (bresenham.size > 1) {
			for (i in 1 until bresenham.size) {
				val to = bresenham[i]

				part = createPart(from, middle, to, trackBlock)
				if (part == null) return null
				map[middle] = part

				from = middle
				middle = to
			}
		}
		part = createPart(from, middle, key2, trackBlock)
		if (part == null) return null
		map[middle] = part
		return map
	}

	/**
	 * Create a track block part at an intermediate point
	 */
	private fun createPart(
		from: Point,
		middle: Point,
		to: Point,
		block: TrackBlock
	): TrackBlockPart? {
		if (used(middle)) return null
		if (from == to || from == middle || middle == to) return null

		val ux = from.x - middle.x
		val uy = from.y - middle.y
		val vx = to.x - middle.x
		val vy = to.y - middle.y

		if (Math.abs(ux) > 1 || Math.abs(uy) > 1 || Math.abs(vx) > 1 || Math.abs(vy) > 1) {
			return null
		}

		val s1 = segmentFor(ux, uy)
		val s2 = segmentFor(vx, vy)
		// vyhodit nehezky dvojice segmentu
		if (s1 == null || s2 == null || conflict(s1, s2)) {
			return null
		}
		return TrackBlockPart(block, arrayOf(s1, s2))
	}

	/**
	 * Bresenham line algorithm for finding intermediate cells between two points
	 */
	private fun bresenham(
		key1: Point,
		key2: Point,
		p1: Point,
		p2: Point,
		points: MutableList<Point>
	): Boolean {
		requireValidArgument(key1 != p1 && key2 != p2 && key1 != p2 && key2 != p1) {
			"Keys and intermediate points must be distinct in bresenham algorithm"
		}

		// Make mutable copies since we need to modify them for the algorithm
		var p1Mut = p1
		var p2Mut = p2

		if (p1Mut == p2Mut) {
			points.add(p1Mut) // snad je naklonovany
			return false
		}

		var dx = Math.abs(p2Mut.x - p1Mut.x)
		var dy = Math.abs(p2Mut.y - p1Mut.y)
		val swapped = dy > dx

		if (swapped) {
			p1Mut = swapXY(p1Mut)
			p2Mut = swapXY(p2Mut)
			val temp = dx
			dx = dy
			dy = temp
		}

		val b = p1Mut.x > p2Mut.x
		if (b) {
			val temp = p1Mut
			// swap p1 and p2
			p1Mut = Point(p2Mut.x, p2Mut.y)
			p2Mut = Point(temp.x, temp.y)
		}

		var P = 2 * dy - dx // prediktor
		val P1 = 2 * dy
		val P2 = P1 - 2 * dx
		var y = p1Mut.y

		val step_y = if (p1Mut.y > p2Mut.y) -1 else 1 // smer kresleni

		for (x in p1Mut.x..p2Mut.x) {
			val newPoint = if (!swapped) Point(x, y) else Point(y, x)

			if (newPoint == key1 || newPoint == key2 || used(newPoint)) {
				points.clear()
				return b
			}
			points.add(newPoint)

			// nastaveni prediktoru
			if (P >= 0) {
				P += P2
				y += step_y
			} else {
				P += P1
			}
		}
		return b
	}

	/**
	 * Check if a point is already used in the grid
	 */
	private fun used(newPoint: Point): Boolean = getGrid().containsKey(newPoint)

	/**
	 * Join two cells with a track block
	 */
	override fun joinCells(
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	) {
		// pokusit se nakreslit primku
		val lineParts = findTrackLineParts(key1, key2, trackBlock)

		if (lineParts == null || lineParts.isEmpty()) {
			logger.debug {
				"Join failed between (${key1.x},${key1.y}) and (${key2.x},${key2.y})"
			}
			changeSupport.firePropertyChange(
				ContextChangeListener.JOIN_FAILED,
				null,
				String.format(
					"Join not success between (%d,%d) and (%d,%d)",
					key1.x,
					key1.y,
					key2.x,
					key2.y
				)
			)
			return
		}

		val mapSize = (lineParts as? MutableMap<Point, TrackBlockPart>)?.size ?: 0
		logger.debug {
			"Created track join (${key1.x},${key1.y})->(${key2.x},${key2.y}) with $mapSize intermediate cells"
		}
		changeSupport.firePropertyChange(
			ContextChangeListener.JOIN_CREATED,
			null,
			String.format(
				"Join created between (%d,%d) and (%d,%d)",
				key1.x,
				key1.y,
				key2.x,
				key2.y
			)
		)
	}

	/**
	 * Add a node cell to the railway network grid
	 */
	@Synchronized
	override fun putCell(
		key: Point,
		nodeCell: NodeCell
	) {
		// Validate coordinates are within grid bounds
		if (key.x < 0 || key.y < 0 || key.x >= railwayNetGrid.getCols() || key.y >= railwayNetGrid.getRows()) {
			throw ContextCreationException(
				"Cell coordinates (${key.x},${key.y}) are outside grid bounds " +
					"(${railwayNetGrid.getCols()}x${railwayNetGrid.getRows()})"
			)
		}
		if (getGrid().put(key, nodeCell) === nodeCell) return

		// vedlejsi Nody (sousedni bunky)
		for (s1: Segment in nodeCell.joins()) {
			val p = s1.transform(key)
			// Skip neighbor if it's outside grid bounds (boundary cells)
			if (p.x < 0 || p.y < 0 || p.x >= railwayNetGrid.getCols() || p.y >= railwayNetGrid.getRows()) {
				continue
			}
			val cell2 = getGrid().get(p)
			if (cell2 !is NodeCell) continue
			val nodeCell2 = cell2

			// vzit proti-segment
			val s2 = anti(s1)
			if (nodeCell2.joins().contains(s2)) {
				requireValidState(s2.transform(p) == key) {
					"Segment transformation inconsistency: s2.transform($p) != $key"
				}
				extendedUnorientedGraph.putIfNotExists(
					key,
					s1,
					p,
					s2,
					SimpleTrackBlock(nodeCell, nodeCell2, Track.MIN_LENGTH, currentMaxSpeed)
				)
			}
		}
		if (nodeCell is InOut) inouts.add(nodeCell as InOut)
		changeSupport.firePropertyChange(ContextChangeListener.CELL_ADDED, null, key)
		logger.trace { "Added ${nodeCell.javaClass.simpleName} at (${key.x},${key.y})" }
	}

	/**
	 * Get the railway network grid
	 */
	private fun getGrid(): DefaultRailWayNetGrid = railwayNetGrid

	/**
	 * Remove a node cell from the railway network grid
	 */
	@Synchronized
	override fun removeCell(key: Point) {
		val cell = getGrid().get(key)
		if (cell is NodeCell) {
			getGrid().remove(key)
			for (tl in extendedUnorientedGraph.removeAll(key)) {
				val set = linesKeys[tl]
				if (set != null) getGrid().keySet().removeAll(set)
			}
			changeSupport.firePropertyChange(
				ContextChangeListener.CELL_REMOVED,
				null,
				String.format("Cell removed at (%d,%d)", key.x, key.y)
			)
		}
	}

	/**
	 * Remove a track line from the railway network
	 */
	override fun removeLine(line: TrackBlock) {
		extendedUnorientedGraph.remove(line)
		getGrid().keySet().removeAll(linesKeys.remove(line) ?: emptySet())
		changeSupport.firePropertyChange(
			ContextChangeListener.TRACK_BLOCK_REMOVED,
			null,
			String.format("TrackBlock %s removed", line)
		)
	}

	/**
	 * Move a cell from one location to another
	 */
	override fun moveCell(
		from: Point,
		to: Point
	) {
		val fromCell = getGrid().get(from)
		if (fromCell !is NodeCell) return

		val toCell = getGrid().get(to)
		if (toCell != null) return

		putCell(to, fromCell)
		removeCell(from)
	}

	/**
	 * Get the extended unoriented graph of track blocks
	 */
	override fun getGraph(): ExtendedUnorientedGraph<Point, TrackBlock, Segment> = extendedUnorientedGraph

	/**
	 * Current name string for train generation
	 */
	override var currentNameString: String
		get() = nameString ?: ""
		set(value) {
			nameString = value
		}
}
