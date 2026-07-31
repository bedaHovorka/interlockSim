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
import cz.vutbr.fit.interlockSim.dispatcher.observation.BlockView
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Purity tests for [ActionValidator] (SP2c.3, Issue #826).
 *
 * Verifies two properties stated in the acceptance criteria:
 *
 * 1. **Constructor-shape purity**: [ActionValidator] holds no reference to
 *    [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort] or [ActuatorCommandQueue] —
 *    both live simulation objects. A reference to either would couple the validator to
 *    mutable sim state and violate the "never mutates" contract.
 *
 * 2. **Idempotency**: calling [ActionValidator.validate] 1 000× on the same
 *    [DispatchAction] and [DispatcherObservation] always returns structurally equal
 *    [ValidationVerdict] values. This confirms that [validate] produces no side effects
 *    that could cause the result to drift across calls.
 *
 * @since Issue #826 (SP2c.3 — Goal 10)
 */
@DisplayName("ActionValidator — purity: constructor-shape and 1000× idempotency (SP2c.3 #826)")
class ActionValidatorPurityTest {
	private val validator =
		ActionValidator(
			validEndpointNames = setOf("A", "B", "C", "sigA"),
			blockIds = setOf("kA", "kB", "kC")
		)

	// ── Constructor-shape tests ────────────────────────────────────────────────

	@Test
	@DisplayName("ActionValidator holds no reference to ActuatorCommandQueue (purity shape)")
	fun validatorHoldsNoActuatorCommandQueueReference() {
		val forbidden = "ActuatorCommandQueue"
		val fields = ActionValidator::class.java.declaredFields.map { it.type.name }
		val violating = fields.filter { it.contains(forbidden) }

		assert(violating.isEmpty()) {
			"ActionValidator must not reference $forbidden; found fields: $violating"
		}
	}

	@Test
	@DisplayName("ActionValidator holds no reference to NetworkActuatorPort (purity shape)")
	fun validatorHoldsNoNetworkActuatorPortReference() {
		val forbidden = "NetworkActuatorPort"
		val fields = ActionValidator::class.java.declaredFields.map { it.type.name }
		val violating = fields.filter { it.contains(forbidden) }

		assert(violating.isEmpty()) {
			"ActionValidator must not reference $forbidden; found fields: $violating"
		}
	}

	// ── Idempotency tests ──────────────────────────────────────────────────────

	@Test
	@DisplayName("validate called 1000× on same VALID action returns identical Valid verdict every time")
	fun validActionIdempotent1000Times() {
		val observation =
			DispatcherObservation(
				tick = 5L,
				simTime = 30.0,
				trains =
					listOf(
						TrainView(
							trainId = "T1",
							phase = TrainPhase.RUNNING,
							frontSectionName = "kA",
							velocityMps = 10.0,
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
						BlockView("kA", TrackFacility.State.OCCUPIED, "T1"),
						BlockView("kB", TrackFacility.State.FREE, null),
						BlockView("kC", TrackFacility.State.FREE, null)
					),
				switches = emptyList(),
				signals = emptyList(),
				reservations = emptyList(),
				queued = emptyList(),
				activeCount = 1,
				capacity = 2,
				appliedOutcomes = emptyList()
			)

		val action = DispatchAction.RequestRoute("T1", "A", "B")

		val firstVerdict = validator.validate(action, observation)
		repeat(999) { iteration ->
			val verdict = validator.validate(action, observation)
			assertThat(verdict, name = "verdict at iteration ${iteration + 1}").isEqualTo(firstVerdict)
		}
	}

	@Test
	@DisplayName("validate called 1000× on same REJECTED action returns identical Rejected verdict every time")
	fun rejectedActionIdempotent1000Times() {
		val observation =
			DispatcherObservation(
				tick = 6L,
				simTime = 40.0,
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
				blocks = emptyList(),
				switches = emptyList(),
				signals = emptyList(),
				reservations =
					listOf(
						ReservationView(trainId = "T1", fromEndpointName = "A", targetName = "B", blockIds = listOf("kA", "kB"))
					),
				queued = emptyList(),
				activeCount = 1,
				capacity = 2,
				appliedOutcomes = emptyList()
			)

		// This is the #814 Symptom 3 scenario — same target already held
		val action = DispatchAction.RequestRoute("T1", "A", "B")

		val firstVerdict = validator.validate(action, observation)
		repeat(999) { iteration ->
			val verdict = validator.validate(action, observation)
			assertThat(verdict, name = "verdict at iteration ${iteration + 1}").isEqualTo(firstVerdict)
		}
	}

	@Test
	@DisplayName("validate called 1000× on ApproveTrain when capacity full returns identical Rejected every time")
	fun capacityFullIdempotent1000Times() {
		val observation =
			DispatcherObservation(
				tick = 7L,
				simTime = 50.0,
				trains = emptyList(),
				blocks = emptyList(),
				switches = emptyList(),
				signals = emptyList(),
				reservations = emptyList(),
				queued = listOf(QueuedTrainView("T1", "B", 1.0)),
				activeCount = 2,
				capacity = 2, // at capacity
				appliedOutcomes = emptyList()
			)

		val action = DispatchAction.ApproveTrain("T1")

		val firstVerdict = validator.validate(action, observation)
		repeat(999) { iteration ->
			val verdict = validator.validate(action, observation)
			assertThat(verdict, name = "verdict at iteration ${iteration + 1}").isEqualTo(firstVerdict)
		}
	}
}
