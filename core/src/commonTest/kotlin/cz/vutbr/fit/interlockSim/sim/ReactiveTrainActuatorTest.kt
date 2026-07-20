/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for ReactiveTrainActuator (SP2a.3, Issue #554): the reactive train agent's
 * "act" step — applying TrainAccelerationDecision to a TrainActuatorPort.
 */
package cz.vutbr.fit.interlockSim.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [ReactiveTrainActuator].
 *
 * Pure common-platform tests (KMP `commonTest`) — no MockK/JUnit 5 — so they run on
 * both JVM and native targets.  Each test verifies that the act step correctly translates
 * a [TrainAccelerationDecision] into [TrainActuatorPort] calls, using a simple
 * recording stub instead of a heavyweight mock.
 *
 * @since Issue #554 (SP2a.3 — Goal 10 reactive train agent)
 */
class ReactiveTrainActuatorTest {
	// ── Recording stub ────────────────────────────────────────────────────────

	/**
	 * Minimal [cz.vutbr.fit.interlockSim.ports.TrainActuatorPort] stub that records
	 * every [setTargetSpeed] and [holdAtStation] call.
	 */
	private class RecordingActuator : cz.vutbr.fit.interlockSim.ports.TrainActuatorPort {
		val speedHistory = mutableListOf<Double>()
		val dwellHistory = mutableListOf<Double>()

		override fun setTargetSpeed(speed: Double) {
			speedHistory.add(speed)
		}

		override fun holdAtStation(dwellDurationSeconds: Double) {
			dwellHistory.add(dwellDurationSeconds)
		}
	}

	// ── applyDecision: ACCELERATE ─────────────────────────────────────────────

	@Test
	fun `applyDecision ACCELERATE forwards targetSpeedMps to setTargetSpeed`() {
		val actuator = RecordingActuator()
		val decision = TrainAccelerationDecision(AccelerationTarget.ACCELERATE, 20.0, "Volno ahead")

		ReactiveTrainActuator.applyDecision(decision, actuator)

		assertEquals(listOf(20.0), actuator.speedHistory)
		assertEquals(emptyList<Double>(), actuator.dwellHistory)
	}

	// ── applyDecision: COAST ──────────────────────────────────────────────────

	@Test
	fun `applyDecision COAST forwards targetSpeedMps to setTargetSpeed`() {
		val actuator = RecordingActuator()
		val decision = TrainAccelerationDecision(AccelerationTarget.COAST, 30.0, "At permitted speed")

		ReactiveTrainActuator.applyDecision(decision, actuator)

		assertEquals(listOf(30.0), actuator.speedHistory)
	}

	@Test
	fun `applyDecision COAST with zero speed calls setTargetSpeed with 0`() {
		val actuator = RecordingActuator()
		val decision = TrainAccelerationDecision(AccelerationTarget.COAST, 0.0, "Holding stop at STOP signal")

		ReactiveTrainActuator.applyDecision(decision, actuator)

		assertEquals(listOf(0.0), actuator.speedHistory)
	}

	// ── applyDecision: BRAKE ──────────────────────────────────────────────────

	@Test
	fun `applyDecision BRAKE with zero targetSpeed calls setTargetSpeed with 0`() {
		val actuator = RecordingActuator()
		val decision = TrainAccelerationDecision(AccelerationTarget.BRAKE, 0.0, "STOP signal ahead")

		ReactiveTrainActuator.applyDecision(decision, actuator)

		assertEquals(listOf(0.0), actuator.speedHistory)
	}

	@Test
	fun `applyDecision BRAKE with partial targetSpeed caps to that speed`() {
		// Výstraha case: braking toward a speed limit below current velocity
		val actuator = RecordingActuator()
		val decision = TrainAccelerationDecision(AccelerationTarget.BRAKE, 6.0, "Výstraha; brake to 6 m/s")

		ReactiveTrainActuator.applyDecision(decision, actuator)

		assertEquals(listOf(6.0), actuator.speedHistory)
	}

	// ── applyDecision: exactly one setTargetSpeed call per invocation ─────────

	@Test
	fun `applyDecision always produces exactly one setTargetSpeed call`() {
		val actuator = RecordingActuator()
		ReactiveTrainActuator.applyDecision(
			TrainAccelerationDecision(AccelerationTarget.ACCELERATE, 15.0, "run"),
			actuator
		)
		assertEquals(1, actuator.speedHistory.size)
		assertEquals(0, actuator.dwellHistory.size)
	}

	// ── holdAtStation ─────────────────────────────────────────────────────────

	@Test
	fun `holdAtStation delegates dwellDurationSeconds to actuator`() {
		val actuator = RecordingActuator()

		ReactiveTrainActuator.holdAtStation(45.0, actuator)

		assertEquals(listOf(45.0), actuator.dwellHistory)
		assertEquals(emptyList<Double>(), actuator.speedHistory)
	}

	@Test
	fun `holdAtStation with minimum positive duration delegates correctly`() {
		val actuator = RecordingActuator()

		ReactiveTrainActuator.holdAtStation(0.001, actuator)

		assertEquals(listOf(0.001), actuator.dwellHistory)
	}

	@Test
	fun `holdAtStation forwards exactly one dwell command per call`() {
		// This object is a pure pass-through: it performs no validation of its own, so the
		// recording stub accepts any duration.  The ≤ 0 rejection is a real-actuator
		// concern and is covered by DefaultTrainActuatorPortTest (port precondition) and
		// TrainHoldAtStationIntegrationTest (kernel precondition).  What matters here is
		// that exactly one command reaches the port, unchanged.
		val actuator = RecordingActuator()

		ReactiveTrainActuator.holdAtStation(1.0, actuator)

		assertEquals(listOf(1.0), actuator.dwellHistory)
	}

	// ── multiple cycles ───────────────────────────────────────────────────────

	@Test
	fun `multiple applyDecision calls accumulate in recording`() {
		val actuator = RecordingActuator()

		ReactiveTrainActuator.applyDecision(
			TrainAccelerationDecision(AccelerationTarget.ACCELERATE, 20.0, "run"),
			actuator
		)
		ReactiveTrainActuator.applyDecision(
			TrainAccelerationDecision(AccelerationTarget.BRAKE, 0.0, "stop"),
			actuator
		)

		assertEquals(listOf(20.0, 0.0), actuator.speedHistory)
	}

	@Test
	fun `holdAtStation followed by applyDecision produces correct call sequence`() {
		val actuator = RecordingActuator()

		ReactiveTrainActuator.holdAtStation(30.0, actuator)
		ReactiveTrainActuator.applyDecision(
			TrainAccelerationDecision(AccelerationTarget.ACCELERATE, 25.0, "resume"),
			actuator
		)

		assertEquals(listOf(30.0), actuator.dwellHistory)
		assertEquals(listOf(25.0), actuator.speedHistory)
	}

	// ── precondition: TrainAccelerationDecision.targetSpeedMps >= 0 ──────────

	@Test
	fun `TrainAccelerationDecision rejects negative targetSpeedMps`() {
		assertFailsWith<IllegalArgumentException> {
			TrainAccelerationDecision(AccelerationTarget.BRAKE, -1.0, "invalid")
		}
	}
}
