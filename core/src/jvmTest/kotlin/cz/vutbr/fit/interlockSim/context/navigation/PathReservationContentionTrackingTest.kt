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
import assertk.assertions.isIn
import assertk.assertions.isInstanceOf
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
}
