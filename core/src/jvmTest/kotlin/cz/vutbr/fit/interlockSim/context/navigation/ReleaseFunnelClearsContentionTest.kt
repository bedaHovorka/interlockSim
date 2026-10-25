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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Positive pin (Issue #1203) that the whole-route release funnel clears the REAL
 * [ReservationContentionTracker] behind the service, through its `clearBlockedTracking` callback.
 *
 * `RouteReleaserTest` pins that callback only against recording fakes, and the only service-API
 * observable, [PathReservationService.flushUnresolvedConflicts], is destructive and drops ownerless
 * events -- a no-op callback would pass through it unnoticed. These tests read the tracker
 * non-destructively through [ReservationContentionTracker.blockedTrainIds] instead, so a mis-wired
 * or no-op callback fails them.
 *
 * Fixture (vyhybna.xml): [TRAIN_A] holds the route `zA -> doB1` (upper track, eastbound), which
 * includes the `zA`-`vA` block every westbound route to `A` must cross. [TRAIN_B] holds the
 * `B -> zB` block, then asks for `B -> A` and is blocked by [TRAIN_A] -- so it is contending while it
 * holds a block, the only state in which a release reaches the funnel's clear. (A blocked train
 * that holds nothing keeps its contention through a release; see
 * [PathReservationContentionTrackingTest].) `unregister` is not covered: it does not clear.
 */
@Tag("integration-test")
class ReleaseFunnelClearsContentionTest : PathReservationServiceTestBase() {
	private lateinit var inOutA: DynamicPathSeparator
	private lateinit var inOutB: DynamicPathSeparator

	private val tracker: ReservationContentionTracker
		get() = (service as DefaultPathReservationService).contention

	@BeforeEach
	fun resolveEndpoints() {
		inOutA = inOutNamed("A")
		inOutB = inOutNamed("B")
	}

	/** Train A holds the upper track; train B holds its `B` stub and is blocked by A. */
	private fun contendBehindTrainA() {
		assertReservationSuccess(service.reservePath(TRAIN_A, findSemaphoreByName("zA"), findSemaphoreByName("doB1")))
		assertReservationSuccess(service.reservePath(TRAIN_B, inOutB, findSemaphoreByName("zB")))
		assertThat(service.reservePath(TRAIN_B, inOutB, inOutA))
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		assertThat(tracker.blockedTrainIds()).isEqualTo(setOf(TRAIN_B))
	}

	@Test
	fun `releasePath clears the real tracker entry of the released train`() {
		contendBehindTrainA()
		val heldByA = registry.getBlocks(TRAIN_A)

		assertThat(service.releasePath(TRAIN_B)).isNotEmpty()

		assertThat(tracker.blockedTrainIds()).isEmpty()
		assertThat(registry.getBlocks(TRAIN_A)).isEqualTo(heldByA)
	}

	@Test
	fun `releasePathDetailed clears the real tracker entry of the released train`() {
		contendBehindTrainA()
		val heldByA = registry.getBlocks(TRAIN_A)
		// Without a proceed aspect at B's entry the detailed release has no approach-lock reason to
		// keep B's stub, so it reaches the whole-route funnel (the partial branch never clears).
		val stub = registry.getBlocks(TRAIN_B).single()
		(stub.reservedFrom as DynamicInOut).inSemaphore.signal = Signal.STOP

		assertThat(service.releasePathDetailed(TRAIN_B).released).isNotEmpty()

		assertThat(tracker.blockedTrainIds()).isEmpty()
		assertThat(registry.getBlocks(TRAIN_A)).isEqualTo(heldByA)
	}

	private companion object {
		const val TRAIN_A = "trainA"
		const val TRAIN_B = "trainB"
	}
}
