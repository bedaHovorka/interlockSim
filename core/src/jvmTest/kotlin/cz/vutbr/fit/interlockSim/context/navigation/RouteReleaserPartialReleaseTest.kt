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
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Test

/**
 * Switch protection of the partial release (`releaseAllBut`, Issue #1050) across its two
 * directions (PR #1194 review round):
 *
 * - a switch inside the KEPT head stays locked and owned, however the PathInfo trim cuts the
 *   freed tail off behind it;
 * - a switch inside the FREED tail is reclaimed (unlocked, unowned) by the per-block registry
 *   reclaim, however the head keeps running.
 *
 * `vyhybna.xml` cannot pin either direction: its trimmable cut sits next to an InOut, so no
 * keep head spans a switch, and every keep head that does span `vyhybna`'s switches ends at one
 * (the trim refuses, so the release defers wholesale instead of freeing anything). The
 * `two-switch-line.xml` fixture places one switch inside each direction; the straight line and
 * the uniform 24.0 m/s keep the expected lock states manually traceable. Both pins run through
 * the production entry (`service.releasePathDetailed`, the `NetworkActuatorPort` release).
 */
class RouteReleaserPartialReleaseTest : PathReservationServiceTestBase() {
	override fun loadTestContext(): DefaultSimulationContext =
		TestFixtures.loadTwoSwitchLineSimulationContext(simulationContextFactory, editingContextFactory)

	@Test
	fun `a partial release keeps the head's switch locked and reclaims the tail's one`() {
		val blocks = reserveWithSpannedHead()
		val headSwitch = findSwitchByName("swX")
		val tailSwitch = findSwitchByName("swY")

		val release = service.releasePathDetailed("train1")

		assertThat(headSwitch.locked, "the switch inside the kept head").isTrue()
		assertThat(registry.getSwitches("train1"), "switches owned after the partial release").contains(headSwitch)
		assertThat(tailSwitch.locked, "the switch inside the freed tail").isFalse()
		assertThat(release.released, "the freed tail").isEqualTo(blocks.drop(3))
		assertThat(release.deferred, "the kept head").isEqualTo(blocks.take(3))
		blocks.take(3).forEach { block ->
			assertThat(block.getState(), "state of kept $block").isEqualTo(TrackFacility.State.OCCUPIED)
			assertThat(registry.getOwner(block), "owner of kept $block").isEqualTo("train1")
		}
		blocks.drop(3).forEach { block ->
			assertThat(block.getState(), "state of freed $block").isEqualTo(TrackFacility.State.FREE)
			assertThat(registry.getOwner(block), "owner of freed $block").isNull()
		}
	}

	@Test
	fun `a partial release survives one block's failed release event and frees the rest of the tail`() {
		val blocks = reserveWithSpannedHead()
		// The funnel emits one BlockReleased per freed block; the first listener throw aborts
		// only its own dropFreedBlock (caught per block, after that block's registry cleanup),
		// so the block is freed physically but dropped from the release's result, while the
		// release continues with the rest of the tail.
		environment.addBlockOccupancyListener(ThrowingOnceReleaseListener())

		val release = service.releasePathDetailed("train1")

		val failedBlock = blocks[3]
		assertThat(release.released, "the blocks freed after the throw").isEqualTo(blocks.drop(4))
		assertThat(failedBlock.getState(), "state of the block whose event threw")
			.isEqualTo(TrackFacility.State.FREE)
		assertThat(registry.getOwner(failedBlock), "the registry cleanup still ran").isNull()
	}

	/**
	 * Reserves the route A→B on the two-switch line and makes train1 span its first three
	 * blocks, the shape both pins need: the kept head ends at the semaphore s3 (so it covers
	 * the switch swX from both sides) and the freed tail starts past s3, whose own switch swY
	 * goes stale. The pre-release s3 aspect is then returned to STOP: after the reservation it
	 * shows proceed, which would otherwise drag k4 in as approach-locked and leave nothing to
	 * free; with it at STOP the trim cuts at s3 (a semaphore, a valid PathInfo end) and frees
	 * k4..k6.
	 */
	private fun reserveWithSpannedHead(): List<DynamicTrackBlock> {
		val blocks =
			assertReservationSuccess(service.reservePath("train1", inOutNamed("A"), inOutNamed("B")))
				.reservedBlocks
		assertThat(blocks, "the two-switch line's route").hasSize(6)
		val occupant = FakeTrackOccupant("train1")
		blocks.take(3).forEach { it.enter(occupant) }
		findSemaphoreByName("s3").signal = Signal.STOP
		return blocks
	}

	/** A [BlockOccupancyListener] whose first BlockReleased notification throws, the later ones pass. */
	private class ThrowingOnceReleaseListener : BlockOccupancyListener {
		private var emitted = 0

		override fun onBlockOccupancyChanged(event: BlockOccupancyEvent) {
			emitted++
			if (emitted == 1) throw IllegalStateException("release listener failure (test)")
		}
	}
}
