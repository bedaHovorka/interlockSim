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

import cz.vutbr.fit.interlockSim.exceptions.requireValidArgument
import cz.vutbr.fit.interlockSim.exceptions.requireValidState
import cz.vutbr.fit.interlockSim.objects.cells.CellUtilities
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.core.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.core.StaticTrack
import cz.vutbr.fit.interlockSim.objects.core.anti
import cz.vutbr.fit.interlockSim.objects.core.conflict
import cz.vutbr.fit.interlockSim.objects.core.segmentFor
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.putMulti
import cz.vutbr.fit.interlockSim.util.valuesMulti
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.sqrt

/**
 * Default implementation of {@link EditingContext}.
 *
 * Extends [BaseContext] with static [TrackBlock] type and adds editing-specific operations:
 * - Grid operations: adding, removing, moving cells
 * - Track block management: joining cells, creating track sections
 * - Track block connectivity: Bresenham line algorithm for intermediate cells
 *
 * This class contains only editing-related functionality. It does NOT include
 * simulation-specific operations (running simulations, train management, etc.).
 * For simulation capabilities, see {@link DefaultSimulationContext}.
 *
 * ## Architecture
 *
 * **BaseContext<TrackBlock>** provides:
 * - Grid and graph storage (graph stores static TrackBlock)
 * - Property change notification
 * - Configuration management (maxSpeed, trackLength, nameString)
 * - InOut list management
 *
 * **DefaultEditingContext** adds:
 * - Cell manipulation (putCell, removeCell, moveCell)
 * - Track block joining (joinCells, hardJoin)
 * - Bresenham line algorithm for intermediate cells
 * - Track block removal (removeLine)
 *
 * ## Thread Safety
 *
 * **This class is NOT thread-safe.**
 *
 * Inherits thread-safety constraints from [BaseContext]. See [BaseContext] for
 * detailed thread safety documentation.
 *
 * ### Usage
 *
 * - Access DefaultEditingContext only from the editing thread
 * - Do not share instances across thread boundaries
 * - Use external synchronization if multi-threaded access is unavoidable
 *
 * @see BaseContext
 * @see EditingContext
 * @see DefaultSimulationContext
 * @see Context
 * @see javax.annotation.concurrent.NotThreadSafe
 */
open class DefaultEditingContext(
	cols: Int,
	rows: Int
) : BaseContext<TrackBlock>(cols, rows),
	EditingContext {
	/**
	 * Koin scope for this editing context.
	 * Manages lifecycle of navigation services (TopologyNavigator).
	 * The context itself is passed as the scope source, allowing services to access it via getSource().
	 * Scope is closed when context is destroyed via close().
	 *
	 * @see navigationModule
	 * @see close
	 */
	override val scope =
		org.koin.core.context.GlobalContext
			.get()
			.createScope(
				scopeId = System.identityHashCode(this).toString(),
				qualifier =
					org.koin.core.qualifier
						.named<DefaultEditingContext>(),
				source = this
			)

	companion object {
		/**
		 * Logger for general class operations.
		 */
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Get the railway network grid for editing operations.
	 *
	 * **Covariant return type**: Overrides [BaseContext.getRailWayNetGrid] to return
	 * `RailwayNetGrid<NodeCell>` instead of `RailwayNetGrid<Cell>`.
	 *
	 * This is type-safe because:
	 * 1. Editing operations (putCell, removeCell, moveCell) only work with NodeCell
	 * 2. TrackBlockPart cells are generated automatically during joinCells
	 * 3. The grid internally stores Cell (NodeCell + TrackBlockPart), but editing
	 *    interface only exposes NodeCell operations
	 *
	 * @return railway network grid containing node cells
	 */
	override fun getRailWayNetGrid(): RailwayNetGrid<NodeCell> {
		// The grid internally stores Cell (NodeCell + TrackBlockPart), but we only expose NodeCell
		// This is type-safe because all NodeCell operations go through putCell/removeCell/moveCell
		@Suppress("UNCHECKED_CAST")
		return getInternalGrid() as RailwayNetGrid<NodeCell>
	}

	/**
	 * Get the list of InOut elements (entry/exit points) in this context.
	 *
	 * Implementation of [EditingContext.getInOuts] interface method.
	 * Returns an immutable copy of the InOut list to prevent external modifications.
	 *
	 * This method enables type-safe access to InOut elements through the EditingContext
	 * interface, supporting the Liskov Substitution Principle by allowing any
	 * EditingContext implementation to provide InOut access without requiring concrete
	 * type knowledge.
	 *
	 * ## Design Note
	 *
	 * This method is distinct from `SimulationContext.getInOuts()` which returns
	 * `Collection<DynamicInOut>`. EditingContext returns static InOut elements,
	 * enabling type-safe transformation without runtime type checking.
	 *
	 * @return Immutable list of InOut elements (copy of internal list)
	 */
	override fun getInOuts(): List<InOut> = getInOutsList()

	/**
	 * Get topology navigator for static path finding.
	 *
	 * Implementation of [EditingContext.getTopologyNavigator] interface method.
	 * Returns a scoped TopologyNavigator instance from Koin DI, initialized with this
	 * context's network topology.
	 *
	 * The TopologyNavigator is scoped to this context, meaning:
	 * - Same instance returned for all calls to this method
	 * - Isolated from other EditingContext instances
	 * - Automatically cleaned up when context.close() is called
	 *
	 * ## Implementation Note
	 *
	 * Uses Koin scope-per-context pattern. The navigator is lazily initialized on first
	 * access and retrieved from the context's scope without requiring `parametersOf()`.
	 *
	 * @return TopologyNavigator instance for this editing context
	 * @see cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
	 * @see cz.vutbr.fit.interlockSim.context.navigation.DefaultTopologyNavigator
	 * @since Issue #292 Phase 5
	 */
	override fun getTopologyNavigator(): cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator = scope.get()

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
		val distanceMap = java.util.TreeMap<Double, MutableSet<Tranporter>>()

		if (key1.distance(key2) <= sqrt(2.0)) return null

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
		if (key1.distance(key2) > sqrt(2.0)) {
			val blockEndFrom = s1.transform(key1)
			val blockEndTo = s2.transform(key2)
			val result = tryJoin(blockEndFrom, blockEndTo, s1, s2, key1, key2, trackBlock)
			if (result == null) {
				// If tryJoin failed, still add the block directly for now
				getGraph().put(key1, s1, key2, s2, trackBlock)
			}
			return true
		}
		getGraph().put(key1, s1, key2, s2, trackBlock)
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
			getLinesKeys()[trackBlock] = mapToAdd.keys.toSet()
			requireValidState(!getGraph().contains(key1, key2)) {
				"Graph already contains edge between ($key1, $key2)"
			}
			getGraph().put(key1, s1, key2, s2, trackBlock)
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
	 * Add a node cell to the railway network grid
	 */
	@Synchronized
	override fun putCell(
		key: Point,
		nodeCell: NodeCell
	) {
		checkNotFrozen("add cell")
		// Validate coordinates are within grid bounds
		val grid = getGrid()
		if (key.x < 0 || key.y < 0 || key.x >= grid.getCols() || key.y >= grid.getRows()) {
			throw ContextCreationException(
				"Cell coordinates (${key.x},${key.y}) are outside grid bounds " +
					"(${grid.getCols()}x${grid.getRows()})"
			)
		}
		if (grid.put(key, nodeCell) === nodeCell) return

		// vedlejsi Nody (sousedni bunky)
		for (s1: Segment in nodeCell.joins()) {
			val p = s1.transform(key)
			// Skip neighbor if it's outside grid bounds (boundary cells)
			if (p.x < 0 || p.y < 0 || p.x >= grid.getCols() || p.y >= grid.getRows()) {
				continue
			}
			val cell2 = grid.get(p)
			if (cell2 !is NodeCell) continue
			val nodeCell2 = cell2

			// vzit proti-segment
			val s2 = anti(s1)
			if (nodeCell2.joins().contains(s2)) {
				requireValidState(s2.transform(p) == key) {
					"Segment transformation inconsistency: s2.transform($p) != $key"
				}
				getGraph().putIfNotExists(
					key,
					s1,
					p,
					s2,
					SimpleTrackBlock(nodeCell, nodeCell2, StaticTrack.MIN_LENGTH, currentMaxSpeed)
				)
			}
		}
		if (nodeCell is InOut && !inouts.contains(nodeCell as InOut)) {
			inouts.add(nodeCell as InOut)
		}
		firePropertyChange(ContextChangeListener.CELL_ADDED, null, key)
		logger.trace { "Added ${nodeCell.javaClass.simpleName} at (${key.x},${key.y})" }
	}

	/**
	 * Remove a node cell from the railway network grid
	 */
	@Synchronized
	override fun removeCell(key: Point) {
		checkNotFrozen("remove cell")
		val grid = getGrid()
		val cell = grid.get(key)
		if (cell is NodeCell) {
			grid.remove(key)
			for (tl in getGraph().removeAll(key)) {
				val set = getLinesKeys()[tl]
				if (set != null) grid.keySet().removeAll(set)
			}
			if (cell is InOut) inouts.remove(cell as InOut)
			firePropertyChange(
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
		checkNotFrozen("remove track block")
		val grid = getGrid()
		getGraph().remove(line)
		grid.keySet().removeAll(getLinesKeys().remove(line) ?: emptySet())
		firePropertyChange(
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
		checkNotFrozen("move cell")
		val grid = getGrid()
		val fromCell = grid.get(from)
		if (fromCell !is NodeCell) return

		val toCell = grid.get(to)
		if (toCell != null) return

		putCell(to, fromCell)
		removeCell(from)
	}

	/**
	 * Join two cells with a track block
	 */
	override fun joinCells(
		key1: Point,
		key2: Point,
		trackBlock: TrackBlock
	) {
		checkNotFrozen("join cells")
		// pokusit se nakreslit primku
		val lineParts = findTrackLineParts(key1, key2, trackBlock)

		if (lineParts == null || lineParts.isEmpty()) {
			logger.debug {
				"Join failed between (${key1.x},${key1.y}) and (${key2.x},${key2.y})"
			}
			firePropertyChange(
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
		firePropertyChange(
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
	 * Fire a property change event for cell modification.
	 *
	 * Implements {@link EditingContext#fireCellModified} to notify listeners
	 * that a cell's properties have been modified in-place (e.g., name changed).
	 *
	 * @param key The grid position of the modified cell
	 */
	override fun fireCellModified(key: Point) {
		firePropertyChange(
			ContextChangeListener.CELL_MODIFIED,
			null,
			key
		)
	}
}
