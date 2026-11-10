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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Sequential use of the same physical path by several trains (Issue #1165 split). */
@Tag("integration-test")
class PathReservationMultipleTrainCoordinationTest : PathReservationServiceTestBase() {
	@Test
	fun `multiple trains can reserve different paths without conflict`() {
		// Note: vyhybna.xml has only one path, so this test verifies sequential usage

		// Reserve for train1
		val result1 = service.reservePath("train1", inOut1, inOut2)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Release for train1
		val released = service.releasePath("train1")
		assertThat(released.size).isEqualTo(7)

		// Reserve for train2 (same path, but now free)
		val result2 = service.reservePath("train2", inOut1, inOut2)
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Verify train2 owns the blocks
		val blocks = service.getReservedBlocks("train2")
		assertThat(blocks.size).isEqualTo(7)
		blocks.forEach { block -> assertThat(block.trainName).isEqualTo("train2") }
	}

	@Test
	fun `getReservedBlocks returns empty list for unknown train`() {
		// Act
		val blocks = service.getReservedBlocks("unknown-train")

		// Assert
		assertThat(blocks).isEmpty()
	}
}
