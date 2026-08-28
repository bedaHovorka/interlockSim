/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.observation

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.separatorName
import cz.vutbr.fit.interlockSim.util.BlockIdentity
import cz.vutbr.fit.interlockSim.util.cellsOfType

/**
 * Captures the sim-thread-only sources described in #824's design table into one immutable
 * [DispatcherObservation], and publishes it for off-thread consumption.
 *
 * ## Threading contract
 *
 * [captureOnSimThread] **must** only ever be called from the kDisco simulation thread — it reads
 * [DynamicRailSwitch.conf] and [PathReservationRegistry] directly, both of which are documented
 * sim-thread-only live domain state (the same access [cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer.describe]
 * performs at agent-construction time, where it is safe because construction itself happens
 * before the kDisco kernel starts). A debug-only thread-identity check
 * ([assertCalledFromSameThreadEveryTime]) guards against a future refactor silently moving the
 * call off-thread and reintroducing a data race — it is compiled out in production (a plain
 * Kotlin `assert`, active only under `-ea`) and exists purely to catch the mistake in tests/dev.
 *
 * The published [DispatcherObservation] is swapped into a single `@Volatile` field, so
 * [latest] never tears: an off-thread reader always observes either the previous tick's
 * observation in full, or this tick's in full — never a mix of fields from two ticks.
 *
 * ## Statefulness (documented, not hidden — #824 "known design tension")
 *
 * [TrainView.waitSeconds] / [TrainView.waitingSinceSimTime] and
 * [QueuedTrainView.queuedSinceSimTime] all need a per-train "waiting/queued since" timestamp that
 * no single [SimulationSnapshot] or [DispatchLoopSnapshot] carries — only whether a train is
 * *currently* waiting, not *since when*. This projector therefore holds a small
 * `Map<String, Double>` ([waitStartSimTime]) recording, per train, the simulation time its
 * current wait began, plus a small amount of bookkeeping ([knownTrainIds], [lastKnownViews]) to
 * emit exactly one [TrainPhase.EXITED] [TrainView] the tick a train disappears.
 *
 * **This projector is therefore *not* a pure function of a single snapshot.** It *is*
 * deterministic over the recorded **snapshot sequence** — replaying the same ordered sequence of
 * [SimulationSnapshot]/[DispatchLoopSnapshot] pairs through a fresh projector always reproduces
 * the same sequence of [DispatcherObservation] values (and digests) — which is exactly what #822
 * principle P8 (reproducibility by construction) requires. It does not claim purity per snapshot;
 * only per sequence.
 *
 * @param perceptionPort Sim-thread source for [simTime][DispatcherObservation.simTime], signals,
 *   blocks, and train kinematics/perception ([NetworkPerceptionPort.captureSnapshot]).
 * @param dispatchLoopSensorPort Sim-thread source for the queued-train list and block-input facts
 *   ([DispatchLoopSensorPort.snapshot]).
 * @param pathReservationRegistry Sim-thread source for switch lock ownership and active path
 *   reservations.
 * @param environment Sim-thread source for the static grid walk that finds every named switch —
 *   the same walk [cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer.describe]
 *   uses (`getRailWayNetGrid().cellsOfType<DynamicRailSwitch>()`). Reading `:core`'s live grid
 *   from `:dispatcher-agent` this way is new coupling accepted in #824's body as a documented
 *   debt a future `NetworkPerceptionPort.allSwitchPositions()` would repay — currently blocked by
 *   #822 constraint C10 (zero `:core` changes).
 * @param capacity Station capacity published as [DispatcherObservation.capacity]. Defaults to
 *   [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS].
 *
 * @since Issue #824 (SP2c.1 — Goal 10 autonomous dispatcher control-loop redesign)
 */
class DispatcherObservationProjector(
	private val perceptionPort: NetworkPerceptionPort,
	private val dispatchLoopSensorPort: DispatchLoopSensorPort,
	private val pathReservationRegistry: PathReservationRegistry,
	private val environment: SimulationEnvironment,
	private val capacity: Int = RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS,
	/**
	 * SP2c.17 (#840): Optional outcome feed drained on each [captureOnSimThread] call.
	 *
	 * When non-null, all [AppliedOutcome]s accumulated since the previous tick are drained and
	 * included in [DispatcherObservation.appliedOutcomes]. When null (the default), the list
	 * remains empty — existing callers that do not need the feedback channel are unaffected.
	 */
	private val outcomeFeed: AppliedOutcomeFeed? = null
) : DispatcherObservationSource {
	@Volatile
	private var published: DispatcherObservation = DispatcherObservation.EMPTY

	private var tickCounter: Long = 0L

	// Sim-thread-only bookkeeping — see the "Statefulness" KDoc section above.
	private val waitStartSimTime: MutableMap<String, Double> = mutableMapOf()
	private val knownTrainIds: MutableSet<String> = mutableSetOf()
	private val lastKnownViews: MutableMap<String, TrainView> = mutableMapOf()

	// Debug-only thread-identity guard (see class KDoc "Threading contract").
	@Volatile
	private var capturingThread: Thread? = null

	/**
	 * Captures the current live simulation state into a fresh [DispatcherObservation] and
	 * publishes it for [latest]. **Must** be called on the kDisco simulation thread only — see
	 * class KDoc.
	 */
	fun captureOnSimThread() {
		assertCalledFromSameThreadEveryTime()
		tickCounter += 1
		published = projectTick(tickCounter)
	}

	override fun latest(): DispatcherObservation = published

	/**
	 * The actual per-tick projection, factored out of [captureOnSimThread] so it can be exercised
	 * directly by tests with an explicit `tick` — see the class KDoc "Statefulness" section for
	 * why this is deterministic given (tick, live sim-thread state) but not a pure function of
	 * [SimulationSnapshot] alone. `internal` (not `private`) so [DispatcherObservationProjector]
	 * tests in this module can verify determinism without going through the auto-incrementing
	 * [captureOnSimThread] twice.
	 */
	internal fun projectTick(tick: Long): DispatcherObservation {
		val snapshot = perceptionPort.captureSnapshot()
		val dispatchSnapshot = dispatchLoopSensorPort.snapshot()

		// Must run before buildQueuedViews: it populates waitStartSimTime for every currently
		// queued/waiting train, which buildQueuedViews then reads.
		val trains = buildTrainViews(snapshot, dispatchSnapshot)

		// SP2c.17 (#840): drain every outcome published since the previous capture. Next-tick
		// semantics come from call order, NOT a tickIndex filter: ShuntingLoop.iteration() runs
		// this capture BEFORE the control step that publishes new outcomes, so at drain time the
		// ring holds only outcomes from previous control steps. A tickIndex-based filter would be
		// unsound here — AppliedOutcome.tickIndex is the driver-cycle counter while `tick` is the
		// projector's sim-tick counter, two independent domains; if the sim tick outpaced the
		// driver cycle the filter would strand outcomes until silent 512-cap eviction. Draining
		// all (fromTickIndex = 0L) avoids that bug.
		val appliedOutcomes = outcomeFeed?.drainSince(0L) ?: emptyList()

		return DispatcherObservation(
			tick = tick,
			simTime = snapshot.simTime,
			trains = trains,
			blocks = snapshot.blocks.map { BlockView(it.blockId, it.state, it.trainId) }.sortedBy { it.blockId },
			switches = buildSwitchViews(),
			signals =
				snapshot.semaphores
					.map { SignalView(it.name, it.signal, it.authorizedFrom, it.authorizedTo) }
					.sortedBy { it.name },
			reservations = buildReservationViews(snapshot),
			queued = buildQueuedViews(dispatchSnapshot, snapshot.simTime),
			activeCount = snapshot.trainPositions.size,
			capacity = capacity,
			appliedOutcomes = appliedOutcomes,
			innerBlockInputs = dispatchSnapshot.innerBlockInputs,
			outerBlockInputs = dispatchSnapshot.outerBlockInputs
		)
	}

	private fun buildTrainViews(
		snapshot: SimulationSnapshot,
		dispatchSnapshot: DispatchLoopSnapshot
	): List<TrainView> {
		val simTime = snapshot.simTime
		val activeById: Map<String, TrainPerceptionReading> = snapshot.trainPerceptions.associateBy { it.trainId }
		val queuedById: Map<String, QueuedTrainObservation> = dispatchSnapshot.queuedTrains.associateBy { it.trainId }
		// Train IDs are unique by invariant; surface a violation under -ea rather than silently
		// dropping a train via associateBy's last-wins semantics.
		assert(snapshot.trainPerceptions.size == activeById.size) {
			"Duplicate trainId in trainPerceptions: ${snapshot.trainPerceptions.size} readings -> ${activeById.size} keys"
		}
		assert(dispatchSnapshot.queuedTrains.size == queuedById.size) {
			"Duplicate trainId in queuedTrains: ${dispatchSnapshot.queuedTrains.size} entries -> ${queuedById.size} keys"
		}

		val currentlyWaitingIds: Set<String> =
			queuedById.keys +
				activeById.values
					.filter { it.isDwelling }
					.map { it.trainId }
					.toSet()

		// Drop wait-start timestamps for trains no longer waiting (started moving, exited, ...).
		waitStartSimTime.keys.retainAll(currentlyWaitingIds)
		for (id in currentlyWaitingIds) {
			waitStartSimTime.putIfAbsent(id, simTime)
		}

		val activeViews =
			activeById.values.map { perception ->
				val waitStart = if (perception.isDwelling) waitStartSimTime[perception.trainId] else null
				TrainView(
					trainId = perception.trainId,
					phase = phaseOf(perception),
					frontSectionName = perception.frontSectionName,
					velocityMps = perception.velocity,
					accelerationMps2 = perception.acceleration,
					destinationInOutName = perception.destinationInOutName,
					signalAheadName = perception.signalAheadName,
					signalAheadAspect = perception.signalAheadAspect,
					distanceToSignalAheadMetres = perception.distanceToSignalAheadMetres,
					waitingSinceSimTime = waitStart,
					waitSeconds = waitStart?.let { simTime - it } ?: 0.0
				)
			}

		val queuedViews =
			queuedById.values.map { queuedTrain ->
				val waitStart = waitStartSimTime.getValue(queuedTrain.trainId)
				TrainView(
					trainId = queuedTrain.trainId,
					phase = TrainPhase.QUEUED,
					frontSectionName = null,
					velocityMps = 0.0,
					accelerationMps2 = 0.0,
					destinationInOutName = queuedTrain.destinationInOutName,
					signalAheadName = null,
					signalAheadAspect = null,
					distanceToSignalAheadMetres = 0.0,
					waitingSinceSimTime = waitStart,
					waitSeconds = simTime - waitStart
				)
			}

		val presentIds = activeById.keys + queuedById.keys
		val exitedViews =
			(knownTrainIds - presentIds).mapNotNull { id ->
				lastKnownViews[id]?.copy(
					phase = TrainPhase.EXITED,
					waitingSinceSimTime = null,
					waitSeconds = 0.0
				)
			}

		// Update tracking state for the next tick — an EXITED view is therefore emitted exactly
		// once, the tick a train disappears (see class KDoc "Statefulness" section).
		knownTrainIds.clear()
		knownTrainIds.addAll(presentIds)
		lastKnownViews.keys.retainAll(presentIds)
		for (view in activeViews) lastKnownViews[view.trainId] = view
		for (view in queuedViews) lastKnownViews[view.trainId] = view

		return (activeViews + queuedViews + exitedViews).sortedBy { it.trainId }
	}

	private fun phaseOf(perception: TrainPerceptionReading): TrainPhase =
		when {
			!perception.isDwelling -> TrainPhase.RUNNING
			perception.isStationDwelling -> TrainPhase.DWELLING
			else -> TrainPhase.HELD
		}

	/**
	 * The named switches in the grid, captured once (the grid is static for a context's lifetime —
	 * cells are never added or removed after context construction). Only [DynamicRailSwitch.conf]
	 * and the registry's switch-owner mapping are live, and those are read per tick in
	 * [buildSwitchViews] from these cached references. Scanning the whole grid every tick
	 * (the previous form) was O(cols×rows) per [projectTick] for a set that never changes.
	 *
	 * `lazy` so the single walk runs on the first [captureOnSimThread] (the sim thread) — the same
	 * construction-time safety [cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer.describe]
	 * relies on, since the kernel has not started mutating live state before the first tick.
	 */
	private val switches: List<DynamicRailSwitch> by lazy {
		environment
			.getRailWayNetGrid()
			.cellsOfType<DynamicRailSwitch>()
			.filter { it.name.isNotBlank() }
			.distinctBy { it.name }
	}

	private fun buildSwitchViews(): List<SwitchView> =
		switches
			.map { switch ->
				SwitchView(
					switchName = switch.name,
					position = switch.conf,
					lockedByTrainId = pathReservationRegistry.getSwitchOwner(switch)
				)
			}.sortedBy { it.switchName }

	private fun buildReservationViews(snapshot: SimulationSnapshot): List<ReservationView> =
		snapshot.trainPositions
			.mapNotNull { position ->
				val pathInfo: PathInfo = pathReservationRegistry.getPathInfo(position.trainId) ?: return@mapNotNull null
				ReservationView(
					trainId = position.trainId,
					fromEndpointName = separatorName(pathInfo.start),
					targetName = separatorName(pathInfo.target) ?: "",
					blockIds = pathReservationRegistry.getBlocks(position.trainId).map { BlockIdentity.stableBlockId(it) }
				)
			}.sortedBy { it.trainId }

	private fun buildQueuedViews(
		dispatchSnapshot: DispatchLoopSnapshot,
		simTime: Double
	): List<QueuedTrainView> =
		dispatchSnapshot.queuedTrains
			.map { queuedTrain ->
				QueuedTrainView(
					trainId = queuedTrain.trainId,
					destinationInOutName = queuedTrain.destinationInOutName,
					queuedSinceSimTime = waitStartSimTime[queuedTrain.trainId] ?: simTime
				)
			}.sortedBy { it.trainId }

	/**
	 * Debug-only guard (a plain Kotlin `assert`, no-op unless `-ea` is passed — active in this
	 * project's test tasks, inert in a normal production launch): records the first thread
	 * [captureOnSimThread] is called from, then asserts every later call is from that same
	 * thread. Catches a future refactor that accidentally moves the call off the kDisco
	 * simulation thread (see class KDoc "Threading contract") — a race that would otherwise be
	 * silent, since [DynamicRailSwitch.conf] and [PathReservationRegistry] reads have no
	 * synchronization of their own.
	 */
	private fun assertCalledFromSameThreadEveryTime() {
		val current = Thread.currentThread()
		val recorded = capturingThread
		if (recorded == null) {
			capturingThread = current
		} else {
			assert(recorded === current) {
				"DispatcherObservationProjector.captureOnSimThread() must only ever be called " +
					"from the kDisco simulation thread; first call was on thread '${recorded.name}', " +
					"this call is on thread '${current.name}'."
			}
		}
	}
}
