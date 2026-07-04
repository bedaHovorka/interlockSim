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
import assertk.assertions.isEmpty
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
 * Verifies that when two trains compete for the same path during `reservePath` and the
 * contention never resolves before the run ends, a [CollisionWarning.ReservationConflict]
 * is emitted (via the end-of-run flush) with the correct train IDs and the conflicting
 * block reference — while routine "path busy, will retry" contention that resolves
 * normally is never flagged (issue #612: timing-based live heuristics misfire on
 * ordinary queueing at shared bottlenecks, so no mid-run signal is derived from
 * `AllPathsBlocked` outcomes).
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
	 *   to reserve a path already owned by the first, and that contention is still
	 *   unresolved when the run ends, a [CollisionWarning.ReservationConflict] is
	 *   emitted by the end-of-run flush.
	 * - Warning includes both train IDs and the conflicting block.
	 * - Exactly one warning is emitted for a single conflict attempt.
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
		//    because T1 already owns the blocks; the contention is recorded (no mid-run event).
		// 3. Stops the simulation with T2 still blocked → the end-of-run flush emits
		//    BlockEvent.ReservationConflictDetected → DefaultCollisionDetectionService
		//    emits exactly one CollisionWarning.ReservationConflict.
		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// Train1 reserves the full A→B path — must succeed
					val result1 = reservationService.reservePath("TrainConflictA", inA, inB)
					assertThat(result1)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					// Train2 tries the same A→B path — must fail (blocks owned by Train1).
					// No event is emitted here; the unresolved contention is reported by
					// flushUnresolvedConflicts once the run ends.
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

	// ------------------------------------------------------------------
	// Test: routine blocked-path retry (busy, not racing) does NOT emit a warning
	// ------------------------------------------------------------------

	/**
	 * Regression test for the false-positive an earlier version of this SP2 feature
	 * caused (see [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService]):
	 * an `AllPathsBlocked` outcome must not, by itself, be treated as a conflict. A
	 * single train waiting for a block that another train is *routinely* holding (not
	 * actively racing for it) -- and which clears normally once that train releases it
	 * -- is expected multi-train shunting choreography, not a collision.
	 *
	 * This reproduces, at a small scale, the shape of contention that made
	 * `SimulationSpeedPerformanceTest`'s 300s ShuntingLoop hang: the original,
	 * over-broad detection fired `BlockEvent.ReservationConflictDetected` (and thus
	 * an auto-pausing collision warning) on *every* blocked-path retry, including
	 * completely routine ones.
	 *
	 * Acceptance criteria:
	 * - TrainBusy reserves A→B and holds it for a while.
	 * - TrainWaiting's reservation attempt against the same path returns
	 *   `AllPathsBlocked` but emits NO [CollisionWarning.ReservationConflict].
	 * - Once TrainBusy releases the path, TrainWaiting's retry succeeds normally, and
	 *   no conflict warning was ever emitted for this routine contention.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Routine blocked-path contention that clears normally does not emit a ReservationConflict warning")
	fun routineBlockedPathContentionDoesNotEmitConflictWarning() {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx
		val inOuts = ctx.getInOuts()

		val warnings = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { w -> warnings.add(w) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// TrainBusy reserves the full A→B path -- succeeds and settles in.
					val result1 = reservationService.reservePath("TrainBusy", inA, inB)
					assertThat(result1)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					// Let the reservation settle in before the blocked attempt — this is
					// ordinary "another train is using the path" traffic, not a race.
					hold(2.0)

					// TrainWaiting tries the same path while it's still busy -- routine
					// "path busy, retry later" outcome; must NOT be flagged as a conflict.
					val result2 = reservationService.reservePath("TrainWaiting", inA, inB)
					assertThat(result2)
						.isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)

					// A little more time passes before TrainBusy clears the path normally.
					hold(1.0)
					reservationService.releasePath("TrainBusy")

					// TrainWaiting retries and now succeeds -- the contention resolved
					// normally, exactly like a real ShuntingLoop train waiting its turn.
					// Because it resolved (Success) before the run ends, the end-of-run
					// flush (flushUnresolvedConflicts) never sees it either.
					val result3 = reservationService.reservePath("TrainWaiting", inA, inB)
					assertThat(result3)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run()

		// Routine busy-retry contention that resolves normally must never be
		// surfaced as a ReservationConflict warning.
		val conflicts = warnings.filterIsInstance<CollisionWarning.ReservationConflict>()
		assertThat(conflicts).isEmpty()
	}

	// ------------------------------------------------------------------
	// Test: contention still unresolved when the run ends IS reported (end-of-run flush)
	// ------------------------------------------------------------------

	/**
	 * Regression test for [DefaultCollisionDetectionService]/[DefaultPathReservationService]'s
	 * "unresolved by end of run" signal: contention that never resolves before the
	 * simulation stops must be surfaced as a [CollisionWarning.ReservationConflict] --
	 * otherwise a genuine deadlock-like situation would silently vanish rather than
	 * being reported at all.
	 *
	 * Deliberately does **not** use a running-clock "stuck for N seconds" threshold (an
	 * earlier version of this feature guessed one and produced false positives on ordinary
	 * long waits during multi-train `ShuntingLoop` choreography). Instead, the signal is
	 * "still unresolved when [cz.vutbr.fit.interlockSim.context.SimulationContext.run]
	 * returns" -- checked exactly once, after the run has fully stopped, so it can never
	 * cause [cz.vutbr.fit.interlockSim.context.SimulationController] to hang (there is
	 * nothing left running to pause).
	 *
	 * Acceptance criteria:
	 * - TrainBusy reserves A→B and never releases it.
	 * - TrainWaiting's blocked attempt produces no warning during the run.
	 * - The simulation stops with TrainWaiting still blocked (no further retry).
	 * - After `ctx.run()` returns, exactly one [CollisionWarning.ReservationConflict] has
	 *   been emitted, for the pair (TrainWaiting, TrainBusy).
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Contention still unresolved when the run ends is reported via the end-of-run flush")
	fun unresolvedContentionAtRunEndEmitsConflictWarning() {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx
		val inOuts = ctx.getInOuts()

		val warnings = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { w -> warnings.add(w) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// TrainBusy reserves the full A→B path and never releases it.
					val result1 = reservationService.reservePath("TrainBusy", inA, inB)
					assertThat(result1)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					// Let the reservation settle in before the blocked attempt.
					hold(2.0)

					// TrainWaiting is blocked and never gets another chance to retry --
					// the run stops with the contention still unresolved.
					val result2 = reservationService.reservePath("TrainWaiting", inA, inB)
					assertThat(result2)
						.isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)

					// No warning yet -- blocked-path contention is never flagged mid-run.
					assertThat(warnings.filterIsInstance<CollisionWarning.ReservationConflict>()).isEmpty()

					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run()

		// The end-of-run flush must have surfaced the still-unresolved contention.
		val conflicts = warnings.filterIsInstance<CollisionWarning.ReservationConflict>()
		assertThat(conflicts).hasSize(1)

		val conflict = conflicts.first()
		assertThat(conflict.trainId).isEqualTo("TrainWaiting")
		assertThat(conflict.conflictingTrainId).isEqualTo("TrainBusy")
		assertThat(conflict.conflictingBlock).isNotNull()
	}
}
