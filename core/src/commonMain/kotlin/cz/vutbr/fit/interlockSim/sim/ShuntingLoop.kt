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
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.exceptions.requireSimulation
import cz.vutbr.fit.interlockSim.exceptions.requireSimulationNotNull
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.util.Util
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import cz.vutbr.fit.interlockSim.util.platformSleep
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.component.KoinComponent

/**
 * Příklad fungování modelu
 * Ovlada sest navestidel a 2 InOuty pomoci dynamicky nalezených cest
 *
 * ## Refactored for Issue #540 (SP0.1 — Goal 10: control/kernel seam)
 *
 * **Changes from Issue #540:**
 * - Inline dispatch logic (`approveTrains`, `checkAllInputs`, `checkInput`) extracted
 *   into [RuleBasedDispatcher] behind the [Dispatcher] seam.
 * - ShuntingLoop is now a thin process shell: it manages the kDisco lifecycle
 *   (`hold`, `terminate`, `activate`) and delegates every dispatch decision to the
 *   injected [Dispatcher].
 * - The [dispatcher] parameter defaults to [RuleBasedDispatcher] so all existing
 *   callers (fast-sim, tests) continue to work without modification.
 *
 * ## Reshaped for Issue #729 (SP0.7 — Goal 10: pure decide() seam)
 *
 * **Changes from Issue #729:**
 * - [Dispatcher] is now a pure function: `decide(observed: DispatchObservation): List<DispatchDecision>`.
 *   `DispatcherTickContext` (callback-based) is removed.
 * - ShuntingLoop builds a [DispatchObservation] each phase ([buildAdmissionObservation],
 *   [buildPathAdvancementObservation]) and applies the returned decisions inline
 *   ([applyDecisions]) — synchronously, on the single kDisco thread, exactly as
 *   before. This is the self-contained, in-kernel slice of the seam; the future
 *   lifted, cross-thread driver (SP0.8–SP0.10) is a separate, later change.
 *
 * **Previous changes (Issue #296):**
 * - Eliminated manual path construction (~100 lines removed)
 * - Integrated TopologyNavigator for dynamic path finding
 * - Uses PathReservationService architecture (Phases 1-3)
 *
 * **Previous changes (Issue #280/#284):**
 * - Migrated from static cells to dynamic wrappers (DynamicInOut, DynamicRailSemaphore, DynamicRailSwitch)
 *
 * **Architecture:**
 * - Uses TopologyNavigator (Phase 1) for static path finding
 * - [Dispatcher] seam (Issue #540) for pluggable control policy
 * - Koin DI integration for service injection
 *
 * ## SP0.9 — Cross-thread command queue draining (Issue #731)
 *
 * An optional [ControlStepListener] can be registered via [controlStepListener]
 * before the simulation starts. If set, [iteration] calls [ControlStepListener.onControlStep]
 * at the top of every tick, giving the SP0.9
 * [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
 * an opportunity to drain the [ActuatorCommandQueue][cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue]
 * and apply pending decisions via actuator ports — all on the kDisco sim thread.
 *
 * @param dispatcher Dispatch policy; defaults to [RuleBasedDispatcher].
 *   Pass a custom implementation to override admission and path-advancement logic.
 *
 * @see RuleBasedDispatcher
 * @see Dispatcher
 * @see ControlStepListener
 * @see TopologyNavigator
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/540">Issue #540 (SP0.1)</a>
 * @see docs/PATH_RESERVATION_ARCHITECTURE.md
 */
class ShuntingLoop(
	context: SimulationContext,
	private val endTime: Long,
	private val enableRealTimeSync: Boolean = false,
	initialSpeedMultiplier: Double = 1.0,
	private val pathReservationService: PathReservationService = context.getRoutingServices().getPathReservationService(),
	val dispatcher: Dispatcher = RuleBasedDispatcher()
) : Interlocking(context),
	SpeedControllable,
	KoinComponent {
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
	}

	// Inject registry for idempotent path reservation checks
	private val registry: PathReservationRegistry by lazy {
		context.scope.get<PathReservationRegistry>()
	}

	companion object {
		private val logger = KotlinLogging.logger {}

		/** Report types enabled by the shunting-loop simulation scenario. */
		internal val ENABLED_REPORT_TYPES =
			arrayOf(
				ReportType.TRAIN_APPROVED,
				ReportType.TRAIN_EVENTS,
				ReportType.TRAIN_CONTINUOUS,
				ReportType.NODE_EVENTS
			)

		// Vyhybna network coordinate contract.
		// Single source of truth: ShuntingLoop.init uses these and
		// ShuntingLoopNetworkValidator (in :fast-sim) iterates them.
		const val COORD_IN_A_X = 11
		const val COORD_IN_A_Y = 8
		const val COORD_IN_B_X = 30
		const val COORD_IN_B_Y = 8
		const val COORD_SW_A_X = 15
		const val COORD_SW_A_Y = 8
		const val COORD_SW_B_X = 26
		const val COORD_SW_B_Y = 8
		const val COORD_SEM_ZA_X = 14
		const val COORD_SEM_ZA_Y = 8
		const val COORD_SEM_DOA1_X = 16
		const val COORD_SEM_DOA1_Y = 8
		const val COORD_SEM_DOA2_X = 17
		const val COORD_SEM_DOA2_Y = 9
		const val COORD_SEM_DOB1_X = 25
		const val COORD_SEM_DOB1_Y = 8
		const val COORD_SEM_DOB2_X = 24
		const val COORD_SEM_DOB2_Y = 9
		const val COORD_SEM_ZB_X = 27
		const val COORD_SEM_ZB_Y = 8
	}

	// fronta neodsouhlasenych - za jinych okolnosti seznam ze ktereho si dispecer vybere
	private val unapprowedTrains: ArrayDeque<Train> = ArrayDeque()
	private val approwedTrains: MutableList<Train> = mutableListOf()
	private val generator: InnerGenerator = InnerGenerator(context)
	private val innerTrackBlocks: MutableList<DynamicTrackBlock> = mutableListOf()
	private val outerTrackblocks: MutableMap<DynamicTrackBlock, DynamicRailSemaphore> = mutableMapOf()

	/**
	 * Perception port backed by this loop's simulation environment and approved-train
	 * list.  Built once here (the semaphore cache is constructed eagerly) and reused
	 * on every tick via [createTickContext].
	 *
	 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
	 */
	private val perceptionPort: cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort =
		cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort(
			env = context,
			activeTrains = { approwedTrains.toList() }
		)

	/**
	 * Actuator port exposing string-based dispatcher commands to the network.
	 * Built once here and reused on every tick via [createTickContext].
	 *
	 * @since Issue #545 (SP0.6 — Goal 10)
	 */
	private val actuatorPort: cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort =
		cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort(
			env = context,
			pathReservationService = pathReservationService
		)

	/**
	 * Optional listener called at the start of each [iteration] to drain and apply
	 * any pending decisions from the cross-thread command queue (SP0.9 — Issue #731).
	 *
	 * Set this **before** `context.run()` is called; the field is read on the kDisco
	 * sim thread only, so no additional synchronization is needed after startup wiring
	 * is complete.  Typical value:
	 * [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
	 * from `:dispatcher-agent`.
	 *
	 * @since Issue #731 (SP0.9 — Goal 10)
	 */
	var controlStepListener: ControlStepListener? = null

	// Test-observability counters (#365) — incremented from existing lifecycle sites only.
	// Not atomic: ShuntingLoop runs on the single kDisco dispatcher thread, so all increment
	// sites (placeTrain, iteration, tryReservePath) serialize naturally. If a future
	// dispatcher becomes concurrent, these need atomicfu.
	private var trainsEnteredCount: Int = 0
	private var trainsExitedCount: Int = 0
	private var maxConcurrentTrainsCount: Int = 0
	private var failedReservationsCount: Int = 0
	private val blockTransitionsByTrain: MutableMap<String, Int> = mutableMapOf()

	private inner class RealTimeSynch : LoopProcess() {
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
			}
		}

		override suspend fun interLoopSleep() {
			beginTime = currentTimeMillisKMP()
			hold(1.0)
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

		val inB: DynamicInOut = elementAt<DynamicInOut>(context, COORD_IN_B_X, COORD_IN_B_Y)
		val inA: DynamicInOut = elementAt<DynamicInOut>(context, COORD_IN_A_X, COORD_IN_A_Y)
		val zA: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, COORD_SEM_ZA_X, COORD_SEM_ZA_Y)
		val doA1: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, COORD_SEM_DOA1_X, COORD_SEM_DOA1_Y)
		val doB1: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, COORD_SEM_DOB1_X, COORD_SEM_DOB1_Y)
		val zB: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, COORD_SEM_ZB_X, COORD_SEM_ZB_Y)
		val doA2: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, COORD_SEM_DOA2_X, COORD_SEM_DOA2_Y)
		val doB2: DynamicRailSemaphore = elementAt<DynamicRailSemaphore>(context, COORD_SEM_DOB2_X, COORD_SEM_DOB2_Y)
		val vA: DynamicRailSwitch = elementAt<DynamicRailSwitch>(context, COORD_SW_A_X, COORD_SW_A_Y)
		val vB: DynamicRailSwitch = elementAt<DynamicRailSwitch>(context, COORD_SW_B_X, COORD_SW_B_Y)

		val k1: DynamicTrackBlock = getBlock(context, "k1", doA1, doB1)
		val k2: DynamicTrackBlock = getBlock(context, "k2", doA2, doB2)
		val kA: DynamicTrackBlock = getBlock(context, "kA", inA, zA)
		val kB: DynamicTrackBlock = getBlock(context, "kB", inB, zB)

		// Issue #296: Removed manual path construction (~100 lines)
		// Paths are now discovered dynamically using TopologyNavigator when needed
		// - innerTrackBlocks: middle blocks with RailSemaphore ends only (k1, k2)
		// - outerTrackblocks: entry/exit blocks with one InOut end (kB, kA)
		innerTrackBlocks.addAll(listOf(k1, k2))
		outerTrackblocks[kB] = zB
		outerTrackblocks[kA] = zA
	}

	/**
	 * Resolves a semaphore name (as carried by [DispatchDecision.ReservePath.fromSemaphoreName])
	 * back to the live [DynamicRailSemaphore], for [applyReservePath]. Built once after
	 * the block-init block above has populated [innerTrackBlocks]/[outerTrackblocks].
	 */
	private val semaphoreByName: Map<String, DynamicRailSemaphore> =
		(
			innerTrackBlocks.flatMap { block ->
				block.ends().map { Util.assertInstanceOf<DynamicRailSemaphore>(it) }
			} + outerTrackblocks.values
		).associateBy { it.name }

	/**
	 * Resolves an InOut name (e.g. a train's timetable destination, or the `to`
	 * separator of a final-section [DispatchDecision.ReservePath]) back to the live
	 * [DynamicInOut]. Built once from the environment's InOuts.
	 */
	private val inoutByName: Map<String, DynamicInOut> =
		env.getInOuts().associateBy { it.name }

	/**
	 * Resolves any separator name carried by a [DispatchDecision.ReservePath]
	 * ([fromSemaphoreName] or [DispatchDecision.ReservePath.toSeparatorName]) back to
	 * the live [cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator] — a
	 * semaphore or an InOut. Used by [applyReservePath] to resolve the `to` target,
	 * which for the final section is the destination InOut, not a semaphore.
	 */
	private val separatorByName: Map<String, DynamicPathSeparator> =
		(semaphoreByName.entries.associate { it.key to (it.value as DynamicPathSeparator) }) + inoutByName

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
		// SP0.9: Drain and apply any decisions posted to the cross-thread command queue
		// before the inline dispatcher runs. This ensures queued decisions are
		// reflected in the admission/path-advancement observations built below.
		controlStepListener?.onControlStep()

		// stare vlaky
		val iter: MutableIterator<Train> = approwedTrains.iterator()
		while (iter.hasNext()) {
			val element: Train = iter.next()
			if (element.terminated()) {
				iter.remove()
				trainsExitedCount++
			}
		}

		// Delegate all dispatch decisions — train admission and forward-path reservation —
		// to the injected Dispatcher (SP0.1 / Issue #540; pure decide() seam since
		// SP0.7 / Issue #729). Admission runs before the polling hold so
		// path-advancement checks (after the hold) observe block state after newly
		// admitted trains have moved — the original ShuntingLoop ordering.
		applyDecisions(dispatcher.decide(buildAdmissionObservation()))

		if (approwedTrains.size > maxConcurrentTrainsCount) {
			maxConcurrentTrainsCount = approwedTrains.size
		}
		// Polling interval: 1.0s (matches baseline timing)
		// Train entry events align with polling to catch RESERVED state
		hold(1.0)
		applyDecisions(dispatcher.decide(buildPathAdvancementObservation()))
	}

	/**
	 * Builds the admission-phase [DispatchObservation]: real
	 * [DispatchObservation.unapprovedTrains], no block-end data — the pre-hold call
	 * only ever admits trains, mirroring the pre-#729 `approve()` phase.
	 */
	private fun buildAdmissionObservation(): DispatchObservation =
		DispatchObservation(
			snapshot = perceptionPort.captureSnapshot(),
			unapprovedTrains =
				unapprowedTrains.map { train ->
					QueuedTrainObservation(train.name, train.timetableDestinationName)
				}
		)

	/**
	 * Builds the path-advancement-phase [DispatchObservation]: no queued trains, real
	 * block-input data for every inner and outer block — the post-hold call only ever
	 * reserves paths, mirroring the pre-#729 `advancePaths()` phase.
	 *
	 * Each input's [BlockInputObservation.toSeparatorName] is pre-computed here (the
	 * next FREE separator one section ahead, via
	 * [PathReservationService.findNextReservationTarget]) so the pure [Dispatcher] can
	 * emit an explicit from→to [DispatchDecision.ReservePath] and the applier can call
	 * [PathReservationService.reservePath] directly, reproducing the pre-#729
	 * `reservePathToAnyNextSemaphore` outcome.
	 */
	private fun buildPathAdvancementObservation(): DispatchObservation =
		DispatchObservation(
			snapshot = perceptionPort.captureSnapshot(),
			unapprovedTrains = emptyList(),
			innerBlockInputs =
				innerTrackBlocks.flatMap { block ->
					block.ends().map { toBlockInputObservation(block, Util.assertInstanceOf<DynamicRailSemaphore>(it)) }
				},
			outerBlockInputs = outerTrackblocks.map { (block, sem) -> toBlockInputObservation(block, sem) }
		)

	/**
	 * Computes the [BlockInputObservation] for [block] toward [to] from live
	 * block/registry state — the directional facts [DispatchObservation]'s
	 * [SimulationSnapshot][cz.vutbr.fit.interlockSim.ports.SimulationSnapshot]
	 * cannot carry (see [BlockInputObservation] KDoc).
	 *
	 * [toSeparatorName] is the next FREE separator one section ahead of [to]
	 * ([PathReservationService.findNextReservationTarget]) — the read-only twin of the
	 * pre-#729 `reservePathToAnyNextSemaphore(to)` call, so the applier's
	 * `reservePath(train, to, target)` reproduces the prior first-FREE outcome.
	 */
	private fun toBlockInputObservation(
		block: DynamicTrackBlock,
		to: DynamicRailSemaphore
	): BlockInputObservation {
		val state = block.getState()
		val ownerTrainId =
			when (state) {
				TrackFacility.State.FREE -> null
				TrackFacility.State.RESERVED -> block.trainName
				TrackFacility.State.OCCUPIED -> requireSimulationNotNull(block.getTrackOccupant()).name
			}
		val toSeparatorName = pathReservationService.findNextReservationTarget(to)?.let(::nameOf)
		return BlockInputObservation(
			blockId = requireNotNull(block.name) { "ShuntingLoop-owned blocks are always named" },
			towardSemaphoreName = to.name,
			toSeparatorName = toSeparatorName,
			state = state,
			ownerTrainId = ownerTrainId,
			isApproachingThisInput =
				state == TrackFacility.State.OCCUPIED &&
					requireSimulationNotNull(block.getTrackOccupant()).nextSemaphore() == to,
			pathSetUpTowardThisInput =
				state == TrackFacility.State.RESERVED &&
					block.isSetUpPath(env.toDynamic(block.getSecondEnd(to))),
			pathAlreadyExtendedBeyond = ownerTrainId != null && registry.isPathExtendedBeyond(ownerTrainId, to)
		)
	}

	/**
	 * Name of a [DynamicPathSeparator] returned by
	 * [PathReservationService.findNextReservationTarget] — a [DynamicInOut] or a
	 * [DynamicRailSemaphore] (the only separator kinds that method returns). Used to
	 * carry the `to` target across the pure [Dispatcher] seam as a name string.
	 */
	private fun nameOf(separator: DynamicPathSeparator): String? =
		when (separator) {
			is DynamicInOut -> separator.name
			is DynamicRailSemaphore -> separator.name
			else -> null
		}

	/** Applies each decision returned by [Dispatcher.decide], in order. */
	private fun applyDecisions(decisions: List<DispatchDecision>) {
		for (decision in decisions) {
			when (decision) {
				is DispatchDecision.ApproveTrain -> applyApproveTrain(decision.trainId)
				is DispatchDecision.ReservePath ->
					applyReservePath(decision.trainId, decision.fromSemaphoreName, decision.toSeparatorName)
				DispatchDecision.NoAction -> Unit
			}
		}
	}

	/**
	 * Moves the train named [trainId] from the unapproved queue into the approved
	 * set and activates it. FIFO order is preserved because [RuleBasedDispatcher]
	 * walks [DispatchObservation.unapprovedTrains] in queue order.
	 */
	private fun applyApproveTrain(trainId: String) {
		val train =
			requireNotNull(unapprowedTrains.firstOrNull { it.name == trainId }) {
				"Dispatcher approved unknown/already-approved train: $trainId"
			}
		unapprowedTrains.remove(train)
		approwedTrains.add(train)
		activate(train)
	}

	/**
	 * Public entry point for the SP0.9 applier to approve a queued train.
	 *
	 * Delegates to [applyApproveTrain]. Must be called on the kDisco simulation
	 * thread (typically from [ControlStepListener.onControlStep]).
	 *
	 * @param trainId The name/identifier of the train to approve.
	 * @throws IllegalArgumentException if no unapproved train with [trainId] exists.
	 * @since Issue #731 (SP0.9 — Goal 10)
	 */
	fun approveQueuedTrain(trainId: String) = applyApproveTrain(trainId)

	/** Resolves [fromSemaphoreName] and [toSeparatorName] and delegates to [tryReservePath]. */
	private fun applyReservePath(
		trainId: String,
		fromSemaphoreName: String,
		toSeparatorName: String
	) {
		val fromSem =
			requireNotNull(semaphoreByName[fromSemaphoreName]) {
				"Dispatcher requested reservation from unknown semaphore: $fromSemaphoreName"
			}
		val toSep =
			requireNotNull(separatorByName[toSeparatorName]) {
				"Dispatcher requested reservation to unknown separator: $toSeparatorName"
			}
		tryReservePath(fromSem, toSep, toSeparatorName, trainId)
	}

	/**
	 * Reserves a forward path of one section from [fromSem] to [toSep] for [trainName]
	 * using [PathReservationService.reservePath] — the explicit from→to primitive.
	 * [toSep] is the next separator toward the train's destination that the shell
	 * pre-computed as FREE, so this reproduces the pre-#729
	 * `reservePathToAnyNextSemaphore` outcome.
	 *
	 * Increments the [blockTransitionsByTrain] counter on success and the
	 * [failedReservationsCount] counter on any failure, for test-observability
	 * (#365); the counters are maintained here (in the shell) rather than in
	 * [dispatcher] so they stay close to the simulation state they measure.
	 */
	private fun tryReservePath(
		fromSem: DynamicRailSemaphore,
		toSep: DynamicPathSeparator,
		toSeparatorName: String,
		trainName: String
	): Boolean {
		val result = pathReservationService.reservePath(trainName, fromSem, toSep)

		return when (result) {
			is PathReservationService.ReservationResult.Success -> {
				logger.debug { "Reserved path from ${fromSem.name} to $toSeparatorName for $trainName" }
				// KMP-safe increment: Map.merge() is a JVM-only default method.
				blockTransitionsByTrain[trainName] = (blockTransitionsByTrain[trainName] ?: 0) + 1
				true
			}
			is PathReservationService.ReservationResult.Conflict -> {
				logger.warn {
					"Conflict for $trainName from ${fromSem.name} to $toSeparatorName: " +
						"block ${result.conflictingBlock.name ?: "unnamed"} " +
						"owned by ${result.existingOwner}"
				}
				failedReservationsCount++
				false
			}
			is PathReservationService.ReservationResult.NoPathExists -> {
				logger.debug { "No path exists from ${fromSem.name} to $toSeparatorName for $trainName" }
				failedReservationsCount++
				false
			}
			is PathReservationService.ReservationResult.AllPathsBlocked -> {
				logger.debug {
					"All paths blocked from ${fromSem.name} to $toSeparatorName for $trainName " +
						"(attempted: ${result.attemptedPaths})"
				}
				failedReservationsCount++
				false
			}
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

	/** Number of dispatcher reservation attempts that failed (Conflict/NoPathExists/AllPathsBlocked). */
	fun getFailedReservations(): Int = failedReservationsCount
}
