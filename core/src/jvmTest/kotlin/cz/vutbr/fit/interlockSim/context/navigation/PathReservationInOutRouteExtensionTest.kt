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
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Issue #911: the InOut branch of [DefaultPathReservationService.configureStartSignal]
 * must fail OPEN on a route-extension's non-adjacent `start`/`forwardBlocks.first()` pair,
 * mirroring the semaphore branch's existing [DefaultPathReservationService.startFacesTravelDirection]
 * tolerance (Issue #893 task A1). A route extension re-invokes `reservePath` with the
 * train's ORIGINAL start; once every block adjacent to that start is already owned, the
 * first genuinely new `forwardBlocks.first()` is several hops away -- not literally an end
 * of the InOut's block, so `SimpleTrack.maxSpeed(start)` throws.
 *
 * ## Ruling (traffic-simulation-expert; binding)
 *
 * Treat as already configured and return `true`, exactly as the semaphore branch does --
 * the InOut's `inSemaphore` carries no extra authority that would make this branch behave
 * differently, and direction/entry-point correctness is enforced earlier by
 * `rejectNonContiguousStart`, not by this guard.
 */
@Tag("integration-test")
class PathReservationInOutRouteExtensionTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath extension from InOut succeeds when start no longer bounds the first forward block`() {
		// Arrange -- reserve only the first hop from inOut1, so inOut1 itself remains the
		// train's registered start while the block(s) immediately adjacent to it become
		// already-owned.
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()
		val firstHop = service.reservePathToAnyNextSemaphore("t1", inOut1, next!!)
		assertThat(firstHop).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(registry.getBlocks("t1")).isNotEmpty()

		// Act -- extend with the SAME original start (inOut1). forwardBlocks.first() is now
		// a block several hops down the path, not literally an end of inOut1's track.
		val extension = service.reservePath("t1", inOut1, inOut2)

		// Assert -- the extension must succeed and cover the full 7-block inOut1->inOut2
		// path (PathReservationSuccessfulReservationTest's baseline), not roll back and retry forever.
		assertThat(extension).isInstanceOf<PathReservationService.ReservationResult.Success>()
		assertThat(registry.getBlocks("t1")).hasSize(7)
	}
}
