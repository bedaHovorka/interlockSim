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

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.each
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isIn
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.TailStallTracker
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Reservation expiry phase 2 (Issue #1220) in a whole rule-based `ShuntingLoop` run on `vyhybna.xml`:
 * one train is forced to stop on its granted route, and over the following expiry cycles the kernel
 * frees its stalled RESERVED tail exactly once; other trains are granted the freed blocks, and the run stays
 * free of conflict events, collision warnings and `errorStop`s.
 *
 * ## How the stop is forced
 *
 * The first eastbound train that enters `kA` (from InOut `A`) with its route beyond `zA` granted
 * gets `zA` put back to STOP in front of it -- the signal-put-back a dispatcher may issue
 * (`set_signal_aspect`). The train brakes and stands at `zA`, on `kA`, holding the rest of its
 * route RESERVED; the rule-based dispatcher sees a route already set past `zA` and does nothing.
 * After the TTL of standstill the kernel frees that tail; the dispatcher then sets a route from `zA`
 * again, and the train continues.
 *
 * "Exactly once" holds because the put-back is one-off, not because the rule allows a tail to expire
 * only once: a train that never moves again cycles release, a same-tick re-grant by the dispatcher
 * and a new stall clock every TTL (traffic-simulation-expert review of #1220, finding F2).
 *
 * The TTL is measured from the standstill, not from the put-back: the train brakes for a while after
 * `zA` drops to STOP. The test follows the stopped train's `distanceToSemaphore()` on every control
 * step with the kernel's own D2 rule (a move of more than 0.01 m from the reading the clock started
 * at restarts it; the train stands on `kA` alone and its tail does not change meanwhile), and asserts
 * that the tail goes exactly one TTL after the last control step that saw it move.
 *
 * Why not a forced navigation answer or a train-side brake: a train held at a separator by an
 * injected `OwnershipConflict` is the #1060 "awaiting extension" state, for which the dispatcher
 * re-requests the route each tick and so re-clears the signal -- approach locking then keeps the tail
 * (correctly: a train may be committed across a proceed aspect). And a target speed of 0 brakes the
 * train to the stop line of the signal ahead, where a proceed aspect lets it pass: it never stalls.
 *
 * Deterministic: the train generator's seed is fixed (`Random(0L)`), the TTL is the production
 * default, and the run is bounded in simulated time.
 */
@Tag("integration-test")
class ReservationTailExpiryScenarioTest : KoinTestBase() {
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `a train stopped on its route loses its tail once and the run stays conflict-free`() {
		val run = runStopOnceScenario()

		assertTailFreedOneTtlAfterStandstill(run)
		assertHeadKeptAndTailRegranted(run)
		assertRunClean(run)
		assertExpiryAnnounced(run)
	}

	/** One finished run of the scenario and everything the test recorded during it. */
	private class ScenarioRun(
		val service: DefaultPathReservationService,
		val registry: PathReservationRegistry,
		val loop: ShuntingLoop,
		val scenario: StopOnceScenario,
		val conflicts: List<Any>,
		val errorStops: List<Throwable>,
		val expired: List<ReservationExpired>
	)

	/** Load `vyhybna.xml`, wire the rule-based loop with the [StopOnceScenario], and run it to the end. */
	private fun runStopOnceScenario(): ScenarioRun {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val service = context.getRoutingServices().getPathReservationService() as DefaultPathReservationService
		val registry = context.scope.get<PathReservationRegistry>()
		val errorStops = mutableListOf<Throwable>()
		// The real navigation: the wrapper only reports every errorStop to this test.
		val env =
			NavigationDecoratingContext(context, context.getRoutingServices().getTrainNavigationService()) {
				errorStops += it
			}
		val loop = ShuntingLoop(env, END_TIME)
		wireSynchronousDispatcher(env, loop)
		context.setMainProcess(loop)
		val zA = context.cellsOfType<DynamicRailSemaphore>().single { it.name == "zA" }
		val scenario = StopOnceScenario(SignalPutBack(loop, registry, zA), service, registry)
		observeBeforeDispatcher(loop, scenario)
		service.addBlockOccupancyListener { event ->
			if (event.type == BlockOccupancyEventType.BLOCK_RESERVED) scenario.reserved(event.block, event.trainId)
		}
		val conflicts = collectConflicts(context)
		val expired = mutableListOf<ReservationExpired>()
		context.onReservationExpired { expired += it }
		context.run()
		return ScenarioRun(service, registry, loop, scenario, conflicts, errorStops, expired)
	}

	/** Runs [scenario] on every control step after the kernel expiry tick and before the dispatcher. */
	private fun observeBeforeDispatcher(
		loop: ShuntingLoop,
		scenario: StopOnceScenario
	) {
		val dispatcherStep = loop.controlStepListener
		loop.controlStepListener =
			ControlStepListener {
				scenario.observe()
				dispatcherStep?.onControlStep()
			}
	}

	/** Every conflict event and collision warning [context] raises from now on. */
	private fun collectConflicts(context: SimulationEnvironment): List<Any> {
		val conflicts = mutableListOf<Any>()
		context.onConflictDetectedEvent { conflicts += it }
		context.getCollisionServices().onCollisionWarning { conflicts += it }
		return conflicts
	}

	/** Non-vacuous: a train was stopped with a tail, rolled on after the put-back, and lost it once. */
	private fun assertTailFreedOneTtlAfterStandstill(run: ScenarioRun) {
		val scenario = run.scenario
		assertThat(scenario.stalledTrain).isNotNull()
		assertThat(scenario.putBack.tail).isNotEmpty()
		val observation = checkNotNull(scenario.observation) { "the stalled tail was never freed" }
		assertThat(scenario.clock.lastMovedAt).isGreaterThan(scenario.putBack.stoppedAt)
		// The tail went exactly one TTL after the train stood still, and only once.
		assertThat(observation.at - scenario.clock.lastMovedAt)
			.isCloseTo(DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS, TIME_TOLERANCE)
		assertThat(run.service.expiry.expiredTailCount).isEqualTo(1)
	}

	/** The block the train stood on stayed its own; the freed blocks went to other trains; it went on. */
	private fun assertHeadKeptAndTailRegranted(run: ScenarioRun) {
		val scenario = run.scenario
		val observation = checkNotNull(scenario.observation) { "the stalled tail was never freed" }
		assertThat(observation.tailFree, "tail FREE and unowned on the expiry tick").isTrue()
		assertThat(observation.headKept, "kA still the stopped train's on the expiry tick").isTrue()
		assertThat(scenario.otherTrainsOnFreedTail, "other trains reserving the freed blocks").isNotEmpty()
		assertThat(scenario.stalledTrain).isNotNull().transform { run.registry.getBlocks(it) }.isEmpty()
		assertThat(run.loop.getTrainsExited()).isGreaterThanOrEqualTo(MIN_EXITED)
	}

	/** No conflict, collision warning or errorStop anywhere in the run. */
	private fun assertRunClean(run: ScenarioRun) {
		assertThat(run.conflicts).isEmpty()
		assertThat(run.errorStops).isEmpty()
	}

	/** The real event bus announced the one tail expiry, on the tick the counter moved (Issue #1243). */
	private fun assertExpiryAnnounced(run: ScenarioRun) {
		val scenario = run.scenario
		val observation = checkNotNull(scenario.observation) { "the stalled tail was never freed" }
		val event = run.expired.single()
		assertThat(event.trainId).isEqualTo(scenario.stalledTrain)
		assertThat(event.kind).isEqualTo(ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL)
		assertThat(event.keptBlocks.map { it.name }).containsExactly("kA")
		assertThat(event.releasedBlocks).isNotEmpty()
		assertThat(event.releasedBlocks).each { it.isIn(*scenario.putBack.tail.toTypedArray()) }
		assertThat(event.time).isCloseTo(observation.at, TIME_TOLERANCE)
		assertThat(event.stalledForSimSeconds)
			.isCloseTo(observation.at - scenario.clock.lastMovedAt, TIME_TOLERANCE)
	}

	/** What the expiry tick left behind, read on the first control step after it. */
	private data class TailExpiryObservation(
		val at: Double,
		val tailFree: Boolean,
		val headKept: Boolean
	)

	/**
	 * The one-off signal put-back: the first eastbound train standing on `kA` alone, far enough from
	 * `zA` to stop short of it and holding a RESERVED route beyond, gets `zA` put back to STOP.
	 */
	private class SignalPutBack(
		private val loop: ShuntingLoop,
		private val registry: PathReservationRegistry,
		private val zA: DynamicRailSemaphore
	) {
		var stoppedAt = Double.NaN
			private set

		/** The RESERVED blocks the stopped train held at the put-back. */
		var tail: List<DynamicTrackBlock> = emptyList()
			private set

		/** Put `zA` back to STOP in front of the first eligible train and return it; `null` if none yet. */
		fun trySelect(): Train? {
			if (!zA.signal.isAllowing()) return null
			val train = loop.getApprovedTrains().firstOrNull(::isEligible) ?: return null
			val held = registry.getBlocks(train.name).filter { it.getState() == TrackFacility.State.RESERVED }
			if (held.isEmpty()) return null
			zA.signal = Signal.STOP
			stoppedAt = currentSimulationTime()
			tail = held
			return train
		}

		/** On `kA` alone and far enough from `zA` to stop short of it. */
		private fun isEligible(candidate: Train): Boolean =
			registry.isOnKaOnly(candidate.name) && candidate.distanceToSemaphore() > BRAKING_ROOM_METERS
	}

	/**
	 * The kernel's D2 reading rule for the stopped train, followed on every control step: a move of
	 * more than [TailStallTracker.PROGRESS_THRESHOLD_METERS] from the reading the clock started at
	 * restarts it.
	 */
	private class StandstillClock {
		/** The last control step at which the followed train had moved by the D2 rule. */
		var lastMovedAt = Double.NaN
			private set
		private var clockReading = Double.NaN

		fun follow(train: Train) {
			val reading = train.distanceToSemaphore()
			if (clockReading.isNaN() || hasMoved(reading)) {
				clockReading = reading
				lastMovedAt = currentSimulationTime()
			}
		}

		private fun hasMoved(reading: Double): Boolean =
			abs(reading - clockReading) > TailStallTracker.PROGRESS_THRESHOLD_METERS
	}

	/** The one forced stop and what the test records around it; all of it on the simulation thread. */
	private class StopOnceScenario(
		val putBack: SignalPutBack,
		private val service: DefaultPathReservationService,
		private val registry: PathReservationRegistry
	) {
		val clock = StandstillClock()
		val otherTrainsOnFreedTail = mutableSetOf<String>()
		private var stopped: Train? = null

		/** Set once, on the first control step after the kernel freed the tail. */
		var observation: TailExpiryObservation? = null
			private set

		val stalledTrain: String?
			get() = stopped?.name

		/** Put the signal back, follow the standstill, or record the expiry -- one step at a time. */
		fun observe() {
			val train = stopped
			when {
				train == null -> stopped = putBack.trySelect()
				observation != null -> Unit
				service.expiry.expiredTailCount == 0 -> clock.follow(train)
				else -> observation = observeExpiry(train.name)
			}
		}

		fun reserved(
			block: DynamicTrackBlock,
			trainId: String?
		) {
			if (trainId != null && isOtherTrainOnFreedTail(block, trainId)) otherTrainsOnFreedTail += trainId
		}

		private fun observeExpiry(trainId: String) =
			TailExpiryObservation(
				at = currentSimulationTime(),
				tailFree = putBack.tail.all(::isFreeAndUnowned),
				headKept = registry.isOnKaOnly(trainId)
			)

		private fun isFreeAndUnowned(block: DynamicTrackBlock): Boolean =
			block.getState() == TrackFacility.State.FREE && registry.getOwner(block) == null

		private fun isOtherTrainOnFreedTail(
			block: DynamicTrackBlock,
			trainId: String
		): Boolean = observation != null && trainId != stalledTrain && block in putBack.tail
	}

	private companion object {
		const val END_TIME: Long = 400L

		/** Room a train in kA needs to brake to a stand short of zA. */
		const val BRAKING_ROOM_METERS = 60.0

		/** The clean 400 s run lets most trains through; the stop costs at most a couple of journeys. */
		const val MIN_EXITED = 5

		/** Control steps fall on whole simulated seconds; this only absorbs floating-point noise. */
		const val TIME_TOLERANCE = 1.0e-6
	}
}

/** Whether [trainId] stands on `kA` and on nothing else. */
private fun PathReservationRegistry.isOnKaOnly(trainId: String): Boolean =
	getOccupiedBlocks(trainId).map { it.name } == listOf("kA")
