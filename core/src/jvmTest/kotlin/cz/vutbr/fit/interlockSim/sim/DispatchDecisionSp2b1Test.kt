/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [DispatchDecision] subtypes introduced or extended in SP2b.1
 * (Issue #556 — Goal 10): [DispatchDecision.HoldTrain] and the [DispatchDecision.rationale]
 * property on all subtypes.
 *
 * The goal of this class is to verify:
 * 1. [DispatchDecision.HoldTrain] construction, properties, and `init` validation.
 * 2. [DispatchDecision.rationale] is accessible on every subtype (defaults to `null`).
 * 3. Backward compatibility: existing subtypes still construct correctly without `rationale`.
 *
 * @since Issue #556 (SP2b.1 — Goal 10)
 */
@DisplayName("DispatchDecision — SP2b.1 HoldTrain + rationale (Issue #556)")
@Timeout(10, unit = TimeUnit.SECONDS)
class DispatchDecisionSp2b1Test {
	// ── HoldTrain construction and validation ─────────────────────────────────

	@Nested
	@DisplayName("HoldTrain construction and validation")
	inner class HoldTrainConstruction {
		@Test
		@DisplayName("HoldTrain is constructed with trainId and holdDurationSeconds")
		fun holdTrain_basicConstruction() {
			val decision = DispatchDecision.HoldTrain("Train-1", 30.0)
			assertThat(decision.trainId).isEqualTo("Train-1")
			assertThat(decision.holdDurationSeconds).isEqualTo(30.0)
		}

		@Test
		@DisplayName("HoldTrain without rationale has null rationale")
		fun holdTrain_defaultRationaleIsNull() {
			val decision = DispatchDecision.HoldTrain("Train-1", 30.0)
			assertThat(decision.rationale).isNull()
		}

		@Test
		@DisplayName("HoldTrain with rationale stores the rationale string")
		fun holdTrain_withRationale_rationaleStored() {
			val decision =
				DispatchDecision.HoldTrain(
					trainId = "Train-1",
					holdDurationSeconds = 45.0,
					rationale = "Train-2 is approaching on the same track"
				)
			assertThat(decision.rationale).isEqualTo("Train-2 is approaching on the same track")
		}

		@Test
		@DisplayName("HoldTrain validation: blank trainId throws IllegalArgumentException")
		fun holdTrain_blankTrainId_throws() {
			assertFailure {
				DispatchDecision.HoldTrain("", 30.0)
			}.isInstanceOf<IllegalArgumentException>()
		}

		@Test
		@DisplayName("HoldTrain validation: whitespace-only trainId throws IllegalArgumentException")
		fun holdTrain_whitespaceTrainId_throws() {
			assertFailure {
				DispatchDecision.HoldTrain("   ", 30.0)
			}.isInstanceOf<IllegalArgumentException>()
		}

		@Test
		@DisplayName("HoldTrain validation: zero holdDurationSeconds throws IllegalArgumentException")
		fun holdTrain_zeroDuration_throws() {
			assertFailure {
				DispatchDecision.HoldTrain("Train-1", 0.0)
			}.isInstanceOf<IllegalArgumentException>()
		}

		@Test
		@DisplayName("HoldTrain validation: negative holdDurationSeconds throws IllegalArgumentException")
		fun holdTrain_negativeDuration_throws() {
			assertFailure {
				DispatchDecision.HoldTrain("Train-1", -1.0)
			}.isInstanceOf<IllegalArgumentException>()
		}

		@Test
		@DisplayName("HoldTrain equality: two instances with same properties are equal")
		fun holdTrain_dataClassEquality() {
			val a = DispatchDecision.HoldTrain("Train-1", 30.0, "some rationale")
			val b = DispatchDecision.HoldTrain("Train-1", 30.0, "some rationale")
			assertThat(a).isEqualTo(b)
		}
	}

	// ── rationale property on existing subtypes ────────────────────────────────

	@Nested
	@DisplayName("rationale property on existing subtypes (backward compatibility)")
	inner class RationaleOnExistingSubtypes {
		@Test
		@DisplayName("ApproveTrain without rationale has null rationale (backward compat)")
		fun approveTrain_defaultRationaleIsNull() {
			val decision = DispatchDecision.ApproveTrain("T1")
			assertThat(decision.rationale).isNull()
		}

		@Test
		@DisplayName("ApproveTrain with rationale stores the rationale string")
		fun approveTrain_withRationale_rationaleStored() {
			val decision = DispatchDecision.ApproveTrain("T1", rationale = "Main track free; admitting first queued train")
			assertThat(decision.rationale).isEqualTo("Main track free; admitting first queued train")
		}

		@Test
		@DisplayName("NoAction has null rationale")
		fun noAction_rationaleIsNull() {
			assertThat(DispatchDecision.NoAction.rationale).isNull()
		}

		@Test
		@DisplayName("ReservePath has null rationale (not LLM-facing)")
		fun reservePath_rationaleIsNull() {
			val decision = DispatchDecision.ReservePath("T1", "zA", "nextSep")
			assertThat(decision.rationale).isNull()
		}

		@Test
		@DisplayName("SetSignalAspect without rationale has null rationale (backward compat)")
		fun setSignalAspect_defaultRationaleIsNull() {
			val decision =
				DispatchDecision.SetSignalAspect(
					"zA",
					cz.vutbr.fit.interlockSim.objects.cells.Signal.FREE
				)
			assertThat(decision.rationale).isNull()
		}

		@Test
		@DisplayName("SetSwitchPosition without rationale has null rationale (backward compat)")
		fun setSwitchPosition_defaultRationaleIsNull() {
			val decision =
				DispatchDecision.SetSwitchPosition(
					"vA",
					cz.vutbr.fit.interlockSim.objects.cells.RailSwitch.Conf.MAIN
				)
			assertThat(decision.rationale).isNull()
		}

		@Test
		@DisplayName("ReleaseRoute without rationale has null rationale (backward compat)")
		fun releaseRoute_defaultRationaleIsNull() {
			val decision = DispatchDecision.ReleaseRoute("T1")
			assertThat(decision.rationale).isNull()
		}

		@Test
		@DisplayName("RequestRoute without rationale has null rationale (backward compat)")
		fun requestRoute_defaultRationaleIsNull() {
			val decision = DispatchDecision.RequestRoute("T1", "A", "B")
			assertThat(decision.rationale).isNull()
		}

		@Test
		@DisplayName("RequestRoute with rationale stores the rationale string")
		fun requestRoute_withRationale_rationaleStored() {
			val decision =
				DispatchDecision.RequestRoute(
					trainName = "T1",
					fromEndpointName = "A",
					toEndpointName = "B",
					rationale = "Shortest free path from A to B chosen"
				)
			assertThat(decision.rationale).isEqualTo("Shortest free path from A to B chosen")
		}
	}
}
