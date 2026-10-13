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
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Test

/**
 * Pins the route release and rollback behaviour that Issue #1168 (SP12.2) moves out of
 * [DefaultPathReservationService] into [RouteReleaser]: the whole-route releases ([PathReservationService.releasePath],
 * [PathReservationService.unregister]), the per-block funnel ([PathReservationService.dropFreedBlock]) and the
 * ownership guards of [PathReservationService.releaseBlock] / [BlockRollbackStep.rollbackBlock].
 *
 * The first group runs through the service API and was green before the move; the second group
 * drives the extracted unit directly, with recording callbacks in place of the service's signal
 * ledger and blocked-contention tracking. Untagged: a unit test of one internal class, using
 * `reservePath` only to set up a reserved route on vyhybna.xml.
 */
class RouteReleaserTest : PathReservationServiceTestBase() {
	private val calls = mutableListOf<String>()

	private fun recordingReleaser(): RouteReleaser =
		RouteReleaser(
			registry = registry,
			resetSemaphoresForReleasedBlocks = { trainId, blocks -> calls += "resetBlocks($trainId,${blocks.size})" },
			resetClearedSemaphores = { trainId ->
				val stillHeld = registry.getBlocks(trainId).size
				calls += "resetCleared($trainId,held=$stillHeld)"
			},
			clearBlockedTracking = { trainId -> calls += "clearBlocked($trainId)" }
		)

	private fun releasedEvents(listener: RecordingBlockOccupancyListener) =
		listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }

	@Test
	fun `releasePath frees every block, unlocks the switches, resets the start signal and announces each block once`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		val switches = registry.getSwitches("train1")
		val entry = (inOut1 as DynamicInOut).inSemaphore
		assertThat(switches, "switches of the route").isNotEmpty()
		assertThat(entry.signal.isAllowing(), "entry cleared by the reservation").isTrue()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		val released = service.releasePath("train1")

		assertThat(released).isEqualTo(blocks)
		blocks.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of $block").isNull()
		}
		switches.forEach { switch -> assertThat(switch.locked, "lock of ${switch.name}").isFalse() }
		assertThat(registry.getSwitches("train1")).isEmpty()
		assertThat(entry.signal.isAllowing(), "entry after release").isFalse()
		assertThat(service.hasClearedSignals("train1")).isFalse()
		assertThat(releasedEvents(listener).map { it.block }).isEqualTo(blocks)
		releasedEvents(listener).forEach { event -> assertThat(event.trainId).isEqualTo("train1") }
	}

	@Test
	fun `a second releasePath finds nothing and announces nothing`() {
		assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		service.releasePath("train1")
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		assertThat(service.releasePath("train1")).isEmpty()
		assertThat(service.releasePathDetailed("train1").released).isEmpty()
		assertThat(listener.events).isEmpty()
	}

	@Test
	fun `unregister frees the route, unlocks its switches and announces each block once`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		val switches = registry.getSwitches("train1")
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		val released = service.unregister("train1")

		assertThat(released.toSet()).isEqualTo(blocks.toSet())
		blocks.forEach { block -> assertThat(block.getState()).isEqualTo(TrackFacility.State.FREE) }
		switches.forEach { switch -> assertThat(switch.locked, "lock of ${switch.name}").isFalse() }
		assertThat(service.hasClearedSignals("train1")).isFalse()
		assertThat(releasedEvents(listener)).hasSize(blocks.size)
	}

	@Test
	fun `dropFreedBlock refuses a block that is still reserved and announces nothing`() {
		val block = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks.first()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		assertThat(service.dropFreedBlock("train1", block)).isFalse()

		assertThat(registry.getOwner(block)).isEqualTo("train1")
		assertThat(listener.events).isEmpty()
	}

	@Test
	fun `unregisterBlock leaves an occupied block registered and its signals alone`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		blocks.first().enter(FakeTrackOccupant("train1"))

		assertThat(service.unregisterBlock("train1", blocks.first())).isFalse()

		assertThat(registry.getOwner(blocks.first())).isEqualTo("train1")
		assertThat(service.hasClearedSignals("train1")).isTrue()
	}

	@Test
	fun `rollbackBlock refuses a block owned by another train`() {
		val block = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks.first()

		assertThat((service as DefaultPathReservationService).rollbackBlock("train2", block)).isFalse()

		assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(registry.getOwner(block)).isEqualTo("train1")
	}

	// ── The extracted unit, driven directly ──

	@Test
	fun `release resets the signals while the blocks are still held and forgets the contention last`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks

		val released = recordingReleaser().releasePath("train1")

		assertThat(released).isEqualTo(blocks)
		assertThat(calls).isEqualTo(
			listOf("resetBlocks(train1,0)", "resetCleared(train1,held=${blocks.size})", "clearBlocked(train1)")
		)
	}

	@Test
	fun `unregister resets cleared signals while the blocks are still held and never touches contention tracking`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks

		recordingReleaser().unregister("train1")

		assertThat(calls).isEqualTo(listOf("resetCleared(train1,held=${blocks.size})"))
	}

	@Test
	fun `unregisterBlock resets the released block's signals before it leaves the registry`() {
		val block = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks.first()
		block.cancelPathSetup(checkNotNull(block.reservedFrom))

		assertThat(recordingReleaser().unregisterBlock("train1", block)).isTrue()

		assertThat(calls).isEqualTo(listOf("resetBlocks(train1,1)"))
		assertThat(registry.getOwner(block)).isNull()
	}

	@Test
	fun `rollbackReservation cancels only blocks reserved from the given separator and leaves the registry alone`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		val releaser = recordingReleaser()

		releaser.rollbackReservation(inOut2, blocks)
		blocks.forEach { block -> assertThat(block.getState(), "after foreign rollback").isEqualTo(RESERVED) }

		releaser.rollbackReservation(inOut1, blocks)
		blocks.forEach { block ->
			assertThat(block.getState(), "after own rollback").isEqualTo(TrackFacility.State.FREE)
			// Physical only: the registry entry survives (the Issue #1025 divergence the WARN names).
			assertThat(registry.getOwner(block), "registry owner").isEqualTo("train1")
		}
		assertThat(calls).isEmpty()
	}

	@Test
	fun `emitBlockReleased publishes one RESERVED-to-FREE event for the train`() {
		val block =
			simulationContext
				.getGraph()
				.values()
				.filterIsInstance<DynamicTrackBlock>()
				.first()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		recordingReleaser().emitBlockReleased(block, "train1", 0.0)

		val event = listener.events.single()
		assertThat(event.type).isEqualTo(BlockOccupancyEventType.BLOCK_RELEASED)
		assertThat(event.trainId).isEqualTo("train1")
		assertThat(event.previousState).isEqualTo(RESERVED)
		assertThat(event.newState).isEqualTo(TrackFacility.State.FREE)
	}

	private companion object {
		val RESERVED = TrackFacility.State.RESERVED
	}
}
