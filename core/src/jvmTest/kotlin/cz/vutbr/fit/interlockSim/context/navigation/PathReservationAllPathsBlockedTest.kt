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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Reservation attempts against blocks already owned by another train (Issue #1165 split). */
@Tag("integration-test")
class PathReservationAllPathsBlockedTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath returns AllPathsBlocked when path is RESERVED by different train`() {
		// Arrange - reserve the path for train1
		val result1 = service.reservePath("train1", inOut1, inOut2)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Act - try to reserve same path for train2
		val result2 = service.reservePath("train2", inOut1, inOut2)

		// Assert
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
	}

	@Test
	fun `reservePath rejects a reverse route through identical blocks while the forward route is held`() {
		// Arrange - reserve the forward path for train1
		val result1 = service.reservePath("train1", inOut1, inOut2)
		assertThat(result1).isInstanceOf<PathReservationService.ReservationResult.Success>()

		// Act - train2 attempts the same physical blocks in the opposite direction
		val result2 = service.reservePath("train2", inOut2, inOut1)

		// Assert - blocks are still exclusively owned by train1, regardless of direction
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat(service.getReservedBlocks("train2")).isEmpty()
	}

	@Test
	fun `isPathAvailable returns false when path is blocked`() {
		// Arrange - reserve the path
		service.reservePath("train1", inOut1, inOut2)

		// Act
		val available = service.isPathAvailable(inOut1, inOut2)

		// Assert
		assertThat(available).isFalse()
	}
}
