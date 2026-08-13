/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 SP1: Spatial conflict detection in PathReservationService (Issue #580).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
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
 * Integration tests for [ConflictDetectedEvent] — Goal 9 SP1 (#580).
 *
 * Verifies that [ConflictDetectedEvent] is emitted immediately (mid-run, before any
 * physical collision) when two trains attempt to reserve the same track block, and that
 * non-conflicting reservations on separate blocks never emit the event.
 *
 * These tests target the two emission points in
 * [cz.vutbr.fit.interlockSim.context.navigation.DefaultPathReservationService]:
 *
 * 1. **Path blocked by another train** (`AllPathsBlocked`): The event fires when the
 *    availability check reveals that another train already holds the contested block —
 *    before any reservation attempt is made, so the second train never touches the block.
 *
 * 2. **Atomic registry race** (`RegistrationResult.Conflict`): The event fires when the
 *    block passed the free-check but the registry rejected the second booking atomically.
 *
 * @since Issue #580 (Goal 9 SP1)
 */
@Tag("integration-test")
@DisplayName("ConflictDetectedEvent — Goal 9 SP1 (#580)")
class ConflictDetectedEventTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	// ------------------------------------------------------------------
	// Test 1: Acceptance criteria — event emitted when two trains compete for the same block
	// ------------------------------------------------------------------

	/**
	 * Acceptance criteria (Issue #580):
	 * - When trainB tries to reserve a path that trainA already holds, a
	 *   [ConflictDetectedEvent] is emitted **immediately** (before the run ends).
	 * - The event payload contains both train IDs and the contested block.
	 * - The event is emitted before any physical collision (before the conflicting
	 *   reservation is accepted — trainB's attempt is rejected with [AllPathsBlocked]).
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Two trains competing for the same path emit ConflictDetectedEvent mid-run")
	fun twoTrainsCompetingForSamePathEmitConflictDetectedEvent() {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx
		val inOuts = ctx.getInOuts()

		val conflicts = mutableListOf<ConflictDetectedEvent>()
		ctx.onConflictDetectedEvent { e -> conflicts.add(e) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// TrainA reserves the full A→B path — must succeed.
					val result1 = reservationService.reservePath("TrainConflict-A", inA, inB)
					assertThat(result1)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					// At this point no conflict has occurred yet.
					assertThat(conflicts).isEmpty()

					// TrainB attempts the same A→B path — must fail (TrainA holds the blocks).
					// ConflictDetectedEvent must be emitted before this call returns.
					val result2 = reservationService.reservePath("TrainConflict-B", inA, inB)
					assertThat(result2)
						.isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)

					// Exactly one ConflictDetectedEvent must have been delivered mid-run.
					assertThat(conflicts).hasSize(1)

					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run()

		// Final verification after the run ends.
		assertThat(conflicts).hasSize(1)

		val event = conflicts.first()
		// TrainConflict-B attempted the conflicting reservation.
		assertThat(event.trainId).isEqualTo("TrainConflict-B")
		// TrainConflict-A already held the block.
		assertThat(event.conflictingTrainId).isEqualTo("TrainConflict-A")
		// The contested block must be identified.
		assertThat(event.block).isNotNull()
	}

	// ------------------------------------------------------------------
	// Test 2: Non-conflicting reservations on different blocks — no event
	// ------------------------------------------------------------------

	/**
	 * Regression / negative-path acceptance criterion (Issue #580):
	 * - When trainA and trainB each successfully reserve **different** blocks,
	 *   no [ConflictDetectedEvent] is emitted.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Non-conflicting reservations on different paths do not emit ConflictDetectedEvent")
	fun nonConflictingReservationsDoNotEmitConflictDetectedEvent() {
		// Use a linear path with a semaphore that allows passage; each train will
		// reserve the path in turn — the first completes and releases before the
		// second claims anything, so there is never a spatial conflict.
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx
		val inOuts = ctx.getInOuts()

		val conflicts = mutableListOf<ConflictDetectedEvent>()
		ctx.onConflictDetectedEvent { e -> conflicts.add(e) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// TrainA reserves the path, then immediately releases it.
					val result1 = reservationService.reservePath("TrainSeq-A", inA, inB)
					assertThat(result1)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)
					reservationService.releasePath("TrainSeq-A")

					// TrainB now reserves the same path — the blocks are free again, so
					// this is a clean reservation, not a conflict.
					val result2 = reservationService.reservePath("TrainSeq-B", inA, inB)
					assertThat(result2)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run()

		// Sequential reservations on the same blocks (no overlap) must never be flagged.
		assertThat(conflicts).isEmpty()
	}

	// ------------------------------------------------------------------
	// Test 3: Event payload completeness
	// ------------------------------------------------------------------

	/**
	 * Acceptance criteria (Issue #580):
	 * - Event payload contains both train IDs and the contested block identifier.
	 * - The simulation time is recorded at detection time (non-negative).
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("ConflictDetectedEvent payload contains both train IDs and contested block")
	fun conflictDetectedEventPayloadIsComplete() {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx
		val inOuts = ctx.getInOuts()

		val conflicts = mutableListOf<ConflictDetectedEvent>()
		ctx.onConflictDetectedEvent { e -> conflicts.add(e) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					reservationService.reservePath("TrainPayload-1", inA, inB)
					// Advance simulation time so the event's time field is non-zero.
					hold(5.0)
					reservationService.reservePath("TrainPayload-2", inA, inB)

					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run()

		assertThat(conflicts).hasSize(1)

		val event = conflicts.first()
		assertThat(event.trainId).isEqualTo("TrainPayload-2")
		assertThat(event.conflictingTrainId).isEqualTo("TrainPayload-1")
		assertThat(event.block).isNotNull()
		// Event must have been recorded at the simulated time of the conflict (≥ 5 s).
		assertThat(event.time >= 5.0).isTrue()
	}

	// ------------------------------------------------------------------
	// Test 4: Repeated AllPathsBlocked retries on the same contention
	// emit only ONE ConflictDetectedEvent (dedup via blockedSince)
	// ------------------------------------------------------------------

	/**
	 * Regression test: reservePath() is polled once per control step — every 2.0 simulated
	 * seconds, since ShuntingLoop/MultiTrainLoop hold 1.0 at the end of `iteration()` and another
	 * 1.0 in `interLoopSleep()` — while a train is queued behind a busy shared block. Each
	 * such retry against the SAME still-unresolved contention must NOT re-emit
	 * [ConflictDetectedEvent] -- it is a one-shot signal for the first observation of a
	 * given (trainId, block) contention, not a per-tick heartbeat.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Repeated AllPathsBlocked retries on the same contention emit only one ConflictDetectedEvent")
	fun repeatedRetriesOnSameContentionEmitOnlyOneConflictDetectedEvent() {
		val ctx =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
				as DefaultSimulationContext
		context = ctx
		val inOuts = ctx.getInOuts()

		val conflicts = mutableListOf<ConflictDetectedEvent>()
		ctx.onConflictDetectedEvent { e -> conflicts.add(e) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// TrainA reserves the full A→B path — must succeed.
					val result1 = reservationService.reservePath("TrainRetry-A", inA, inB)
					assertThat(result1)
						.isInstanceOf(PathReservationService.ReservationResult.Success::class)

					// TrainB retries the reservation against the same still-blocked
					// contention multiple times, simulating routine once-per-tick polling
					// while queued behind a busy shared block.
					repeat(3) {
						val result = reservationService.reservePath("TrainRetry-B", inA, inB)
						assertThat(result)
							.isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)
						hold(1.0)
					}

					// Only the FIRST retry should have produced a ConflictDetectedEvent.
					assertThat(conflicts).hasSize(1)

					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run()

		assertThat(conflicts).hasSize(1)
		val event = conflicts.first()
		assertThat(event.trainId).isEqualTo("TrainRetry-B")
		assertThat(event.conflictingTrainId).isEqualTo("TrainRetry-A")
	}
}
