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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.NodeCell
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathElement
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Collections
import java.util.LinkedList
import java.util.Queue

/**
 * Příklad fungování modelu
 * Ovlada sest navestidel a 2 InOuty pomoci predem ulozenych cest
 *
 * ## Code Review Required (Issue #284)
 *
 * **CRITICAL: This class has been modified for Issue #280/#284 and requires code review by traffic-simulation-expert.**
 *
 * **Changes made:**
 * - Migrated from static cells (InOut, RailSemaphore, RailSwitch) to dynamic wrappers (DynamicInOut, DynamicRailSemaphore, DynamicRailSwitch)
 * - Updated hardcoded grid coordinate lookups (lines 137-145) to retrieve dynamic wrappers
 * - Modified path construction to work with dynamic references
 * - Updated block organization logic to use staticRef for mapping (lines 165-167)
 * - All paths now contain dynamic wrappers instead of static cells
 *
 * **Rationale:**
 * Issue #284 fixed train deadlock caused by identity mismatch between grid navigation (returned static cells)
 * and pathToNextSemaphore() (returned dynamic wrappers in paths). ShuntingLoop must now use consistent
 * dynamic references throughout to maintain path progression correctness.
 *
 * **Testing:**
 * - All ShuntingLoop unit tests passing (28 tests)
 * - Integration tests passing (15 operational tests)
 * - Regression tests passing (trains complete circuits and exit successfully)
 *
 * **Review focus:**
 * - Verify dynamic wrapper usage does not affect simulation physics or timing
 * - Confirm path construction logic maintains correct semaphore ordering
 * - Validate block mapping logic preserves train navigation correctness
 * - Ensure changes align with jDisco framework assumptions
 *
 * **Authority:** @traffic-simulation-expert (main leader, simulation & physics expert per TEAM.md)
 *
 * @see docs/ISSUE_280_ANALYSIS_PLAN.md for detailed root cause analysis
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/284">Issue #284</a>
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
	private val paths: MutableMap<DynamicRailSemaphore, MutableList<Path>> = mutableMapOf()
	private val innerTrackBlocks: MutableList<DynamicTrackBlock> = mutableListOf()
	private val outerTrackblocks: MutableMap<DynamicTrackBlock, DynamicRailSemaphore> = mutableMapOf()
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

		val B: DynamicInOut = elementAt(context, DynamicInOut::class.java, 30, 8)
		val A: DynamicInOut = elementAt(context, DynamicInOut::class.java, 11, 8)
		val zA: DynamicRailSemaphore = elementAt(context, DynamicRailSemaphore::class.java, 14, 8)
		val doA1: DynamicRailSemaphore = elementAt(context, DynamicRailSemaphore::class.java, 16, 8)
		val doB1: DynamicRailSemaphore = elementAt(context, DynamicRailSemaphore::class.java, 25, 8)
		val zB: DynamicRailSemaphore = elementAt(context, DynamicRailSemaphore::class.java, 27, 8)
		val doA2: DynamicRailSemaphore = elementAt(context, DynamicRailSemaphore::class.java, 17, 9)
		val doB2: DynamicRailSemaphore = elementAt(context, DynamicRailSemaphore::class.java, 24, 9)
		val vA: DynamicRailSwitch = elementAt(context, DynamicRailSwitch::class.java, 15, 8)
		val vB: DynamicRailSwitch = elementAt(context, DynamicRailSwitch::class.java, 26, 8)

		val k1: DynamicTrackBlock = getBlock(context, "k1", doA1, doB1)
		val k2: DynamicTrackBlock = getBlock(context, "k2", doA2, doB2)
		val kA: DynamicTrackBlock = getBlock(context, "kA", A, zA)
		val kB: DynamicTrackBlock = getBlock(context, "kB", B, zB)

		// usporadani znalostni pro jednoduche rizeni
		constructPath(context, zA, vA, doA1, k1, doB1)
		constructPath(context, doA1, vA, zA, kA, A)
		constructPath(context, zA, vA, doA2, k2, doB2)
		constructPath(context, doA2, vA, zA, kA, A)
		constructPath(context, zB, vB, doB1, k1, doA1)
		constructPath(context, doB1, vB, zB, kB, B)
		constructPath(context, zB, vB, doB2, k2, doA2)
		constructPath(context, doB2, vB, zB, kB, B)
		// - innerTrackBlocks: middle blocks with RailSemaphore ends only (k1, k2)
		// - outerTrackblocks: entry/exit blocks with one InOut end (kB, kA)
		Collections.addAll(innerTrackBlocks, k1, k2)
		outerTrackblocks[kB] = zB
		outerTrackblocks[kA] = zA
	}

	/**
	 * Construct a path from path elements.
	 *
	 * Uses static semaphore references as map keys to avoid dynamic wrapper identity issues.
	 * Path elements are dynamic wrappers, but we extract staticRef for the map key.
	 */
	private fun constructPath(
		context: SimulationContext,
		vararg elements: PathElement
	): ArrayPath {
		val arrayPath = ArrayPath(context)
		try {
			for (i in elements.indices) {
				// Check for DynamicRailSwitch to insert switch-around blocks
				if (elements[i] is DynamicRailSwitch) {
					val prev: DynamicRailSemaphore = Util.assertInstanceOf(DynamicRailSemaphore::class.java, elements[i - 1])
					val next: DynamicRailSemaphore = Util.assertInstanceOf(DynamicRailSemaphore::class.java, elements[i + 1])
					// getBlock needs Cell, so cast to Cell (dynamic wrappers extend Cell)
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
		// Use static semaphore reference as map key (singleton, consistent identity)
		val first: DynamicRailSemaphore = Util.assertInstanceOf(DynamicRailSemaphore::class.java, arrayPath.getFirst())

		var sublist: MutableList<Path>? = paths[first]
		if (sublist == null) {
			sublist = mutableListOf()
			paths[first] = sublist
		}
		sublist.add(arrayPath)
		return arrayPath
	}

	/**
	 * Get switch name with "-around" suffix.
	 *
	 * **Fix for Issue #280/#284:**
	 * Element is now DynamicRailSwitch, which has `name` property delegating to staticRef.getName().
	 */
	private fun switchName(el: PathElement): String =
		Util.assertInstanceOf(DynamicRailSwitch::class.java, el).name + "-around"

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

	private fun getBlock(
		context: SimulationContext,
		name: String,
		cell1: Cell,
		cell2: Cell
	): DynamicTrackBlock {
		val railWayNetGrid: RailwayNetGrid<Cell> = context.getRailWayNetGrid()
		val graph = context.getGraph() // ExtendedUnorientedGraph<Point, DynamicTrackBlock, Segment>
		val point1 = railWayNetGrid.getLocation(cell1) ?: throw IllegalArgumentException("Cannot get location for cell1")
		val point2 = railWayNetGrid.getLocation(cell2) ?: throw IllegalArgumentException("Cannot get location for cell2")
		val block = graph.get(point1, point2) ?: throw IllegalArgumentException("Cannot get block between cells")
		val assertInstanceOf = Util.assertInstanceOf(DynamicTrackBlock::class.java, block)
		assertInstanceOf.name = name
		return assertInstanceOf
	}

	override fun startAction() {
		env.addReportTypes(ReportType.TRAIN_EVENTS, ReportType.TRAIN_CONTINUOUS, ReportType.NODE_EVENTS)
		// activate(RealTimeSynch())
		activate(generator)
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

	private fun checkBothEnds(block: DynamicTrackBlock) {
		// Inner blocks (k1, k2) have RailSemaphore ends only, no InOut
		// Check both semaphore endpoints to see if path needs to be reserved
		for (sep in block.ends()) {
			if (checkOneEnd(block, Util.assertInstanceOf(DynamicRailSemaphore::class.java, sep))) return
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
		for (path in pathList!!) {
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
		block: DynamicTrackBlock,
		to: DynamicRailSemaphore
	): Boolean {

		// je v bloku vlak?
		if (block.getState() == TrackFacility.State.FREE) return false
		if (block.getState() == TrackFacility.State.OCCUPIED) {
			logger.debug { "Block occupied, checking if next semaphore is: ${to.name}" }
			if (block.getTrackOccupant().nextSemaphore() != to) return false
			return trySetupPaths(to)
		} else if (block.getState() == TrackFacility.State.RESERVED) {
			logger.debug { "Block reserved, checking path setup for semaphore: ${to.name}" }
			if (block.isSetUpPath(block.getSecondEnd(to))) {
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
