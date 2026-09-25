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
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.motorOf
import cz.vutbr.fit.interlockSim.testutil.runHoldAtSeparatorScenario
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
		val context =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		val zB = context.cellsOfType<DynamicRailSemaphore>().single { it.name == HOLD_SIGNAL }

		val holding = AtomicBoolean(true)

		var standDistance = -1.0
		var aspectAllowingAtStand = false
		var peakAccelerationWhileStanding = 0.0
		var motorPassivatedBeforeRelease = false
		var releasedAt = -1.0

		val run =
			runHoldAtSeparatorScenario(
				context,
				holdSignal = HOLD_SIGNAL,
				standThreshold = DISTANCE_TO_ZB / 2,
				trainLength = TRAIN_LENGTH,
				standHoldSeconds = STAND_HOLD_SECONDS,
				holding = holding::get,
				onSample = { observation ->
					if (observation.standing && releasedAt < 0.0) {
						peakAccelerationWhileStanding =
							maxOf(peakAccelerationWhileStanding, abs(observation.train.getAcceleration()))
					}
				},
				onStand = { observation ->
					standDistance = observation.sample.totalDistance
					aspectAllowingAtStand = zB.signal.isAllowing()
				},
				onHoldElapsed = { observation ->
					motorPassivatedBeforeRelease = motorOf(observation.train).isPassivated()
					holding.set(false)
					releasedAt = observation.sample.time
				}
			)
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
}
