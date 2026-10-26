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
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.TailStallTracker
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
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val service = context.getRoutingServices().getPathReservationService() as DefaultPathReservationService
		val registry = context.scope.get<PathReservationRegistry>()
		val zA = context.cellsOfType<DynamicRailSemaphore>().single { it.name == "zA" }
		val errorStops = mutableListOf<Throwable>()
		// The real navigation: the wrapper only reports every errorStop to this test.
		val env =
			NavigationDecoratingContext(context, context.getRoutingServices().getTrainNavigationService()) {
				errorStops += it
			}
		val loop = ShuntingLoop(env, END_TIME)
		wireSynchronousDispatcher(env, loop)
		context.setMainProcess(loop)

		val scenario = StopOnceScenario(loop, service, registry, zA)
		val dispatcherStep = loop.controlStepListener
		// Runs on every control step after the kernel expiry tick and before the dispatcher.
		loop.controlStepListener =
			ControlStepListener {
				scenario.observe()
				dispatcherStep?.onControlStep()
			}
		val conflicts = mutableListOf<Any>()
		context.onConflictDetectedEvent { conflicts += it }
		context.getCollisionServices().onCollisionWarning { conflicts += it }
		service.addBlockOccupancyListener { event ->
			if (event.type == BlockOccupancyEventType.BLOCK_RESERVED) scenario.reserved(event.block, event.trainId)
		}

		context.run()

		// Non-vacuous: a train was stopped, with a tail; it rolled on for a while after the put-back ...
		val stalled = assertThat(scenario.stalledTrain).isNotNull()
		assertThat(scenario.tail).isNotEmpty()
		val tailFreedAt = checkNotNull(scenario.tailFreedAt) { "the stalled tail was never freed" }
		assertThat(scenario.lastMovedAt).isGreaterThan(scenario.stoppedAt)
		// ... and its tail went exactly one TTL after it stood still.
		assertThat(tailFreedAt - scenario.lastMovedAt)
			.isCloseTo(DefaultPathReservationService.DEFAULT_RESERVATION_TTL_SIM_SECONDS, TIME_TOLERANCE)
		// The tail went once, the block the train stood on stayed its own ...
		assertThat(service.expiry.expiredTailCount).isEqualTo(1)
		assertThat(scenario.tailFreeAtExpiry, "tail FREE and unowned on the expiry tick").isTrue()
		assertThat(scenario.headKeptAtExpiry, "kA still the stopped train's on the expiry tick").isTrue()
		// ... the freed blocks were granted to other trains again, and the stopped train got going again ...
		assertThat(scenario.otherTrainsOnFreedTail, "other trains reserving the freed blocks").isNotEmpty()
		stalled.transform { registry.getBlocks(it) }.isEmpty()
		assertThat(loop.getTrainsExited()).isGreaterThanOrEqualTo(MIN_EXITED)
		// ... and no conflict, collision warning or errorStop anywhere in the run.
		assertThat(conflicts).isEmpty()
		assertThat(errorStops).isEmpty()
	}

	/** The one forced stop and what the test records around it; all of it on the simulation thread. */
	private class StopOnceScenario(
		private val loop: ShuntingLoop,
		private val service: DefaultPathReservationService,
		private val registry: PathReservationRegistry,
		private val zA: DynamicRailSemaphore
	) {
		var stalledTrain: String? = null
		var stoppedAt = Double.NaN

		/** The last control step at which the stopped train had moved by the kernel's D2 rule. */
		var lastMovedAt = Double.NaN
		private var stopped: Train? = null
		private var clockReading = Double.NaN
		var tail: List<DynamicTrackBlock> = emptyList()
		var tailFreedAt: Double? = null
		var tailFreeAtExpiry = false
		var headKeptAtExpiry = false
		val otherTrainsOnFreedTail = mutableSetOf<String>()

		fun observe() {
			val stalled = stalledTrain
			if (stalled == null) {
				putBackSignalInFrontOfTheFirstEastboundTrain()
			} else if (tailFreedAt == null && service.expiry.expiredTailCount == 0) {
				followStandstill()
			} else if (tailFreedAt == null) {
				tailFreedAt = currentSimulationTime()
				tailFreeAtExpiry =
					tail.all { it.getState() == TrackFacility.State.FREE && registry.getOwner(it) == null }
				headKeptAtExpiry = registry.getOccupiedBlocks(stalled).map { it.name } == listOf("kA")
			}
		}

		fun reserved(
			block: DynamicTrackBlock,
			trainId: String?
		) {
			if (tailFreedAt != null && trainId != null && trainId != stalledTrain && block in tail) {
				otherTrainsOnFreedTail += trainId
			}
		}

		/** The kernel's D2 reading rule for the stopped train: a move above 0.01 m restarts the clock. */
		private fun followStandstill() {
			val reading = checkNotNull(stopped).distanceToSemaphore()
			val moved = abs(reading - clockReading) > TailStallTracker.PROGRESS_THRESHOLD_METERS
			if (clockReading.isNaN() || moved) {
				clockReading = reading
				lastMovedAt = currentSimulationTime()
			}
		}

		private fun putBackSignalInFrontOfTheFirstEastboundTrain() {
			if (!zA.signal.isAllowing()) return
			val train =
				loop.getApprovedTrains().firstOrNull { candidate ->
					registry.getOccupiedBlocks(candidate.name).map { it.name } == listOf("kA") &&
						// Far enough from zA to stop short of it.
						candidate.distanceToSemaphore() > BRAKING_ROOM_METERS
				} ?: return
			val held = registry.getBlocks(train.name).filter { it.getState() == TrackFacility.State.RESERVED }
			if (held.isEmpty()) return
			zA.signal = Signal.STOP
			stalledTrain = train.name
			stopped = train
			stoppedAt = currentSimulationTime()
			tail = held
		}
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
