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
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Propagation and clearing of the owning train id on blocks (Issue #1165 split). */
@Tag("integration-test")
class PathReservationTrainIdPropagationTest : PathReservationServiceTestBase() {
	@Test
	fun `trainId is set on all blocks during reservation`() {
		// Act
		val result = service.reservePath("train123", inOut1, inOut2)

		// Assert
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val blocks = assertReservationSuccess(result).reservedBlocks
		blocks.forEach { block -> assertThat(block.trainName).isEqualTo("train123") }
	}

	@Test
	fun `trainId is cleared when train leaves block`() {
		// Arrange
		val result = service.reservePath("train1", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()

		val blocks = assertReservationSuccess(result).reservedBlocks
		val firstBlock = blocks.first()

		val occupant =
			FakeTrackOccupant("test-occupant")

		firstBlock.enter(occupant)

		// Act - leave block (OCCUPIED → FREE)
		firstBlock.leave(occupant)

		// Assert - trainId should be cleared
		assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(firstBlock.trainName).isNull()
	}
}
