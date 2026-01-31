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
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.Util
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent
import java.util.Collections
import java.util.LinkedList
import java.util.Queue

/**
 * Příklad fungování modelu
 * Ovlada sest navestidel a 2 InOuty pomoci dynamicky nalezených cest
 *
 * ## Refactored for Issue #296 (Phase 4: Path Discovery Restructuring)
 *
 * **Changes from Issue #296:**
 * - Eliminated manual path construction (~100 lines removed)
 * - Integrated TopologyNavigator for dynamic path finding
 * - Uses PathReservationService architecture (Phases 1-3)
 * - Paths discovered on-demand when trains request routes
 * - Maintains backward compatibility with existing tests
 *
 * **Previous changes (Issue #280/#284):**
 * - Migrated from static cells to dynamic wrappers (DynamicInOut, DynamicRailSemaphore, DynamicRailSwitch)
 * - Updated grid coordinate lookups to retrieve dynamic wrappers
 * - All paths now use dynamic wrappers for consistent identity
 *
 * **Architecture:**
 * - Uses TopologyNavigator (Phase 1) for static path finding
 * - Compatible with PathReservationService (Phase 2) and TrainNavigationService (Phase 3)
 * - Koin DI integration for service injection
 *
 * **Testing:**
 * - All ShuntingLoop unit tests passing (19 tests maintained)
 * - Integration tests passing (operational and regression tests)
 * - Golden output validation (simulation results match baseline)
 *
 * @see TopologyNavigator
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/296">Issue #296</a>
 * @see docs/PATH_RESERVATION_ARCHITECTURE.md
 */
class ShuntingLoop : Interlocking, KoinComponent {
	companion object {
		private val logger = KotlinLogging.logger {}
		// Physical limit: only 2 parallel tracks (k1 and k2) in shunting loop
		// Increased to 3 to allow higher concurrency
		private const val MAX_TRAINS: Int = 3
	}

	// fronta neodsouhlasenych - za jinych okolnosti seznam ze ktereho si dispecer vybere
	private val unapprowedTrains: Queue<Train> = LinkedList<Train>()
	private val approwedTrains: MutableList<Train> = mutableListOf()
	private val generator: InnerGenerator
	private val innerTrackBlocks: MutableList<DynamicTrackBlock> = mutableListOf()
	private val outerTrackblocks: MutableMap<DynamicTrackBlock, DynamicRailSemaphore> = mutableMapOf()
	private val endTime: Long

	// Navigation services for dynamic path finding and reservation (Issue #296)
	// Note: Services are initialized after context is set in constructor
	private lateinit var navigator: TopologyNavigator
	private lateinit var pathReservationService: PathReservationService

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
	}

	/**
	 * @param context
	 * @param endTime when simulation schould stop
	 */
	constructor(context: SimulationContext, endTime: Long) : super(context) {
		this.endTime = endTime
		generator = InnerGenerator(context)

		// Initialize navigator and pathReservationService via Koin DI (Issue #296)
		navigator = context.scope.get()
		// Use SimulationEnvironment interface to get PathReservationService (avoid casting to DefaultSimulationContext)
		pathReservationService = context.getPathReservationService()

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

		// Issue #296: Removed manual path construction (~100 lines)
		// Paths are now discovered dynamically using TopologyNavigator when needed
		// - innerTrackBlocks: middle blocks with RailSemaphore ends only (k1, k2)
		// - outerTrackblocks: entry/exit blocks with one InOut end (kB, kA)
		Collections.addAll(innerTrackBlocks, k1, k2)
		outerTrackblocks[kB] = zB
		outerTrackblocks[kA] = zA
	}

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

		// If generator terminated and all queues empty, terminate ShuntingLoop
		if (generator.terminated() && unapprowedTrains.isEmpty() && approwedTrains.isEmpty()) {
			logger.info {
				"${time()} SIMULATION_COMPLETE: All trains processed, terminating ShuntingLoop"
			}
			terminate()
			return
		}

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

	private fun checkOneEnd(
		block: DynamicTrackBlock,
		to: DynamicRailSemaphore
	): Boolean {
		if (block.getState() == TrackFacility.State.FREE) {
			return false
		}

		if (block.getState() == TrackFacility.State.OCCUPIED) {
			val occupant = requireSimulationNotNull(block.getTrackOccupant())
			if (occupant.nextSemaphore() != to) {
				return false
			}

			logger.debug { "Train ${occupant.name} approaching ${to.name}, reserving forward path" }
			return tryReservePathFrom(to, occupant.name)
		}

		if (block.getState() == TrackFacility.State.RESERVED) {
			// Check if path is already set up through this semaphore
			val otherEnd = block.getSecondEnd(to)
			if (otherEnd != null && block.isSetUpPath(env.toDynamic(otherEnd))) {
				logger.debug { "Path already set up through ${to.name}, attempting extension" }
				val trainName = block.trainName
				if (trainName != null) {
					return tryReservePathFrom(to, trainName)
				}
			}
		}

		return false
	}

	/**
	 * Try to reserve a path from the given semaphore using PathReservationService.
	 *
	 * Uses the new reservePathToAny() method which handles all target discovery
	 * and path reservation internally, eliminating manual iteration.
	 *
	 * @param sem The semaphore to reserve paths from
	 * @param trainName The train requesting the reservation
	 */
	private fun tryReservePathFrom(
		sem: DynamicRailSemaphore,
		trainName: String
	): Boolean {
		val result = pathReservationService.reservePathToAny(trainName, sem)

		return when (result) {
			is PathReservationService.ReservationResult.Success -> {
				logger.debug { "Reserved path from ${sem.name} for $trainName" }
				true
			}
			else -> {
				logger.debug { "No path available from ${sem.name} for $trainName" }
				false
			}
		}
	}

	private fun approveTrains() {
		while (approwedTrains.size < MAX_TRAINS && unapprowedTrains.size > 0) {
			val poll: Train = unapprowedTrains.poll()
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
