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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Re-reservation of a route that was released beforehand (Issue #1165 split). */
@Tag("integration-test")
class PathReservationReservationAfterReleaseTest : PathReservationServiceTestBase() {
	@Test
	fun `path can be re-reserved after release`() {
		// Reserve
		val result1 = service.reservePath("train1", inOut1, inOut2)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Release
		service.releasePath("train1")

		// Re-reserve
		val result2 = service.reservePath("train1", inOut1, inOut2)
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Verify blocks are RESERVED again
		val blocks = service.getReservedBlocks("train1")
		assertThat(blocks.size).isEqualTo(7)
		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo("train1")
		}
	}

	@Test
	fun `different train can reserve after previous train releases`() {
		// Train1 reserves
		service.reservePath("train1", inOut1, inOut2)

		// Train1 releases
		service.releasePath("train1")

		// Train2 reserves
		val result = service.reservePath("train2", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Verify ownership
		val blocks = service.getReservedBlocks("train2")
		blocks.forEach { block -> assertThat(block.trainName).isEqualTo("train2") }

		val train1Blocks = service.getReservedBlocks("train1")
		assertThat(train1Blocks).isEmpty()
	}
}
