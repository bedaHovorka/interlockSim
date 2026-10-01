/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Interlocking Facade Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Issue #974: [InterlockingFacade.releaseRoute] runs the approach-locked release, so it keeps an
 * occupied or approach-locked block reserved, exactly as the production port's
 * `releaseRouteDetailed` does. Runs against the real [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService]
 * on the `A -- Sem -- B` linear network, where the tail past the semaphore can be freed.
 */
@Tag("integration-test")
@DisplayName("Issue #974 — the facade's releaseRoute uses the approach lock")
class InterlockingFacadeApproachLockReleaseTest : KoinTestBase() {
	@Test
	fun `releaseRoute keeps an occupied block reserved and frees the tail (Issue 974)`() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val context = network.context.tracked()
		val registry = context.scope.get<PathReservationRegistry>()
		val service = context.getRoutingServices().getPathReservationService()
		val inOuts = context.getInOuts().toList()
		val trainId = "train1"
		val blocks =
			assertReservationSuccess(
				service.reservePath(
					trainId,
					inOuts.single { it.name == "A" },
					inOuts.single { it.name == "B" }
				)
			).reservedBlocks
		val head = blocks.first()
		val tail = blocks.last()
		head.enter(FakeTrackOccupant(trainId))
		network.semaphore.signal = Signal.STOP

		DefaultInterlockingFacade(context, registry).releaseRoute(trainId, SignalId("any"))

		assertThat(head.getState(), "the occupied head stays OCCUPIED").isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(registry.getOwner(head), "the occupied head stays registered").isEqualTo(trainId)
		assertThat(registry.getOwner(tail), "the tail is freed").isEqualTo(null)
		assertThat(registry.getBlocks(trainId).toSet(), "only the head is still held").isEqualTo(setOf(head))
	}

	@Test
	fun `releaseRoute defers the same block the service's detailed release defers (Issue 974)`() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val context = network.context.tracked()
		val registry = context.scope.get<PathReservationRegistry>()
		val service = context.getRoutingServices().getPathReservationService()
		val inOuts = context.getInOuts().toList()
		val trainId = "train1"
		val blocks =
			assertReservationSuccess(
				service.reservePath(
					trainId,
					inOuts.single { it.name == "A" },
					inOuts.single { it.name == "B" }
				)
			).reservedBlocks
		val head = blocks.first()

		// The start signal is lit, the intermediate one is STOP: the train waiting at A is
		// committed to the head, which the approach lock keeps; the tail is freed.
		DefaultInterlockingFacade(context, registry).releaseRoute(trainId, SignalId("any"))

		assertThat(registry.getBlocks(trainId).toSet(), "the approach-locked head stays registered")
			.isEqualTo(setOf(head))
		// A repeat through the service finds nothing more to free: the facade already did the
		// same partial release the port performs.
		val repeat = service.releasePathDetailed(trainId)
		assertThat(repeat.released, "nothing left to free").isEmpty()
		assertThat(repeat.deferred.toSet(), "the head is still deferred").isEqualTo(setOf(head))
	}
}
