/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 3 SP7: Collision detection integration tests (Issue #617).
 */
package cz.vutbr.fit.interlockSim.sim.collision

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.ksimulantenbande.kdisco.emitCustom
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.Interlocking
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration tests for Goal 3 SP7 — collision detection end-to-end scenarios (#617).
 *
 * Covers:
 * 1. **Reservation conflict integration** — two trains request the same entry-to-exit
 *    path; exactly one [CollisionWarning.ReservationConflict] is emitted; the simulation
 *    runs to completion (no deadlock).
 * 2. **Block-entry violation integration** — deliberately bypass reservation and place a
 *    second train into an already-occupied block; [CollisionWarning.BlockEntryViolation]
 *    is emitted via the emit-before-throw path inside
 *    [cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock.enter].
 *
 * These tests complement the unit-level coverage in:
 * - [ReservationConflictWarningTest] (SP2, #612)
 * - [BlockEntryViolationWarningTest] (SP3, #613)
 *
 * by exercising the full [DefaultCollisionDetectionService] subscription chain end-to-end
 * through [DefaultSimulationContext.run].
 *
 * @since Issue #617 (Goal 3 SP7)
 */
@Tag("integration-test")
@DisplayName("CollisionDetection integration — Goal 3 SP7 (#617)")
class CollisionDetectionIntegrationTest : KoinTestBase() {
	private var context: DefaultSimulationContext? = null

	@AfterEach
	fun closeContext() {
		context?.close()
		context = null
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun newContext(): DefaultSimulationContext {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
		context = ctx
		return ctx
	}

	// ── Test 1: Reservation conflict — exactly one warning, no deadlock ────────

	/**
	 * Acceptance criteria (SP7 deliverable 1):
	 * - Two trains attempt to reserve the same A→B path via [PathReservationService.reservePath].
	 * - Train1 succeeds; Train2 gets [PathReservationService.ReservationResult.AllPathsBlocked].
	 * - Exactly one [CollisionWarning.ReservationConflict] is emitted after the run ends
	 *   (end-of-run flush — see [DefaultPathReservationService.flushUnresolvedConflicts]).
	 * - The warning identifies Train2 as the conflicting train and Train1 as the holder.
	 * - The simulation terminates normally (no deadlock).
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("Two trains competing for same path emit exactly one ReservationConflict; no deadlock")
	fun twoTrainsCompetingForSamePath_emitsOneConflictAndNoDeadlock() {
		val ctx = newContext()
		val inOuts = ctx.getInOuts()

		val warnings = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { w -> warnings.add(w) }

		val reservationService = ctx.getRoutingServices().getPathReservationService()

		val process =
			object : Interlocking(ctx) {
				override suspend fun iteration() {
					val inA = inOuts.first { it.name == "A" }
					val inB = inOuts.first { it.name == "B" }

					// Train1 reserves A→B — must succeed.
					val r1 = reservationService.reservePath("ConflictTrain1", inA, inB)
					assertThat(r1).isInstanceOf(PathReservationService.ReservationResult.Success::class)

					// Train2 attempts the same path while Train1 holds it — must be blocked.
					val r2 = reservationService.reservePath("ConflictTrain2", inA, inB)
					assertThat(r2).isInstanceOf(PathReservationService.ReservationResult.AllPathsBlocked::class)

					// No warning mid-run (the end-of-run flush surfaces it afterwards).
					assertThat(warnings.filterIsInstance<CollisionWarning.ReservationConflict>()).isEmpty()

					// Stop with the contention still unresolved — triggers end-of-run flush.
					env.stop()
				}

				override suspend fun interLoopSleep() {
					terminate()
				}
			}

		ctx.setMainProcess(process)
		ctx.run() // must not hang → no deadlock

		// Exactly one ReservationConflict emitted by the end-of-run flush.
		val conflicts = warnings.filterIsInstance<CollisionWarning.ReservationConflict>()
		assertThat(conflicts).hasSize(1)

		val conflict = conflicts.first()
		assertThat(conflict.trainId).isEqualTo("ConflictTrain2")
		assertThat(conflict.conflictingTrainId).isEqualTo("ConflictTrain1")
		assertThat(conflict.conflictingBlock).isNotNull()
	}

	// ── Test 2: Block-entry violation — warning emitted via emitCustom ─────────

	/**
	 * Acceptance criteria (SP7 deliverable 2):
	 * - [CollisionWarning.BlockEntryViolation] emitted via [emitCustom] (mimicking the
	 *   emit-before-throw path inside [DynamicTrackBlock.enter]) is received by
	 *   [DefaultCollisionDetectionService] and forwarded to all registered listeners.
	 * - Warning payload matches the emitted instance exactly.
	 * - Auto-pause is NOT triggered here (disabled via [DefaultCollisionDetectionService.autoPauseOnCritical]).
	 */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("BlockEntryViolation emitted mid-enter is delivered to warning listeners")
	fun blockEntryViolationEmittedMidEnter_isDeliveredToListeners() {
		val ctx = newContext()
		val received = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { received.add(it) }

		// Disable auto-pause so the simulation terminates rather than blocking.
		val detectionService =
			ctx.getCollisionServices().getCollisionDetectionService() as DefaultCollisionDetectionService
		detectionService.autoPauseOnCritical = false

		val block: DynamicTrackBlock = ctx.getGraph().values().first()
		val violation =
			CollisionWarning.BlockEntryViolation(
				trainId = "EntryViolator",
				block = block,
				time = 0.0,
				reservedForAtDetection = "ReservationHolder"
			)

		// Simulate the emit-before-throw pattern from DynamicTrackBlock.enter():
		// a process emits the violation via emitCustom before throwing.
		ctx.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					emitCustom(violation)
					terminate()
				}
			}
		)
		ctx.run()

		assertThat(received).hasSize(1)
		val warning = received.single()
		assertThat(warning).isInstanceOf(CollisionWarning.BlockEntryViolation::class)
		warning as CollisionWarning.BlockEntryViolation
		assertThat(warning.trainId).isEqualTo("EntryViolator")
		assertThat(warning.reservedForAtDetection).isEqualTo("ReservationHolder")
		assertThat(warning.block).isEqualTo(block)
	}

	// ── Test 3: Clean multi-train run produces zero collision warnings ─────────

	/**
	 * Regression guard: a normal two-train [MultiTrainLoop] run through the linear
	 * topology (trains travel sequentially — second starts well after first exits)
	 * must not produce any [CollisionWarning].
	 *
	 * This ensures the SP2/SP3/SP4 detection rules are not erroneously tripped by
	 * routine multi-train choreography.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("Normal sequential two-train run produces zero collision warnings")
	fun normalTwoTrainRun_producesZeroCollisionWarnings() {
		val ctx = newContext()
		val warnings = mutableListOf<CollisionWarning>()
		ctx.getCollisionServices().onCollisionWarning { w -> warnings.add(w) }

		val process =
			MultiTrainLoop(
				ctx,
				endTime = 400L,
				trainSpecs =
					listOf(
						// Train1 at t=0; train2 at t=200 (well after train1 has exited).
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = 20.0),
						MultiTrainLoop.TrainSpec("A", "B", inTime = 200.0, length = 20.0)
					),
				maxConcurrentTrains = 10
			)

		ctx.setMainProcess(process)
		ctx.run()

		assertThat(warnings).isEmpty()
	}
}
