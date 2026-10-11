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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The signals-before-blocks ordering of `unregisterBlock` (Issue #1165 split). */
@Tag("integration-test")
class PathReservationUnregisterBlockSignalOrderingTest : PathReservationServiceTestBase() {
	@Test
	fun `unregisterBlock resets the governing semaphore to STOP before publishing BlockReleased`() {
		// Given: a route reserved (governing start signal cleared to proceed), then the first
		// block driven through enter -> leave so it is FREE -- the production tail-clearance
		// state in which unregisterBlock's vacancy guard passes.
		val result = service.reservePath("train1", inOut1, inOut2)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val firstBlock =
			assertReservationSuccess(result).reservedBlocks.first()

		// The governing semaphores bounding the first block that reservePath cleared to proceed.
		val governedSemaphores =
			firstBlock.ends().filterIsInstance<DynamicRailSemaphore>().filter { it.signal.isAllowing() }
		assertThat(governedSemaphores).isNotEmpty()

		// Drive the first block to FREE the production way (enter then leave). enter/leave do
		// not reset signal aspects, so the governing semaphore is still proceed afterwards.
		val occupant =
			FakeTrackOccupant("test-occupant")
		firstBlock.enter(occupant)
		firstBlock.leave(occupant)
		assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.FREE)
		// Guard: the governing semaphore must still be proceed here, otherwise the
		// STOP-at-receipt assertion below would pass vacuously.
		assertThat(governedSemaphores.all { it.signal.isAllowing() }).isTrue()

		// A listener that captures the governing semaphore aspect at BlockReleased-receipt time.
		val aspectAtRelease = mutableListOf<Signal>()
		val listener =
			object : BlockOccupancyListener {
				override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
					if (event.type == BlockOccupancyEventType.BLOCK_RELEASED && event.block == firstBlock) {
						aspectAtRelease.addAll(governedSemaphores.map { it.signal })
					}
				}
			}
		environment.addBlockOccupancyListener(listener)

		// When: unregisterBlock publishes BlockReleased.
		assertThat(service.unregisterBlock("train1", firstBlock)).isTrue()

		// Then: at BlockReleased-receipt time the governing semaphore must already be STOP.
		// Before the swap, emitBlockReleased fired before resetSemaphoresForReleasedBlocks,
		// so the subscriber observed a FREE block whose authorising signal still showed proceed
		// -- the signals-before-blocks invariant violation (releasePath :897-900).
		assertThat(aspectAtRelease).isNotEmpty()
		aspectAtRelease.forEach { assertThat(it).isEqualTo(Signal.STOP) }
	}
}
