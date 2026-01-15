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
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.Cell.Segment
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.paths.PathElement
import cz.vutbr.fit.interlockSim.objects.paths.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.SimpleTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackFacility.State
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.Point
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
		context: SimulationContext
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

		val B: InOut = elementAt(InOut::class.java, 30, 8)
		val A: InOut = elementAt(InOut::class.java, 11, 8)
		val zA: RailSemaphore = elementAt("zA", RailSemaphore::class.java, 14, 8)
		val doA1: RailSemaphore = elementAt("doA1", RailSemaphore::class.java, 16, 8)
		val doB1: RailSemaphore = elementAt("doB1", RailSemaphore::class.java, 25, 8)
		val zB: RailSemaphore = elementAt("zB", RailSemaphore::class.java, 27, 8)
		val doA2: RailSemaphore = elementAt("doA2", RailSemaphore::class.java, 17, 9)
		val doB2: RailSemaphore = elementAt("doB2", RailSemaphore::class.java, 24, 9)
		val vA: RailSwitch = elementAt("vA", RailSwitch::class.java, 15, 8)
		val vB: RailSwitch = elementAt("vB", RailSwitch::class.java, 26, 8)

		val k1: SimpleTrackBlock = getBlock("k1", doA1, doB1)
		val k2: SimpleTrackBlock = getBlock("k2", doA2, doB2)
		val kA: SimpleTrackBlock = getBlock("kA", A, zA)
		val kB: SimpleTrackBlock = getBlock("kB", B, zB)

		// usporadani znalostni pro jednoduche rizeni
		constructPath(zA, vA, doA1, k1, doB1)
		constructPath(doA1, vA, zA, kA, A)
		constructPath(zA, vA, doA2, k2, doB2)
		constructPath(doA2, vA, zA, kA, A)
		constructPath(zB, vB, doB1, k1, doA1)
		constructPath(doB1, vB, zB, kB, B)
		constructPath(zB, vB, doB2, k2, doA2)
		constructPath(doB2, vB, zB, kB, B)
		Collections.addAll(innerTrackBlocks, k1, k2)
		staticOuterTrackblocks[kB] = zB
		staticOuterTrackblocks[kA] = zA
	}

	private fun constructPath(vararg elements: PathElement): ArrayPath {
		val arrayPath = ArrayPath(context)
		try {
			for (i in elements.indices) {
				if (elements[i] is RailSwitch) {
					val prev: RailSemaphore = Util.assertInstanceOf(RailSemaphore::class.java, elements[i - 1])
					val next: RailSemaphore = Util.assertInstanceOf(RailSemaphore::class.java, elements[i + 1])
					arrayPath.addLast(getBlock(switchName(elements[i]), prev, elements[i] as Cell))
					arrayPath.addLast(elements[i])
					arrayPath.addLast(getBlock(switchName(elements[i]), next, elements[i] as Cell))
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
		clazz: Class<T>,
		x: Int,
		y: Int
	): T {
		val railWayNetGrid: RailwayNetGrid = context.getRailWayNetGrid()
		val cell = railWayNetGrid.getCellAt(x, y) ?: throw IllegalArgumentException("No cell at position ($x, $y)")
		return Util.assertInstanceOf(clazz, cell)
	}

	private fun <T : NodeCell> elementAt(
		name: String,
		clazz: Class<T>,
		x: Int,
		y: Int
	): T {
		val elementAt: T = elementAt(clazz, x, y)
		elementAt.setName(name)
		return elementAt
	}

	private fun getBlock(
		name: String,
		cell1: Cell,
		cell2: Cell
	): SimpleTrackBlock {
		val railWayNetGrid: RailwayNetGrid = context.getRailWayNetGrid()
		val graph: ExtendedUnorientedGraph<Point, TrackBlock, Segment> = context.getGraph()
		val point1 = railWayNetGrid.getLocation(cell1) ?: throw IllegalArgumentException("Cannot get location for cell1")
		val point2 = railWayNetGrid.getLocation(cell2) ?: throw IllegalArgumentException("Cannot get location for cell2")
		val block = graph.get(point1, point2) ?: throw IllegalArgumentException("Cannot get block between cells")
		val assertInstanceOf: SimpleTrackBlock = Util.assertInstanceOf(SimpleTrackBlock::class.java, block)
		assertInstanceOf.setName(name)
		return assertInstanceOf
	}

	override fun startAction() {
		// Convert static objects to Dynamic* wrappers (now available after simulation initialization)
		paths = mutableMapOf()
		for ((staticSem, pathList) in staticPaths) {
			val dynamicSem = context.toDynamic(staticSem) as DynamicRailSemaphore
			val dynamicPaths = mutableListOf<Path>()
			for (path in pathList) {
				val dynamicPath = convertPathToDynamic(path)
				dynamicPaths.add(dynamicPath)
			}
			paths[dynamicSem] = dynamicPaths
		}

		outerTrackblocks = staticOuterTrackblocks.mapValues { (_, staticSem) ->
			context.toDynamic(staticSem) as DynamicRailSemaphore
		}.toMutableMap()

		context.addReportTypes(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS, ReportType.NODE_EVENTS)
		// activate(RealTimeSynch())
		activate(generator)
	}

	private fun convertPathToDynamic(staticPath: Path): Path {
		val dynamicPath = ArrayPath(context)
		for (element in staticPath) {
			val dynamicElement = when (element) {
				is RailSemaphore -> context.toDynamic(element)
				is RailSwitch -> context.toDynamic(element)
				is InOut -> context.toDynamic(element)  // Use toDynamic for consistency
				is SimpleTrackBlock -> element  // Track blocks remain STATIC - they manage their own state
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
		hold(1.0)
		for (block in innerTrackBlocks) checkBothEnds(block)
		for (e in outerTrackblocks.entries) checkOneEnd(e.key, e.value)
	}

	private fun checkBothEnds(block: SimpleTrackBlock) {
		for (sep in block.ends()) {
			val railSem = Util.assertInstanceOf(RailSemaphore::class.java, sep)
			val dynamicSem = context.toDynamic(railSem) as DynamicRailSemaphore
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
		for (path in paths[sem]!!) {
			// zkusit postavit cestu
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
// 		 je v bloku vlak?
		if (block.getState() == State.FREE) return false
		if (block.getState() == State.OCCUPIED) {
			logger.debug { "Block occupied, checking if next semaphore is: ${to.name}" }
			// Compare using static references to avoid Dynamic wrapper identity issues
			val nextSem = block.getTrackOccupant().nextSemaphore()
			val nextSemStatic = when (nextSem) {
				is cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore -> nextSem.static
				is cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut -> nextSem.static
				else -> nextSem
			}
			if (nextSemStatic != to.static) return false
			return trySetupPaths(to)
		} else if (block.getState() == State.RESERVED) {
			logger.debug { "Block reserved, checking path setup for semaphore: ${to.name}" }
			// Use static separator for both getSecondEnd AND isSetUpPath
			// (SimpleTrack uses identity comparison, so Dynamic wrappers would fail the check)
			val staticSecondEnd = block.getSecondEnd(to.static)
			if (block.isSetUpPath(staticSecondEnd)) {
				return trySetupPaths(to)
			}
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
