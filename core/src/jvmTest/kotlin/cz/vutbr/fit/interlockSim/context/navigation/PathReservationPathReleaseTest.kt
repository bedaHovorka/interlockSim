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
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Wholesale release of a reserved route (Issue #1165 split). */
@Tag("integration-test")
class PathReservationPathReleaseTest : PathReservationServiceTestBase() {
	@Test
	fun `releasePath frees all blocks and clears trainId`() {
		// Arrange
		val result = service.reservePath("train1", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val blocks = assertReservationSuccess(result).reservedBlocks

		// Act
		val released = service.releasePath("train1")

		// Assert
		assertThat(released).containsExactly(*blocks.toTypedArray())

		blocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(block.reservedFrom).isNull()
			assertThat(block.trainName).isNull()
		}

		// Verify registry is cleared
		val remainingBlocks = service.getReservedBlocks("train1")
		assertThat(remainingBlocks).isEmpty()
	}

	@Test
	fun `releasePath is idempotent`() {
		// Arrange
		service.reservePath("train1", inOut1, inOut2)

		// Act
		val released1 = service.releasePath("train1")
		val released2 = service.releasePath("train1") // Second call

		// Assert
		assertThat(released1.size).isEqualTo(7)
		assertThat(released2).isEmpty() // No blocks to release on second call
	}

	@Test
	fun `releasePath returns empty list for unknown train`() {
		// Act
		val released = service.releasePath("unknown-train")

		// Assert
		assertThat(released).isEmpty()
	}
}
