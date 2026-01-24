/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Collections
import java.util.LinkedList
import java.util.Queue

/**
 * Příklad fungování modelu
 * Ovlada sest navestidel a 2 InOuty pomoci predem ulozenych cest
 */
class ShuntingLoop : Interlocking {
	companion object {
		private val logger = KotlinLogging.logger {}
		private const val MAX_TRAINS: Int = 2 // maximalni pocet odsouhlasených vlaků v systému
	}

	// fronta neodsouhlasenych - za jinych okolnosti seznam ze ktereho si dispecer vybere
	private val unapprowedTrains: Queue<Train> = LinkedList<Train>()
	private val approwedTrains: MutableList<Train> = mutableListOf()
	private val generator: InnerGenerator
	// Temp during construction
	private val staticPaths: MutableMap<RailSemaphore, MutableList<Path>> = mutableMapOf()
	// Converted in startAction()
	private lateinit var paths: MutableMap<DynamicRailSemaphore, MutableList<Path>>
	private val innerTrackBlocks: MutableList<SimpleTrackBlock> = mutableListOf()
	// Temp during construction
	private val staticOuterTrackblocks: MutableMap<SimpleTrackBlock, RailSemaphore> = mutableMapOf()
	// Converted in startAction()
	private lateinit var outerTrackblocks: MutableMap<SimpleTrackBlock, DynamicRailSemaphore>
	// Cache for static→dynamic semaphore mapping (eliminates redundant type casts)
	private val semaphoreCache: MutableMap<RailSemaphore, DynamicRailSemaphore> = mutableMapOf()
	// Reverse mapping: internal semaphore -> parent InOut (for InOut semaphores only)
	private val semaphoreToInOut: MutableMap<RailSemaphore, InOut> = mutableMapOf()
	private val endTime: Long

	private inner class RealTimeSynch : LoopProcess() {
		private var presvihnuto: Double = 0.0
		private var beginTime: Long = 0

		override fun startAction() {
			interLoopSleep()
		}

		override fun iteration() {
			val endTime: Long = System.currentTimeMillis()
			val sleepTime: Long = 1000 - (endTime - beginTime)
			if (sleepTime > 10) {
				try {
					Thread.sleep(sleepTime)
				} catch (e: InterruptedException) {
					requireSimulation(false) { "Unexpected thread interruption during real-time synchronization: $e" }
					Thread.currentThread().interrupt() // Restore interrupt status
					terminate()
				}
			} else if (sleepTime < 0) {
				presvihnuto = sleepTime / 1000.0
			}
		}

		override fun interLoopSleep() {
			beginTime = System.currentTimeMillis()
			hold(1 + presvihnuto)
			presvihnuto = 0.0
		}
	}

	private inner class InnerGenerator(
		context: SimulationEnvironment
	) : Generator(context) {
		override fun placeTrain(train: Train) {
			unapprowedTrains.offer(train)
		}

		override fun iteration() {
			// Stop generating trains when approaching endTime
			if (time() >= endTime) {
				terminate()
				return
			}
			super.iteration()
		}
	}

	/**
	 * @param context
	 * @param endTime when simulation schould stop
	 */
	constructor(context: SimulationContext, endTime: Long) : super(context) {
		this.endTime = endTime
		generator = InnerGenerator(context)

		requireSimulation(context.getGraph().size() > 0) {
			"Railway network graph is empty - must be loaded from vyhybna.xml first"
		}
		// Sit jiz musi byt nactena z vyhybna.xml !!!

		val B: InOut = elementAt(context, InOut::class.java, 30, 8)
		val A: InOut = elementAt(context, InOut::class.java, 11, 8)
		val zA: RailSemaphore = elementAt(context, "zA", RailSemaphore::class.java, 14, 8)
		val doA1: RailSemaphore = elementAt(context, "doA1", RailSemaphore::class.java, 16, 8)
		val doB1: RailSemaphore = elementAt(context, "doB1", RailSemaphore::class.java, 25, 8)
		val zB: RailSemaphore = elementAt(context, "zB", RailSemaphore::class.java, 27, 8)
		val doA2: RailSemaphore = elementAt(context, "doA2", RailSemaphore::class.java, 17, 9)
		val doB2: RailSemaphore = elementAt(context, "doB2", RailSemaphore::class.java, 24, 9)
		val vA: RailSwitch = elementAt(context, "vA", RailSwitch::class.java, 15, 8)
		val vB: RailSwitch = elementAt(context, "vB", RailSwitch::class.java, 26, 8)

		val k1: SimpleTrackBlock = getBlock(context, "k1", doA1, doB1)
		val k2: SimpleTrackBlock = getBlock(context, "k2", doA2, doB2)
		val kA: SimpleTrackBlock = getBlock(context, "kA", A, zA)
		val kB: SimpleTrackBlock = getBlock(context, "kB", B, zB)

		// usporadani znalostni pro jednoduche rizeni
		constructPath(context, zA, vA, doA1, k1, doB1)
		constructPath(context, doA1, vA, zA, kA, A)
		constructPath(context, zA, vA, doA2, k2, doB2)
		constructPath(context, doA2, vA, zA, kA, A)
		constructPath(context, zB, vB, doB1, k1, doA1)
		constructPath(context, doB1, vB, zB, kB, B)
		constructPath(context, zB, vB, doB2, k2, doA2)
		constructPath(context, doB2, vB, zB, kB, B)
		// Block organization matches baseline (working version):
		// - innerTrackBlocks: middle blocks with RailSemaphore ends only (k1, k2)
		// - staticOuterTrackblocks: entry/exit blocks with one InOut end (kB, kA)
		Collections.addAll(innerTrackBlocks, k1, k2)
		staticOuterTrackblocks[kB] = zB
		staticOuterTrackblocks[kA] = zA
	}

	private fun constructPath(context: SimulationContext, vararg elements: PathElement): ArrayPath {
		val arrayPath = ArrayPath(context)
		try {
			for (i in elements.indices) {
				if (elements[i] is RailSwitch) {
					val prev: RailSemaphore = Util.assertInstanceOf(RailSemaphore::class.java, elements[i - 1])
					val next: RailSemaphore = Util.assertInstanceOf(RailSemaphore::class.java, elements[i + 1])
					arrayPath.addLast(getBlock(context, switchName(elements[i]), prev, elements[i] as Cell))
					arrayPath.addLast(elements[i])
					arrayPath.addLast(getBlock(context, switchName(elements[i]), next, elements[i] as Cell))
				} else {
					arrayPath.addLast(elements[i])
				}
			}
		} catch (e: ArrayIndexOutOfBoundsException) {
			requireSimulation(false) { "Invalid path element access during path construction: $e" }
		}
		val first: RailSemaphore = Util.assertInstanceOf(RailSemaphore::class.java, arrayPath.getFirst())
		var sublist: MutableList<Path>? = staticPaths[first]
		if (sublist == null) {
			sublist = mutableListOf()
			staticPaths[first] = sublist
		}
		sublist.add(arrayPath)
		return arrayPath
	}

	private fun switchName(el: PathElement): String =
		Util.assertInstanceOf(RailSwitch::class.java, el).getName() + "-around"

	private fun <T : Cell> elementAt(
		context: SimulationContext,
		clazz: Class<T>,
		x: Int,
		y: Int
	): T {
		val railWayNetGrid: RailwayNetGrid<Cell> = context.getRailWayNetGrid()
		val cell = railWayNetGrid.getCellAt(x, y) ?: throw IllegalArgumentException("No cell at position ($x, $y)")
		return Util.assertInstanceOf(clazz, cell)
	}

	private fun <T : NodeCell> elementAt(
		context: SimulationContext,
		name: String,
		clazz: Class<T>,
		x: Int,
		y: Int
	): T {
		val elementAt: T = elementAt(context, clazz, x, y)
		elementAt.setName(name)
		return elementAt
	}

	private fun getBlock(
		context: SimulationContext,
		name: String,
		cell1: Cell,
		cell2: Cell
	): SimpleTrackBlock {
		val railWayNetGrid: RailwayNetGrid<Cell> = context.getRailWayNetGrid()
		val graph = context.getGraph()  // ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>
		val point1 = railWayNetGrid.getLocation(cell1) ?: throw IllegalArgumentException("Cannot get location for cell1")
		val point2 = railWayNetGrid.getLocation(cell2) ?: throw IllegalArgumentException("Cannot get location for cell2")
		val block = graph.get(point1, point2) ?: throw IllegalArgumentException("Cannot get block between cells")
		// In simulation context, block is DynamicTrackBlock wrapping a SimpleTrackBlock
		val dynamicBlock = block as DynamicTrackBlock
		val assertInstanceOf: SimpleTrackBlock = Util.assertInstanceOf(SimpleTrackBlock::class.java, dynamicBlock.staticRef)
		assertInstanceOf.setName(name)
		return assertInstanceOf
	}

	/**
	 * Pre-build cache of static→dynamic semaphore mappings.
	 * Eliminates need for repeated toDynamic() calls and type casting.
	 *
	 * This method performs all type casts once during initialization,
	 * improving type safety and performance during simulation execution.
	 */
	private fun buildSemaphoreCache() {
		// Cache all semaphores from staticPaths
		for (staticSem in staticPaths.keys) {
			if (staticSem !in semaphoreCache) {
				val dynamicSem = env.toDynamic(staticSem) as DynamicRailSemaphore
				semaphoreCache[staticSem] = dynamicSem
				logger.trace { "Cached semaphore from staticPaths: ${staticSem.getName()} -> ${dynamicSem.name}" }
			}
		}

		// Cache all semaphores from staticOuterTrackblocks
		for (staticSem in staticOuterTrackblocks.values) {
			if (staticSem !in semaphoreCache) {
				val dynamicSem = env.toDynamic(staticSem) as DynamicRailSemaphore
				semaphoreCache[staticSem] = dynamicSem
				logger.trace { "Cached semaphore from staticOuterTrackblocks: ${staticSem.getName()} -> ${dynamicSem.name}" }
			}
		}

		// Cache all semaphores from innerTrackBlocks endpoints
		for (block in innerTrackBlocks) {
			for (sep in block.ends()) {
				when (sep) {
					is RailSemaphore -> {
						if (sep !in semaphoreCache) {
							val dynamicSem = env.toDynamic(sep) as DynamicRailSemaphore
							semaphoreCache[sep] = dynamicSem
							logger.trace { "Cached semaphore from innerTrackBlocks: ${sep.getName()} -> ${dynamicSem.name}" }
						}
					}
					is InOut -> {
						// For InOut, cache the semaphore returned by asRailSemaphore()
						// This handles the direction-specific semaphore selection
						val railSem = sep.asRailSemaphore()
						if (railSem !in semaphoreCache) {
							val dynamicSem = env.toDynamic(railSem) as DynamicRailSemaphore
							semaphoreCache[railSem] = dynamicSem
							// Store reverse mapping for later use in checkOneEnd()
							semaphoreToInOut[railSem] = sep
							logger.debug {
								"Cached InOut semaphore: ${sep.getName()} -> ${railSem.getName()} -> ${dynamicSem.name}"
							}
						}
					}
				}
			}
		}

		logger.info { "Semaphore cache built: ${semaphoreCache.size} semaphores cached" }
	}

	/**
	 * Validates that all required semaphores are present in the cache.
	 * This catches configuration errors early before simulation runs.
	 */
	private fun validateSemaphoreCacheCompleteness() {
		// Validate all staticPaths keys are cached
		for (staticSem in staticPaths.keys) {
			requireSimulation(staticSem in semaphoreCache) {
				"Semaphore ${staticSem.getName()} from staticPaths not in cache! " +
					"Cache contains: ${semaphoreCache.keys.joinToString(", ") { it.getName() }}"
			}
		}

		// Validate all staticOuterTrackblocks values are cached
		for (staticSem in staticOuterTrackblocks.values) {
			requireSimulation(staticSem in semaphoreCache) {
				"Semaphore ${staticSem.getName()} from staticOuterTrackblocks not in cache! " +
					"Cache contains: ${semaphoreCache.keys.joinToString(", ") { it.getName() }}"
			}
		}

		logger.info { "Semaphore cache validation passed: all ${semaphoreCache.size} semaphores are properly cached" }
	}

	override fun startAction() {
		// Build cache of all semaphores used in this simulation
		buildSemaphoreCache()

		// Validate cache completeness before converting to dynamic paths
		validateSemaphoreCacheCompleteness()

		// Convert static objects to Dynamic* wrappers using cached typed references (NO CASTS)
		paths = staticPaths.mapKeys { (staticSem, _) ->
			semaphoreCache[staticSem]
				?: throw IllegalStateException(
					"Semaphore ${staticSem.getName()} not in cache during paths conversion! " +
						"Cache has ${semaphoreCache.size} entries: ${semaphoreCache.keys.joinToString(", ") { it.getName() }}"
				)
		}.mapValues { (_, pathList) ->
			pathList.map { convertPathToDynamic(it) }.toMutableList()
		}.toMutableMap()

		outerTrackblocks = staticOuterTrackblocks.mapValues { (_, staticSem) ->
			semaphoreCache[staticSem]
				?: throw IllegalStateException(
					"Semaphore ${staticSem.getName()} not in cache during outerTrackblocks conversion! " +
						"Cache has ${semaphoreCache.size} entries: ${semaphoreCache.keys.joinToString(", ") { it.getName() }}"
				)
		}.toMutableMap()

		logger.info {
			"Dynamic paths initialized: ${paths.size} semaphore entries, " +
				"${outerTrackblocks.size} outer trackblocks"
		}

		env.addReportTypes(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS, ReportType.NODE_EVENTS)
		// activate(RealTimeSynch())
		activate(generator)
	}

	private fun convertPathToDynamic(staticPath: Path): Path {
		val context = env as SimulationContext
		val dynamicPath = ArrayPath(context)
		for (element in staticPath) {
			val dynamicElement = when (element) {
				is RailSemaphore -> env.toDynamic(element)
				is RailSwitch -> env.toDynamic(element)
				is InOut -> env.toDynamic(element)  // Use toDynamic for consistency
				is SimpleTrackBlock -> element  // Track blocks remain static in path - wrapped on-demand by AbstractPath
				else -> element
			}
			dynamicPath.addLast(dynamicElement)
		}
		return dynamicPath
	}

	override fun iteration() {
		// stare vlaky
		val iter: MutableIterator<Train> = approwedTrains.iterator()
		while (iter.hasNext()) {
			val element: Train = iter.next()
			if (element.terminated()) iter.remove()
		}
		// nove vlaky a inouty
		approveTrains()
		// Polling interval: 1.0s (matches baseline timing)
		// Critical: Train entry events align with polling to catch RESERVED state
		hold(1.0)
		for (block in innerTrackBlocks) checkBothEnds(block)
		for (e in outerTrackblocks.entries) checkOneEnd(e.key, e.value)
	}

	private fun checkBothEnds(block: SimpleTrackBlock) {
		// Inner blocks (k1, k2) have RailSemaphore ends only, no InOut
		// Check both semaphore endpoints to see if path needs to be reserved
		for (sep in block.ends()) {
			val railSem = (sep as OrientedPathSeparator).asRailSemaphore()
			val dynamicSem = semaphoreCache[railSem]
				?: throw IllegalStateException(
					"Semaphore ${railSem.getName()} not in cache! " +
						"Block: ${block.hashCode()}, " +
						"Cache has ${semaphoreCache.size} entries: ${semaphoreCache.keys.joinToString(", ") { it.getName() }}"
				)
			if (checkOneEnd(block, dynamicSem)) return
		}
	}

	private fun trySetupPath(path: Path): Boolean {
		try {
			val from: PathSeparator = path.getFirst()
			if (!path.isFreeFrom(from)) {
				logger.debug { "Path not free from separator: $from" }
				return false
			}
			logger.debug { "Setting up path from separator: $from" }
			path.setUpPath(from)
			return true
		} catch (e: TrackOperationException) {
			requireSimulation(false) { "Unexpected track operation exception during path setup: $e" }
			logger.debug { "Exception during path setup: ${e.message}" }
			return false
		}
	}

	private fun trySetupPaths(sem: DynamicRailSemaphore): Boolean {
		logger.debug { "Attempting to setup paths from semaphore: ${sem.name}" }
		val pathList = paths[sem]
		if (pathList == null) {
			// Enhanced diagnostics to debug wrapper instance identity issues
			val staticRef = sem.staticRef
			logger.error {
				"CRITICAL: No paths found for semaphore: ${sem.name}\n" +
					"  Dynamic wrapper: ${System.identityHashCode(sem)}\n" +
					"  Static reference: ${staticRef.getName()} (identity: ${System.identityHashCode(staticRef)})\n" +
					"  Available path keys (${paths.size}):\n" +
					paths.keys.joinToString("\n") { key ->
						"    - ${key.name} (wrapper identity: ${System.identityHashCode(key)}, " +
							"static identity: ${System.identityHashCode(key.staticRef)})"
					} +
					"\n  Semaphore cache entries (${semaphoreCache.size}):\n" +
					semaphoreCache.entries.joinToString("\n") { (static, dynamic) ->
						"    - ${static.getName()} -> ${dynamic.name} " +
							"(static identity: ${System.identityHashCode(static)}, " +
							"wrapper identity: ${System.identityHashCode(dynamic)})"
					}
			}
			return false
		}
		// zkusit postavit cestu
		for (path in pathList) {
			try {
				if (path.isSetUpPath(sem) || trySetupPath(path)) {
					logger.debug { "Path setup successful from semaphore: ${sem.name}" }
					return true
				}
			} catch (e: TrackOperationException) {
				requireSimulation(false) { "Unexpected track operation exception during path setup attempt: $e" }
				logger.debug { "Exception in path setup attempt: ${e.message}" }
			}
		}
		logger.debug { "All path setup attempts failed from semaphore: ${sem.name}" }
		return false
	}

	private fun checkOneEnd(
		block: SimpleTrackBlock,
		to: DynamicRailSemaphore
	): Boolean {
		val dynamicBlock = env.toDynamic(block)
		val blockState = dynamicBlock.state

		logger.info {
			"${time()} CHECK_ONE_END: block=${block.toString().take(20)}, to=${to.name}, state=$blockState"
		}

		if (blockState == TrackFacility.State.FREE) return false

		// PRE-RESERVE: When block is RESERVED, reserve next path segment immediately
		// This prevents race condition where train enters block before next polling cycle
		if (blockState == TrackFacility.State.RESERVED) {
			// Get the other end of the block from the 'to' semaphore
			val staticSecondEnd = block.getSecondEnd(to.staticRef)
			val isSetUp = dynamicBlock.isSetUpPath(staticSecondEnd)
			logger.info {
				"${time()} PRE_RESERVE_CHECK: block=${block.toString().take(20)} RESERVED, " +
					"to=${to.name}, secondEnd=$staticSecondEnd, " +
					"isSetUp=$isSetUp, reservedFrom=${dynamicBlock.reservedFrom}"
			}
			if (isSetUp) {
				logger.info {
					"${time()} PRE_RESERVE: Reserving continuation path to ${to.name}"
				}
				return trySetupPaths(to)
			}
			return false
		}

		// OCCUPIED: Reserve path when train is in block (backup)
		if (blockState == TrackFacility.State.OCCUPIED) {
			val nextSem = dynamicBlock.getTrackOccupant().nextSemaphore()
			val nextSemName = when (nextSem) {
				is cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore -> nextSem.name
				is cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut -> nextSem.name
				else -> nextSem.toString()
			}
			// Extract static reference for comparison
			val nextSemStatic = when (nextSem) {
				is cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore -> nextSem.staticRef
				is cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut -> nextSem.staticRef
				else -> nextSem
			}
			val match = nextSemStatic === to.staticRef
			logger.info {
				"${time()} OCCUPIED_CHECK: block=${block.toString().take(20)}, to=${to.name}, " +
					"train's nextSem=$nextSemName, match=$match, " +
					"nextStatic@${System.identityHashCode(nextSemStatic)}, " +
					"toStatic@${System.identityHashCode(to.staticRef)}"
			}
			// Use identity comparison on static references
			if (nextSemStatic !== to.staticRef) return false
			logger.info { "Block occupied, train heading to ${to.name}, reserving next path" }
			return trySetupPaths(to)
		}

		return false
	}

	private fun approveTrains() {
		while (approwedTrains.size < MAX_TRAINS && unapprowedTrains.size > 0) {
			val poll: Train = unapprowedTrains.poll()
			logger.debug { "Approving train: $poll (approved: ${approwedTrains.size + 1}/$MAX_TRAINS max)" }
			approwedTrains.add(poll)
			activate(poll)
		}
	}

	override fun interLoopSleep() {
		if (time() >= endTime) {
			generator.terminate()
			terminate()
			return
		}
		hold(1.0)
	}
}
