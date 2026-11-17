/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Issue #1387 review round: the bounded lifetime of the class-A fallback record.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.inOutCandidate
import cz.vutbr.fit.interlockSim.testutil.semaphoreCandidate
import kotlin.test.Test

/**
 * Unit tests for [WrongOutFallbackRecorder] — the per-train class-A fallback record of
 * [ShuntingLoop] and its bounded lifetime (Issue #1387 and its review round).
 *
 * The record is written only on the tick at which the steer was applicable and was lost: the own
 * exit was not a station-exit candidate, every signal candidate toward it (the observation's
 * `destinationReachableVia`) was unavailable, and some other candidate was available so the pick
 * actually fell back to the fixed order. The deciding fact is the pick itself, read through the
 * one selection seam ([BlockInputObservation.chosenTargetName], [ReservationTargetPolicy] steps 3
 * and 4). A tick with no available candidate at all decides nothing and must not create a record
 * (the Copilot review of #1387). A later step-3 pick (a reachable signal available) and the #1244
 * awaiting state clear the record; an input the steer does not apply to keeps what is known.
 *
 * Pure common-platform tests (KMP `commonTest`): no mocks, no JUnit 5 API beyond `kotlin.test`, so
 * they run on both the JVM and the native target.
 */
class WrongOutFallbackRecorderTest {
	@Test
	fun `a fallback pick records the busy platforms toward the own exit`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), reachable = setOf("S9"))
		)

		assertThat(recorder.remove("Train #1"), name = "fallback record").isEqualTo(setOf("S9"))
	}

	@Test
	fun `a busy observation without any pick records nothing`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", false)), reachable = setOf("S9"))
		)

		assertThat(recorder.remove("Train #1"), name = "fallback record of a pick-less tick").isNull()
	}

	@Test
	fun `a step-3 pick clears an earlier record`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), reachable = setOf("S9"))
		)
		recorder.observe(input(candidates = listOf(semaphoreCandidate("S9", true)), reachable = setOf("S9")))

		assertThat(recorder.remove("Train #1"), name = "fallback record after a steered pick").isNull()
	}

	@Test
	fun `the awaiting state clears an earlier record`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), reachable = setOf("S9"))
		)
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), awaiting = true)
		)

		assertThat(recorder.remove("Train #1"), name = "fallback record after the awaiting state").isNull()
	}

	@Test
	fun `a no-pick tick keeps the deciding set`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), reachable = setOf("S9"))
		)
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", false)), reachable = setOf("S9"))
		)

		assertThat(recorder.remove("Train #1"), name = "fallback record across a pick-less tick").isEqualTo(setOf("S9"))
	}

	@Test
	fun `a newer fallback replaces an earlier one`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), reachable = setOf("S9"))
		)
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S7", false), inOutCandidate("B2", true)), reachable = setOf("S7"))
		)

		assertThat(recorder.remove("Train #1"), name = "fallback record").isEqualTo(setOf("S7"))
	}

	@Test
	fun `an input the steer does not apply to keeps an earlier record`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), reachable = setOf("S9"))
		)
		// The own exit is now listed itself (Issue #1278 decides): the reachable set is empty, the
		// record this train gathered while the steer applied still classifies it.
		recorder.observe(input(candidates = listOf(inOutCandidate("N-Vys-1", false)), reachable = emptySet()))

		assertThat(recorder.remove("Train #1"), name = "fallback record while the steer is inapplicable")
			.isEqualTo(setOf("S9"))
	}

	@Test
	fun `an input without an owner changes nothing`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(
				candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)),
				reachable = setOf("S9"),
				ownerTrainId = null
			)
		)

		assertThat(recorder.remove("Train #1"), name = "fallback record of an ownerless input").isNull()
	}

	@Test
	fun `remove drops the record and reports it once`() {
		val recorder = WrongOutFallbackRecorder()
		recorder.observe(
			input(candidates = listOf(semaphoreCandidate("S9", false), inOutCandidate("B2", true)), reachable = setOf("S9"))
		)

		assertThat(recorder.remove("Train #1"), name = "first removal").isEqualTo(setOf("S9"))
		assertThat(recorder.remove("Train #1"), name = "second removal").isNull()
		assertThat(recorder.remove("Train #2"), name = "removal of an unrecorded train").isNull()
	}

	private companion object {
		/** A [BlockInputObservation] reduced to the facts [WrongOutFallbackRecorder.observe] reads. */
		private fun input(
			candidates: List<CandidateTarget>,
			reachable: Set<String> = emptySet(),
			awaiting: Boolean = false,
			ownerTrainId: String? = "Train #1"
		): BlockInputObservation =
			BlockInputObservation(
				blockId = "k1",
				towardSemaphoreName = "S_60_4",
				state = TrackFacility.State.OCCUPIED,
				ownerTrainId = ownerTrainId,
				isApproachingThisInput = true,
				pathSetUpTowardThisInput = false,
				pathAlreadyExtendedBeyond = false,
				awaitingRouteExtension = awaiting,
				candidateTargets = candidates,
				destinationName = "N-Vys-2",
				destinationReachableVia = reachable
			)
	}
}
