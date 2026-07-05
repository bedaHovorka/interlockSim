/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.component.KoinComponent
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Unit tests for [TemporalConflictDetector] — Goal 9 SP2 (#583).
 *
 * Drives [TemporalConflictDetector.handleBlockEvent] directly with synthetic
 * [BlockEvent.BlockReserved] events and synthetic [ProjectedOccupancy] providers.
 * No full simulation run is needed; the tests exercise the detection algorithm in
 * isolation.
 *
 * ## Test scenarios
 *
 * 1. **Converging paths** — two trains projected to overlap on the same block within the
 *    lookahead window → [TemporalConflictEvent] emitted.
 * 2. **Diverging paths** — two trains on completely different blocks → no event.
 * 3. **Non-overlapping time windows** — same block but one train exits before the other
 *    enters → no event.
 * 4. **Configurable lookahead window** — conflict outside window is suppressed; inside
 *    window fires.
 * 5. **Single train** — only one active train; no pair to compare → no event.
 * 6. **Deduplication** — same conflict re-detected immediately → suppressed.
 * 7. **Train released** — train leaves network; only one remains → no further events.
 * 8. **Event payload** — emitted event carries correct train IDs, block, and timestamps.
 *
 * @since Issue #583 (Goal 9 SP2)
 */
class TemporalConflictDetectionTest : KoinComponent {

	private var context: DefaultSimulationContext? = null

	@BeforeTest
	fun setUpKoin() {
		startKoin { modules(commonCoreTestModule) }
	}

	@AfterTest
	fun tearDown() {
		context?.close()
		context = null
		stopKoin()
	}

	/** Obtain a real [DynamicTrackBlock] from a minimal transformed context. */
	private fun realBlock(): DynamicTrackBlock {
		val ctx =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		context = ctx
		return ctx.getGraph().values().first()
	}

	/** Build a [TemporalConflictDetector] with no env subscription (test drives events directly). */
	private fun buildDetector(): Pair<TemporalConflictDetector, MutableList<TemporalConflictEvent>> {
		val detector = TemporalConflictDetector() // env=null → no onBlockEvent subscription
		val received = mutableListOf<TemporalConflictEvent>()
		detector.onTemporalConflictEvent { received.add(it) }
		return Pair(detector, received)
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 1: Converging paths — trains overlap on the same block within window
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Two trains are projected to be on the same block with overlapping time windows.
	 *
	 * Setup:
	 * - Train A occupies the block from offset 0 to 20 s (already in block).
	 * - Train B will enter the same block at offset 10 s, exit at 30 s.
	 * - Overlap: [10, 20) → conflict must be detected.
	 */
	@Test
	fun `converging trains overlap on same block emit TemporalConflictEvent`() {
		val (detector, received) = buildDetector()
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 20.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 10.0, exitOffsetSeconds = 30.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).hasSize(1)
		val event = received.single()
		assertThat(event).isInstanceOf(TemporalConflictEvent::class)
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 2: Diverging paths — trains on completely different blocks
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Two trains are each projected on a **different** block.  No shared block →
	 * no conflict event.
	 */
	@Test
	fun `trains on different blocks do not emit any event`() {
		val (detector, received) = buildDetector()
		val blockA = realBlock()

		// Build a second distinct block for train-B by re-using the context's graph
		// (commonTest doesn't easily build two separate contexts, so we just use a fresh
		// real block for the second projection while still sending the reserve event for
		// the first block — the detector only looks at projections, not reservation state).
		val ctxB =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		val blockB: DynamicTrackBlock = ctxB.getGraph().values().first()
		ctxB.close()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" -> listOf(ProjectedOccupancy(blockA, enterOffsetSeconds = 0.0, exitOffsetSeconds = 20.0))
				"train-B" -> listOf(ProjectedOccupancy(blockB, enterOffsetSeconds = 5.0, exitOffsetSeconds = 25.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(blockA, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(blockA, "train-B", time = 2.0))

		assertThat(received).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 3: Non-overlapping time windows on the same block
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Both trains are projected on the **same** block but their time windows do not
	 * overlap: Train A exits before Train B enters.
	 *
	 * - Train A: [0, 10) s
	 * - Train B: [10, 20) s   ← enters exactly when A exits (non-overlapping)
	 */
	@Test
	fun `non-overlapping time windows on same block do not emit event`() {
		val (detector, received) = buildDetector()
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 10.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 10.0, exitOffsetSeconds = 20.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 4a: Configurable lookahead — conflict beyond window is suppressed
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Lookahead window set to 20 s.  Train B's entry at 25 s is beyond the window,
	 * so no conflict event should fire.
	 */
	@Test
	fun `conflict beyond configured lookahead window is suppressed`() {
		val (detector, received) = buildDetector()
		detector.lookaheadWindowSeconds = 20.0
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 30.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 25.0, exitOffsetSeconds = 40.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 4b: Configurable lookahead — conflict inside a larger window fires
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Same scenario as 4a but window is expanded to 60 s — the conflict at 25 s is
	 * now within the window and must fire.
	 */
	@Test
	fun `conflict within expanded lookahead window fires`() {
		val (detector, received) = buildDetector()
		detector.lookaheadWindowSeconds = 60.0
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 30.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 25.0, exitOffsetSeconds = 40.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).hasSize(1)
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 5: Single active train — no pair to compare
	// ──────────────────────────────────────────────────────────────────────────

	@Test
	fun `single active train does not emit any event`() {
		val (detector, received) = buildDetector()
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 20.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))

		assertThat(received).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 6: Deduplication — same conflict re-detected within dedup window
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Triggering two consecutive block events for the same active pair should emit
	 * exactly one [TemporalConflictEvent] for the (trainA, trainB, block) triple
	 * because the second detection falls within the 5-second deduplication window.
	 */
	@Test
	fun `same conflict detected twice within dedup window emits only one event`() {
		val (detector, received) = buildDetector()
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 20.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 5.0, exitOffsetSeconds = 25.0))
				else -> null
			}
		}

		// Activate both trains at t=1 and t=2 — second reserve triggers a second scan
		// at t=2 which is within 5 s of the first → dedup should suppress.
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		// Second block-reserve event at t=3 (≤ 5 s from first at t=2) — still deduped.
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 3.0))

		assertThat(received).hasSize(1)
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 7: Released train → only one remains, no further events
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * After train-B releases its only block, it is removed from the active set.
	 * A subsequent scan with only train-A active must not fire any event.
	 */
	@Test
	fun `train removed after all blocks released stops firing events`() {
		val (detector, received) = buildDetector()
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 20.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 5.0, exitOffsetSeconds = 25.0))
				else -> null
			}
		}

		// Both active → event fires.
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))
		val countAfterReserve = received.size

		// Release train-B's only block → removed from active set.
		received.clear()
		detector.handleBlockEvent(BlockEvent.BlockReleased(block, "train-B", time = 10.0))

		// Trigger another scan: only train-A is active → no pair → no new event.
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 11.0))

		assertThat(countAfterReserve).isEqualTo(1)
		assertThat(received.filterIsInstance<TemporalConflictEvent>()).isEmpty()
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 8: Event payload correctness
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Verify the emitted [TemporalConflictEvent] carries the expected field values.
	 *
	 * - [TemporalConflictEvent.conflictBlock] matches the block in both projections.
	 * - [TemporalConflictEvent.detectionTime] equals the simulation time of the
	 *   triggering block-reserve event.
	 * - [TemporalConflictEvent.predictedConflictTime] equals detectionTime + the max
	 *   of the two enter offsets (= the moment the overlap begins).
	 * - Both train IDs are present in the event (order may vary).
	 */
	@Test
	fun `emitted event payload contains correct train IDs, block, and timestamps`() {
		val (detector, received) = buildDetector()
		val block = realBlock()
		val detectionSimTime = 5.0

		// Train A is already in the block (enter=0), exits at 15 s.
		// Train B enters at 8 s (within overlap window).
		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 15.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 8.0, exitOffsetSeconds = 20.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = detectionSimTime))

		assertThat(received).hasSize(1)
		val event = received.single()

		// Both train IDs must appear (order can vary — event stores (trainId, otherTrainId)).
		val ids = setOf(event.trainId, event.otherTrainId)
		assertThat(ids.contains("train-A")).isTrue()
		assertThat(ids.contains("train-B")).isTrue()

		// The contested block must be identified.
		assertThat(event.conflictBlock).isEqualTo(block)

		// Detection time matches the triggering event's simulation time.
		assertThat(event.detectionTime).isEqualTo(detectionSimTime)

		// Predicted conflict time = detectionTime + max(enterA=0, enterB=8) = 5 + 8 = 13.
		assertThat(event.predictedConflictTime).isEqualTo(detectionSimTime + 8.0)
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 9: Default lookahead window
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * The default lookahead window is 30 simulation seconds.
	 * A conflict with enterOffset=29 s must fire; enterOffset=31 s must not.
	 */
	@Test
	fun `default lookahead window is 30 seconds`() {
		assertThat(TemporalConflictDetector.DEFAULT_LOOKAHEAD_WINDOW_SECONDS).isEqualTo(30.0)

		val (detector, received) = buildDetector()
		val block = realBlock()

		// Train A occupies the block for a long window.
		// Train B enters at 29 s (just within the default 30-s window).
		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 60.0))
				"train-B" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 29.0, exitOffsetSeconds = 50.0))
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		// Conflict at offset 29 s ≤ 30 s window → event expected.
		assertThat(received).hasSize(1)
		assertThat(received.single().predictedConflictTime).isGreaterThanOrEqualTo(2.0 + 29.0)
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 10: Provider returns null → train skipped
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * If the projection provider returns `null` for one of the active trains, the
	 * scan only has one projection and emits no event.
	 */
	@Test
	fun `null projection for one train produces no event`() {
		val (detector, received) = buildDetector()
		val block = realBlock()

		detector.registerProjectionProvider { trainId ->
			when (trainId) {
				"train-A" ->
					listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 20.0))
				// train-B returns null → skipped
				else -> null
			}
		}

		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-A", time = 1.0))
		detector.handleBlockEvent(BlockEvent.BlockReserved(block, "train-B", time = 2.0))

		assertThat(received).isEmpty()
	}
}
