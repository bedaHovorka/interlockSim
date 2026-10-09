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
import assertk.assertions.isNull
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
	private fun inOut(
		name: String,
		available: Boolean
	) = CandidateTarget(name, SeparatorKind.IN_OUT, available)

	private fun semaphore(
		name: String,
		available: Boolean
	) = CandidateTarget(name, SeparatorKind.SEMAPHORE, available)

	@Test
	fun `picks the first available candidate`() {
		val candidates = listOf(semaphore("doB1", true), semaphore("doB2", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphore("doB1", true))
	}

	@Test
	fun `skips an unavailable prefix`() {
		val candidates = listOf(semaphore("doB1", false), semaphore("doB2", false), semaphore("doB3", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphore("doB3", true))
	}

	@Test
	fun `an InOut listed after an available semaphore wins`() {
		val candidates = listOf(semaphore("zB", true), inOut("B", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(inOut("B", true))
	}

	@Test
	fun `an unavailable InOut does not shadow an available semaphore`() {
		val candidates = listOf(semaphore("zB", true), inOut("B", false))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphore("zB", true))
	}

	@Test
	fun `an empty list gives null`() {
		assertThat(ReservationTargetPolicy.pick(emptyList())).isNull()
	}

	@Test
	fun `all candidates unavailable gives null`() {
		val candidates = listOf(inOut("A", false), semaphore("doB1", false), semaphore("doB2", false))

		assertThat(ReservationTargetPolicy.pick(candidates)).isNull()
	}

	@Test
	fun `candidates of one kind keep their search order`() {
		val candidates = listOf(semaphore("doB2", false), semaphore("doB1", true), semaphore("doB3", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(semaphore("doB1", true))
	}

	@Test
	fun `InOuts keep their search order among themselves`() {
		val candidates = listOf(semaphore("zB", true), inOut("B2", true), inOut("B1", true))

		assertThat(ReservationTargetPolicy.pick(candidates)).isEqualTo(inOut("B2", true))
	}
}
