/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.all
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.each
import assertk.assertions.isBetween
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import assertk.assertions.single
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.ExpiredRouteQuarantine
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.UncaughtSimulationExceptions
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The re-grant cycle of Issue #1243 on a real rule-based `ShuntingLoop` run of `vyhybna.xml`, and the
 * reference consumer [ExpiredRouteQuarantine] that ends it.
 *
 * One eastbound train is held on a platform track: a [StandingHoldScenario] picks the first eastbound
 * train whose route onto `k1` (or `k2`) has been granted while it is still on `kA`, and puts that
 * track's exit signal `doB1` (`doB2`) to STOP for good, so the train stands on the platform track alone
 * with its route beyond the signal RESERVED (traffic-simulation-expert follow-up ruling F1b). A train
 * that does not move loses its RESERVED tail every TTL (phase 2, Issue #1220); the plain
 * [RuleBasedDispatcher] grants it back in the same control step, and the cycle repeats (the control
 * arm). The quarantine forgives the first expiry and withholds every `ReservePath` for the train after
 * the second one on the same footprint, so the freed blocks go to the other trains, which run single
 * track on the other platform (the quarantine arm). A hold on `kA` would not do: `kA` is the one
 * approach every train needs (eastbound entry, westbound exit), so nothing could ever use the freed
 * tail (traffic-simulation-expert review of #1243, F1).
 *
 * ## The un-watched and the watched standstill (follow-up ruling F1c)
 *
 * The control arm's train always has a route: it stands at a STOP aspect inside a granted route, the
 * `waitUntilCrossing` standstill that the Issue #943 bound does not watch, so the plain dispatcher
 * starves the whole station silently. The quarantine withholds every route until the footprint changes,
 * a standing train cannot change its footprint without a route, and `Train.waitForPathOrReportStall`
 * watches exactly a train without a route: the quarantined train is diagnosed and the run stopped
 * [ISSUE_943_ERROR_HORIZON] seconds after its second expiry. The quarantine moves the failed train from
 * the un-watched state into the watched one, after the freed blocks went to the other trains; that
 * #943 FATAL is the quarantine arm's expected end.
 *
 * ## Measured (`vyhybna.xml`, `Random(0L)` generator, END_TIME 600 s)
 *
 * Both arms hold the first eastbound train on `k1` at `doB1`; it stands on `k1` alone at every expiry
 * and on every control step after the first. Control arm: eight expiries, at t = 130, 192, 254, 316,
 * 378, 440, 502 and 564 s (62 s apart), each `OCCUPYING_TRAIN_TAIL` freeing `doB1 -> B` (two 5 m
 * blocks and `kB`) and re-granted from `doB1` in the same control step; no errorStop, the run reaches
 * t = 600 s; one train exits, at t = 24 s, none in the remaining 576 s; no conflict. Quarantine arm: two
 * expiries (t = 130, 192 s), no route for the held train afterwards; two other trains reserve blocks of
 * the freed tail (`kB`) and two trains exit (t = 222, 252 s); the held train's #943 FATAL
 * (`no route extension at 'doB1' after 180 s of waiting`) ends the run at t = 372.7 s, the last control
 * step at t = 372 s = 192 s + 180 s; no conflict.
 *
 * The stack is the [cz.vutbr.fit.interlockSim.dispatcher.testutil.RuleBasedDispatcherDeterminismRunner]
 * stack minus its `DispatchTickLoop`: the dispatcher decides on the simulation thread inside the control
 * step, the decisions go through the [ActuatorCommandQueue] and the [DispatchDecisionApplier], whose
 * duplicate-`ReservePath` guard is evicted on every block release exactly as
 * `ExampleRegistry.wireDispatcherAgent` evicts it. The loop runs on a [NavigationDecoratingContext] so
 * every `errorStop` (which stops the run without throwing) is recorded. Both arms run the same fixture
 * and seed; the only difference is the decorator.
 */
@Tag("integration-test")
class ExpiredRouteRegrantCycleTest : DispatcherKoinTestBase() {
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `the plain rule-based dispatcher re-grants a standing train's expired tail in the same step`() {
		val run = runStandingHold { RuleBasedDispatcher() }

		val standing = checkNotNull(run.hold) { "no train was held on a platform track" }
		val events = run.eventsOf(standing.trainId)
		// It stands on the platform track alone and never passes the held signal (a 40 m train, 100 m track).
		assertThat(
			run.occupiedAtEvents,
			"its occupied blocks at each expiry"
		).each { it.isEqualTo(listOf(standing.trackName)) }
		assertThat(run.occupiedAfterFirstExpiry, "its occupied blocks after the first expiry")
			.each { it.isEqualTo(listOf(standing.trackName)) }
		// The cycle exists: release -> same-step re-grant -> new stall clock, again and again.
		assertThat(events.size).isGreaterThanOrEqualTo(MIN_CONTROL_EXPIRIES)
		assertThat(events.map { it.kind }).each { it.isEqualTo(ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL) }
		assertThat(events.map { event -> event.keptBlocks.map { it.name } })
			.each { it.isEqualTo(listOf(standing.trackName)) }
		events.zipWithNext().forEach { (earlier, later) ->
			assertThat(later.time - earlier.time, "time between expiries").isGreaterThanOrEqualTo(TTL)
		}
		// Each expiry is answered in its own control step by a ReservePath from the held signal that really
		// reserved.
		events.forEach { event ->
			assertThat(run.reservePathTimes(standing.trainId, standing.signalName), "ReservePath at ${event.time}")
				.transform { times -> times.any { it == event.time } }
				.isTrue()
			assertThat(run.reservedAt(standing.trainId), "a block reserved again at ${event.time}")
				.transform { times -> times.any { it == event.time } }
				.isTrue()
		}
		// The harm: the run reaches its end without a diagnosis, and nothing leaves the station meanwhile.
		assertThat(run.errorStops, "errorStops").isEmpty()
		assertThat(run.lastControlStepAt, "last control step").isGreaterThanOrEqualTo(END_TIME - TICK)
		assertThat(run.exitTimes.filter { it > events.first().time }, "exits after the first expiry").isEmpty()
		assertThat(run.conflicts).isEmpty()
		assertThat(run.uncaught).isEmpty()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `the quarantine stops re-granting a standing train after its second expiry`() {
		val run = runStandingHold { context -> ExpiredRouteQuarantine(RuleBasedDispatcher()).alsoRegisteredOn(context) }

		val standing = checkNotNull(run.hold) { "no train was held on a platform track" }
		val events = run.eventsOf(standing.trainId)
		// It stands on the platform track alone and never passes the held signal (a 40 m train, 100 m track).
		assertThat(
			run.occupiedAtEvents,
			"its occupied blocks at each expiry"
		).each { it.isEqualTo(listOf(standing.trackName)) }
		assertThat(run.occupiedAfterFirstExpiry, "its occupied blocks after the first expiry")
			.each { it.isEqualTo(listOf(standing.trackName)) }
		// Forgiven once (re-granted), quarantined at the second expiry: no third one.
		assertThat(events.size).isEqualTo(2)
		val quarantinedAt = events[1].time
		// No ReservePath for it at all, from any origin, and no block granted to it.
		assertThat(run.reservePathTimes(standing.trainId).filter { it >= quarantinedAt }, "ReservePaths after")
			.isEmpty()
		assertThat(run.reservedAt(standing.trainId).filter { it >= quarantinedAt }, "blocks reserved for it after")
			.isEmpty()
		assertThat(run.holdingsAfterQuarantine.filter { it != listOf(standing.trackName) }, "its holding after")
			.isEmpty()
		assertThat(run.holdingsAfterQuarantine, "control steps checked after").isNotEmpty()
		// The freed tail goes to another train, and traffic flows on ...
		assertThat(events[1].releasedBlocks).isNotEmpty()
		assertThat(run.otherTrainsReserving(events[1].releasedBlocks, standing.trainId, after = quarantinedAt))
			.isNotEmpty()
		assertThat(run.exitTimes.filter { it > quarantinedAt }, "exits after the quarantine").isNotEmpty()
		assertThat(run.conflicts).isEmpty()
		assertThat(run.uncaught).isEmpty()
		// ... until the #943 bound diagnoses the quarantined train, which has no route: the expected end.
		val diagnosis = assertThat(run.errorStops, "errorStops").single()
		diagnosis.isInstanceOf<SimulationException>()
		diagnosis.transform { it.message.orEmpty() }.all {
			startsWith("${standing.trainId}: no route extension at")
			contains("after 180 s of waiting")
		}
		assertThat(run.lastControlStepAt, "last control step")
			.isBetween(
				quarantinedAt + ISSUE_943_ERROR_HORIZON - TICK,
				quarantinedAt + ISSUE_943_ERROR_HORIZON + TICK
			)
	}

	/** Register [this] quarantine for the kernel's [ReservationExpired] events before `run()`. */
	private fun ExpiredRouteQuarantine.alsoRegisteredOn(context: DefaultSimulationContext): ExpiredRouteQuarantine =
		also { context.onReservationExpired(it::onReservationExpired) }

	/** The train the scenario holds, the platform track it stands on and the exit signal it is held at. */
	private class Hold(
		val trainId: String,
		val trackName: String,
		val signalName: String
	)

	/** What one run recorded; every field was written on the simulation thread. */
	private class Run(
		val hold: Hold?,
		val events: List<ReservationExpired>,
		val appliedReservePaths: List<Pair<Double, DispatchDecision.ReservePath>>,
		val reservations: List<Reservation>,
		val holdingsAfterQuarantine: List<List<String?>>,
		val occupiedAtEvents: List<List<String?>>,
		val occupiedAfterFirstExpiry: List<List<String?>>,
		val exitTimes: List<Double>,
		val conflicts: List<Any>,
		val errorStops: List<Throwable>,
		val uncaught: List<Throwable>,
		val lastControlStepAt: Double
	) {
		fun eventsOf(trainId: String): List<ReservationExpired> = events.filter { it.trainId == trainId }

		/** Sim times at which a `ReservePath` of [trainId] was applied, from [fromSemaphoreName] or any origin. */
		fun reservePathTimes(
			trainId: String,
			fromSemaphoreName: String? = null
		): List<Double> =
			appliedReservePaths
				.filter { (_, decision) ->
					decision.trainId == trainId &&
						(fromSemaphoreName == null || decision.fromSemaphoreName == fromSemaphoreName)
				}.map { it.first }

		/** Sim times at which a block was reserved for [trainId]. */
		fun reservedAt(trainId: String): List<Double> = reservations.filter { it.trainId == trainId }.map { it.time }

		/** The trains other than [trainId] that reserved one of [blocks] strictly after [after]. */
		fun otherTrainsReserving(
			blocks: List<DynamicTrackBlock>,
			trainId: String,
			after: Double
		): Set<String> =
			reservations
				.filter { it.time > after && it.trainId != trainId && it.block in blocks }
				.mapTo(HashSet()) { it.trainId }
	}

	private class Reservation(
		val time: Double,
		val trainId: String,
		val block: DynamicTrackBlock
	)

	/**
	 * Run [END_TIME] simulated seconds of the stack with the dispatcher [makeDispatcher] builds, the
	 * [StandingHoldScenario] observing every control step.
	 */
	private fun runStandingHold(makeDispatcher: (DefaultSimulationContext) -> Dispatcher): Run {
		val context = TestFixtures.newShuntingSimulationContext().tracked()
		// Creates the dynamic InOut wrappers ShuntingLoop's constructor needs; the list itself is not used.
		context.getInOuts()
		val errorStops = mutableListOf<Throwable>()
		// The real navigation: the wrapper only reports every errorStop to this test.
		val env =
			NavigationDecoratingContext(context, context.getRoutingServices().getTrainNavigationService()) {
				errorStops += it
			}
		val loop = ShuntingLoop(env, END_TIME)
		val service = context.getRoutingServices().getPathReservationService() as DefaultPathReservationService
		val registry = context.scope.get<PathReservationRegistry>()
		val semaphores = context.cellsOfType<DynamicRailSemaphore>().associateBy { it.name }
		val exitSignals = PLATFORM_EXITS.mapValues { (_, signal) -> checkNotNull(semaphores[signal]) { signal } }

		val dispatcher = makeDispatcher(context)
		val scenario = StandingHoldScenario(loop, registry, exitSignals)
		val events = mutableListOf<ReservationExpired>()
		// Records only: the registry read is a query, never a call back into a mutating seam.
		val occupiedAtEvents = mutableListOf<List<String?>>()
		context.onReservationExpired { event ->
			events += event
			if (event.trainId == scenario.hold?.trainId) {
				occupiedAtEvents += registry.getOccupiedBlocks(event.trainId).map { it.name }
			}
		}

		val perceptionPort = DefaultNetworkPerceptionPort(env = context, activeTrains = loop::getApprovedTrains)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
		val queue = ActuatorCommandQueue()
		val appliedReservePaths = mutableListOf<Pair<Double, DispatchDecision.ReservePath>>()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = actuatorPort,
				onApproveTrain = loop::approveQueuedTrain,
				onBlockTransition = loop::incrementBlockTransition,
				onFailedReservation = loop::incrementFailedReservation,
				onDecisionApplied = { decision ->
					if (decision is DispatchDecision.ReservePath) {
						appliedReservePaths += currentSimulationTime() to decision
					}
				}
			)
		val reservations = mutableListOf<Reservation>()
		service.addBlockOccupancyListener { event ->
			when (event.type) {
				// The duplicate-ReservePath guard, evicted as ExampleRegistry.wireDispatcherAgent evicts it.
				BlockOccupancyEventType.BLOCK_RELEASED -> event.trainId?.let(applier::evictReservationsFor)
				BlockOccupancyEventType.BLOCK_RESERVED ->
					event.trainId?.let { reservations += Reservation(currentSimulationTime(), it, event.block) }
				else -> Unit
			}
		}
		val conflicts = mutableListOf<Any>()
		context.onConflictDetectedEvent { conflicts += it }
		context.getCollisionServices().onCollisionWarning { conflicts += it }

		val holdingsAfterQuarantine = mutableListOf<List<String?>>()
		val occupiedAfterFirstExpiry = mutableListOf<List<String?>>()
		val exitTimes = mutableListOf<Double>()
		var lastControlStepAt = Double.NaN
		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		loop.controlStepListener =
			ControlStepListener {
				lastControlStepAt = currentSimulationTime()
				repeat(loop.getTrainsExited() - exitTimes.size) { exitTimes += lastControlStepAt }
				scenario.observe()
				val observation =
					DispatchObservation(
						perceptionPort.snapshot(),
						loop.getQueuedTrains(),
						loop.getInnerBlockInputs(),
						loop.getOuterBlockInputs()
					)
				queue.postAll(dispatcher.decide(observation))
				applier.onControlStep()
				scenario.holdAfterTheDispatcher()
				val held = scenario.hold
				val heldEvents = events.count { it.trainId == held?.trainId }
				if (held != null && heldEvents >= 1) {
					occupiedAfterFirstExpiry.add(registry.getOccupiedBlocks(held.trainId).map { it.name })
				}
				if (held != null && heldEvents >= 2) {
					holdingsAfterQuarantine.add(registry.getBlocks(held.trainId).map { it.name })
				}
			}
		context.setMainProcess(loop)
		val result = UncaughtSimulationExceptions.record { context.run() }

		return Run(
			hold = scenario.hold,
			events = events,
			appliedReservePaths = appliedReservePaths,
			reservations = reservations,
			holdingsAfterQuarantine = holdingsAfterQuarantine,
			occupiedAtEvents = occupiedAtEvents,
			occupiedAfterFirstExpiry = occupiedAfterFirstExpiry,
			exitTimes = exitTimes,
			conflicts = conflicts,
			errorStops = errorStops,
			uncaught = result.uncaught,
			lastControlStepAt = lastControlStepAt
		)
	}

	/**
	 * A standing hold on a platform track (traffic-simulation-expert follow-up ruling F1b on #1243), after
	 * `ReservationTailExpiryScenarioTest.StopOnceScenario` (`:core` jvmTest), but not one-off. The first
	 * approved train that still stands on `kA` alone (eastbound, before `zA`) and whose route onto a
	 * platform track has been granted (its holding contains `k1` or `k2`) is held: the exit signal of that
	 * track ([PLATFORM_EXITS]: `doB1` for `k1`, `doB2` for `k2`) is put to STOP at once, so the train passes
	 * `zA`, brakes towards the exit signal and stands on the platform track alone (a 40 m train on a
	 * 100 m track). From then on the signal is put back to STOP on every control step while the train is
	 * approved -- at the start of the step and again after the dispatcher's decisions were applied, so a
	 * re-grant never shows the train a proceed aspect. No other train can be routed through that signal:
	 * the track is the held train's. The hold is the scenario's stand-in for a train that cannot move;
	 * whether its route comes back is the dispatcher's business. All of it runs on the simulation thread.
	 */
	private class StandingHoldScenario(
		private val loop: ShuntingLoop,
		private val registry: PathReservationRegistry,
		private val exitSignals: Map<String, DynamicRailSemaphore>
	) {
		var hold: Hold? = null
			private set
		private var heldSignal: DynamicRailSemaphore? = null

		/** At the start of a control step, after the kernel's expiry tick and before the dispatcher. */
		fun observe() {
			if (hold == null) holdTheFirstEastboundTrain() else holdAfterTheDispatcher()
		}

		/** Put the held signal back to STOP while the held train is approved. */
		fun holdAfterTheDispatcher() {
			val held = hold ?: return
			if (loop.getApprovedTrains().any { it.name == held.trainId }) checkNotNull(heldSignal).signal = Signal.STOP
		}

		private fun holdTheFirstEastboundTrain() {
			for (train in loop.getApprovedTrains()) {
				if (registry.getOccupiedBlocks(train.name).map { it.name } != listOf("kA")) continue
				// Its route onto a platform track has landed: the holding contains k1 or k2.
				val track =
					registry
						.getBlocks(train.name)
						.firstNotNullOfOrNull { block -> block.name?.takeIf { it in exitSignals } }
						?: continue
				val exit = checkNotNull(exitSignals[track])
				exit.signal = Signal.STOP
				heldSignal = exit
				hold = Hold(train.name, track, exit.name)
				return
			}
		}
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val TTL = DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS

		/** The loop's control period: `ShuntingLoop.iteration()` runs every 2.0 simulated seconds. */
		const val TICK = 2.0

		/** Expiries of the standing train the plain dispatcher must show inside [END_TIME] (eight measured). */
		const val MIN_CONTROL_EXPIRIES = 3

		/**
		 * The Issue #943 awaiting-extension error horizon, in simulated seconds: `Train`'s
		 * `OWNERSHIP_CONFLICT_ERROR_HORIZON_SECONDS` (`internal` to `:core`, so it cannot be referenced
		 * here), pinned in `:core` by `Issue943OwnershipConflictStallBoundTest`. A drift of that horizon
		 * fails this test on purpose: the quarantine arm's end is timed by it.
		 */
		const val ISSUE_943_ERROR_HORIZON = 180.0

		/** The platform tracks of `vyhybna.xml` and their eastbound exit signals. */
		val PLATFORM_EXITS = mapOf("k1" to "doB1", "k2" to "doB2")
	}
}
