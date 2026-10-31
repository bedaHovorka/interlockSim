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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.assertReservedBlocks
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Reservation of the short route up to the next semaphore (Issue #1165 split). */
@Tag("integration-test")
class PathReservationToNextSemaphoreTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePathToAnyNextSemaphore succeeds when path to semaphore is FREE`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Act
		val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

		// Assert
		val success = assertReservationSuccess(result)
		assertThat(success.reservedBlocks).isNotNull()
		// vyhybna.xml: inOut1 (11,8) -> first semaphore at (14,8) = 1 block
		assertThat(success.reservedBlocks.size).isEqualTo(1)

		// Verify all blocks are RESERVED
		assertReservedBlocks(success.reservedBlocks, "train1", inOut1)
	}

	@Test
	fun `reservePathToAnyNextSemaphore registers ownership in registry`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Act
		val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val reservedBlocks = service.getReservedBlocks("train1")
		assertThat(reservedBlocks.size).isEqualTo(1) // Path to first semaphore
	}

	@Test
	fun `reservePathToAnyNextSemaphore returns AllPathsBlocked when blocks are occupied`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Reserve the path for another train to block it
		service.reservePath("other-train", inOut1, inOut2)

		// Act - try to reserve to next semaphore for train1
		val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)

		// Assert - should be blocked because first block is already reserved
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
	}

	@Test
	fun `isPathToAnyNextSemaphoreAvailable returns true when path is FREE`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)

		// Act
		val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

		// Assert
		assertThat(available).isTrue()
	}

	@Test
	fun `isPathToAnyNextSemaphoreAvailable returns false when next is null`() {
		// Act
		val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, null)

		// Assert
		assertThat(available).isFalse()
	}

	@Test
	fun `isPathToAnyNextSemaphoreAvailable returns false when path is BLOCKED`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Reserve path for another train to block it
		service.reservePath("other-train", inOut1, inOut2)

		// Act
		val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

		// Assert - first block is reserved, so path to first semaphore is not available
		assertThat(available).isFalse()
	}

	@Test
	fun `isPathToAnyNextSemaphoreAvailable returns false when path is RESERVED by another train`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Reserve path for train2
		service.reservePath("train2", inOut1, inOut2)

		// Act - check availability (doesn't reserve)
		val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

		// Assert
		assertThat(available).isFalse()
	}

	@Test
	fun `consistency between isPathToAnyNextSemaphoreAvailable and reservePathToAnyNextSemaphore`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Act - check availability
		val available = service.isPathToAnyNextSemaphoreAvailable(inOut1, next)

		// Assert - if available, reservation should succeed
		if (available) {
			val result = service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		}
	}

	@Test
	fun `reservePathToAnyNextSemaphore works after releasePath`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Reserve and release
		service.reservePathToAnyNextSemaphore("train1", inOut1, next!!)
		service.releasePath("train1")

		// Act - reserve again
		val result = service.reservePathToAnyNextSemaphore("train2", inOut1, next)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val blocks = service.getReservedBlocks("train2")
		assertThat(blocks.size).isEqualTo(1) // Path to first semaphore
	}

	@Test
	fun `isPathToAnyNextSemaphoreAvailable becomes true after releasePath`() {
		// Arrange
		val next = navigator.getNextTrackSection(inOut1, null)
		assertThat(next).isNotNull()

		// Reserve path
		service.reservePath("train1", inOut1, inOut2)

		// Verify path is not available
		assertThat(service.isPathToAnyNextSemaphoreAvailable(inOut1, next)).isFalse()

		// Act - release path
		service.releasePath("train1")

		// Assert - path should be available now
		assertThat(service.isPathToAnyNextSemaphoreAvailable(inOut1, next)).isTrue()
	}
}
