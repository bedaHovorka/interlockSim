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
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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
	fun `unregisterBlock resets the released block's signals and drops it from the registry`() {
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

	// ── Review round: fail-closed exception containment and switch protection of the partial release ──

	// The partial-release switch-protection pins (both directions) and the failed-release-event
	// containment live in RouteReleaserPartialReleaseTest: they need the two-switch-line.xml
	// fixture, on which the PathInfo trim cuts at a semaphore with one switch kept and one
	// freed. vyhybna.xml cannot run those scenarios (see that class's KDoc).

	@Test
	fun `releasePath tolerates a block whose path setup someone already cancelled`() {
		val blocks = reserveRouteWithStaleLastBlock()
		val preCancelled = blocks.last()

		val released = service.releasePath("train1")

		assertThat(released, "the whole route releases despite the stale block").isEqualTo(blocks)
		blocks.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of $block").isNull()
		}
	}

	@Test
	fun `releaseBlock drops a block whose reservation was already cancelled`() {
		val block = reserveRouteWithStaleLastBlock().last()

		assertThat(recordingReleaser().releaseBlock("train1", block)).isTrue()

		assertThat(registry.getOwner(block)).isNull()
		assertThat(calls).isEqualTo(listOf("resetBlocks(train1,1)"))
	}

	@Test
	fun `rollbackBlock drops an already-cancelled block without touching the signals`() {
		val block = reserveRouteWithStaleLastBlock().last()

		assertThat(recordingReleaser().rollbackBlock("train1", block)).isTrue()

		assertThat(registry.getOwner(block)).isNull()
		assertThat(calls).isEmpty()
	}

	@Test
	fun `rollbackBlocks swallows one block's failed cancellation and rolls the rest back`() {
		val separator = inOut1 as DynamicInOut
		val stuck =
			mockk<DynamicTrackBlock> {
				every { reservedFrom } returns separator
				every { cancelPathSetup(separator) } answers { throw IllegalStateException("forced") }
			}
		val healthy =
			mockk<DynamicTrackBlock> {
				every { reservedFrom } returns separator
				every { cancelPathSetup(separator) } just Runs
			}

		recordingReleaser().rollbackReservation(separator, listOf(stuck, healthy))

		verify(exactly = 1) { stuck.cancelPathSetup(separator) }
		verify(exactly = 1) { healthy.cancelPathSetup(separator) }
		assertThat(calls).isEmpty()
	}

	/**
	 * Issue #1202: the whole-route funnel contains a throwing legacy block-occupancy listener per
	 * block, so every freed block is still announced and the caller
	 * (`NetworkActuatorPort.releaseRoute`: `cancel_route` and the orphan sweeper) sees no
	 * exception. The per-block funnel keeps propagating instead — pinned elsewhere by
	 * `PathReservationReleaseAndRollbackBlockTest` and `Issue1076RegressionTest`.
	 */
	@Test
	fun `releasePath announces every block although the release listener throws on each`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		assertThat(blocks.size, "the route must span more than one block").isGreaterThan(1)
		val throwing = ThrowingReleaseListener()
		environment.addBlockOccupancyListener(throwing)

		val released = service.releasePath("train1")
		environment.removeBlockOccupancyListener(throwing)

		assertThat(released, "the whole route still releases").isEqualTo(blocks)
		assertThat(throwing.announced, "every freed block was still announced").isEqualTo(blocks)
		blocks.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of $block").isNull()
		}
	}

	/** Issue #1202: the funnel's contention clear runs after a throwing release listener. */
	@Test
	fun `a throwing release listener does not skip the funnel's contention clear`() {
		assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val throwing = ThrowingReleaseListener()
		environment.addBlockOccupancyListener(throwing)

		recordingReleaser().releasePath("train1")
		environment.removeBlockOccupancyListener(throwing)

		assertThat(calls.last(), "the train's contention is still forgotten").isEqualTo("clearBlocked(train1)")
	}

	/** Issue #1202: the other wholesale funnel, the train-completion path, is contained the same way. */
	@Test
	fun `unregister announces every block although the release listener throws on each`() {
		val blocks = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		val throwing = ThrowingReleaseListener()
		environment.addBlockOccupancyListener(throwing)

		val released = service.unregister("train1")
		environment.removeBlockOccupancyListener(throwing)

		assertThat(released.toSet(), "the whole route still releases").isEqualTo(blocks.toSet())
		assertThat(throwing.announced.toSet(), "every freed block was still announced").isEqualTo(blocks.toSet())
	}

	/**
	 * Reserves the vyhybna route for train1 and cancels its last block's path setup by hand,
	 * leaving that block FREE with a null `reservedFrom` still owned in the registry — the
	 * "someone released part of the route under us" state the three pins below exercise.
	 */
	private fun reserveRouteWithStaleLastBlock(): List<DynamicTrackBlock> =
		assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks.also { blocks ->
			blocks.last().cancelPathSetup(checkNotNull(blocks.last().reservedFrom))
		}

	private companion object {
		val RESERVED = TrackFacility.State.RESERVED
	}

	/** A [BlockOccupancyListener] that records every BlockReleased notification and then throws. */
	private class ThrowingReleaseListener : BlockOccupancyListener {
		val announced = mutableListOf<DynamicTrackBlock>()

		override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
			if (event.type != BlockOccupancyEventType.BLOCK_RELEASED) return
			announced += event.block
			throw IllegalStateException("release listener failure (test)")
		}
	}
}
