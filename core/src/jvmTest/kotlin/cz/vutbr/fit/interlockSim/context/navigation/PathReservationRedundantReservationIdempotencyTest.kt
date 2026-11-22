/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Redundant re-request of an already granted route must stay idempotent (Issue #1165 split). */
@Tag("integration-test")
class PathReservationRedundantReservationIdempotencyTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath for a train that already holds the granted route does not duplicate its PathInfo`() {
		// Given: a route already granted once (mirrors the LLM dispatcher's request_route
		// succeeding for a train, Goal 10 SP2b.9)
		val first = service.reservePath("train1", inOut1, inOut2)
		assertThat(first).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val originalPathSize = requireNotNull(registry.getPathInfo("train1")).reservedPath.size

		// When: the identical route is redundantly re-requested for the SAME train
		// multiple times — exactly what a stateless per-cycle LLM dispatcher does when
		// it re-issues request_route for a train it already granted a route to, since
		// it has no memory of its own prior tool calls.
		repeat(2) {
			val redundant = service.reservePath("train1", inOut1, inOut2)
			assertThat(redundant).isInstanceOf<PathReservationService.ReservationResult.Success>()
		}

		// Then: PathInfo must stay exactly as originally granted — a redundant re-request
		// for a route the train already fully owns must never grow/duplicate the reserved
		// path. Without a guard, each redundant call re-merges the identical route onto
		// itself (doubling every separator), and a further redundant call hits
		// PathReservationRegistry.mergePathInfo's 3rd-occurrence cycle-abort — which
		// silently reverts the merge while reservePath still reports Success, an invisible
		// PathInfo/reality divergence that (per the same bug class) can strand a train
		// permanently at whichever semaphore first depends on the discarded segment.
		val finalPathInfo = requireNotNull(registry.getPathInfo("train1"))
		assertThat(finalPathInfo.reservedPath.size).isEqualTo(originalPathSize)
		assertThat(finalPathInfo.target).isEqualTo(inOut2)
	}
}
