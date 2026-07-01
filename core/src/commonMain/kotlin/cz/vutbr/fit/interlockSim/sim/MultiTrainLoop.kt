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

import cz.hovorka.kdisco.Process
import cz.hovorka.kdisco.Resource
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import cz.vutbr.fit.interlockSim.util.platformSleep
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * First slice of Goal 1: Multi-Train Simulation.
 *
 * This interlocking process demonstrates multiple trains running on a shared
 * railway network. Unlike [ShuntingLoop], which caps concurrency to the number
 * of parallel tracks in `vyhybna.xml`, [MultiTrainLoop] accepts deterministic
 * train specifications and uses kDisco's [Resource] primitive during path setup.
 *
 * ## kDisco `Resource` mapping
 *
 * Each physical [DynamicTrackBlock] is represented by a capacity-1 kDisco
 * [Resource]. The resource is used as a **setup-time capacity/FIFO gate**:
 * before reserving a full entry-to-exit path via
 * [PathReservationService.reservePath], the dispatcher atomically checks that
 * every required block resource is available, reserves all of them, calls the
 * ownership service, and immediately releases the resources again.
 *
 * - **kDisco [Resource]** = physical-capacity serialization during dispatcher
 *   setup (ensures the dispatcher never holds some resources while waiting
 *   for others, which would deadlock).
 * - **[PathReservationService]** / **[cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry]** =
 *   ownership registry, switch locking and signal configuration that protects
 *   the blocks for the whole train journey.
 *
 * ## Determinism
 *
 * Train injection times come from caller-supplied specs, so runs are repeatable
 * when paired with a fixed kDisco seed. No standalone [cz.hovorka.kdisco.Random]
 * generator is required.
 *
 * ## Scope limitations (documented)
 *
 * This slice reserves the **full entry-to-exit path** for each train before
 * activation. That guarantees no intra-slice collisions and avoids touching the
 * existing [Train] process, but it serializes trains that share any block.
 * Future slices can move to incremental reservation following the
 * [ShuntingLoop] semaphore-polling pattern to obtain true block-sharing
 * concurrency.
 *
 * @see Resource
 * @see PathReservationService
 * @see ShuntingLoop
 */
open class MultiTrainLoop(
	context: SimulationContext,
	private val endTime: Long,
	private val trainSpecs: List<TrainSpec> = emptyList(),
	private val enableRealTimeSync: Boolean = false,
	initialSpeedMultiplier: Double = 1.0,
	private val maxConcurrentTrains: Int = DEFAULT_MAX_CONCURRENT_TRAINS,
	private val pathReservationService: PathReservationService = context.getRoutingServices().getPathReservationService()
) : Interlocking(context),
	SpeedControllable {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default concurrency cap for the first slice. */
		internal const val DEFAULT_MAX_CONCURRENT_TRAINS: Int = 10

		/** Report types enabled by the multi-train scenario. */
		internal val ENABLED_REPORT_TYPES =
			arrayOf(
				ReportType.TRAIN_APPROVED,
				ReportType.TRAIN_EVENTS,
				ReportType.TRAIN_CONTINUOUS,
				ReportType.NODE_EVENTS
			)
	}

	/**
	 * Specification of a single train to inject into the simulation.
	 *
	 * @property inName name of the entry [DynamicInOut]
	 * @property outName name of the exit [DynamicInOut]
	 * @property inTime simulation time at which the train becomes available
	 * @property length train length in meters
	 */
	data class TrainSpec(
		val inName: String,
		val outName: String,
		val inTime: Double,
		val length: Double = 40.0
	)

	@kotlin.concurrent.Volatile
	override var speedMultiplier: Double = initialSpeedMultiplier
		set(value) {
			require(value > 0.0) { "Speed multiplier must be positive, got: $value" }
			field = value
		}

	init {
		require(initialSpeedMultiplier > 0.0) {
			"Speed multiplier must be positive, got: $initialSpeedMultiplier"
		}
		require(maxConcurrentTrains > 0) {
			"maxConcurrentTrains must be positive, got: $maxConcurrentTrains"
		}
	}

	private val topologyNavigator: TopologyNavigator = context.getRoutingServices().getTopologyNavigator()
	private val blockResources: BlockResourceRegistry = BlockResourceRegistry(context)

	private val pendingSpecs: ArrayDeque<TrainSpec> = ArrayDeque(trainSpecs.sortedBy { it.inTime })
	private val unapprovedTrains: ArrayDeque<Train> = ArrayDeque()
	private val approvedTrains: MutableList<Train> = mutableListOf()
	private val trainToSpec: MutableMap<Train, TrainSpec> = mutableMapOf()
	private val generator: DeterministicGenerator = DeterministicGenerator(context)

	// Test-observability counters (#365 pattern).
	private var trainsEnteredCount: Int = 0
	private var trainsExitedCount: Int = 0
	private var maxConcurrentTrainsCount: Int = 0

	private inner class RealTimeSynch : LoopProcess() {
		private var beginTime: Long = 0

		override suspend fun startAction() {
			interLoopSleep()
		}

		override suspend fun iteration() {
			val iterationEndTime = currentTimeMillisKMP()
			val targetInterval = (1000.0 / speedMultiplier).toLong()
			val sleepTime: Long = targetInterval - (iterationEndTime - beginTime)
			if (sleepTime > 10) {
				// platformSleep restores the interrupt flag on InterruptedException (JVM only).
				// Simulation termination is handled by LoopProcess.terminate() setting the flag
				// which is checked between iterations.
				platformSleep(sleepTime)
			}
		}

		override suspend fun interLoopSleep() {
			beginTime = currentTimeMillisKMP()
			hold(1.0)
		}
	}

	private inner class DeterministicGenerator(
		context: SimulationEnvironment
	) : Generator(context, shuffleInOuts = false) {
		override suspend fun iteration() {
			if (pendingSpecs.isEmpty()) {
				terminate()
				return
			}
			val spec = pendingSpecs.first()
			val now = time()
			if (now < spec.inTime) {
				return
			}
			pendingSpecs.removeFirst()

			val inOuts = env.getInOuts().toList()
			val inIo =
				requireSimulationNotNull(inOuts.find { it.name == spec.inName }) {
					"No DynamicInOut named '${spec.inName}' in context"
				}
			val outIo =
				requireSimulationNotNull(inOuts.find { it.name == spec.outName }) {
					"No DynamicInOut named '${spec.outName}' in context"
				}
			val timetable =
				Timetable(
					inIo,
					outIo,
					Time(spec.inTime),
					Time(spec.inTime + 1000.0),
					spec.length
				)
			val train = Train(env, timetable)
			trainToSpec[train] = spec
			logger.debug {
				"MultiTrainLoop: generated ${train.name} (${spec.inName} -> ${spec.outName}) at t=$now"
			}
			placeTrain(train)
			trains.add(train)
		}

		override fun placeTrain(train: Train) {
			unapprovedTrains.addLast(train)
			trainsEnteredCount++
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	override suspend fun startAction() {
		env.addReportTypes(*ENABLED_REPORT_TYPES)
		if (enableRealTimeSync) {
			activate(RealTimeSynch())
		}
		Process.activate(generator)
	}

	override suspend fun iteration() {
		cleanupTerminatedTrains()
		approveTrains()
		if (approvedTrains.size > maxConcurrentTrainsCount) {
			maxConcurrentTrainsCount = approvedTrains.size
		}
		startApprovedTrains()
		blockResources.releaseFreeResources()
		hold(1.0)
	}

	private fun cleanupTerminatedTrains() {
		val iter = approvedTrains.iterator()
		while (iter.hasNext()) {
			val train = iter.next()
			if (train.terminated()) {
				iter.remove()
				trainToSpec.remove(train)
				trainsExitedCount++
				logger.debug { "MultiTrainLoop: ${train.name} completed journey" }
			}
		}
	}

	private fun approveTrains() {
		while (approvedTrains.size < maxConcurrentTrains && unapprovedTrains.isNotEmpty()) {
			val train = unapprovedTrains.removeFirst()
			approvedTrains.add(train)
			logger.debug { "MultiTrainLoop: approved ${train.name} for dispatch" }
		}
	}

	private suspend fun startApprovedTrains() {
		for (train in approvedTrains) {
			if (trainHasActivePath(train)) {
				continue
			}
			val spec = trainToSpec[train] ?: continue
			val reserved = reserveEntryPath(train, spec)
			if (reserved) {
				logger.info { "MultiTrainLoop: starting ${train.name}" }
				Process.activate(train)
			}
		}
	}

	private fun trainHasActivePath(train: Train): Boolean =
		pathReservationService.getReservedBlocks(train.name).isNotEmpty()

	/**
	 * Atomically reserve the physical resources for every block on an
	 * entry-to-exit path, then register the path via [PathReservationService].
	 *
	 * The resource reservations are checked non-blocking (`isAvailable`) and only
	 * acquired when all of them can be. Once acquired, [reservePath] should
	 * succeed because no other dispatcher attempt can hold those resources.
	 * Resources are released immediately afterwards, before the train starts:
	 * the actual journey-time exclusivity is enforced by
	 * [PathReservationService].
	 */
	private suspend fun reserveEntryPath(
		train: Train,
		spec: TrainSpec
	): Boolean {
		val inOuts = env.getInOuts().toList()
		val inIo = inOuts.find { it.name == spec.inName } ?: return false
		val outIo = inOuts.find { it.name == spec.outName } ?: return false

		val candidatePaths = topologyNavigator.findAllTopologicalPaths(inIo, outIo, maxDepth = 100)
		if (candidatePaths.isEmpty()) {
			logger.error { "MultiTrainLoop: no topological path ${spec.inName} -> ${spec.outName} for ${train.name}" }
			return false
		}

		for (path in candidatePaths) {
			val blocks = extractUniqueBlocks(path)
			if (blocks.isEmpty()) {
				continue
			}

			if (!blockResources.areAllAvailable(blocks)) {
				logger.debug {
					"MultiTrainLoop: not all blocks available for ${train.name}, will retry"
				}
				continue
			}

			val acquired = mutableListOf<Resource>()
			try {
				for (block in blocks) {
					blockResources.resourceFor(block).reserve(1)
					acquired.add(blockResources.resourceFor(block))
				}

				when (val result = pathReservationService.reservePath(train.name, inIo, outIo)) {
					is PathReservationService.ReservationResult.Success -> {
						logger.debug {
							"MultiTrainLoop: reserved ${blocks.size} block(s) for ${train.name}"
						}
						return true
					}
					is PathReservationService.ReservationResult.NoPathExists -> {
						logger.warn { "MultiTrainLoop: no path ${spec.inName} -> ${spec.outName} for ${train.name}" }
					}
					is PathReservationService.ReservationResult.AllPathsBlocked -> {
						logger.debug {
							"MultiTrainLoop: all paths blocked for ${train.name}, will retry"
						}
					}
					is PathReservationService.ReservationResult.Conflict -> {
						logger.warn {
							"MultiTrainLoop: conflict for ${train.name} on " +
								"${result.conflictingBlock.name ?: "unnamed"} owned by ${result.existingOwner}"
						}
					}
				}
			} finally {
				for (resource in acquired) {
					releaseIfOccupied(resource)
				}
			}
		}
		return false
	}

	private fun releaseIfOccupied(resource: Resource) {
		if (resource.occupied > 0) {
			resource.release(1)
		}
	}

	private fun extractUniqueBlocks(path: List<TrackSection>): List<DynamicTrackBlock> {
		val seen = mutableSetOf<DynamicTrackBlock>()
		return path.mapNotNull { section ->
			val block = section.getTrackBlock()
			if (block is DynamicTrackBlock && seen.add(block)) block else null
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

	/** Test-observability: number of trains that entered the pending queue. */
	fun getTrainsEntered(): Int = trainsEnteredCount

	/** Test-observability: number of trains that completed their journey. */
	fun getTrainsExited(): Int = trainsExitedCount

	/** Test-observability: peak number of concurrently approved trains. */
	fun getMaxConcurrentTrains(): Int = maxConcurrentTrainsCount

	/** Test-observability: number of block resources currently occupied. */
	fun getOccupiedResourceCount(): Int = blockResources.occupiedCount()
}
