/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * A motor cancelled with no follow-up command must go idle, not re-run its last command.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.motorOf
import cz.vutbr.fit.interlockSim.testutil.runClearanceStopScenario
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * A `Motor.cancelAccelerating()` with no command after it must leave the motor idle.
 *
 * ## The defect this pins
 *
 * On an engine whose `activate` and wait-notice wake-ups are separate channels (kdisco#74), a
 * cancel aimed at a motor parked in its wait wakes it twice: once through the activate it
 * issues, once through the wait notice its `accelerate = false` satisfies. The first turn
 * finishes the iteration and passivates; the second resumed the passivated motor and ran
 * `iteration()` again with the **stale** condition — the motor started integrating towards
 * its old target with `accelerate == true`, on a train whose velocity integration was stopped,
 * so it stood there reporting a non-zero acceleration until the next real command.
 *
 * Measured on `shuntingLoopAI` on 2026-09-13: a train stopped at `zB` for an ownership conflict
 * reported `2.469 m/s²` (`v² / 2s` for the old 22.22 m/s target) for the rest of the run. That
 * stop's cancel lands on a still-waiting motor only when the last integration step ends on the
 * near side of the signal — a rounding coin flip, since the train reaches its target exactly
 * at the signal — so this test forces the shape deterministically instead: the train is halted
 * mid-leg through the public [Train.requestHalt] and the bare cancel then comes from
 * [Train.holdAtStation], which issues no command of its own by design.
 *
 * The fixture is the linear ladder with its intermediate signal left allowing, so the motor is
 * in a plain `accelerateTo` wait far from its target when the halt lands.
 */
@Tag("integration-test")
@DisplayName("A bare motor cancel leaves the motor idle")
class MotorBareCancelTest : KoinTestBase() {
	private companion object {
		/** Approach length; long enough for the halt to land well before the target speed. */
		const val APPROACH = 100.0

		/** Simulation end time; the halt lands within the first few seconds. */
		const val END_TIME = 20L

		/** Sampling period of the kinematic sampler that also drives the scenario. */
		const val SAMPLE_PERIOD = 0.05

		/** Speed at which the train is halted: moving, and far below the commanded 27.78 m/s. */
		const val HALT_SPEED_MPS = 5.0

		/** Station dwell requested at the halt; the motor is inspected during it. */
		const val DWELL_SECONDS = 4.0

		/** Settling time after the halt before the motor's state is read. */
		const val SETTLE_SECONDS = 1.0
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a halt followed by a station dwell leaves the motor passivated with zero acceleration")
	fun haltThenDwellLeavesTheMotorIdle() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork(approachLength = APPROACH)
		val ctx = network.context.tracked()
		var haltedAt = -1.0
		var peakAccelerationWhileDwelling = 0.0
		var motorPassivatedAfterSettling = false
		var settled = false

		runClearanceStopScenario(
			ctx,
			semaphores = listOf(network.semaphore),
			endTime = END_TIME,
			initialAspect = Signal.FREE,
			samplePeriod = SAMPLE_PERIOD,
			onSample = { train, sample ->
				if (haltedAt < 0.0 && sample.velocity > HALT_SPEED_MPS) {
					haltedAt = sample.time
					train.requestHalt()
					train.holdAtStation(DWELL_SECONDS)
				} else if (haltedAt >= 0.0 && sample.time < haltedAt + DWELL_SECONDS) {
					peakAccelerationWhileDwelling = maxOf(peakAccelerationWhileDwelling, abs(train.getAcceleration()))
					if (!settled && sample.time >= haltedAt + SETTLE_SECONDS) {
						settled = true
						motorPassivatedAfterSettling = motorOf(train).isPassivated()
					}
				}
			}
		)

		assertThat(haltedAt, name = "time of the halt").isGreaterThan(0.0)
		assertThat(settled, name = "the motor state was read after settling").isTrue()
		assertThat(peakAccelerationWhileDwelling, name = "acceleration reported while dwelling").isZero()
		assertThat(motorPassivatedAfterSettling, name = "motor passivated after the halt").isTrue()
	}
}
