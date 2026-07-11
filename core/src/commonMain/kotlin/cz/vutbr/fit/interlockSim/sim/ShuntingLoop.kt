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
import cz.vutbr.fit.interlockSim.util.platformStartDaemonThread
import org.koin.core.component.KoinComponent

/**
 * Příklad fungování modelu
 * Ovlada sest navestidel a 2 InOuty pomoci dynamicky nalezených cest
 *
 * ## SP0.11 thin-shell refactor (Issue #733 — Goal 10)
 *
 * [ShuntingLoop] is now a thin kDisco process shell. It owns simulation lifecycle,
 * publishes observation snapshots for the off-kernel dispatcher driver, and drains
 * decisions posted back onto the sim thread via [controlStepListener]. Admission and
 * route-reservation policy live outside this class in the dispatcher-agent stack.
 *
 * Both train admission and forward-path advancement happen pre-hold in the same
 * iteration tick: [iteration] publishes one combined observation, then
 * [controlStepListener] applies the dispatcher's decisions before the per-iteration
 * `hold()` (SP0.11 — Issue #733). A newly admitted train therefore receives its
 * first path reservation in the same tick it is admitted.
 *
 * **SP0.11 responsibilities kept here:**
 * - simulation lifecycle (`hold`, `terminate`, `activate`)
 * - live block/input observation publishing for the external driver
 * - queued-train approval callback entry point
 * - test-observability counters tied directly to simulation state
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
 * - Koin DI integration for service injection
 * - Optional [ControlStepListener] seam for sim-thread command draining
 *
 * @see ControlStepListener
 * @see TopologyNavigator
 * @see docs/PATH_RESERVATION_ARCHITECTURE.md
 */
class ShuntingLoop(
	context: SimulationContext,
	private val endTime: Long,
	private val enableRealTimeSync: Boolean = false,
	initialSpeedMultiplier: Double = 1.0
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

	// Lazy injection (SP0.11: moved from ctor param to lazy property — construction stays in scope)
	private val pathReservationService: PathReservationService by lazy {
		context.getRoutingServices().getPathReservationService()
	}

	companion object {
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

	/**
	 * SP0.11: Hook called every [iteration] to publish a fresh [SimulationSnapshot] for the
	 * off-kernel driver thread. Set this to [NetworkPerceptionPort.captureSnapshot] of the
	 * externally-created perception port (wired in ExampleRegistry or Frame) before
	 * `context.run()`.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	var snapshotCaptureHook: (() -> Unit)? = null

	/**
	 * SP0.11: Coroutine block started in a daemon thread alongside [startAction].
	 * Set this to the agent-driver's run loop before `context.run()`. The block is
	 * expected to loop while [isSimActive] returns `true` and call
	 * [AgentLoopDriver.runCycle][cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver.runCycle]
	 * on each iteration.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	var agentDriverAction: (suspend () -> Unit)? = null

	/** `true` from [startAction] to the moment [interLoopSleep] detects the end condition. */
	@kotlin.concurrent.Volatile
	private var simActive: Boolean = false

	// SP0.11: Observation data published at the start of each iteration for the off-kernel
	// driver thread. Written on the single kDisco sim thread as a single immutable snapshot
	// so the driver thread always observes a consistent set of fields from the SAME sim tick
	// (three independent @Volatile fields could be read cross-tick, producing a stale
	// pathAlreadyExtendedBeyond and a duplicate re-decide). @Volatile ensures visibility.
	@kotlin.concurrent.Volatile
	private var latestObservation: TickObservation =
		TickObservation(queuedTrains = emptyList(), innerBlockInputs = emptyList(), outerBlockInputs = emptyList())

	/**
	 * Immutable per-tick observation bundle published for the off-kernel driver thread.
	 * The three lists are computed together on the sim thread and published via a single
	 * [latestObservation] reference write, so a reader either sees all three from tick N
	 * or all three from tick N-1 — never a mix.
	 *
	 * Public so off-kernel callers (e.g. [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver])
	 * can read all three fields with a single [getLatestObservation] call instead of three
	 * separate accessor calls, which would defeat the single-tick atomicity this type exists
	 * to guarantee.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10); single-snapshot publication added by the
	 *   SP0.11 review follow-up; made public by the tearing-fix follow-up
	 */
	data class TickObservation(
		val queuedTrains: List<QueuedTrainObservation>,
		val innerBlockInputs: List<BlockInputObservation>,
		val outerBlockInputs: List<BlockInputObservation>
	)

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
		simActive = true
		env.addReportTypes(*ENABLED_REPORT_TYPES)

		// Conditionally activate real-time synchronization for GUI mode
		if (enableRealTimeSync) {
			activate(RealTimeSynch())
		}

		Process.activate(generator)

		// SP0.11: Launch the external agent-driver coroutine alongside the kDisco kernel.
		// The action runs in a daemon thread so the JVM can exit cleanly when the
		// simulation finishes; the loop inside the action checks [isSimActive].
		// KMP: thread creation is JVM-only, hence the expect/actual indirection.
		agentDriverAction?.let { action ->
			platformStartDaemonThread("dispatcher-agent-driver", action)
		}
	}

	override suspend fun iteration() {
		// Prune terminated trains first so the snapshot reflects accurate approved-train counts.
		val iter: MutableIterator<Train> = approwedTrains.iterator()
		while (iter.hasNext()) {
			val element: Train = iter.next()
			if (element.terminated()) {
				iter.remove()
				trainsExitedCount++
			}
		}

		// SP0.11: Publish a fresh snapshot and per-block observation data for the off-kernel
		// driver thread.  Published before the applier fires so the applier's callbacks and
		// the driver read current-tick state.  Written on the single kDisco sim thread as a
		// single immutable [TickObservation] reference write; @Volatile ensures visibility.
		snapshotCaptureHook?.invoke()
		latestObservation =
			TickObservation(
				queuedTrains = unapprowedTrains.map { QueuedTrainObservation(it.name, it.timetableDestinationName) },
				innerBlockInputs =
					innerTrackBlocks.flatMap { block ->
						block.ends().map { end -> toBlockInputObservation(block, Util.assertInstanceOf<DynamicRailSemaphore>(end)) }
					},
				outerBlockInputs = outerTrackblocks.map { (block, sem) -> toBlockInputObservation(block, sem) }
			)

		// SP0.9: Drain and apply any decisions posted to the cross-thread command queue.
		controlStepListener?.onControlStep()

		if (approwedTrains.size > maxConcurrentTrainsCount) {
			maxConcurrentTrainsCount = approwedTrains.size
		}
		// Polling interval: 1.0s (matches baseline timing)
		hold(1.0)
	}

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
		val isApproachingThisInput =
			state == TrackFacility.State.OCCUPIED &&
				requireSimulationNotNull(block.getTrackOccupant()).nextSemaphore() == to
		val pathSetUpTowardThisInput =
			state == TrackFacility.State.RESERVED &&
				block.isSetUpPath(env.toDynamic(block.getSecondEnd(to)))
		val pathAlreadyExtendedBeyond = ownerTrainId != null && registry.isPathExtendedBeyond(ownerTrainId, to)

		// Only inputs that can actually yield a forward reservation need a target searched for.
		// findNextReservationTarget is a graph walk (BFS + per-candidate path enumeration); running
		// it for FREE and already-extended inputs cost ~9% of fast-sim wall time (#738).
		val canReserveForward =
			!pathAlreadyExtendedBeyond &&
				(isApproachingThisInput || pathSetUpTowardThisInput)
		val toSeparatorName =
			if (canReserveForward) {
				pathReservationService.findNextReservationTarget(to)?.let(::nameOf)
			} else {
				null
			}
		return BlockInputObservation(
			blockId = requireNotNull(block.name) { "ShuntingLoop-owned blocks are always named" },
			towardSemaphoreName = to.name,
			toSeparatorName = toSeparatorName,
			state = state,
			ownerTrainId = ownerTrainId,
			isApproachingThisInput = isApproachingThisInput,
			pathSetUpTowardThisInput = pathSetUpTowardThisInput,
			pathAlreadyExtendedBeyond = pathAlreadyExtendedBeyond
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

	/**
	 * Returns the list of trains currently queued but not yet approved, as published at the
	 * start of the most recent [iteration]. Safe to call from off-kernel threads.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	fun getQueuedTrains(): List<QueuedTrainObservation> = latestObservation.queuedTrains

	/**
	 * Returns the block-input observations for all inner track blocks, as published at the
	 * start of the most recent [iteration]. Safe to call from off-kernel threads.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	fun getInnerBlockInputs(): List<BlockInputObservation> = latestObservation.innerBlockInputs

	/**
	 * Returns the block-input observations for all outer track blocks, as published at the
	 * start of the most recent [iteration]. Safe to call from off-kernel threads.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	fun getOuterBlockInputs(): List<BlockInputObservation> = latestObservation.outerBlockInputs

	/**
	 * Returns the full per-tick observation bundle (queued trains plus inner/outer block
	 * inputs) as published at the start of the most recent [iteration], in a single
	 * @Volatile read. Safe to call from off-kernel threads.
	 *
	 * Prefer this over calling [getQueuedTrains]/[getInnerBlockInputs]/[getOuterBlockInputs]
	 * separately when a caller needs more than one of the three fields together — three
	 * independent calls can observe different sim ticks if the sim thread republishes
	 * [latestObservation] in between, tearing the single-tick guarantee [TickObservation]
	 * exists to provide.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10); added by the tearing-fix follow-up
	 */
	fun getLatestObservation(): TickObservation = latestObservation

	/**
	 * Returns a snapshot of the currently approved (active) trains. Used by the external
	 * [DefaultNetworkPerceptionPort][cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort]
	 * to build [SimulationSnapshot.trainPositions].
	 *
	 * Must only be called on the kDisco simulation thread.
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	fun getApprovedTrains(): List<Train> = approwedTrains.toList()

	/** Returns `true` while the simulation is active (between [startAction] and [interLoopSleep] end). */
	fun isSimActive(): Boolean = simActive

	/**
	 * Public entry point for the SP0.9 applier to approve a queued train.
	 *
	 * Moves the train named [trainId] from the unapproved queue into the approved set and
	 * activates it. FIFO order is preserved because [RuleBasedDispatcher] walks
	 * [DispatchObservation.unapprovedTrains] in queue order.
	 *
	 * **Idempotency (SP0.11):** if no unapproved train named [trainId] exists (e.g. the
	 * async driver posted a duplicate [ApproveTrain] decision), the call is silently
	 * ignored. The train was already approved by an earlier decision in the same drain.
	 *
	 * Must be called on the kDisco simulation thread (typically from
	 * [ControlStepListener.onControlStep]).
	 *
	 * @param trainId The name/identifier of the train to approve.
	 * @since Issue #731 (SP0.9 — Goal 10); idempotency added in SP0.11
	 */
	fun approveQueuedTrain(trainId: String) {
		val train = unapprowedTrains.firstOrNull { it.name == trainId } ?: return
		unapprowedTrains.remove(train)
		approwedTrains.add(train)
		activate(train)
	}

	/**
	 * Increments the block-transition counter for [trainId].
	 *
	 * Called by [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
	 * on every successful path reservation; the counter previously lived inside [tryReservePath].
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	fun incrementBlockTransition(trainId: String) {
		blockTransitionsByTrain[trainId] = (blockTransitionsByTrain[trainId] ?: 0) + 1
	}

	/**
	 * Increments the failed-reservation counter.
	 *
	 * Called by [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
	 * on every rejected reservation; the counter previously lived inside [tryReservePath].
	 *
	 * @since Issue #733 (SP0.11 — Goal 10)
	 */
	fun incrementFailedReservation() {
		failedReservationsCount++
	}

	override suspend fun interLoopSleep() {
		if (time() >= endTime) {
			simActive = false // signal the companion driver thread to stop
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

	/**
	 * Peak number of trains simultaneously approved (holding an admitted slot) at any
	 * observed tick during this run.
	 *
	 * **Safety invariant:** this value must never exceed the station's fixed topology
	 * capacity ([RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS], or whatever
	 * `maxConcurrentTrains` the wired dispatcher was constructed with) — that capacity is
	 * not a tunable performance knob, it is how many parallel tracks the physical station
	 * actually has (2 for `vyhybna.xml`: k1, k2). If a dispatcher ever let a 3rd train in
	 * concurrently on this topology, that train would have no physical track left to occupy —
	 * an unresolvable state, not a recoverable "wait and retry" condition, since every
	 * available parallel track is already held by the other two approved trains.
	 */
	fun getMaxConcurrentTrains(): Int = maxConcurrentTrainsCount

	fun getBlockTransitions(trainId: String): Int = blockTransitionsByTrain[trainId] ?: 0

	fun getAllBlockTransitions(): Map<String, Int> = blockTransitionsByTrain.toMap()

	/** Number of dispatcher reservation attempts that failed (Conflict/NoPathExists/AllPathsBlocked). */
	fun getFailedReservations(): Int = failedReservationsCount
}
