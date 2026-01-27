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
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.ArrayPath
import cz.vutbr.fit.interlockSim.objects.paths.Path
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
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
		private const val MAX_TRAINS: Int = 2 // maximalni pocet odsouhlasených vlaků v systému
	}

	// fronta neodsouhlasenych - za jinych okolnosti seznam ze ktereho si dispecer vybere
	private val unapprowedTrains: Queue<Train> = LinkedList<Train>()
	private val approwedTrains: MutableList<Train> = mutableListOf()
	private val generator: InnerGenerator
	private val innerTrackBlocks: MutableList<DynamicTrackBlock> = mutableListOf()
	private val outerTrackblocks: MutableMap<DynamicTrackBlock, DynamicRailSemaphore> = mutableMapOf()
	private val endTime: Long

	// Navigation service for dynamic path finding (Issue #296)
	// Note: navigator is initialized after context is set in constructor
	private lateinit var navigator: TopologyNavigator

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

		// Initialize navigator via Koin DI (Issue #296)
		navigator = context.scope.get()

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

	/**
	 * Try to set up a path from the given semaphore using dynamic path discovery.
	 *
	 * Issue #296: Refactored to use TopologyNavigator for on-demand path finding
	 * instead of pre-constructed paths.
	 *
	 * Algorithm:
	 * 1. Determine target separator based on network topology
	 * 2. Use navigator to find all possible paths
	 * 3. For each path, construct ArrayPath and try to set it up
	 * 4. Return true if any path setup succeeds, false otherwise
	 */
	private fun trySetupPaths(sem: DynamicRailSemaphore): Boolean {
		logger.debug { "Attempting to setup paths from semaphore: ${sem.name}" }

		// Find all potential target separators (InOuts and other semaphores)
		val targets = findPotentialTargets(sem)

		for (target in targets) {
			logger.debug { "Trying to find path from ${sem.name} to ${getTargetName(target)}" }

			// Find all topological paths from sem to target
			val topologicalPaths = navigator.findAllTopologicalPaths(sem, target, maxDepth = 50)

			for (pathSections in topologicalPaths) {
				try {
					// Convert list of TrackSections to ArrayPath
					val path = buildPathFromSections(sem, pathSections, target)

					// Try to set up this path
					if (path.isSetUpPath(sem) || trySetupPath(path)) {
						logger.debug { "Path setup successful from semaphore: ${sem.name} to ${getTargetName(target)}" }
						return true
					}
				} catch (e: TrackOperationException) {
					logger.debug { "Path setup failed (trying next): ${e.message}" }
					// Continue to next path
				}
			}
		}

		logger.debug { "All path setup attempts failed from semaphore: ${sem.name}" }
		return false
	}

	/**
	 * Find potential target separators from a given source semaphore.
	 *
	 * Returns list of separators that could be valid path destinations.
	 */
	private fun findPotentialTargets(from: DynamicRailSemaphore): List<PathSeparator> {
		val targets = mutableListOf<PathSeparator>()

		// Add all InOut elements as potential targets
		val inouts = env.getInOuts()
		for (dynamicInOut in inouts) {
			if (dynamicInOut != from) {
				targets.add(dynamicInOut)
			}
		}

		// Add relevant semaphores based on known topology
		// For vyhybna.xml: zA, doA1, doA2, doB1, doB2, zB
		val context = env as SimulationContext
		val grid: RailwayNetGrid<Cell> = context.getRailWayNetGrid()
		for (x in 0 until 50) {
			for (y in 0 until 20) {
				val cell = grid.getCellAt(x, y)
				if (cell is DynamicRailSemaphore && cell != from) {
					targets.add(cell)
				}
			}
		}

		return targets
	}

	/**
	 * Build an ArrayPath from a list of TrackSections returned by TopologyNavigator.
	 */
	private fun buildPathFromSections(
		start: PathSeparator,
		sections: List<TrackSection>,
		end: PathSeparator
	): ArrayPath {
		val path = ArrayPath(env as SimulationContext)

		// Add start separator
		path.addLast(start)

		// Add track sections (which include separators and blocks)
		var lastSeparator: PathSeparator = start
		for (section in sections) {
			val staticBlock = section.getTrackBlock()
			val nextSep = section.getSecondEnd(lastSeparator)

			// Convert static TrackBlock to DynamicTrackBlock
			val dynamicTrack = env.toDynamic(staticBlock as TrackFacility)
			// DynamicTrackBlock implements PathElement, so safe to cast
			val dynamicBlock = dynamicTrack as DynamicTrackBlock
			path.addLast(dynamicBlock)
			path.addLast(nextSep)

			lastSeparator = nextSep
		}

		return path
	}

	/**
	 * Get display name for a target separator (for logging).
	 */
	private fun getTargetName(target: PathSeparator): String = when (target) {
		is DynamicRailSemaphore -> target.name ?: "unnamed_semaphore"
		is DynamicInOut -> target.staticRef.getName() ?: "unnamed_inout"
		else -> target.toString()
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
