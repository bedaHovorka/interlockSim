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
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
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
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import cz.vutbr.fit.interlockSim.util.platformSleep
import cz.hovorka.kdisco.Process
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent

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
class ShuntingLoop(
	context: SimulationContext,
	private val endTime: Long,
	private val enableRealTimeSync: Boolean = false,
	private val speedMultiplier: Double = 1.0,
	private val pathReservationService: PathReservationService = context.getPathReservationService()
) : Interlocking(context),
	KoinComponent {
	init {
		require(speedMultiplier > 0.0) { "Speed multiplier must be positive, got: $speedMultiplier" }
	}

	// Inject registry for idempotent path reservation checks
	private val registry: PathReservationRegistry by lazy {
		context.scope.get<PathReservationRegistry>()
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		// Physical limit: only 2 parallel tracks (k1 and k2) in shunting loop
		// MAX_TRAINS = 2 enforces physical capacity constraint
		// All 5 generated trains complete sequentially via queueing mechanism
		private const val MAX_TRAINS: Int = 2

		/** Report types enabled by the shunting-loop simulation scenario. */
		internal val ENABLED_REPORT_TYPES = arrayOf(
			ReportType.TRAIN_APPROVED,
			ReportType.TRAIN_EVENTS,
			ReportType.TRAIN_CONTINUOUS,
			ReportType.NODE_EVENTS
		)
	}

	// fronta neodsouhlasenych - za jinych okolnosti seznam ze ktereho si dispecer vybere
	private val unapprowedTrains: ArrayDeque<Train> = ArrayDeque()
	private val approwedTrains: MutableList<Train> = mutableListOf()
	private val generator: InnerGenerator = InnerGenerator(context)
	private val innerTrackBlocks: MutableList<DynamicTrackBlock> = mutableListOf()
	private val outerTrackblocks: MutableMap<DynamicTrackBlock, DynamicRailSemaphore> = mutableMapOf()

	// Test-observability counters (#365) — incremented from existing lifecycle sites only.
	// Not atomic: ShuntingLoop runs on the single kDisco dispatcher thread, so all increment
	// sites (placeTrain, iteration, tryReservePathFrom) serialize naturally. If a future
	// dispatcher becomes concurrent, these need atomicfu.
	private var trainsEnteredCount: Int = 0
	private var trainsExitedCount: Int = 0
	private var maxConcurrentTrainsCount: Int = 0
	private val blockTransitionsByTrain: MutableMap<String, Int> = mutableMapOf()

	private inner class RealTimeSynch : LoopProcess() {
		private var presvihnuto: Double = 0.0
		private var beginTime: Long = 0

		override suspend fun startAction() {
			interLoopSleep()
		}

		override suspend fun iteration() {
			val iterationEndTime: Long = currentTimeMillisKMP()
			val targetInterval = (1000.0 / speedMultiplier).toLong()
			val sleepTime: Long = targetInterval - (iterationEndTime - beginTime)
			if (sleepTime > 10) {
				// platformSleep restores the interrupt flag on InterruptedException (JVM only).
				// Simulation termination is handled by LoopProcess.terminate() setting the flag
				// which is checked between iterations — platformSleep interrupt does not cause a tight loop.
				platformSleep(sleepTime)
			} else if (sleepTime < 0) {
				presvihnuto = sleepTime / 1000.0
			}
		}

		override suspend fun interLoopSleep() {
			beginTime = currentTimeMillisKMP()
			hold(1 + presvihnuto)
			presvihnuto = 0.0
		}
	}

	private inner class InnerGenerator(
		context: SimulationEnvironment
	) : Generator(context) {
		override fun placeTrain(train: Train) {
			unapprowedTrains.addLast(train)
			trainsEnteredCount++
			blockTransitionsByTrain[train.name] = 0
		}
	}

	init {
		requireSimulation(context.getGraph().size() > 0) {
			"Railway network graph is empty - must be loaded from vyhybna.xml first"
		}
		// Sit jiz musi byt nactena z vyhybna.xml !!!

		val B: DynamicInOut = elementAt<DynamicInOut>(context, 30, 8)
		val A: DynamicInOut = elementAt<DynamicInOut>(context, 11, 8)
		val zA: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, 14, 8)
		val doA1: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, 16, 8)
		val doB1: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, 25, 8)
		val zB: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, 27, 8)
		val doA2: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, 17, 9)
		val doB2: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, 24, 9)
		val vA: DynamicRailSwitch = elementAt<DynamicRailSwitch>(context, 15, 8)
		val vB: DynamicRailSwitch = elementAt<DynamicRailSwitch>(context, 26, 8)

		val k1: DynamicTrackBlock = getBlock(context, "k1", doA1, doB1)
		val k2: DynamicTrackBlock = getBlock(context, "k2", doA2, doB2)
		val kA: DynamicTrackBlock = getBlock(context, "kA", A, zA)
		val kB: DynamicTrackBlock = getBlock(context, "kB", B, zB)

		// Issue #296: Removed manual path construction (~100 lines)
		// Paths are now discovered dynamically using TopologyNavigator when needed
		// - innerTrackBlocks: middle blocks with RailSemaphore ends only (k1, k2)
		// - outerTrackblocks: entry/exit blocks with one InOut end (kB, kA)
		innerTrackBlocks.addAll(listOf(k1, k2))
		outerTrackblocks[kB] = zB
		outerTrackblocks[kA] = zA
	}

	private inline fun <reified T : Cell> elementAt(
		context: SimulationContext,
		x: Int,
		y: Int
	): T {
		val railWayNetGrid: RailwayNetGrid<Cell> = context.getRailWayNetGrid()
		val cell = railWayNetGrid.getCellAt(x, y) ?: throw IllegalArgumentException("No cell at position ($x, $y)")
		return Util.assertInstanceOf(cell)
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
		val dynamicBlock = Util.assertInstanceOf<DynamicTrackBlock>(block)
		dynamicBlock.name = name
		return dynamicBlock
	}

	override suspend fun startAction() {
		env.addReportTypes(*ENABLED_REPORT_TYPES)

		// Conditionally activate real-time synchronization for GUI mode
		if (enableRealTimeSync) {
			activate(RealTimeSynch())
		}

		Process.activate(generator)
	}

	override suspend fun iteration() {
		// stare vlaky
		val iter: MutableIterator<Train> = approwedTrains.iterator()
		while (iter.hasNext()) {
			val element: Train = iter.next()
			if (element.terminated()) {
				iter.remove()
				trainsExitedCount++
			}
		}
		// nove vlaky a inouty
		approveTrains()
		if (approwedTrains.size > maxConcurrentTrainsCount) {
			maxConcurrentTrainsCount = approwedTrains.size
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
			if (checkOneEnd(block, Util.assertInstanceOf<DynamicRailSemaphore>(sep))) return
		}
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
		val result = pathReservationService.reservePathToAnyNextSemaphore(trainName, sem)

		return when (result) {
			is PathReservationService.ReservationResult.Success -> {
				logger.debug { "Reserved path from ${sem.name} for $trainName" }
				// KMP-safe increment: Map.merge() is a JVM-only default method.
				blockTransitionsByTrain[trainName] = (blockTransitionsByTrain[trainName] ?: 0) + 1
				true
			}
			is PathReservationService.ReservationResult.Conflict -> {
				logger.warn {
					"Conflict for $trainName at ${sem.name}: " +
						"block ${result.conflictingBlock.name ?: "unnamed"} " +
						"owned by ${result.existingOwner}"
				}
				false
			}
			is PathReservationService.ReservationResult.NoPathExists -> {
				logger.debug { "No path exists from ${sem.name} for $trainName" }
				false
			}
			is PathReservationService.ReservationResult.AllPathsBlocked -> {
				logger.debug {
					"All paths blocked from ${sem.name} for $trainName " +
						"(attempted: ${result.attemptedPaths})"
				}
				false
			}
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

			// NEW: Idempotent check - skip if path already extends beyond this semaphore
			if (registry.isPathExtendedBeyond(occupant.name, to)) {
				logger.debug {
					"Path already extends beyond ${to.name} for ${occupant.name}, " +
						"skipping redundant reservation"
				}
				return true // ← Early exit, like working tag's pattern
			}

			logger.debug { "Train ${occupant.name} approaching ${to.name}, reserving forward path" }
			return tryReservePathFrom(to, occupant.name)
		}

		if (block.getState() == TrackFacility.State.RESERVED) {
			// Check if path is already set up through this semaphore
			val otherEnd = block.getSecondEnd(to)
			if (block.isSetUpPath(env.toDynamic(otherEnd))) {
				val trainName = requireSimulationNotNull(block.trainName)

				// NEW: Idempotent check for reserved blocks too
				if (registry.isPathExtendedBeyond(trainName, to)) {
					logger.debug {
						"Path already extends beyond ${to.name} for $trainName " +
							"(reserved block), skipping"
					}
					return true
				}

				logger.debug { "Path already set up through ${to.name}, attempting extension" }
				return tryReservePathFrom(to, trainName)
			}
		}

		return false
	}

	private fun approveTrains() {
		while (approwedTrains.size < MAX_TRAINS && unapprowedTrains.isNotEmpty()) {
			val poll: Train = unapprowedTrains.removeFirst()
			approwedTrains.add(poll)
			activate(poll)
		}
	}

	override suspend fun interLoopSleep() {
		if (time() >= endTime) {
			generator.terminate()
			env.stop()
			return
		}
		hold(1.0)
	}

	/**
	 * Test-observability instrumentation (#365).
	 *
	 * Downstream consumers (per 2026-04-14 backlog election — see
	 * docs/election/2026-04-14-backlog-election.md):
	 *
	 * High impact (score 2):
	 *   - #366 Individual train movement tests with dedicated test process
	 *   - #195 Phase 4.1: Golden Output Tests
	 *   - #198 Phase 4.4: Regression Testing
	 *   - #197 Phase 4.3: Integration Tests
	 *   - #196 Phase 4.2: Performance Benchmarks
	 *   - #453 Increase test coverage — next volume
	 *
	 * Supporting (score 1):
	 *   - #376 SonarCloud new-code coverage quality gate
	 *   - #187 Goal 7: Simulation Speed Control
	 *   - #435 fast-sim configurable simulation process
	 *
	 * Counters are incremented from existing lifecycle sites; no new
	 * polling loop is introduced.
	 */
	fun getTrainsEntered(): Int = trainsEnteredCount

	fun getTrainsExited(): Int = trainsExitedCount

	fun getMaxConcurrentTrains(): Int = maxConcurrentTrainsCount

	fun getBlockTransitions(trainId: String): Int = blockTransitionsByTrain[trainId] ?: 0

	fun getAllBlockTransitions(): Map<String, Int> = blockTransitionsByTrain.toMap()
}
