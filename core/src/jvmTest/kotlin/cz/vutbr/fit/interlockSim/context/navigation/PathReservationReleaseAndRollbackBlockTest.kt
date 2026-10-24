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
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.RecordingBlockOccupancyListener
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.cellsOfType
import cz.vutbr.fit.interlockSim.testutil.isNotEmpty
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Issue #961: the one public committed-release step ([PathReservationService.releaseBlock]) and the
 * one rollback step ([BlockRollbackStep.rollbackBlock]). A committed release publishes the
 * release event; a rollback never does, because the block it undoes was never announced as reserved.
 */
@Tag("integration-test")
class PathReservationReleaseAndRollbackBlockTest : PathReservationServiceTestBase() {
	/** The rollback step is internal to `:core`, so it is reached through the implementation type. */
	private fun rollbackStep(): DefaultPathReservationService = service as DefaultPathReservationService

	private fun releasedEventsFor(
		listener: RecordingBlockOccupancyListener,
		block: DynamicTrackBlock
	): List<BlockOccupancyEvent> =
		listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED && it.block == block }

	@Test
	fun `releaseBlock frees a reserved block and publishes exactly one BLOCK_RELEASED`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.RESERVED)
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		assertThat(service.releaseBlock("train1", firstBlock)).isTrue()

		assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(firstBlock.reservedFrom).isNull()
		assertThat(registry.getOwner(firstBlock)).isNull()
		assertThat(releasedEventsFor(listener, firstBlock)).hasSize(1)
		assertThat(releasedEventsFor(listener, firstBlock).single().trainId).isEqualTo("train1")
	}

	@Test
	fun `releaseBlock reclaims a switch lock that no held block protects any more`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val switches = registry.getSwitches("train1")
		assertThat(switches, "switches of the reserved route").isNotEmpty()

		success.reservedBlocks.forEach { block ->
			assertThat(service.releaseBlock("train1", block), "release of $block").isTrue()
		}

		assertThat(registry.getBlocks("train1")).isEmpty()
		assertThat(registry.getSwitches("train1"), "switches still owned").isEmpty()
		switches.forEach { switch ->
			assertThat(switch.locked, "lock of ${switch.name}").isFalse()
		}
	}

	@Test
	fun `releaseBlock refuses a block the train does not own and changes nothing`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		assertThat(service.releaseBlock("otherTrain", firstBlock)).isFalse()

		assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(firstBlock.trainName).isEqualTo("train1")
		assertThat(registry.getOwner(firstBlock)).isEqualTo("train1")
		assertThat(listener.events).isEmpty()
	}

	@Test
	fun `rollbackBlock frees and unregisters a reserved block without a release event`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		assertThat(rollbackStep().rollbackBlock("train1", firstBlock)).isTrue()

		assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(registry.getOwner(firstBlock)).isNull()
		assertThat(releasedEventsFor(listener, firstBlock)).isEmpty()
	}

	/**
	 * PR #1115 review (ruling 4): [PathReservationService.releaseBlock] cancels the path setup before its
	 * signal reset, so for an un-travelled block whose `reservedFrom` is a far route START -- a semaphore
	 * that is not one of the block's ends -- it leaves that START lit. A caller that first calls
	 * [PathReservationService.resetSemaphoresForReleasedBlocks], as `RegistryPartialRouteReleaser`
	 * does, drives it to STOP. Pins both halves of the documented contract.
	 */
	@ParameterizedTest(name = "reset signals first = {0}")
	@ValueSource(booleans = [false, true])
	fun `releaseBlock resets a far route START only after the caller's own signal reset`(resetFirst: Boolean) {
		val zA = simulationContext.cellsOfType<DynamicRailSemaphore>().single { it.name == "zA" }
		val inOutB = simulationContext.getInOuts().single { it.name == "B" }
		assertReservationSuccess(service.reservePath("t1", zA, inOutB))
		val farBlock =
			checkNotNull(service.getReservedBlocks("t1").firstOrNull { it.reservedFrom == zA && zA !in it.ends() }) {
				"the zA->B route must have a block that zA governs from afar"
			}
		assertThat(zA.signal.isAllowing(), "zA cleared for t1").isTrue()

		if (resetFirst) service.resetSemaphoresForReleasedBlocks("t1", listOf(farBlock))
		assertThat(service.releaseBlock("t1", farBlock)).isTrue()

		assertThat(zA.signal.isAllowing(), "zA still cleared").isEqualTo(!resetFirst)
	}

	/**
	 * PR #1115 review, settled by Issue #1103: the stale-switch reclaim runs inside
	 * [PathReservationRegistry.unregisterBlock], BEFORE the release event, so a throwing release
	 * listener propagates its failure from every release but can no longer skip the reclaim.
	 */
	@Test
	fun `a throwing release listener cannot skip the stale switch reclaim`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val switches = registry.getSwitches("train1")
		assertThat(switches, "switches of the reserved route").isNotEmpty()
		val throwing =
			BlockOccupancyListener { event ->
				if (event.type == BlockOccupancyEventType.BLOCK_RELEASED) error("listener failure")
			}
		registry.addBlockOccupancyListener(throwing)

		val failures =
			success.reservedBlocks.map { block -> runCatching { service.releaseBlock("train1", block) }.exceptionOrNull() }
		registry.removeBlockOccupancyListener(throwing)

		assertThat(failures.map { it?.message }, "every release reported the listener")
			.isEqualTo(success.reservedBlocks.map { "listener failure" })
		assertThat(registry.getBlocks("train1"), "blocks still owned").isEmpty()
		assertThat(registry.getSwitches("train1"), "switches still owned").isEmpty()
		switches.forEach { switch ->
			assertThat(switch.locked, "lock of ${switch.name}").isFalse()
		}
	}

	/**
	 * PR #1115 review: a rollback with the wrong train must not cancel the owner's path setup. Without
	 * the ownership guard the block went FREE under train1's route while the registry, which refuses a
	 * foreign unregister, kept it registered to train1.
	 */
	@Test
	fun `rollbackBlock refuses a block the train does not own and changes nothing`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val firstBlock = success.reservedBlocks.first()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		assertThat(rollbackStep().rollbackBlock("otherTrain", firstBlock)).isFalse()

		assertThat(firstBlock.getState()).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(firstBlock.trainName).isEqualTo("train1")
		assertThat(firstBlock.reservedFrom).isNotNull()
		assertThat(registry.getOwner(firstBlock)).isEqualTo("train1")
		assertThat(listener.events).isEmpty()
	}

	/**
	 * The candidate rollback leaves no registry entry, no stale switch ownership and no release event.
	 * The switch list passed here is empty on purpose: the route's switches are then released only by
	 * the per-block reclaim, which the rollback used to skip (it called the registry directly).
	 */
	@Test
	fun `candidate rollback leaves no block entry and no stale switch ownership and publishes no release`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		val switches = registry.getSwitches("train1")
		assertThat(switches, "switches of the reserved route").isNotEmpty()
		val listener = RecordingBlockOccupancyListener()
		environment.addBlockOccupancyListener(listener)

		(service as DefaultPathReservationService).rollbackUnconfigurableCandidate(
			trainId = "train1",
			forwardBlocks = success.reservedBlocks,
			switches = emptyList(),
			priorSwitches = emptySet()
		)

		success.reservedBlocks.forEach { block ->
			assertThat(block.getState(), "state of $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of $block").isNull()
		}
		assertThat(registry.getSwitches("train1"), "stale switch ownership").isEmpty()
		switches.forEach { switch ->
			assertThat(switch.locked, "lock of ${switch.name}").isFalse()
		}
		assertThat(listener.events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED }).isEmpty()
	}

	@Test
	fun `candidate rollback removes the reservation timestamps it recorded (Issue 975)`() {
		val success = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2))
		success.reservedBlocks.forEach { block ->
			assertThat(registry.getRegisteredAtSimTime(block), "timestamp of $block after reserve").isNotNull()
		}

		(service as DefaultPathReservationService).rollbackUnconfigurableCandidate(
			trainId = "train1",
			forwardBlocks = success.reservedBlocks,
			switches = emptyList(),
			priorSwitches = emptySet()
		)

		success.reservedBlocks.forEach { block ->
			assertThat(registry.getRegisteredAtSimTime(block), "timestamp of $block after rollback").isNull()
		}
	}
}
