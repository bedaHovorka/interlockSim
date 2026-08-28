/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for AlgorithmicTrainDecisionPolicy (SP2a.4, Issue #555): the default
 * pluggable train-agent decision policy wrapping ReactiveTrainDecider.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.TrainPerceptionReading
import cz.vutbr.fit.interlockSim.testutil.trainPerceptionReading
import kotlin.test.Test

/**
 * Unit tests for [AlgorithmicTrainDecisionPolicy].
 *
 * Pure common-platform tests (KMP `commonTest`) — no MockK / JUnit 5 — so they run on
 * both JVM and native targets.  Each test builds a [TrainPerceptionReading] via [reading]
 * and asserts the decided [TrainAccelerationDecision].
 *
 * @since Issue #555 (SP2a.4 — Goal 10 reactive train agent)
 */
class AlgorithmicTrainDecisionPolicyTest {
	// ── No directive — delegates to ReactiveTrainDecider ────────────────

	@Test
	fun `without directives FREE signal below speed limit accelerates`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 10.0, speedLimit = 30.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
		assertThat(decision.targetSpeedMps).isEqualTo(30.0)
	}

	@Test
	fun `without directives STOP signal brakes to a stand`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		val decision = policy.decide(reading(signalAhead = Signal.STOP, velocity = 15.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `without directives no reserved path while stopped coasts`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		val decision = policy.decide(reading(signalAhead = null, velocity = 0.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.COAST)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	// ── HoldImmediately ──────────────────────────────────────────────────

	@Test
	fun `HoldImmediately while moving brakes to a stand`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldImmediately)
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 20.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `HoldImmediately while stopped coasts at zero`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldImmediately)
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 0.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.COAST)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `HoldImmediately overrides FREE signal that would normally allow proceeding`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldImmediately)
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 5.0, speedLimit = 30.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `HoldImmediately is idempotent across multiple calls`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldImmediately)
		policy.acceptDirective(TrainDirective.HoldImmediately)
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 10.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
	}

	// ── HoldAt ──────────────────────────────────────────────────────────

	@Test
	fun `HoldAt activates hold override regardless of signal`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldAt("L1"))
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 10.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `HoldAt while stopped coasts at zero`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldAt("zA"))
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 0.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.COAST)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	// ── RouteGranted clears hold ──────────────────────────────────────

	@Test
	fun `RouteGranted after HoldImmediately clears hold and restores perception-based decisions`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldImmediately)
		policy.acceptDirective(TrainDirective.RouteGranted(Aspect.Volno, 60))
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 5.0, speedLimit = 30.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
		assertThat(decision.targetSpeedMps).isEqualTo(30.0)
	}

	@Test
	fun `RouteGranted after HoldAt clears hold and restores perception-based decisions`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldAt("L3"))
		policy.acceptDirective(TrainDirective.RouteGranted(Aspect.Rychlost(60), 60))
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 5.0, speedLimit = 30.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
	}

	@Test
	fun `RouteGranted without prior hold is a no-op on decision behaviour`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.RouteGranted(Aspect.Volno, 60))
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 5.0, speedLimit = 30.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
	}

	// ── RouteDenied does not activate hold ─────────────────────────────

	@Test
	fun `RouteDenied does not activate hold — null signal path handled by ReactiveTrainDecider`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.RouteDenied("section U3 occupied"))
		// No reserved path → ReactiveTrainDecider handles: null signal → COAST at 0
		val decision = policy.decide(reading(signalAhead = null, velocity = 0.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.COAST)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	@Test
	fun `RouteDenied does not activate hold — FREE signal still allows proceeding`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.RouteDenied("conflict"))
		// FREE signal + no hold → ReactiveTrainDecider accelerates
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 5.0, speedLimit = 30.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
	}

	// ── Directive sequence ────────────────────────────────────────────

	@Test
	fun `Hold then RouteGranted then new Hold reactivates hold`() {
		val policy = AlgorithmicTrainDecisionPolicy()
		policy.acceptDirective(TrainDirective.HoldImmediately)
		policy.acceptDirective(TrainDirective.RouteGranted(Aspect.Volno, 60))
		policy.acceptDirective(TrainDirective.HoldAt("L2"))
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 12.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.BRAKE)
		assertThat(decision.targetSpeedMps).isEqualTo(0.0)
	}

	// ── Invariants ─────────────────────────────────────────────────────

	@Test
	fun `decision always yields non-negative target speed and non-empty rationale`() {
		val aspects = listOf(null, Signal.STOP, Signal.S30, Signal.S60, Signal.FREE)
		val directives =
			listOf(
				null,
				TrainDirective.HoldImmediately,
				TrainDirective.RouteGranted(Aspect.Volno, 60),
				TrainDirective.RouteDenied("test")
			)
		for (directiveToApply in directives) {
			val policy = AlgorithmicTrainDecisionPolicy()
			if (directiveToApply != null) policy.acceptDirective(directiveToApply)
			for (signal in aspects) {
				val decision = policy.decide(reading(signalAhead = signal, velocity = 10.0))
				assertThat(decision.targetSpeedMps).isGreaterThanOrEqualTo(0.0)
				assertThat(decision.rationale).isNotEmpty()
			}
		}
	}

	// ── TrainDecisionPolicy interface contract ─────────────────────────

	@Test
	fun `implements TrainDecisionPolicy interface`() {
		val policy: TrainDecisionPolicy = AlgorithmicTrainDecisionPolicy()
		val decision = policy.decide(reading(signalAhead = Signal.FREE, velocity = 0.0, speedLimit = 30.0))
		assertThat(decision.target).isEqualTo(AccelerationTarget.ACCELERATE)
	}

	private companion object {
		/**
		 * Build a [TrainPerceptionReading] with sensible defaults, overriding only the
		 * fields relevant to the decision under test.
		 */
		fun reading(
			signalAhead: Signal?,
			velocity: Double = 0.0,
			speedLimit: Double = 30.0
		): TrainPerceptionReading =
			trainPerceptionReading(signalAhead = signalAhead, velocity = velocity, speedLimit = speedLimit)
	}
}
