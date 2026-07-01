/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 3 SP2: Reservation conflict warning integration test (Issue #612).
 */
package cz.vutbr.fit.interlockSim.sim.collision

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.sim.Interlocking
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration tests for [DefaultCollisionDetectionService] SP2 — reservation conflict warnings.
 *
 * Verifies that when two trains compete for the same path during `reservePath`,
 * a [CollisionWarning.ReservationConflict] is emitted with the correct train IDs
 * and the conflicting block reference.
 *
 * Deduplication ensures that only one warning fires per unique conflict pair within
 * the deduplication window, so a single `reservePath` conflict produces exactly one warning.
 *
 * @since Issue #612 (Goal 3 SP2)
 */
@Tag("integration-test")
@DisplayName("ReservationConflictWarning — Goal 3 SP2 (#612)")
class ReservationConflictWarningTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	// ------------------------------------------------------------------
	// Test: two trains competing for the same path emit exactly one warning
	// ------------------------------------------------------------------

	/**
	 * Acceptance criteria (Issue #612):
	 * - When `reservePath` returns `AllPathsBlocked` because a second train attempts
	 *   to reserve a path already owned by the first, a [CollisionWarning.ReservationConflict]
	 *   is emitted.
	 * - Warning includes both train IDs and the conflicting block.
	 * - Exactly one warning is emitted for a single conflict attempt (dedup window).
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Two trains competing for the same path emit exactly one ReservationConflict warning")
	fun twoTrainsCompetingForSamePathEmitExactlyOneConflictWarning() {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx
		val inOuts = ctx.getInOuts()

		val warnings = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { w -> warnings.add(w) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		// Custom interlocking process:
		// 1. Reserves path for T1 (A→B) — succeeds.
		// 2. Immediately tries to reserve the same path for T2 — gets AllPathsBlocked
		//    because T1 already owns the blocks, which triggers BlockEvent.ReservationConflictDetected
		//    → DefaultCollisionDetectionService emits CollisionWarning.ReservationConflict.
		// 3. Stops the simulation; no further retries → exactly one warning.
		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// Train1 reserves the full A→B path — must succeed
					val result1 = reservationService.reservePath("TrainConflictA", inA, inB)
					assertThat(result1)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					// Train2 tries the same A→B path — must fail (blocks owned by Train1)
					// This call triggers BlockEvent.ReservationConflictDetected inside reservePath,
					// which DefaultCollisionDetectionService converts to CollisionWarning.ReservationConflict.
					val result2 = reservationService.reservePath("TrainConflictB", inA, inB)
					assertThat(result2)
						.isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)

					// Terminate the simulation; no retry loop means exactly one warning
					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run()

		// Exactly one ReservationConflict warning must have been emitted
		val conflicts = warnings.filterIsInstance<CollisionWarning.ReservationConflict>()
		assertThat(conflicts).hasSize(1)

		val conflict = conflicts.first()
		// TrainConflictB was the one that attempted the conflicting reservation
		assertThat(conflict.trainId).isEqualTo("TrainConflictB")
		// TrainConflictA was already holding the blocks
		assertThat(conflict.conflictingTrainId).isEqualTo("TrainConflictA")
		// The conflicting block must be identified (SP2 requirement)
		assertThat(conflict.conflictingBlock).isNotNull()
	}
}
