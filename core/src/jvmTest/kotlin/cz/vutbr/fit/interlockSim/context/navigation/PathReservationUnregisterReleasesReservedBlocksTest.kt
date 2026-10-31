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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** `unregister` must leave no orphan RESERVED block behind (Issue #1165 split). */
@Tag("integration-test")
class PathReservationUnregisterReleasesReservedBlocksTest : PathReservationServiceTestBase() {
	@Test
	fun `unregister frees blocks still RESERVED from an un-entered route (no orphan RESERVED blocks)`() {
		// Given: a route reserved but never entered (no boundary crossed) -- the
		// bidirectional-reversal / abandoned-extension footprint, minimally reproduced.
		val result = service.reservePath("train1", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val reservedBlocks =
			assertReservationSuccess(result).reservedBlocks
		assertThat(reservedBlocks).isNotEmpty()
		reservedBlocks.forEach {
			assertThat(it.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(it.reservedFrom).isEqualTo(inOut1)
			assertThat(it.trainName).isEqualTo("train1")
		}

		// When: the train completes its journey via the production path (unregister).
		val released = service.unregister("train1")

		// Then: every reserved block must be physically FREE -- no orphan RESERVED blocks
		// left that no other train can ever reserve. Before the fix, registry.unregister
		// only removed ownership maps and the blocks stayed RESERVED (emitBlockReleased
		// falsely reported newState=FREE), so a second train's reservePath for the same
		// route was blocked forever (until the 60s OrphanReservationSweeper reclaimed).
		assertThat(released).hasSize(reservedBlocks.size)
		reservedBlocks.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.FREE)
			assertThat(block.reservedFrom).isNull()
			assertThat(block.trainName).isNull()
		}
		assertThat(service.getReservedBlocks("train1")).isEmpty()

		// And a second train can now reserve the same route (an orphan would have blocked it).
		val result2 = service.reservePath("train2", inOut1, inOut2)
		assertThat(result2).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}
}
