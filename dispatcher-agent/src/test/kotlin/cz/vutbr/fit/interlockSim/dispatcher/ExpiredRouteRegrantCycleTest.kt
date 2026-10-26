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

import assertk.assertThat
import assertk.assertions.each
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.ExpiredRouteQuarantine
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
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
 * One eastbound train is held at `zA`, on `kA`, with its route beyond `zA` granted: a
 * [StandingHoldScenario] puts `zA` back to STOP for as long as the train stands on `kA` alone. A train
 * that does not move loses its RESERVED tail every TTL (phase 2, Issue #1220); the plain
 * [RuleBasedDispatcher] grants it back in the same control step, and the cycle repeats (the control
 * arm). The quarantine forgives the first expiry and withholds every `ReservePath` for the train after
 * the second one on the same footprint, so the freed blocks are offered to the other trains (the
 * quarantine arm).
 *
 * ## Measured (base 13fab2be, `Random(0L)` generator)
 *
 * The hold on `kA` clogs the station: `kA` is also the westbound exit to InOut `A`, so westbound
 * trains wait at `doA2` for a route out that cannot be set, and the second of them to reach its 180 s
 * horizon ends the run with the Issue #943 FATAL ("no route extension ... after 180 s of waiting") at
 * about t = 299 s, in both arms; the last control step is t = 298 s. Up to then the control arm shows
 * three expiries of the held train (t = 128, 190, 252 s), each re-granted `zA -> doB1` in its own
 * control step; one train exits. The quarantine arm shows two (t = 128, 190 s) and no re-grant after
 * the second, over the 54 control steps that follow. No other train reserves a block after t = 94 s
 * in either arm, so the freed tail cannot be shown going to another train: per the
 * traffic-simulation-expert ruling on #1243 (E5 pin 10) the quarantine arm pins instead that the
 * tail is FREE and unowned at the end of the run, and that the run lasted more than a TTL past the
 * second expiry, so the missing third expiry is not an artefact of the run ending.
 *
 * The stack is the [cz.vutbr.fit.interlockSim.dispatcher.testutil.RuleBasedDispatcherDeterminismRunner]
 * stack minus its `DispatchTickLoop`: the dispatcher decides on the simulation thread inside the control
 * step, the decisions go through the [ActuatorCommandQueue] and the [DispatchDecisionApplier], whose
 * duplicate-`ReservePath` guard is evicted on every block release exactly as
 * `ExampleRegistry.wireDispatcherAgent` evicts it. Both arms run the same fixture and seed; the only
 * difference is the decorator.
 */
@Tag("integration-test")
class ExpiredRouteRegrantCycleTest : DispatcherKoinTestBase() {
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `the plain rule-based dispatcher re-grants a standing train's expired tail in the same step`() {
		val run = runStandingHold { RuleBasedDispatcher() }

		assertThat(run.standingTrain, "the train held at zA").isNotNull()
		val events = run.eventsOf(run.standingTrain)
		// The cycle exists: release -> same-step re-grant -> new stall clock, again and again.
		assertThat(events.size).isGreaterThanOrEqualTo(MIN_CONTROL_EXPIRIES)
		assertThat(events.map { it.kind }).each { it.isEqualTo(ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL) }
		assertThat(events.map { event -> event.keptBlocks.map { it.name } }).each { it.isEqualTo(listOf("kA")) }
		events.zipWithNext().forEach { (earlier, later) ->
			assertThat(later.time - earlier.time, "time between expiries").isGreaterThanOrEqualTo(TTL)
		}
		// Each expiry is answered in its own control step by a ReservePath from zA that really reserved.
		events.forEach { event ->
			assertThat(run.appliedReservePathTimes(run.standingTrain), "ReservePath from zA at ${event.time}")
				.transform { times -> times.any { it == event.time } }
				.isTrue()
			assertThat(run.reservedAt(run.standingTrain), "a block reserved again at ${event.time}")
				.transform { times -> times.any { it == event.time } }
				.isTrue()
		}
		assertThat(run.uncaught).isEmpty()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	fun `the quarantine stops re-granting a standing train after its second expiry`() {
		val run = runStandingHold { context -> ExpiredRouteQuarantine(RuleBasedDispatcher()).alsoRegisteredOn(context) }

		val standing = checkNotNull(run.standingTrain) { "no train was held at zA" }
		val events = run.eventsOf(standing)
		// Forgiven once (re-granted), quarantined at the second expiry: no third one.
		assertThat(events.size).isEqualTo(2)
		val quarantinedAt = events[1].time
		assertThat(run.appliedReservePathTimes(standing).filter { it >= quarantinedAt }, "ReservePaths after")
			.isEmpty()
		assertThat(run.reservedAt(standing).filter { it >= quarantinedAt }, "blocks reserved for it after")
			.isEmpty()
		assertThat(run.holdingsAfterQuarantine.filter { it != listOf("kA") }, "its holding after").isEmpty()
		assertThat(run.holdingsAfterQuarantine, "control steps checked after").isNotEmpty()
		// Non-vacuous: without the quarantine a third expiry would have fallen inside the run.
		assertThat(run.lastControlStepAt - quarantinedAt, "run time after the second expiry")
			.isGreaterThan(TTL)
		// The freed tail stays offered to the other trains: FREE and unowned at the end of the run (the
		// measured fallback of the class KDoc -- no other train reaches it within the run).
		assertThat(events[1].releasedBlocks).isNotEmpty()
		assertThat(run.tailFreeAndUnownedAtEnd, "the freed tail FREE and unowned at the end").isTrue()
		assertThat(run.conflicts).isEmpty()
		assertThat(run.uncaught).isEmpty()
	}

	/** Register [this] quarantine for the kernel's [ReservationExpired] events before `run()`. */
	private fun ExpiredRouteQuarantine.alsoRegisteredOn(context: DefaultSimulationContext): ExpiredRouteQuarantine =
		also { context.onReservationExpired(it::onReservationExpired) }

	/** What one run recorded; every field was written on the simulation thread. */
	private class Run(
		val standingTrain: String?,
		val events: List<ReservationExpired>,
		val appliedReservePaths: List<Pair<Double, DispatchDecision.ReservePath>>,
		val reservations: List<Reservation>,
		val holdingsAfterQuarantine: List<List<String?>>,
		val conflicts: List<Any>,
		val uncaught: List<Throwable>,
		val lastControlStepAt: Double,
		val tailFreeAndUnownedAtEnd: Boolean
	) {
		fun eventsOf(trainId: String?): List<ReservationExpired> = events.filter { it.trainId == trainId }

		/** Sim times at which a `ReservePath` of [trainId] from `zA` was applied. */
		fun appliedReservePathTimes(trainId: String?): List<Double> =
			appliedReservePaths
				.filter { (_, decision) -> decision.trainId == trainId && decision.fromSemaphoreName == "zA" }
				.map { it.first }

		/** Sim times at which a block was reserved for [trainId]. */
		fun reservedAt(trainId: String?): List<Double> = reservations.filter { it.trainId == trainId }.map { it.time }
	}

	private class Reservation(
		val time: Double,
		val trainId: String
	)

	/**
	 * Run [END_TIME] simulated seconds of the stack with the dispatcher [makeDispatcher] builds, the
	 * [StandingHoldScenario] observing every control step.
	 */
	private fun runStandingHold(makeDispatcher: (DefaultSimulationContext) -> Dispatcher): Run {
		val context = TestFixtures.newShuntingSimulationContext().tracked()
		context.getInOuts()
		val loop = ShuntingLoop(context, END_TIME)
		val service = context.getRoutingServices().getPathReservationService() as DefaultPathReservationService
		val registry = context.scope.get<PathReservationRegistry>()
		val zA = context.cellsOfType<DynamicRailSemaphore>().single { it.name == "zA" }

		val events = mutableListOf<ReservationExpired>()
		context.onReservationExpired { events += it }
		val dispatcher = makeDispatcher(context)
		val scenario = StandingHoldScenario(loop, registry, zA)

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
					if (decision is DispatchDecision.ReservePath) appliedReservePaths += currentSimulationTime() to decision
				}
			)
		val reservations = mutableListOf<Reservation>()
		service.addBlockOccupancyListener { event ->
			when (event.type) {
				// The duplicate-ReservePath guard, evicted as ExampleRegistry.wireDispatcherAgent evicts it.
				BlockOccupancyEventType.BLOCK_RELEASED -> event.trainId?.let(applier::evictReservationsFor)
				BlockOccupancyEventType.BLOCK_RESERVED ->
					event.trainId?.let { reservations += Reservation(currentSimulationTime(), it) }
				else -> Unit
			}
		}
		val conflicts = mutableListOf<Any>()
		context.onConflictDetectedEvent { conflicts += it }
		context.getCollisionServices().onCollisionWarning { conflicts += it }

		val holdingsAfterQuarantine = mutableListOf<List<String?>>()
		var lastControlStepAt = Double.NaN
		loop.snapshotCaptureHook = perceptionPort::captureSnapshot
		loop.controlStepListener =
			ControlStepListener {
				lastControlStepAt = currentSimulationTime()
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
				val standing = scenario.standingTrain
				if (standing != null && events.count { it.trainId == standing } >= 2) {
					holdingsAfterQuarantine.add(registry.getBlocks(standing).map { it.name })
				}
			}
		context.setMainProcess(loop)
		val result = UncaughtSimulationExceptions.record { context.run() }
		val lastTail = events.lastOrNull { it.trainId == scenario.standingTrain }?.releasedBlocks.orEmpty()

		return Run(
			standingTrain = scenario.standingTrain,
			events = events,
			appliedReservePaths = appliedReservePaths,
			reservations = reservations,
			holdingsAfterQuarantine = holdingsAfterQuarantine,
			conflicts = conflicts,
			uncaught = result.uncaught,
			lastControlStepAt = lastControlStepAt,
			tailFreeAndUnownedAtEnd =
				lastTail.all { it.getState() == TrackFacility.State.FREE && registry.getOwner(it) == null }
		)
	}

	/**
	 * A standing hold at `zA`: a copy of `ReservationTailExpiryScenarioTest.StopOnceScenario` (`:core`
	 * jvmTest) whose put-back is not one-off. The first eastbound train that stands on `kA` alone, far
	 * enough from `zA` to brake, with RESERVED blocks beyond, gets `zA` put back to STOP; from then on
	 * `zA` is put back on every control step while that train stands on `kA` alone -- at the start of
	 * the step and again after the dispatcher's decisions were applied, so a re-grant never shows the
	 * train a proceed aspect. The train never moves again; whether its route comes back is the
	 * dispatcher's business. All of it runs on the simulation thread.
	 */
	private class StandingHoldScenario(
		private val loop: ShuntingLoop,
		private val registry: PathReservationRegistry,
		private val zA: DynamicRailSemaphore
	) {
		var standingTrain: String? = null
			private set

		/** At the start of a control step, after the kernel's expiry tick and before the dispatcher. */
		fun observe() {
			if (standingTrain == null) holdTheFirstEastboundTrain() else holdAfterTheDispatcher()
		}

		/** Put `zA` back while the held train still stands on `kA` alone. */
		fun holdAfterTheDispatcher() {
			val held = standingTrain ?: return
			if (registry.getOccupiedBlocks(held).map { it.name } == listOf("kA")) zA.signal = Signal.STOP
		}

		private fun holdTheFirstEastboundTrain() {
			if (!zA.signal.isAllowing()) return
			val train =
				loop.getApprovedTrains().firstOrNull { candidate ->
					registry.getOccupiedBlocks(candidate.name).map { it.name } == listOf("kA") &&
						// Far enough from zA to stop short of it.
						candidate.distanceToSemaphore() > BRAKING_ROOM_METERS
				} ?: return
			if (registry.getBlocks(train.name).none { it.getState() == TrackFacility.State.RESERVED }) return
			zA.signal = Signal.STOP
			standingTrain = train.name
		}
	}

	private companion object {
		const val END_TIME: Long = 600L
		const val TTL = DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS

		/** Room a train in kA needs to brake to a stand short of zA. */
		const val BRAKING_ROOM_METERS = 60.0

		/** Expiries of the standing train the plain dispatcher must show inside [END_TIME]. */
		const val MIN_CONTROL_EXPIRIES = 3
	}
}
