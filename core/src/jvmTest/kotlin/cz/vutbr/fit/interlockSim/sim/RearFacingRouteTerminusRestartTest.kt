/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * A route that ends at a rear-facing signal: the stand at the last facing signal and the
 * restart once the route is extended.
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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSample
import cz.vutbr.fit.interlockSim.testutil.TrainKinematicSampler
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.motorOf
import cz.vutbr.fit.interlockSim.testutil.runSimpleLinearTrackScenario
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The stop a dispatcher produces by granting a route that ends at a **rear-facing** signal, and
 * what the train does at that stop.
 *
 * ## The scenario
 *
 * On `vyhybna.xml` a train running B → A meets `zB` and `doA1` facing it; `doB1` and `zA` face
 * the other way. The route granted here is `B → doB1`: three blocks, the last of which ends at a
 * signal the train can never be held at. Navigation reports the train's leg from `zB` as an
 * ownership conflict — "wait for the route to be extended" (PR #940) — so `Site.actions()` stops
 * the train at the separator and waits. From the outside the train stands at a green `zB` and
 * does nothing, which is what a `shuntingLoopAI` run showed on 2026-09-13 (Train #3, run.log):
 * the LLM had granted `B → doB1` and neither it nor the rule-based fallback extended the route.
 *
 * Nothing in that stop is the motor's doing. The two rungs pin the stop and the recovery:
 *
 * - [theTrainStandsAtTheGreenSignalWhereItsRouteStopsServingIt] — the stand itself: at the
 *   separator, not a clearance short of it, with the aspect still allowing, and the motor idle
 *   (zero acceleration, passivated). Documents the pre-existing behaviour, so the next reader
 *   does not chase it into the braking code.
 * - [theTrainRestartsOnceTheRouteIsExtended] — extending the route to `doA1` and on to `A`
 *   wakes the wait, the separator restarts the train, and it completes the journey.
 *
 * The motor's idleness at this stand is a rounding coin flip on the unfixed code: the stop's
 * cancel wakes the motor twice only when the last integration step ends on the near side of the
 * signal. `MotorBareCancelTest` pins that defect deterministically; the assertion here is the
 * invariant, kept because this is the stop the measured run showed.
 */
@Tag("integration-test")
@DisplayName("A route ending at a rear-facing signal: the stand at zB and the restart")
class RearFacingRouteTerminusRestartTest : KoinTestBase() {
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
		 * How long the train is left standing before the route is extended. Well inside the
		 * ownership-conflict WARN horizon, and long enough for every wake-up of the stop to be
		 * delivered and settle before the motor is inspected.
		 */
		const val STAND_HOLD_SECONDS = 3.0

		/**
		 * Position tolerance for the stand: the `maxAbsError = 1e-2` the generator is configured
		 * with. The measured stand is `dtMin` short of the separator.
		 */
		const val POSITION_TOLERANCE = 1e-2
	}

	/** Everything one run of the scenario produces for assertion. */
	private class Outcome(
		val standDistance: Double,
		val aspectAllowingAtStand: Boolean,
		val peakAccelerationWhileStanding: Double,
		val motorPassivatedBeforeExtension: Boolean,
		val extendedAt: Double,
		val trainsExited: Int,
		val finalDistance: Double
	)

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the train stands at the green signal where its reserved route stops serving it")
	fun theTrainStandsAtTheGreenSignalWhereItsRouteStopsServingIt() {
		val outcome = runScenario()

		assertThat(outcome.standDistance, name = "distance travelled at the stand")
			.isBetween(DISTANCE_TO_ZB - POSITION_TOLERANCE, DISTANCE_TO_ZB + POSITION_TOLERANCE)
		assertThat(outcome.aspectAllowingAtStand, name = "zB allowing at the stand").isTrue()
		assertThat(outcome.peakAccelerationWhileStanding, name = "acceleration reported while standing").isZero()
		assertThat(outcome.motorPassivatedBeforeExtension, name = "motor passivated at the stand").isTrue()
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("the train restarts once the route is extended past the rear-facing signal")
	fun theTrainRestartsOnceTheRouteIsExtended() {
		val outcome = runScenario()

		assertThat(outcome.extendedAt, name = "time the route was extended").isGreaterThan(0.0)
		assertThat(outcome.trainsExited, name = "trains exited").isEqualTo(1)
		assertThat(outcome.finalDistance, name = "distance travelled").isGreaterThan(ROUTE_LENGTH - 5.0)
	}

	// ── Shared ────────────────────────────────────────────────────────────────────

	/**
	 * One run of the scenario: one train B → A over `vyhybna.xml`, its route reserved only as
	 * far as `doB1`, sampled throughout. The sampler's callback is the scenario driver — it runs
	 * on the simulation thread, the only thread allowed to read the train and reserve routes:
	 * it records the stand, watches the motor while the train stands, and after
	 * [STAND_HOLD_SECONDS] extends the route to `doA1` and on to `A`.
	 */
	private fun runScenario(): Outcome {
		val context = loadVyhybnaContext().tracked()
		val inOuts = context.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }
		val zB = context.cellsOfType<DynamicRailSemaphore>().single { it.name == "zB" }
		val doB1 = context.cellsOfType<DynamicRailSemaphore>().single { it.name == "doB1" }
		val doA1 = context.cellsOfType<DynamicRailSemaphore>().single { it.name == "doA1" }
		val reservationService = context.getRoutingServices().getPathReservationService()

		var standTime = -1.0
		var standDistance = -1.0
		var aspectAllowingAtStand = false
		var peakAccelerationWhileStanding = 0.0
		var motorPassivatedBeforeExtension = false
		var extendedAt = -1.0

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
					)
			) { train ->
				assertReservationSuccess(reservationService.reservePath(train.name, b, doB1))
				Process.activate(
					TrainKinematicSampler(train, END_TIME.toDouble(), SAMPLE_PERIOD) { sample ->
						if (standTime < 0.0 && isStandingPastHalfway(sample)) {
							standTime = sample.time
							standDistance = sample.totalDistance
							aspectAllowingAtStand = zB.signal.isAllowing()
						}
						if (standTime >= 0.0 && extendedAt < 0.0) {
							peakAccelerationWhileStanding = maxOf(peakAccelerationWhileStanding, abs(train.getAcceleration()))
							if (sample.time >= standTime + STAND_HOLD_SECONDS) {
								motorPassivatedBeforeExtension = motorOf(train).isPassivated()
								assertReservationSuccess(reservationService.reservePath(train.name, zB, doA1))
								assertReservationSuccess(reservationService.reservePath(train.name, doA1, a))
								extendedAt = sample.time
							}
						}
					}
				)
			}
		return Outcome(
			standDistance = standDistance,
			aspectAllowingAtStand = aspectAllowingAtStand,
			peakAccelerationWhileStanding = peakAccelerationWhileStanding,
			motorPassivatedBeforeExtension = motorPassivatedBeforeExtension,
			extendedAt = extendedAt,
			trainsExited = run.process.getTrainsExited(),
			finalDistance = run.train.totalDistance
		)
	}

	/** The stand at zB, told apart from the stand at the origin before the train is admitted. */
	private fun isStandingPastHalfway(sample: TrainKinematicSample): Boolean =
		sample.velocity == 0.0 && sample.totalDistance > DISTANCE_TO_ZB / 2

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)
}
