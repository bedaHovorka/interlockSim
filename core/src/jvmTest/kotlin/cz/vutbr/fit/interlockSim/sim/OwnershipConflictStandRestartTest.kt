/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * A train that navigation holds at a separator with an ownership conflict: the stand at the
 * separator and the restart once navigation lets it go.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.NavigationDecoratingContext
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSample
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSampler
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.decoratingTrainNavigationService
import cz.vutbr.fit.interlockSim.testutil.motorOf
import cz.vutbr.fit.interlockSim.testutil.runSimpleLinearTrackScenario
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * The stop a train makes when navigation answers its query at a separator with an **ownership
 * conflict** — "wait for the route to be extended" (PR #940) — and what the train does at that stop.
 *
 * ## History
 *
 * This class was `RearFacingRouteTerminusRestartTest`. It reproduced the stand with the grant a
 * `shuntingLoopAI` run showed on 2026-09-13 (Train #3, run.log): the LLM granted `B → doB1`, a route
 * whose end faces away from a B → A train, so navigation answered its leg from `zB` with an ownership
 * conflict and the train stood at a green `zB`. G8 (Issue #1064) now refuses that grant, so the
 * scenario can no longer be built that way.
 *
 * The stand itself did not go away with the grant. `Site.actions()` stops the train at the
 * separator on every ownership-conflict answer, and navigation still gives that answer whenever the
 * stored route does not reach the next facing separator yet — a route extension the dispatcher has
 * not made, or a PathInfo that stops short of the train's next signal. This test therefore forces
 * the answer: the train runs a legal `B → A` route, navigation reports an ownership conflict at `zB`
 * until the test lifts it, and the two rungs pin the same behaviour as before:
 *
 * - [theTrainStandsAtTheGreenSignalWhereNavigationHoldsIt] — the stand: at the separator, not a
 *   clearance short of it, with the aspect still allowing, and the motor idle (zero acceleration,
 *   passivated).
 * - [theTrainRestartsOnceNavigationLetsItGo] — lifting the conflict wakes the wait, the separator
 *   restarts the train, and it completes the journey.
 *
 * The motor's idleness at this stand is a rounding coin flip on code without the `commandPending`
 * guard: the stop's cancel wakes the motor twice only when the last integration step ends on the
 * near side of the signal. `MotorBareCancelTest` pins that defect deterministically; the assertion
 * here is the invariant at the stop a real run showed.
 */
@Tag("integration-test")
@DisplayName("A train held at a separator by an ownership conflict: the stand at zB and the restart")
class OwnershipConflictStandRestartTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private companion object {
		/** `B —100 m— zB`: the stand lands at the separator itself, the aspect being allowing. */
		const val DISTANCE_TO_ZB = 100.0

		/** Full length of the route B → A: three 100 m blocks and four 5 m blocks. */
		const val ROUTE_LENGTH = 320.0

		/** Train length; short enough to stand inside kB with its tail clear of B. */
		const val TRAIN_LENGTH = 20.0

		/** Simulation end time; the journey with the stand takes about 30 s. */
		const val END_TIME = 90L

		/** Sampling period of the kinematic sampler that also drives the scenario. */
		const val SAMPLE_PERIOD = 0.05

		/**
		 * How long the train is held before navigation lets it go. Well inside the
		 * ownership-conflict WARN horizon, and long enough for every wake-up of the stop to be
		 * delivered and settle before the motor is inspected.
		 */
		const val STAND_HOLD_SECONDS = 3.0

		/**
		 * Position tolerance for the stand: the `maxAbsError = 1e-2` the generator is configured
		 * with. The measured stand is `dtMin` short of the separator.
		 */
		const val POSITION_TOLERANCE = 1e-2

		/** The separator navigation holds the train at. */
		const val HOLD_SIGNAL = "zB"
	}

	/** Everything one run of the scenario produces for assertion. */
	private class Outcome(
		val standDistance: Double,
		val aspectAllowingAtStand: Boolean,
		val peakAccelerationWhileStanding: Double,
		val motorPassivatedBeforeRelease: Boolean,
		val releasedAt: Double,
		val trainsExited: Int,
		val finalDistance: Double
	)

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the train stands at the green signal where navigation holds it")
	fun theTrainStandsAtTheGreenSignalWhereNavigationHoldsIt() {
		val outcome = runScenario()

		assertThat(outcome.standDistance, name = "distance travelled at the stand")
			.isBetween(DISTANCE_TO_ZB - POSITION_TOLERANCE, DISTANCE_TO_ZB + POSITION_TOLERANCE)
		assertThat(outcome.aspectAllowingAtStand, name = "zB allowing at the stand").isTrue()
		assertThat(outcome.peakAccelerationWhileStanding, name = "acceleration reported while standing").isZero()
		assertThat(outcome.motorPassivatedBeforeRelease, name = "motor passivated at the stand").isTrue()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the train restarts once navigation lets it go")
	fun theTrainRestartsOnceNavigationLetsItGo() {
		val outcome = runScenario()

		assertThat(outcome.releasedAt, name = "time navigation let the train go").isGreaterThan(0.0)
		assertThat(outcome.trainsExited, name = "trains exited").isEqualTo(1)
		assertThat(outcome.finalDistance, name = "distance travelled").isGreaterThan(ROUTE_LENGTH - 5.0)
	}

	// ── Shared ────────────────────────────────────────────────────────────────────

	/**
	 * One run of the scenario: one train B → A over `vyhybna.xml` with its whole route reserved,
	 * sampled throughout. Navigation answers the train's query at `zB` with an ownership conflict
	 * until the sampler lifts it. The sampler's callback is the scenario driver — it runs on the
	 * simulation thread, the only thread allowed to read the train: it records the stand, watches
	 * the motor while the train stands, and after [STAND_HOLD_SECONDS] lets the train go.
	 */
	private fun runScenario(): Outcome {
		val context = loadVyhybnaContext().tracked()
		val inOuts = context.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }
		val zB = context.cellsOfType<DynamicRailSemaphore>().single { it.name == HOLD_SIGNAL }
		val reservationService = context.getRoutingServices().getPathReservationService()
		val realNav = context.getRoutingServices().getTrainNavigationService()

		val holding = AtomicBoolean(true)
		val holdingNav =
			decoratingTrainNavigationService(realNav) { trainId, separator ->
				if (holding.get() && isHoldSignal(separator)) {
					PathResult.OwnershipConflict
				} else {
					realNav.findReservedPathForTrain(trainId, separator)
				}
			}
		val env = NavigationDecoratingContext(context, holdingNav)

		var standTime = -1.0
		var standDistance = -1.0
		var aspectAllowingAtStand = false
		var peakAccelerationWhileStanding = 0.0
		var motorPassivatedBeforeRelease = false
		var releasedAt = -1.0

		val run =
			runSimpleLinearTrackScenario(
				context,
				endTime = END_TIME,
				trainSpecs =
					listOf(
						SimpleLinearTrackTestProcess.TrainSpec(
							inName = "B",
							outName = "A",
							inTime = 1.0,
							outTime = END_TIME.toDouble(),
							length = TRAIN_LENGTH
						)
					),
				env = env
			) { train ->
				assertReservationSuccess(reservationService.reservePath(train.name, b, a))
				Process.activate(
					TrainKinematicSampler(train, END_TIME.toDouble(), SAMPLE_PERIOD) { sample ->
						if (standTime < 0.0 && isStandingPastHalfway(sample)) {
							standTime = sample.time
							standDistance = sample.totalDistance
							aspectAllowingAtStand = zB.signal.isAllowing()
						}
						if (standTime >= 0.0 && releasedAt < 0.0) {
							peakAccelerationWhileStanding = maxOf(peakAccelerationWhileStanding, abs(train.getAcceleration()))
							if (sample.time >= standTime + STAND_HOLD_SECONDS) {
								motorPassivatedBeforeRelease = motorOf(train).isPassivated()
								holding.set(false)
								releasedAt = sample.time
							}
						}
					}
				)
			}
		return Outcome(
			standDistance = standDistance,
			aspectAllowingAtStand = aspectAllowingAtStand,
			peakAccelerationWhileStanding = peakAccelerationWhileStanding,
			motorPassivatedBeforeRelease = motorPassivatedBeforeRelease,
			releasedAt = releasedAt,
			trainsExited = run.process.getTrainsExited(),
			finalDistance = run.train.totalDistance
		)
	}

	/** Whether [separator] — static or dynamic — is the signal navigation holds the train at. */
	private fun isHoldSignal(separator: PathSeparator): Boolean =
		when (separator) {
			is DynamicRailSemaphore -> separator.name == HOLD_SIGNAL
			is RailSemaphore -> separator.getName() == HOLD_SIGNAL
			else -> false
		}

	/** The stand at zB, told apart from the stand at the origin before the train is admitted. */
	private fun isStandingPastHalfway(sample: TrainKinematicSample): Boolean =
		sample.velocity == 0.0 && sample.totalDistance > DISTANCE_TO_ZB / 2

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
}
