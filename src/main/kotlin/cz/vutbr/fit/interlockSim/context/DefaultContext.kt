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

import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.TrackBlockPart
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.Track
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.Generator
import cz.vutbr.fit.interlockSim.sim.InOutWorker
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.HashMapGraph
import cz.vutbr.fit.interlockSim.util.Point
import cz.vutbr.fit.interlockSim.util.putMulti
import cz.vutbr.fit.interlockSim.util.valuesMulti
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import jDisco.DiscoException
import jDisco.Process
import jDisco.Random
import java.beans.PropertyChangeSupport
import java.util.ArrayList
import java.util.Collection
import java.util.Collections
import java.util.EnumSet
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.List
import java.util.Map
import java.util.Set
import java.util.TreeMap

/**
 * implementation of {@link EditingContext} and {@link SimulationContext}
 */
abstract class DefaultContext :
	EditingContext,
	SimulationContext {
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
	 * Set of allowed report types for simulation output
	 */
	private val allowedReportTypes: MutableSet<ReportType> = EnumSet.noneOf(ReportType::class.java)

	/**
	 * List of entry/exit points in the railway network
	 */
	private var inouts: ArrayList<InOut> = ArrayList(2)

	/**
	 * Workers for each entry/exit point
	 */
	private var workers: MutableMap<InOut, InOutWorker> = IdentityHashMap<InOut, InOutWorker>()

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
	private val railwayNetGrid: DefaultRailWayNetGrid

	/**
	 * Name string for train generation (backing field for currentNameString)
	 */
	private var nameString: String? = null

	/**
	 * Main simulation process
	 */
	private var mainProcess: LoopProcess? = null

	/**
	 * Random number generator for name generation (jDisco)
	 */
	private val random: Random = Random(0)

	companion object {
		/**
		 * Logger for general class operations.
		 */
		private val logger = KotlinLogging.logger {}

		/**
		 * Separate logger for simulation events to allow independent level control.
		 * Configured in logback.xml as "cz.vutbr.fit.interlockSim.simulation".
		 */
		private val simulationLogger = KotlinLogging.logger("cz.vutbr.fit.interlockSim.simulation")

		/**
		 * Constant - square root of 2
		 * 1.4142135623730951
		 */
		const val SQRT2: Double = 1.4142135623730951
	}

	protected constructor(cols: Int, rows: Int) {
		this.railwayNetGrid = DefaultRailWayNetGrid(cols, rows)
		logger.debug { "Initialized railway network grid: ${cols}x$rows cells" }
	}

	override fun getRailWayNetGrid(): DefaultRailWayNetGrid = railwayNetGrid

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

		val nodecell1: NodeCell = Util.assertNodeCell(getGrid().get(key1)!!)
		val nodecell2: NodeCell = Util.assertNodeCell(getGrid().get(key2)!!)

		for (s1: Segment in nodecell1.joins()) {
			assert(s1 != null) { nodecell1 }
			val p1 = s1.transform(key1)
			if (used(p1)) continue
			for (s2: Segment in nodecell2.joins()) {
				assert(s2 != null) { nodecell2 }
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
		assert(key1 != null && key2 != null)
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
		if (pi1 == null || pi2 == null) throw IllegalArgumentException()
		val p1 = pi1
		val p2 = pi2

		val keys: MutableList<Point> = ArrayList()
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
			val mapToAdd = builtPath as java.util.Map<Point, TrackBlockPart>
			getGrid().putMap(mapToAdd)
			@Suppress("UNCHECKED_CAST")
			val mapKeys: Set<Point> = mapToAdd.keySet() as Set<Point>
			linesKeys[trackBlock] = mapKeys
			assert(!extendedUnorientedGraph.contains(key1, key2))
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
		assert(bresenham != null && bresenham.isNotEmpty())
		val map: MutableMap<Point, TrackBlockPart> = LinkedHashMap()
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
		assert(block != null)
		if (used(middle)) return null
		if (from == to || from == middle || middle == to) return null

		val ux = from.x - middle.x
		val uy = from.y - middle.y
		val vx = to.x - middle.x
		val vy = to.y - middle.y

		if (Math.abs(ux) > 1 || Math.abs(uy) > 1 || Math.abs(vx) > 1 || Math.abs(vy) > 1) {
			return null
		}

		val s1 = Cell.Segment.segmentFor(ux, uy)
		val s2 = Cell.Segment.segmentFor(vx, vy)
		// vyhodit nehezky dvojice segmentu
		if (s1 == null || s2 == null || Cell.Segment.conflict(s1, s2)) {
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
		assert(key1 != null && key2 != null && p1 != null && p2 != null)
		assert(key1 != p1 && key2 != p2 && key1 != p2 && key2 != p1)
		
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
		assert(key != null)
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
			assert(s1 != null) { nodeCell }
			val p = s1.transform(key)
			// Skip neighbor if it's outside grid bounds (boundary cells)
			if (p.x < 0 || p.y < 0 || p.x >= railwayNetGrid.getCols() || p.y >= railwayNetGrid.getRows()) {
				continue
			}
			val cell2 = getGrid().get(p)
			if (cell2 !is NodeCell) continue
			val nodeCell2 = cell2

			// vzit proti-segment
			val s2 = Segment.anti(s1)
			if (nodeCell2.joins().contains(s2)) {
				assert(s2.transform(p) == key)
				extendedUnorientedGraph.putIfNotExists(
					key,
					s1,
					p,
					s2,
					SimpleTrackBlock(nodeCell, nodeCell2, Track.MIN_LENGTH, currentMaxSpeed)
				)
			}
		}
		if (nodeCell is InOut) getInOuts().add(nodeCell as InOut)
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
		assert(line != null)
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
	 * Get segment for a path separator and tracks
	 */
	override fun getSegment(
		separator: PathSeparator,
		track: Track?,
		secondEndTrack: Track?
	): Segment? {
		// If track is not null, use it; otherwise use secondEndTrack
		if (track != null) return getSegment(separator, track)
		assert(separator != null)
		assert(secondEndTrack != null) { separator }
		assert(separator is OrientedPathSeparator) // Util
		val segment = getSegment(separator, secondEndTrack!!)
		// Match Java 1:1: return null when segment doesn't exist
		return separator.getFollowingSegment(segment)
	}

	/**
	 * Get segment for a path separator and track
	 */
	override fun getSegment(
		separator: PathSeparator,
		track: Track
	): Segment? {
		if (separator == null || track == null) {
			throw IllegalArgumentException("separator or track is null")
		}
		return if (track is TrackSection) {
			@Suppress("UNCHECKED_CAST")
			val section = track as TrackSection
			// Match Java 1:1: return directly (inner method should not return null here)
			getSegment(separator, section) ?: throw IllegalStateException("getSegment returned null for TrackSection")
		} else {
			val nodeCell: NodeCell = Util.assertNodeCell(separator)
			val trackBlock: TrackBlock = Util.assertInstanceOf(TrackBlock::class.java, track)
			// Match Java 1:1: return directly (inner method should not return null here)
			getSegment(nodeCell, trackBlock as TrackBlock?)
		}
	}

	/**
	 * Get pseudo join segment in block for a path separator and track section
	 */
	fun getSegment(
		separator: PathSeparator,
		section: TrackSection
	): Segment? {
		if (section == null) {
			throw IllegalArgumentException("section is null")
		}
		val trackBlock = section.getTrackBlock()
		if (trackBlock.isInnerElement(separator)) {
			return trackBlock.getJoin(separator, section)
		}
		val nodeCell: NodeCell = Util.assertNodeCell(separator)
		return getSegment(nodeCell, trackBlock as TrackBlock?)
	}

	/**
	 * Get segment at a node cell for a track block
	 */
	private fun getSegment(
		node: NodeCell,
		current: TrackBlock?
	): Segment? {
		val location = getLocation(node)
		return getSegment(location, current)
	}

	/**
	 * Get segment at a location for a track block
	 */
	private fun getSegment(
		location: Point,
		current: TrackBlock?
	): Segment? {
		if (current != null) {
			assert(getGraph().get(location).contains(current)) { current }
		}
		return if (current == null) null else getGraph().extensionalObject(location, current)
	}

	/**
	 * Get location of a node cell in the railway network
	 */
	private fun getLocation(node: NodeCell): Point {
		val location = getRailWayNetGrid().getLocation(node)
		assert(location != null) { this }
		return location!!
	}

	/**
	 * Get the next track block after the current one from a node
	 */
	override fun getNextTrackBlock(
		nodeCell: NodeCell,
		current: TrackBlock?
	): TrackBlock? {
		if (nodeCell == null) throw IllegalArgumentException("node is null")
		val location = getLocation(nodeCell)
		val segment = getSegment(location, current)
		val followingSegment = nodeCell.getFollowingSegment(segment)
		if (followingSegment == null) return null

		val assignedEdges = getGraph().assignedEdges(location)
		return assignedEdges[followingSegment]
	}

	/**
	 * Get the next track section from a path separator
	 */
	override fun getNextTrackSection(
		separator: PathSeparator,
		current: TrackSection?
	): TrackSection? {
		if (separator == null) throw IllegalArgumentException("separator is null")
		var trackBlock: TrackBlock? = null
		if (current != null) {
			trackBlock = current.getTrackBlock()
			assert(trackBlock != null)
			val nextTrackSection = trackBlock?.getNextTrackSection(separator, current)
			if (nextTrackSection != null) {
				if (logger.isTraceEnabled()) {
					logger.trace(
						"getNextTrackSection: found next section within same block from {}",
						separator
					)
				}
				return nextTrackSection
			}
		}

		// z dalsi TrackBlock
		val nodeCell = Util.assertNodeCell(separator)
		val nextTrackBlock = getNextTrackBlock(nodeCell, trackBlock)

		@Suppress("UNCHECKED_CAST")
		val result = nextTrackBlock?.getNextTrackSection(nodeCell, null as TrackSection?)
		logger.trace {
			"getNextTrackSection: navigating network from $separator, result: ${if (result != null) "found" else "not found"}"
		}
		return result
	}

	/**
	 * Run the simulation (jDisco framework integration)
	 */
	@Throws(EmptyContextException::class, SimulationException::class)
	override fun run() {
		if (getGraph().isEmpty() || getGrid().isEmpty() || inouts.isEmpty()) {
			logger.warn {
				"Cannot start simulation: graph=${if (getGraph().isEmpty()) "empty" else "ok"}, " +
					"grid=${if (getGrid().isEmpty()) "empty" else "ok"}, " +
					"inouts=${if (inouts.isEmpty()) "empty" else "ok"}"
			}
			throw EmptyContextException()
		}
		if (mainProcess == null) mainProcess = Generator(this)

		logger.info {
			"Starting simulation: ${inouts.size} InOut points, ${getGraph().size()} track blocks, " +
				"main process=${mainProcess!!.javaClass.simpleName}"
		}

		for (i in inouts) {
			workers[i] = InOutWorker(this, i)
		}

		try {
			Process.activate(mainProcess)
		} catch (e: DiscoException) {
			logger.error(e) { "Failed to activate main simulation process" }
			throw SimulationException(e)
		}
	}

	/**
	 * Stop the simulation
	 */
	override fun stop() {
		assert(mainProcess != null)
		for (worker in workers.values) {
			worker.terminate()
		}
		mainProcess?.terminate()
		System.exit(1) // TODO: Remove System.exit - see GitHub issue #56
	}

	/**
	 * Stop simulation with error reporting
	 */
	override fun errorStop(error: Throwable) {
		stop()
		error.printStackTrace()
	}

	/**
	 * Get the extended unoriented graph of track blocks
	 */
	override fun getGraph(): ExtendedUnorientedGraph<Point, TrackBlock, Segment> = extendedUnorientedGraph

	/**
	 * Get list of entry/exit points
	 */
	override fun getInOuts(): Collection<InOut> = inouts as Collection<InOut>

	/**
	 * Check if a separator is in the specified direction
	 */
	override fun isSeparatorInDirection(
		separator: OrientedPathSeparator,
		next: Track?,
		previous: Track?
	): Boolean {
		assert(separator != null)
		val segment = getSegment(separator, next, previous)
		if (segment == null && separator is InOut) return true
		assert(segment != null) { separator }
		val direction = separator.direction()
		assert(direction != null)
		val inDirection = segment === direction
		if (logger.isDebugEnabled()) {
			logger.debug(
				"isSeparatorInDirection: separator {}, segment={}, direction={}, result={}",
				separator,
				segment,
				direction,
				inDirection
			)
		}
		return inDirection
	}

	/**
	 * Find path to the next semaphore from a path separator
	 */
	override fun pathToNextSemaphore(
		sep: PathSeparator,
		nxt: TrackSection
	): Path? {
		if (sep == null || nxt == null) {
			throw IllegalArgumentException("wrong arguments for aPath finding")
		}
		logger.debug { "pathToNextSemaphore: searching path from $sep via track section" }
		var separator = sep
		var previous: TrackSection? = null
		var next: TrackSection? = nxt
		val path = ArrayPath(this)
		do {
			path.add(separator)
			if (next != null) {
				path.add(next)
				separator = next.getSecondEnd(separator)
				previous = next
				next = getNextTrackSection(separator, next)
			} else {
				break
			}
			if (separator is OrientedPathSeparator) {
				if (isSeparatorInDirection(separator, next, previous)) {
					path.add(separator)
					logger.trace { "pathToNextSemaphore: found complete path with length ${path.length()}" }
					return path
				}
			}
		} while (next != null)
		logger.debug { "pathToNextSemaphore: no path found from $sep" }
		return null
	}

	/**
	 * Report simulation events
	 */
	override fun report(
		report: CharSequence,
		obj: Any,
		type: ReportType
	) {
		if (!isReporting(type)) return

		val buf = if (report is StringBuilder) report else StringBuilder(report)
		try {
			if (obj.javaClass.getMethod("toString") != Any::class.java.getMethod("toString")) {
				buf.insert(0, ' ')
				buf.insert(0, obj)
			}
		} catch (e: Exception) {
			logger.error(e) { "Error generating simulation report for type $type" }
		}
		buf.insert(0, ' ')
		buf.insert(0, jDisco.Process.time())
		simulationLogger.info { buf }
	}

	/**
	 * Add report types to be reported
	 */
	override fun addReportTypes(vararg types: ReportType) {
		if (types == null || types.isEmpty()) {
			allowedReportTypes.clear()
		} else {
			Collections.addAll(allowedReportTypes, *types)
		}
	}

	/**
	 * Check if a report type is enabled
	 */
	override fun isReporting(type: ReportType): Boolean = allowedReportTypes.contains(type)

	/**
	 * Remove report types from reporting
	 */
	override fun removeReportTypes(vararg types: ReportType) {
		if (types == null || types.isEmpty()) return
		for (t in types) {
			if (t == null) continue
			allowedReportTypes.remove(t)
		}
	}

	/**
	 * Current name string for train generation
	 */
	override var currentNameString: String
		get() = nameString ?: randomString()
		set(value) {
			nameString = value
		}

	/**
	 * Generate random name string (single character A-T)
	 */
	private fun randomString(): String = String(Character.toChars(65 + random.nextInt(20)))

	/**
	 * Get the worker for an entry/exit point
	 */
	override fun getWorkerFor(inOut: InOut): InOutWorker =
		workers[inOut] ?: throw IllegalStateException("No worker found for InOut: $inOut")

	/**
	 * Set the main process for the simulation
	 * (for examples where the main process is not a generator)
	 */
	fun setMainProcess(loop: ShuntingLoop) {
		mainProcess = loop
	}
}
