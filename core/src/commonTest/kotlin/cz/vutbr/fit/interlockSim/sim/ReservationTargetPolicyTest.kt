/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for ReservationTargetPolicy (Issue #970).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.inOutCandidate
import cz.vutbr.fit.interlockSim.testutil.semaphoreCandidate
import kotlin.test.Test

/**
 * Unit tests for [ReservationTargetPolicy] — the one place where the dispatcher chooses the
 * forward-reservation target among the candidates the shell reports (Issue #970).
 *
 * Pure common-platform tests (KMP `commonTest`): no mocks, no JUnit 5, so they run on both the
 * JVM and the native target.
 *
 * @since Issue #970
 */
class ReservationTargetPolicyTest {
	@Test
	fun `picks the first available candidate`() {
		val candidates = listOf(semaphoreCandidate("doB1", true), semaphoreCandidate("doB2", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphoreCandidate("doB1", true))
	}

	@Test
	fun `skips an unavailable prefix`() {
		val candidates =
			listOf(
				semaphoreCandidate("doB1", false),
				semaphoreCandidate("doB2", false),
				semaphoreCandidate("doB3", true)
			)

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphoreCandidate("doB3", true))
	}

	@Test
	fun `an InOut listed after an available semaphore wins`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(inOutCandidate("B", true))
	}

	@Test
	fun `an unavailable InOut does not shadow an available semaphore`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B", false))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphoreCandidate("zB", true))
	}

	@Test
	fun `an empty list gives null`() {
		assertThat(ReservationTargetPolicy.pick(emptyList())).isNull()
	}

	@Test
	fun `all candidates unavailable gives null`() {
		val candidates =
			listOf(
				inOutCandidate("A", false),
				semaphoreCandidate("doB1", false),
				semaphoreCandidate("doB2", false)
			)

		assertThat(ReservationTargetPolicy.pick(candidates)).isNull()
	}

	@Test
	fun `candidates of one kind keep their search order`() {
		val candidates =
			listOf(
				semaphoreCandidate("doB2", false),
				semaphoreCandidate("doB1", true),
				semaphoreCandidate("doB3", true)
			)

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphoreCandidate("doB1", true))
	}

	@Test
	fun `InOuts keep their search order among themselves`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B2", true), inOutCandidate("B1", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(inOutCandidate("B2", true))
	}

	// Destination preference (Issue #1278): the timetable's exit wins when it is an available
	// station-exit candidate; in every other case the order above is kept unchanged.

	@Test
	fun `the destination exit wins over an exit listed before it`() {
		val candidates = listOf(inOutCandidate("B2", true), inOutCandidate("B1", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1"))
			.isEqualTo(inOutCandidate("B1", true))
	}

	@Test
	fun `the destination exit wins over an available semaphore listed first`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B2", true), inOutCandidate("B1", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1"))
			.isEqualTo(inOutCandidate("B1", true))
	}

	@Test
	fun `an unavailable destination exit falls back to the current order`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B2", true), inOutCandidate("B1", false))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1"))
			.isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `a destination that is not among the candidates keeps the current order`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B2", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1"))
			.isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `a signal that shares the destination's name is not preferred`() {
		val candidates = listOf(inOutCandidate("B2", true), semaphoreCandidate("B1", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1"))
			.isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `no destination keeps the current order`() {
		val candidates = listOf(inOutCandidate("B2", true), inOutCandidate("B1", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = null))
			.isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `chosenTargetName prefers the observation's destination`() {
		val input =
			BlockInputObservation(
				blockId = "block",
				towardSemaphoreName = "zB",
				state = TrackFacility.State.OCCUPIED,
				ownerTrainId = "Train #1",
				isApproachingThisInput = true,
				pathSetUpTowardThisInput = false,
				pathAlreadyExtendedBeyond = false,
				candidateTargets = listOf(inOutCandidate("B2", true), inOutCandidate("B1", true)),
				destinationName = "B1"
			)

		assertThat(input.chosenTargetName()).isEqualTo("B1")
		assertThat(input.copy(destinationName = null).chosenTargetName()).isEqualTo("B2")
	}
	// Waiting for the own exit (Issue #1299): when the destination exit is a candidate but not
	// available, the train holds out for it, for at most OWN_EXIT_WAIT_BOUND_SECONDS of simulation
	// time as reported by the shell; then the fixed-order fallback applies again.

	@Test
	fun `a train holds out for its own exit while it is blocked`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B2", true), inOutCandidate("B1", false))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1", destinationWaitSeconds = 0.0))
			.isNull()
	}

	@Test
	fun `a train still holds out for its own exit just below the bound`() {
		val candidates = listOf(inOutCandidate("B2", true), inOutCandidate("B1", false))
		val justBelowBound = ReservationTargetPolicy.OWN_EXIT_WAIT_BOUND_SECONDS - CONTROL_TICK_SECONDS

		assertThat(
			ReservationTargetPolicy.pick(candidates, destinationName = "B1", destinationWaitSeconds = justBelowBound)
		).isNull()
	}

	@Test
	fun `at the bound the fallback takes the next available exit`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B2", true), inOutCandidate("B1", false))
		val atBound = ReservationTargetPolicy.OWN_EXIT_WAIT_BOUND_SECONDS

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1", destinationWaitSeconds = atBound))
			.isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `an own exit that becomes available wins while waiting`() {
		val candidates = listOf(inOutCandidate("B2", true), inOutCandidate("B1", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1", destinationWaitSeconds = 0.0))
			.isEqualTo(inOutCandidate("B1", true))
	}

	@Test
	fun `no wait is held for a destination that is not among the candidates`() {
		val candidates = listOf(semaphoreCandidate("zB", true), inOutCandidate("B2", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1", destinationWaitSeconds = 0.0))
			.isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `no wait is held for a blocked signal that shares the destination's name`() {
		val candidates = listOf(inOutCandidate("B2", true), semaphoreCandidate("B1", false))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1", destinationWaitSeconds = 0.0))
			.isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `isWaitingForOwnExit is true only for a listed but unavailable own exit`() {
		val blocked = listOf(inOutCandidate("B2", true), inOutCandidate("B1", false))

		assertThat(ReservationTargetPolicy.isWaitingForOwnExit(blocked, "B1")).isTrue()
		assertThat(ReservationTargetPolicy.isWaitingForOwnExit(blocked, "B2")).isFalse()
		assertThat(ReservationTargetPolicy.isWaitingForOwnExit(blocked, "B3")).isFalse()
		assertThat(ReservationTargetPolicy.isWaitingForOwnExit(blocked, null)).isFalse()
		assertThat(ReservationTargetPolicy.isWaitingForOwnExit(listOf(semaphoreCandidate("B1", false)), "B1"))
			.isFalse()
	}

	@Test
	fun `chosenTargetName holds out for the observation's blocked destination until the bound`() {
		val input =
			BlockInputObservation(
				blockId = "block",
				towardSemaphoreName = "zB",
				state = TrackFacility.State.OCCUPIED,
				ownerTrainId = "Train #1",
				isApproachingThisInput = true,
				pathSetUpTowardThisInput = false,
				pathAlreadyExtendedBeyond = false,
				candidateTargets = listOf(inOutCandidate("B2", true), inOutCandidate("B1", false)),
				destinationName = "B1",
				destinationWaitSeconds = 0.0
			)

		assertThat(input.chosenTargetName()).isNull()
		assertThat(
			input.copy(destinationWaitSeconds = ReservationTargetPolicy.OWN_EXIT_WAIT_BOUND_SECONDS).chosenTargetName()
		).isEqualTo("B2")
		// An observation that carries no wait (a shell that does not track it) keeps the #1278 fallback.
		assertThat(input.copy(destinationWaitSeconds = null).chosenTargetName()).isEqualTo("B2")
	}

	// Platform choice by reachability (Issue #1387): when the own exit is not a station-exit
	// candidate, an available signal from which it is reachable wins; when none is available the
	// fixed order applies unchanged (no wait for a busy reachable signal).

	@Test
	fun `an available signal from which the own exit is reachable wins over one listed before it`() {
		val candidates = listOf(semaphoreCandidate("S2", true), semaphoreCandidate("S3", true))

		assertThat(
			ReservationTargetPolicy.pick(candidates, destinationName = "N-Lib-2", destinationReachableVia = setOf("S3"))
		).isEqualTo(semaphoreCandidate("S3", true))
	}

	@Test
	fun `a reachable signal wins over a wrong available exit`() {
		val candidates = listOf(inOutCandidate("B2", true), semaphoreCandidate("zB", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = "B1", destinationReachableVia = setOf("zB")))
			.isEqualTo(semaphoreCandidate("zB", true))
	}

	@Test
	fun `a busy reachable signal falls back to the fixed order at once`() {
		val candidates = listOf(semaphoreCandidate("S2", true), semaphoreCandidate("S3", false))

		assertThat(
			ReservationTargetPolicy.pick(candidates, destinationName = "N-Lib-2", destinationReachableVia = setOf("S3"))
		).isEqualTo(ReservationTargetPolicy.pick(candidates, destinationName = "N-Lib-2"))
	}

	@Test
	fun `the reachable set is ignored without a destination`() {
		val candidates = listOf(semaphoreCandidate("S2", true), semaphoreCandidate("S3", true))

		assertThat(ReservationTargetPolicy.pick(candidates, destinationName = null, destinationReachableVia = setOf("S3")))
			.isEqualTo(semaphoreCandidate("S2", true))
	}

	@Test
	fun `the own exit as a candidate keeps the Issue 1278 and Issue 1299 rules and the reachable set does not apply`() {
		val candidates = listOf(inOutCandidate("B2", true), semaphoreCandidate("zB", true), inOutCandidate("B1", false))
		val reachable = setOf("zB")

		// Below the bound the train still holds out for its blocked own exit.
		assertThat(
			ReservationTargetPolicy.pick(
				candidates,
				destinationName = "B1",
				destinationWaitSeconds = 0.0,
				destinationReachableVia = reachable
			)
		).isNull()
		// Past the bound the pick equals the old fixed-order fallback, not the reachable signal.
		assertThat(
			ReservationTargetPolicy.pick(
				candidates,
				destinationName = "B1",
				destinationWaitSeconds = ReservationTargetPolicy.OWN_EXIT_WAIT_BOUND_SECONDS,
				destinationReachableVia = reachable
			)
		).isEqualTo(inOutCandidate("B2", true))
	}

	@Test
	fun `an available own exit wins over the reachable steer`() {
		val candidates = listOf(inOutCandidate("N-Lib-2", true), semaphoreCandidate("S3", true))

		// Step 1 precedes step 3: a reachable signal must never shadow the own exit itself.
		assertThat(
			ReservationTargetPolicy.pick(candidates, destinationName = "N-Lib-2", destinationReachableVia = setOf("S3"))
		).isEqualTo(inOutCandidate("N-Lib-2", true))
	}

	@Test
	fun `chosenTargetName reads the observation's reachable set`() {
		val input =
			BlockInputObservation(
				blockId = "block",
				towardSemaphoreName = "S_60_4",
				state = TrackFacility.State.OCCUPIED,
				ownerTrainId = "Train #1",
				isApproachingThisInput = true,
				pathSetUpTowardThisInput = false,
				pathAlreadyExtendedBeyond = false,
				candidateTargets = listOf(semaphoreCandidate("S2", true), semaphoreCandidate("S3", true)),
				destinationName = "N-Lib-2",
				destinationReachableVia = setOf("S3")
			)

		assertThat(input.chosenTargetName()).isEqualTo("S3")
		assertThat(input.copy(destinationReachableVia = emptySet()).chosenTargetName()).isEqualTo("S2")
	}

	private companion object {
		/** ShuntingLoop's control step: observations, and hence wait readings, arrive every 2 s of simulation time. */
		const val CONTROL_TICK_SECONDS: Double = 2.0
	}
}
