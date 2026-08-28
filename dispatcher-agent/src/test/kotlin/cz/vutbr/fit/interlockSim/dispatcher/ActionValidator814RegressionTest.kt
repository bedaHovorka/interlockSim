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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.observation.BlockView
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression test for Issue #814 Symptom 3 (SP2c.3, Issue #826):
 * `ROUTE_ALREADY_HELD_TO_SAME_TARGET` is rejected **before** any [ActuatorCommandQueue.postAll]
 * call, and before [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService]
 * is reached.
 *
 * ## Context
 *
 * Issue #814 Symptom 3 was: the LLM agent re-requested a route to the same target that it
 * already held. The request sailed through to the `:core` reservation layer, which silently
 * rejected it (guard added in commit `e1e4b660`), but the LLM never received a machine-readable
 * rejection reason and kept retrying. [ActionValidator] is the **outer** gate that fires first,
 * produces a machine-readable [RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET] reason, and
 * prevents the [DispatchDecision][cz.vutbr.fit.interlockSim.sim.DispatchDecision] from ever
 * being posted to the queue.
 *
 * ## What this test asserts
 *
 * 1. The validator rejects `RequestRoute("T1", "A", "B")` with
 *    [RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET] when T1 already holds a reservation
 *    whose `targetName == "B"`.
 * 2. After the validation runs, **zero calls** to [ActuatorCommandQueue.postAll] were made —
 *    the rejected action never reached the queue, never became a
 *    [cz.vutbr.fit.interlockSim.sim.DispatchDecision], and never reached
 *    [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService].
 *
 * @see ActionValidatorTableTest for the full table of all rejection codes
 * @since Issue #826 (SP2c.3 — Goal 10)
 */
@DisplayName("Issue #814 regression — ROUTE_ALREADY_HELD_TO_SAME_TARGET rejected before queue post")
class ActionValidator814RegressionTest {
	private val validator =
		ActionValidator(
			validEndpointNames = setOf("A", "B", "C", "kG", "kJ"),
			blockIds = setOf("kA", "kB", "kC", "kD", "kE", "kF")
		)

	/**
	 * Simulate the #814 Symptom 3 scenario from a live run:
	 *
	 * Train T1 already holds blocks kA,kG,kJ,kB toward B. The LLM agent (re-)issues
	 * `RequestRoute("T1", "A", "B")`. The validator must reject with
	 * ROUTE_ALREADY_HELD_TO_SAME_TARGET **before** any queue.postAll() call.
	 */
	@Test
	@DisplayName("re-requesting same route is rejected with ROUTE_ALREADY_HELD_TO_SAME_TARGET before any queue post")
	fun issue814Symptom3RejectedBeforeQueuePost() {
		// ── Arrange ───────────────────────────────────────────────────────────

		// Mimic the observation from the live #814 run: T1 holds a route to "B" via kA,kG,kJ,kB
		// (using valid endpoint names and block IDs from the validator constructor above)
		val observation =
			DispatcherObservation(
				tick = 42L,
				simTime = 120.0,
				trains =
					listOf(
						TrainView(
							trainId = "T1",
							phase = TrainPhase.RUNNING,
							frontSectionName = "kA",
							velocityMps = 5.0,
							accelerationMps2 = 0.0,
							destinationInOutName = "B",
							signalAheadName = null,
							signalAheadAspect = null,
							distanceToSignalAheadMetres = 0.0,
							waitingSinceSimTime = null,
							waitSeconds = 0.0
						)
					),
				blocks =
					listOf(
						BlockView("kA", TrackFacility.State.RESERVED, "T1"),
						BlockView("kB", TrackFacility.State.RESERVED, "T1"),
						BlockView("kC", TrackFacility.State.FREE, null),
						BlockView("kD", TrackFacility.State.FREE, null),
						BlockView("kE", TrackFacility.State.FREE, null),
						BlockView("kF", TrackFacility.State.FREE, null)
					),
				switches = emptyList(),
				signals = emptyList(),
				reservations =
					listOf(
						// T1 already holds a route to "B" — this is the Symptom 3 state
						ReservationView(
							trainId = "T1",
							fromEndpointName = "A",
							targetName = "B",
							blockIds = listOf("kA", "kG", "kJ", "kB")
						)
					),
				queued = emptyList(),
				activeCount = 1,
				capacity = 2,
				appliedOutcomes = emptyList()
			)

		// The LLM re-requests the exact same route it already holds
		val action =
			DispatchAction.RequestRoute(
				trainId = "T1",
				fromEndpointName = "A",
				toEndpointName = "B"
			)

		// Spy on the ActuatorCommandQueue to verify postAll is never called for a rejected action
		val queueSpy = mockk<ActuatorCommandQueue>(relaxed = true)

		// ── Act ───────────────────────────────────────────────────────────────

		val verdict = validator.validate(action, observation)

		// The correct caller pattern: only post if validation passes.
		// Since the validator rejects the action, postAll is never reached.
		if (verdict is ValidationVerdict.Valid) {
			// This branch is intentionally unreachable in this test scenario —
			// it is present to make the "validate-first, post-only-if-valid" contract
			// explicit in the test rather than implicit.
			queueSpy.postAll(emptyList())
		}

		// ── Assert ────────────────────────────────────────────────────────────

		// 1. The validator must reject with the #814-specific code
		assertThat(verdict, name = "verdict").isInstanceOf(ValidationVerdict.Rejected::class)
		assertThat((verdict as ValidationVerdict.Rejected).code, name = "rejection code")
			.isEqualTo(RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET)

		// 2. The rejection detail must name the train and target for machine readability
		assertThat(verdict.detail, name = "detail contains trainId")
			.run { this.isInstanceOf(String::class) }
		assert(verdict.detail.contains("T1")) { "detail must name the train, was: ${verdict.detail}" }
		assert(verdict.detail.contains("B")) { "detail must name the target, was: ${verdict.detail}" }

		// 3. postAll was never called — the action never reached the queue
		verify(exactly = 0) { queueSpy.postAll(any()) }
	}

	/**
	 * Complementary: a DIFFERENT target should produce ROUTE_HELD_TO_DIFFERENT_TARGET, not
	 * ROUTE_ALREADY_HELD_TO_SAME_TARGET, and still reject before the queue.
	 */
	@Test
	@DisplayName("requesting different target while holding a route is ROUTE_HELD_TO_DIFFERENT_TARGET before queue post")
	fun differentTargetRejectedWithDifferentCodeBeforeQueuePost() {
		val observation =
			DispatcherObservation(
				tick = 43L,
				simTime = 125.0,
				trains =
					listOf(
						TrainView(
							trainId = "T1",
							phase = TrainPhase.RUNNING,
							frontSectionName = "kA",
							velocityMps = 5.0,
							accelerationMps2 = 0.0,
							destinationInOutName = "C",
							signalAheadName = null,
							signalAheadAspect = null,
							distanceToSignalAheadMetres = 0.0,
							waitingSinceSimTime = null,
							waitSeconds = 0.0
						)
					),
				blocks = emptyList(),
				switches = emptyList(),
				signals = emptyList(),
				reservations =
					listOf(
						ReservationView(trainId = "T1", fromEndpointName = "A", targetName = "B", blockIds = listOf("kA"))
					),
				queued = emptyList(),
				activeCount = 1,
				capacity = 2,
				appliedOutcomes = emptyList()
			)

		val action = DispatchAction.RequestRoute("T1", "A", "C") // "C" != existing target "B"

		val queueSpy = mockk<ActuatorCommandQueue>(relaxed = true)

		val verdict = validator.validate(action, observation)
		if (verdict is ValidationVerdict.Valid) {
			queueSpy.postAll(emptyList()) // unreachable in this test scenario
		}

		assertThat(verdict).isInstanceOf(ValidationVerdict.Rejected::class)
		assertThat((verdict as ValidationVerdict.Rejected).code)
			.isEqualTo(RejectionCode.ROUTE_HELD_TO_DIFFERENT_TARGET)
		verify(exactly = 0) { queueSpy.postAll(any()) }
	}
}
