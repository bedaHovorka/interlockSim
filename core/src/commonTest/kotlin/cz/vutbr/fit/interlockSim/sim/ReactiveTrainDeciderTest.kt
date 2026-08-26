/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for ReactiveTrainDecider (SP2a.2, Issue #553): the reactive train agent's
 * "decide correct acceleration target" step.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.testutil.trainPerceptionReading
import kotlin.test.Test

/**
 * Unit tests for [ReactiveTrainDecider.decide].
 *
 * Pure common-platform tests (KMP `commonTest`) — no MockK/JUnit 5 — so they run on
 * both JVM and native targets. Each test builds a [TrainPerceptionReading] via [reading]
 * and asserts the decided [TrainAccelerationDecision].
 *
 * @since Issue #553 (SP2a.2 — Goal 10 reactive train agent)
 */
class ReactiveTrainDeciderTest {
	// ── No movement authority ──────────────────────────────────────────────

	@Test
	fun `no reserved path while moving brakes to a stand`() {
		val decision = ReactiveTrainDecider.decide(trainPerceptionReading(signalAhead = null, velocity = 12.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `no reserved path while stopped coasts holding the stop`() {
		val decision = ReactiveTrainDecider.decide(trainPerceptionReading(signalAhead = null, velocity = 0.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.COAST)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `STOP signal ahead while moving brakes to a stand`() {
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(signalAhead = Signal.STOP, velocity = 15.0, distanceToSignal = 200.0)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `STOP signal ahead while stopped coasts`() {
		val decision =
			ReactiveTrainDecider.decide(trainPerceptionReading(signalAhead = Signal.STOP, velocity = 0.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.COAST)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	// ── Volno ahead (both aspects allowing) ────────────────────────────────

	@Test
	fun `Volno ahead below permitted speed accelerates to track limit`() {
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.FREE,
					speedLimit = 30.0,
					velocity = 10.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
		// FREE allows ABSOLUTE_MAX_SPEED, so the track limit governs.
		assertThat(decision.targetSpeedMps).isEqualTo(30.0)
	}

	@Test
	fun `Volno ahead at permitted speed coasts`() {
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.FREE,
					speedLimit = 30.0,
					velocity = 30.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.COAST)
		assertThat(decision.targetSpeedMps).isEqualTo(30.0)
	}

	@Test
	fun `second signal speed restriction caps the target below track limit`() {
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.S30,
					speedLimit = 30.0,
					velocity = 20.0
				)
			)
		// Next signal S30 (8.33 m/s) < track limit → brake toward it.
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isCloseTo(Signal.S30.allowedSpeed(), delta = 1e-9)
	}

	// ── Výstraha (immediate allowing, next STOP) ───────────────────────────

	@Test
	fun `Vystraha near the immediate signal brakes toward stop-at-second speed`() {
		// distance 6 m, braking 3 m/s²: v = sqrt(2*3*6) = 6.0 m/s.
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.STOP,
					distanceToSignal = 6.0,
					speedLimit = 30.0,
					velocity = 20.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isCloseTo(6.0, delta = 1e-9)
	}

	@Test
	fun `Vystraha far from the immediate signal still permits track-limit running`() {
		// distance 1000 m: braking limit sqrt(6000) ~= 77.5 m/s > track limit 30 → base governs.
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.STOP,
					distanceToSignal = 1000.0,
					speedLimit = 30.0,
					velocity = 10.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
		assertThat(decision.targetSpeedMps).isEqualTo(30.0)
	}

	@Test
	fun `Vystraha with zero distance to immediate signal brakes to a stand`() {
		// brakingSpeedLimit(0.0) hits the `distanceMetres <= 0.0` guard → 0.0; min(base, 0.0) = 0.0.
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.STOP,
					distanceToSignal = 0.0,
					speedLimit = 30.0,
					velocity = 20.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `Vystraha with negative distance to immediate signal brakes to a stand`() {
		// Defensive: perception spec says distance >= 0, but the guard must still hold.
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.STOP,
					distanceToSignal = -5.0,
					speedLimit = 30.0,
					velocity = 20.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `speed-restricted Vystraha with immediate S30 and next STOP caps to the immediate aspect`() {
		// "očekávej rychlost X + Výstraha": base = min(30, S30=8.33…) = 8.33… governs
		// (brakeToStop sqrt(2·3·1000)=77.5 > base), so the immediate S30 aspect caps the target.
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.S30,
					nextSignalAhead = Signal.STOP,
					distanceToSignal = 1000.0,
					speedLimit = 30.0,
					velocity = 20.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isCloseTo(Signal.S30.allowedSpeed(), delta = 1e-9)
	}

	@Test
	fun `Vystraha below the braking cap accelerates back up to it`() {
		// distance 6 m: brakeToStop sqrt(2*3*6) = 6.0 governs (min(30, 6.0) = 6.0);
		// velocity 3.0 < 6.0 - ε → ACCELERATE back up, not stuck in BRAKE.
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.FREE,
					nextSignalAhead = Signal.STOP,
					distanceToSignal = 6.0,
					speedLimit = 30.0,
					velocity = 3.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
		assertThat(decision.targetSpeedMps).isCloseTo(6.0, delta = 1e-9)
	}

	// ── Near destination (no second signal) ────────────────────────────────

	@Test
	fun `no second signal runs to the permitted speed`() {
		val decision =
			ReactiveTrainDecider.decide(
				trainPerceptionReading(
					signalAhead = Signal.S60,
					nextSignalAhead = null,
					speedLimit = 30.0,
					velocity = 5.0
				)
			)
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
		// S60 = 16.67 m/s < track limit 30 → immediate aspect governs.
		assertThat(decision.targetSpeedMps).isCloseTo(Signal.S60.allowedSpeed(), delta = 1e-9)
	}

	// ── Invariants ─────────────────────────────────────────────────────────

	@Test
	fun `decision always yields a non-negative target speed and a rationale`() {
		val aspects = listOf(null, Signal.STOP, Signal.S30, Signal.S60, Signal.FREE)
		for (immediate in aspects) {
			for (next in aspects) {
				val decision =
					ReactiveTrainDecider.decide(
						trainPerceptionReading(signalAhead = immediate, nextSignalAhead = next, velocity = 13.0)
					)
				assertThat(decision.targetSpeedMps).isGreaterThanOrEqualTo(0.0)
				assertThat(decision.rationale).isNotEmpty()
			}
		}
	}
}
