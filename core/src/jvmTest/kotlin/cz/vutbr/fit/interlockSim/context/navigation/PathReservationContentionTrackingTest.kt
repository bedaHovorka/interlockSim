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
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Pins the blocked-request tracking that [DefaultPathReservationService] exposes through
 * [PathReservationService.flushUnresolvedConflicts], end to end through the service (Issue #1167).
 * Written before the tracking moved into [ReservationContentionTracker], so it proves the move
 * changed nothing observable.
 */
@Tag("integration-test")
class PathReservationContentionTrackingTest : PathReservationServiceTestBase() {
	private fun blockTrain2BehindTrain1(): List<DynamicTrackBlock> {
		val held = assertReservationSuccess(service.reservePath("train1", inOut1, inOut2)).reservedBlocks
		assertThat(service.reservePath("train2", inOut1, inOut2))
			.isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
		return held
	}

	@Test
	fun `unresolved contention is flushed once with the owner and the end time`() {
		val held = blockTrain2BehindTrain1()

		val events = service.flushUnresolvedConflicts(42.0)

		assertThat(events).hasSize(1)
		val event = events.single()
		assertThat(event.trainId).isEqualTo("train2")
		assertThat(event.conflictingTrainId).isEqualTo("train1")
		assertThat(event.time).isEqualTo(42.0)
		assertThat(event.block).isIn(*held.toTypedArray())
		assertThat(service.flushUnresolvedConflicts(43.0)).isEmpty()
	}

	@Test
	fun `repeated blocked attempts on the same block are tracked once`() {
		blockTrain2BehindTrain1()
		repeat(3) { service.reservePath("train2", inOut1, inOut2) }

		assertThat(service.flushUnresolvedConflicts(1.0)).hasSize(1)
	}

	@Test
	fun `a later success of the blocked train forgets its contention`() {
		blockTrain2BehindTrain1()
		service.releasePath("train1")
		assertReservationSuccess(service.reservePath("train2", inOut1, inOut2))

		assertThat(service.flushUnresolvedConflicts(1.0)).isEmpty()
	}

	@Test
	fun `releasing a blocked train that holds no block keeps its contention`() {
		// releasePath forgets the contention only on its whole-route branch, which a train that
		// holds no block never reaches -- current behaviour, pinned so the move cannot change it.
		blockTrain2BehindTrain1()
		service.releasePath("train2")

		assertThat(service.flushUnresolvedConflicts(1.0)).hasSize(1)
	}

	@Test
	fun `contention on a block nobody owns any more is dropped at flush`() {
		blockTrain2BehindTrain1()
		service.releasePath("train1")

		assertThat(service.flushUnresolvedConflicts(1.0)).isEmpty()
	}

	@Test
	fun `flush without any contention returns nothing`() {
		assertThat(service.flushUnresolvedConflicts(1.0)).isEmpty()
	}

	// ── Read-only snapshot of open contentions (Issue #1305, Goal 6 SP7) ────────────────────

	@Test
	fun `a blocked train shows as one open contention owned by the holder`() {
		val held = blockTrain2BehindTrain1()
		repeat(2) { service.reservePath("train2", inOut1, inOut2) }

		val open = service.openContentions()

		assertThat(open.map { it.trainId to it.owner }).containsExactly("train2" to "train1")
		assertThat(open.single().block).isIn(*held.toTypedArray())
		assertThat(open.single().owner).isEqualTo(service.getBlockOwner(open.single().block))
	}

	@Test
	fun `a later success of the blocked train closes its open contention`() {
		blockTrain2BehindTrain1()
		service.releasePath("train1")
		assertReservationSuccess(service.reservePath("train2", inOut1, inOut2))

		assertThat(service.openContentions()).isEmpty()
		assertThat(service.blockedSeconds(now = 0.0).keys.toList()).containsExactly("train2")
	}

	@Test
	fun `a blocked train that holds no block keeps its open contention after its release`() {
		// The SP12.1 quirk, pinned through the snapshot as well: the contention stays to the run end.
		blockTrain2BehindTrain1()
		service.releasePath("train2")

		assertThat(service.openContentions().map { it.trainId }).containsExactly("train2")
	}

	@Test
	fun `reading the snapshot changes nothing the end-of-run flush reports`() {
		blockTrain2BehindTrain1()
		repeat(3) { service.openContentions() }

		assertThat(service.flushUnresolvedConflicts(1.0)).hasSize(1)
		assertThat(service.openContentions()).isEmpty()
	}

	@Test
	fun `a successful retry has closed the open contention before its block events are delivered`() {
		blockTrain2BehindTrain1()
		service.releasePath("train1")
		val openAtReserved = mutableListOf<Int>()
		service.addBlockOccupancyListener { event ->
			if (event.type == BlockOccupancyEventType.BLOCK_RESERVED) openAtReserved += service.openContentions().size
		}

		assertReservationSuccess(service.reservePath("train2", inOut1, inOut2))

		assertThat(openAtReserved.isNotEmpty()).isEqualTo(true)
		assertThat(openAtReserved.toSet()).isEqualTo(setOf(0))
	}
}
