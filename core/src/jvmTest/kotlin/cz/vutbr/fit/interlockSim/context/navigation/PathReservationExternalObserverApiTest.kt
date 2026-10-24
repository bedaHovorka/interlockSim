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
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The externally observable listener API of the reservation service (Issue #1165 split). */
@Tag("integration-test")
class PathReservationExternalObserverApiTest : PathReservationServiceTestBase() {
	@Test
	fun `environment addBlockOccupancyListener receives reserve and release events`() {
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		val result = service.reservePath("train1", inOut1, inOut2)
		val success = assertReservationSuccess(result)

		assertThat(listener.events).hasSize(success.reservedBlocks.size)
		listener.events.forEach { event ->
			assertThat(event.type).isEqualTo(BlockOccupancyEventType.BLOCK_RESERVED)
			assertThat(event.trainId).isEqualTo("train1")
			assertThat(event.previousState).isEqualTo(TrackFacility.State.FREE)
			assertThat(event.newState).isEqualTo(TrackFacility.State.RESERVED)
		}

		service.releasePath("train1")

		val reservedCount = listener.events.count { it.type == BlockOccupancyEventType.BLOCK_RESERVED }
		val releasedCount = listener.events.count { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
		assertThat(releasedCount).isEqualTo(reservedCount)
		listener.events
			.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
			.forEach { event ->
				assertThat(event.trainId).isEqualTo("train1")
				assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
				assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
			}
	}

	@Test
	fun `legacy listener receives BLOCK_RELEASED on unregister path`() {
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		val result = service.reservePath("train1", inOut1, inOut2)
		val success = assertReservationSuccess(result)
		val reservedCount = success.reservedBlocks.size

		// Clear reserve events so we can count releases in isolation
		listener.events.clear()

		service.unregister("train1")

		val releasedEvents = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
		assertThat(releasedEvents).hasSize(reservedCount)
		releasedEvents.forEach { event ->
			assertThat(event.trainId).isEqualTo("train1")
			assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
			assertThat(event.occupant).isNull()
		}
	}

	@Test
	fun `legacy listener receives BLOCK_RELEASED on unregisterBlock path`() {
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		val result = service.reservePath("train1", inOut1, inOut2)
		val success = assertReservationSuccess(result)
		val firstBlock = success.reservedBlocks.first()

		// unregisterBlock only releases FREE blocks (production path is after block.leave()).
		// Cancel the reservation manually so we can exercise the single-block release path.
		firstBlock.cancelPathSetup(inOut1)

		listener.events.clear()

		val released = service.unregisterBlock("train1", firstBlock)
		assertThat(released).isTrue()

		val releasedEvents = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
		assertThat(releasedEvents).hasSize(1)
		val event = releasedEvents.first()
		assertThat(event.block).isEqualTo(firstBlock)
		assertThat(event.trainId).isEqualTo("train1")
		assertThat(event.previousState).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
	}

	/**
	 * Issue #1067 (PR #1068 review): the partial tail release finishes a failed [PathReservationService.unregisterBlock]
	 * through [PathReservationService.dropFreedBlock]. It must publish the same release event, because
	 * the metrics and the conflict and collision detectors count reservations from it.
	 */
	@Test
	fun `dropFreedBlock unregisters a freed block and publishes one BLOCK_RELEASED`() {
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		firstBlock.cancelPathSetup(inOut1)
		listener.events.clear()

		assertThat(service.dropFreedBlock("train1", firstBlock)).isTrue()

		val releasedEvents = listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }
		assertThat(releasedEvents).hasSize(1)
		assertThat(releasedEvents.first().block).isEqualTo(firstBlock)
		assertThat(releasedEvents.first().trainId).isEqualTo("train1")
		assertThat(registry.getOwner(firstBlock)).isNull()
	}

	/** The signals that authorise entry into [block] and show proceed now. */
	private fun proceedSignalsInto(block: DynamicTrackBlock): List<DynamicRailSemaphore> =
		block
			.ends()
			.mapNotNull { end ->
				when (end) {
					is DynamicRailSemaphore -> end
					is DynamicInOut -> end.inSemaphore
					else -> null
				}
			}.filter { it.signal.isAllowing() }

	/**
	 * PR #1068 review: a refused [PathReservationService.unregisterBlock] must not reset the train's
	 * signals. The block is not released, so its boundaries are not known to be behind the head.
	 */
	@Test
	fun `unregisterBlock refuses a block that is not FREE and leaves its signals as they are`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		val proceed = proceedSignalsInto(firstBlock)
		assertThat(proceed, "proceed signals into the reserved block").isNotEmpty()

		assertThat(service.unregisterBlock("train1", firstBlock)).isFalse()

		assertThat(proceedSignalsInto(firstBlock), "proceed signals after the refused release").isEqualTo(proceed)
		assertThat(registry.getOwner(firstBlock)).isEqualTo("train1")
	}

	@Test
	fun `unregisterBlock refuses a block the train no longer owns and leaves its signals as they are`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		firstBlock.cancelPathSetup(inOut1)
		assertThat(service.dropFreedBlock("train1", firstBlock)).isTrue()
		val proceed = proceedSignalsInto(firstBlock)
		assertThat(proceed, "proceed signals into the dropped block").isNotEmpty()

		assertThat(service.unregisterBlock("train1", firstBlock)).isFalse()

		assertThat(proceedSignalsInto(firstBlock), "proceed signals after the refused release").isEqualTo(proceed)
	}

	@Test
	fun `dropFreedBlock refuses a block the train does not own and publishes nothing`() {
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		firstBlock.cancelPathSetup(inOut1)
		listener.events.clear()

		assertThat(service.dropFreedBlock("otherTrain", firstBlock)).isFalse()

		assertThat(listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }).isEmpty()
		assertThat(registry.getOwner(firstBlock)).isEqualTo("train1")
	}
}
