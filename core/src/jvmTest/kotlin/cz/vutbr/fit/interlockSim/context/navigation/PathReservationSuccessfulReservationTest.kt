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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.assertReservedBlocks
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Successful reservation on a fully free vyhybna.xml network (Issue #1165 split). */
@Tag("integration-test")
class PathReservationSuccessfulReservationTest : PathReservationServiceTestBase() {
	@Test
	fun `reservePath succeeds when all blocks are FREE`() {
		// Act
		val result = service.reservePath("train1", inOut1, inOut2)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val success = result as PathReservationService.ReservationResult.Success
		// vyhybna.xml has 7 unique blocks in the path from InOut1 to InOut2
		assertThat(success.reservedBlocks).hasSize(7)

		// Verify all blocks are DynamicTrackBlock instances (not separators) with correct ownership
		success.reservedBlocks.forEach { block ->
			assertThat(block).isInstanceOf(DynamicTrackBlock::class)
		}
		assertReservedBlocks(success.reservedBlocks, "train1", inOut1)
	}

	@Test
	fun `reservePath registers ownership in registry`() {
		// Act
		val result = service.reservePath("train1", inOut1, inOut2)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val reservedBlocks = service.getReservedBlocks("train1")
		assertThat(reservedBlocks.size).isEqualTo(7)
	}

	@Test
	fun `isPathAvailable returns true for free path`() {
		// Act
		val available = service.isPathAvailable(inOut1, inOut2)

		// Assert
		assertThat(available).isTrue()
	}
}
