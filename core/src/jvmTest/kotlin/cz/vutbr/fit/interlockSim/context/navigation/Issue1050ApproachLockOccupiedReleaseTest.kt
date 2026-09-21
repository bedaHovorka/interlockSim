/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Navigation Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType.BLOCK_RELEASED
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyListener
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.FakeTrackOccupant
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.LinearSemaphoreTopology
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Issue #1050 review round: the detailed release must keep an OCCUPIED block on every call.
 *
 * [PathReservationService.releasePathDetailed] approach-locks a RESERVED, unoccupied block a
 * train may be committed to. But once that train books the block it becomes OCCUPIED, and the
 * approach-lock predicate no longer lists it. A repeat release that then falls through to the
 * wholesale [PathReservationService.releasePath] unregisters the still-occupied block and
 * emits `BlockReleased` for it -- the exact registry-vs-physical divergence the release exists
 * to prevent, on the very path its own outcome messages instruct the dispatcher to take
 * ("repeat cancel_route").
 *
 * The `A —100 m— Sem —100 m— B` network of [TestTopologies.linearPathWithSemaphoreNetwork] is
 * used instead of vyhybna because its boundary past the first block is a semaphore, a valid
 * PathInfo end: the tail CAN be trimmed, so the release actually frees something and the
 * occupied-kept rule is observable (vyhybna's switch past the first block keeps the whole route).
 */
@Tag("integration-test")
@DisplayName("Issue #1050 review — the detailed release keeps an occupied block on every call")
class Issue1050ApproachLockOccupiedReleaseTest : KoinTestBase() {
	@Test
	fun `an occupied block stays registered and reserved while the detailed release frees the tail (Issue 1050)`() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val context = network.context.tracked()
		val reservation = reserve(network, context)
		val releasedEvents = mutableListOf<BlockOccupancyEvent>()
		context.addBlockOccupancyListener(BlockOccupancyListener { event -> if (event.type == BLOCK_RELEASED) releasedEvents += event })

		val head = reservation.blocks.first()
		head.enter(FakeTrackOccupant(reservation.trainId))
		// The reservation lit the intermediate semaphore; force it STOP so the tail block is
		// not approach-locked and can be freed while the occupied head is kept.
		network.semaphore.signal = Signal.STOP

		val release = reservation.service.releasePathDetailed(reservation.trainId)

		assertThat(release.deferred.toSet(), "deferred blocks").isEqualTo(setOf(head))
		assertThat(release.released.toSet(), "released blocks").isEqualTo((reservation.blocks - head).toSet())
		assertThat(head.getState(), "the occupied head stays OCCUPIED").isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(reservation.registry.getOwner(head), "the occupied head stays registered").isEqualTo(reservation.trainId)
		assertThat(releasedEvents.map { it.block }, "no BlockReleased for the occupied head").doesNotContain(head)
		val pathInfo = reservation.registry.getPathInfo(reservation.trainId)
		assertThat(pathInfo, "the PathInfo survives the partial release").isNotNull()
		assertThat(pathInfo!!.target, "the PathInfo is trimmed to the semaphore").isEqualTo(network.semaphore)
	}

	@Test
	fun `a repeat release after the train booked the deferred head keeps it occupied and registered (Issue 1050)`() {
		val network = TestTopologies.linearPathWithSemaphoreNetwork()
		val context = network.context.tracked()
		val reservation = reserve(network, context)
		val releasedEvents = mutableListOf<BlockOccupancyEvent>()
		context.addBlockOccupancyListener(BlockOccupancyListener { event -> if (event.type == BLOCK_RELEASED) releasedEvents += event })

		// The reservation lit the start signal (the intermediate semaphore stays STOP), so a
		// train waiting at A is committed to the head block only: the release defers the head
		// and frees the tail across the valid semaphore boundary.
		val head = reservation.blocks.first()
		val tail = reservation.blocks.last()
		val first = reservation.service.releasePathDetailed(reservation.trainId)
		assertThat(first.released.toSet(), "the first release frees the tail").isEqualTo(setOf(tail))
		assertThat(first.deferred.toSet(), "the first release defers the head").isEqualTo(setOf(head))

		// The committed train books the deferred head -- the scenario the outcome messages tell
		// the dispatcher to expect -- and repeats the release. The head is now OCCUPIED, so it
		// is kept by the occupied rule instead of falling through to the wholesale release.
		head.enter(FakeTrackOccupant(reservation.trainId))
		val repeat = reservation.service.releasePathDetailed(reservation.trainId)

		assertThat(repeat.released, "the repeat frees nothing").isEmpty()
		assertThat(repeat.deferred.toSet(), "the repeat still defers the occupied head").isEqualTo(setOf(head))
		assertThat(head.getState(), "the booked head stays OCCUPIED").isEqualTo(TrackFacility.State.OCCUPIED)
		assertThat(reservation.registry.getOwner(head), "the booked head stays registered").isEqualTo(reservation.trainId)

		// A third repeat changes nothing: the head stays occupied and registered, the release
		// stays partial, and no BlockReleased is ever emitted for it.
		val third = reservation.service.releasePathDetailed(reservation.trainId)
		assertThat(third.released, "the third repeat frees nothing").isEmpty()
		assertThat(third.deferred.toSet(), "the third repeat still defers the head").isEqualTo(setOf(head))
		assertThat(reservation.registry.getOwner(head), "the head is still registered").isEqualTo(reservation.trainId)
		assertThat(
			releasedEvents.none { it.block === head },
			"no BlockReleased was ever emitted for the occupied block"
		).isTrue()
	}

	/**
	 * Reserves `A → B` on the linear network and hands back the reservation's blocks with the
	 * service and registry that produced them.
	 */
	private fun reserve(
		network: LinearSemaphoreTopology,
		context: DefaultSimulationContext
	): Reservation {
		val inOuts = context.getInOuts().toList()
		val a = inOuts.single { it.name == "A" }
		val b = inOuts.single { it.name == "B" }
		val service = context.getRoutingServices().getPathReservationService()
		val registry = context.scope.get<PathReservationRegistry>()
		val trainId = "train1"
		val blocks = assertReservationSuccess(service.reservePath(trainId, a, b)).reservedBlocks
		require(blocks.size >= 2) { "Test requires a route of at least two blocks" }
		require(blocks.first().reservedFrom == a) { "The first reserved block must start at A" }
		return Reservation(trainId, blocks, service, registry)
	}

	/** Everything [reserve] produces for a test body. */
	private data class Reservation(
		val trainId: String,
		val blocks: List<DynamicTrackBlock>,
		val service: PathReservationService,
		val registry: PathReservationRegistry
	)
}