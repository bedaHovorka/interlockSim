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
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Integration test for the [ActionValidator] → [ActuatorCommandQueue] gating contract
 * (SP2c.3, Issue #826).
 *
 * Asserts that a caller following the correct validation pattern — validate first, post only if
 * [ValidationVerdict.Valid] — never calls [ActuatorCommandQueue.postAll] for rejected actions.
 * A spy wraps the queue so [io.mockk.verify] can confirm zero posts.
 *
 * This test does **not** run [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] or
 * any live simulation state — it exercises the pre-execution validation layer in isolation.
 *
 * @since Issue #826 (SP2c.3 — Goal 10)
 */
@Tag("integration-test")
@DisplayName("ActionValidator integration — rejected actions must not reach ActuatorCommandQueue (SP2c.3 #826)")
class ActionValidatorQueueGatingIntegrationTest {
	private val validator = ActionValidator(
		validEndpointNames = setOf("A", "B", "C"),
		blockIds = setOf("kA", "kB", "kC"),
	)

	/** A spy wrapping a real [ActuatorCommandQueue] so we can verify `postAll` calls. */
	private val queue = spyk(ActuatorCommandQueue())

	// ── Helper to simulate the correct "validate-then-post" pattern ────────────

	/**
	 * Validate [action] against [observation]; call [ActuatorCommandQueue.postAll] with a
	 * dummy decision if and only if the verdict is [ValidationVerdict.Valid].
	 * Returns the [ValidationVerdict].
	 */
	private fun validateAndMaybePost(
		action: DispatchAction,
		observation: DispatcherObservation,
	): ValidationVerdict {
		val verdict = validator.validate(action, observation)
		if (verdict is ValidationVerdict.Valid) {
			// In production this would be the actual DispatchDecision derived from the action;
			// here we post a NoAction placeholder so the queue interaction is observable.
			queue.postAll(listOf(cz.vutbr.fit.interlockSim.sim.DispatchDecision.NoAction))
		}
		return verdict
	}

	// ── Tests ─────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("ROUTE_ALREADY_HELD_TO_SAME_TARGET: postAll is NOT called for the rejected action")
	fun routeAlreadyHeldRejectedBeforeQueuePost() {
		val observation = DispatcherObservation(
			tick = 10L,
			simTime = 60.0,
			trains = listOf(
				TrainView(
					trainId = "T1",
					phase = TrainPhase.RUNNING,
					frontSectionName = "kA",
					velocityMps = 3.0,
					accelerationMps2 = 0.0,
					destinationInOutName = "B",
					signalAheadName = null,
					signalAheadAspect = null,
					distanceToSignalAheadMetres = 0.0,
					waitingSinceSimTime = null,
					waitSeconds = 0.0,
				)
			),
			blocks = emptyList(),
			switches = emptyList(),
			signals = emptyList(),
			reservations = listOf(
				ReservationView(trainId = "T1", fromEndpointName = "A", targetName = "B", blockIds = listOf("kA"))
			),
			queued = emptyList(),
			activeCount = 1,
			capacity = 2,
			appliedOutcomes = emptyList(),
		)

		val verdict = validateAndMaybePost(
			DispatchAction.RequestRoute("T1", "A", "B"),
			observation,
		)

		assertThat(verdict).isInstanceOf(ValidationVerdict.Rejected::class)
		assertThat((verdict as ValidationVerdict.Rejected).code)
			.isEqualTo(RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET)

		verify(exactly = 0) { queue.postAll(any()) }
	}

	@Test
	@DisplayName("CAPACITY_FULL (approve_train): postAll is NOT called for the rejected action")
	fun capacityFullRejectedBeforeQueuePost() {
		val observation = DispatcherObservation(
			tick = 11L,
			simTime = 61.0,
			trains = emptyList(),
			blocks = emptyList(),
			switches = emptyList(),
			signals = emptyList(),
			reservations = emptyList(),
			queued = listOf(QueuedTrainView("T1", "B", 1.0)),
			activeCount = 2,
			capacity = 2, // at capacity
			appliedOutcomes = emptyList(),
		)

		val verdict = validateAndMaybePost(DispatchAction.ApproveTrain("T1"), observation)

		assertThat(verdict).isInstanceOf(ValidationVerdict.Rejected::class)
		assertThat((verdict as ValidationVerdict.Rejected).code).isEqualTo(RejectionCode.CAPACITY_FULL)

		verify(exactly = 0) { queue.postAll(any()) }
	}

	@Test
	@DisplayName("valid ApproveTrain: postAll IS called exactly once")
	fun validApproveTrainPostsToQueue() {
		val observation = DispatcherObservation(
			tick = 12L,
			simTime = 62.0,
			trains = emptyList(),
			blocks = emptyList(),
			switches = emptyList(),
			signals = emptyList(),
			reservations = emptyList(),
			queued = listOf(QueuedTrainView("T1", "B", 1.0)),
			activeCount = 0,
			capacity = 2,
			appliedOutcomes = emptyList(),
		)

		val verdict = validateAndMaybePost(DispatchAction.ApproveTrain("T1"), observation)

		assertThat(verdict).isEqualTo(ValidationVerdict.Valid)
		verify(exactly = 1) { queue.postAll(any()) }
	}

	@Test
	@DisplayName("UNKNOWN_TRAIN: postAll is NOT called for any of three action types")
	fun unknownTrainRejectedBeforeQueuePost() {
		val emptyObservation = DispatcherObservation.EMPTY

		listOf(
			DispatchAction.ApproveTrain("Ghost"),
			DispatchAction.RequestRoute("Ghost", "A", "B"),
			DispatchAction.CancelRoute("Ghost"),
		).forEach { action ->
			val verdict = validateAndMaybePost(action, emptyObservation)
			assertThat(verdict, name = "verdict for ${action.kind}").isInstanceOf(ValidationVerdict.Rejected::class)
		}

		verify(exactly = 0) { queue.postAll(any()) }
	}

	@Test
	@DisplayName("NoOp is always valid and does not trigger a queue post (no action needed)")
	fun noOpIsValidButCallerChoosesNotToPost() {
		val verdict = validator.validate(DispatchAction.NoOp, DispatcherObservation.EMPTY)

		assertThat(verdict).isEqualTo(ValidationVerdict.Valid)
		// NoOp is valid but the caller (by convention) does not post a decision for it
		verify(exactly = 0) { queue.postAll(any()) }
	}
}
