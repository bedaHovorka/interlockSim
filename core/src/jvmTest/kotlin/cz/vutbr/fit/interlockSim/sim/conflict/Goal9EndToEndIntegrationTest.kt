/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 SP7: End-to-end integration and false-positive validation (Issue #593).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.objects.paths.SegmentCost
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.Interlocking
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * End-to-end integration and false-positive validation suite for Goal 9 (#593).
 *
 * Wires the spatial/temporal detectors, resolution generator, ranking engine, and
 * [StrategyPreferenceStore] together and validates the complete Goal 9 flow against
 * the parent-issue verification plan (#531).
 *
 * ## Naming note
 *
 * Two stores exist in the `sim.conflict` package with similar names:
 * - **[DispatcherPreferenceStore]** (Issue #568) — the choice ledger: records every
 *   `(event, resolution, source)` triple applied during a run. Consumed by
 *   [DefaultAutoConflictResolutionService] to persist auto-applied selections.
 * - **[StrategyPreferenceStore]** (Issue #592) — the learning counter: accumulates
 *   per-`(conflictTypeKey, strategy)` selection counts and feeds
 *   [ConflictResolutionRanker.rank]'s preference-weighted overload to promote
 *   frequently chosen strategies in future rankings.
 *
 * The preference-learning acceptance criterion (AC4) exercises [StrategyPreferenceStore],
 * not [DispatcherPreferenceStore]; test method names reflect the correct type.
 *
 * ## Acceptance criteria verified
 *
 * - AC1: Two trains reserving the same block trigger a [ConflictDetectedEvent] before
 *   any collision; the full pipeline from event → [AutoConflictResolutionService]
 *   → [DispatcherPreferenceStore] records the auto-applied choice.
 * - AC2: Converging-path projections trigger a [TemporalConflictEvent] within the
 *   30-second lookahead window configured on [TemporalConflictDetector].
 * - AC3: [DefaultConflictResolver] generates both [ConflictResolution.HoldTrain] and
 *   [ConflictResolution.Reroute] candidates, ranked correctly by
 *   [ConflictResolutionRanker].
 * - AC4: Selecting [ConflictResolution.Strategy.REROUTE] 3 times via
 *   [StrategyPreferenceStore] causes it to rank above [ConflictResolution.Strategy.HOLD_TRAIN]
 *   in subsequent similar conflicts.
 * - AC5: Trains on separate non-conflicting paths produce no [ConflictDetectedEvent].
 *
 * ## Scope note
 *
 * AC4 exercises [StrategyPreferenceStore] + [ConflictResolutionRanker] in isolation
 * (it does not flow through [AutoConflictResolutionService] or a live `run()`). The
 * end-to-end interaction of [AutoConflictResolutionService] with [StrategyPreferenceStore]
 * (verifying that the auto-applied choice shifts toward the learned preference across
 * repeated conflicts) is not covered by this suite.
 *
 * @since Issue #593 (Goal 9 SP7)
 */
@Tag("integration-test")
@DisplayName("Goal 9 SP7 — End-to-end integration and false-positive validation (#593)")
class Goal9EndToEndIntegrationTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	/** Track per-test contexts that need closing beyond the KoinTestBase lifecycle. */
	private var extraContext: DefaultSimulationContext? = null

	@AfterEach
	fun closeExtraContext() {
		extraContext?.close()
		extraContext = null
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private fun createShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory)

	/** Build a mock [Route] with the given total length for use in reroute candidates. */
	private fun mockRoute(
		lengthMeters: Double,
		costValue: Double = lengthMeters
	): Route {
		val inA = mockk<InOut>(relaxed = true)
		val inB = mockk<InOut>(relaxed = true)
		val section = mockk<TrackSection>(relaxed = true)
		every { section.length() } returns lengthMeters
		return Route(
			start = inA,
			target = inB,
			segments = listOf(section),
			cost = costValue,
			costBreakdown = listOf(SegmentCost(section, costValue))
		)
	}

	// ─────────────────────────────────────────────────────────────────────────
	// AC1: Same-block reservation triggers ConflictDetectedEvent and the full
	//      pipeline routes it through AutoConflictResolutionService →
	//      DispatcherPreferenceStore.
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC1 — Same-block reservation triggers ConflictDetectedEvent and auto-resolution pipeline")
	inner class Ac1SameBlockConflictDetectionPipeline {
		/**
		 * Two trains reserving the same block emit a [ConflictDetectedEvent] **before any
		 * collision** (mid-run), and the event is consumed by the production-wired
		 * [AutoConflictResolutionService] which selects the top-ranked resolution and
		 * records it in the scoped [DispatcherPreferenceStore].
		 *
		 * Verification plan (Issue #531):
		 * > "Two trains reserve the same block; verify [ConflictDetectedEvent] fired before
		 * > collision."
		 *
		 * @since Issue #593 (Goal 9 SP7)
		 */
		@Test
		@Timeout(value = 60, unit = TimeUnit.SECONDS)
		@DisplayName(
			"same-block reservation emits ConflictDetectedEvent and auto-resolver records choice in DispatcherPreferenceStore"
		)
		fun sameBlockReservation_emitsConflictDetectedEvent_andAutoResolverRecordsChoiceInDispatcherPreferenceStore() {
			val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
			extraContext = ctx

			val conflictEvents = mutableListOf<ConflictDetectedEvent>()
			val autoResolver = ctx.scope.get<AutoConflictResolutionService>()
			val preferenceStore = ctx.scope.get<DispatcherPreferenceStore>()
			val appliedResolutions = mutableListOf<ConflictResolution>()

			// Wire the full pipeline: ConflictDetectedEvent → AutoConflictResolutionService
			// → DispatcherPreferenceStore (via the production-scoped beans)
			ctx.onConflictDetectedEvent { event ->
				conflictEvents.add(event)
				val resolution = autoResolver.applyTopRanked(event)
				if (resolution != null) appliedResolutions.add(resolution)
			}

			val reservationService = ctx.getRoutingServices().getPathReservationService()
			val inOuts = ctx.getInOuts()

			val process =
				object : Interlocking(ctx) {
					override suspend fun iteration() {
						val inA = inOuts.first { it.name == "A" }
						val inB = inOuts.first { it.name == "B" }

						// Train1 reserves A→B — must succeed.
						val r1 = reservationService.reservePath("Train-Same-Block-1", inA, inB)
						assertThat(r1).isInstanceOf(PathReservationService.ReservationResult.Success::class)

						// No conflict yet: Train1 is the only holder.
						assertThat(conflictEvents).isEmpty()

						// Train2 attempts the same A→B path while Train1 holds it — mid-run conflict.
						reservationService.reservePath("Train-Same-Block-2", inA, inB)

						// ConflictDetectedEvent must have been emitted (before any collision).
						assertThat(conflictEvents).hasSize(1)
						assertThat(conflictEvents[0].trainId).isEqualTo("Train-Same-Block-2")
						assertThat(conflictEvents[0].conflictingTrainId).isEqualTo("Train-Same-Block-1")

						env.stop()
					}

					override suspend fun interLoopSleep() {
						terminate()
					}
				}

			ctx.setMainProcess(process)
			ctx.run()

			// Post-run: verify the full pipeline delivered exactly one conflict event
			// and the auto-resolver recorded the choice in the DispatcherPreferenceStore.
			assertThat(conflictEvents).hasSize(1)
			assertThat(appliedResolutions).hasSize(1)
			val choice = appliedResolutions[0]
			assertThat(choice).isNotNull()

			// The resolution must be recorded in the DispatcherPreferenceStore.
			val choices = preferenceStore.getChoices()
			assertThat(choices).hasSize(1)
			assertThat(choices[0].applied).isEqualTo(choice)
			assertThat(choices[0].source).isEqualTo(DispatcherPreferenceStore.ApplicationSource.AUTO)
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// AC2: Converging-path projections trigger TemporalConflictEvent within
	//      the 30-second lookahead window.
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC2 — Converging-path projections trigger TemporalConflictEvent within 30 s window")
	inner class Ac2TemporalConflictWithinLookaheadWindow {
		/**
		 * Two trains with overlapping projected occupancies on the same block are detected
		 * by the production-wired [TemporalConflictDetector] within the default 30-second
		 * lookahead window.
		 *
		 * The projection provider is registered on the real scoped detector before the
		 * scan is triggered by synthetic [BlockEvent.BlockReserved] events. Both trains
		 * project onto the same [DynamicTrackBlock] at overlapping future offsets (10–25 s
		 * and 15–30 s respectively), with the overlap beginning 15 s after detection — well
		 * within the 30-second window.
		 *
		 * Verification plan (Issue #531):
		 * > "Two trains on converging paths; verify detection within the lookahead window."
		 *
		 * @since Issue #593 (Goal 9 SP7)
		 */
		@Test
		@Timeout(value = 60, unit = TimeUnit.SECONDS)
		@DisplayName("converging-path projections trigger TemporalConflictEvent within the 30 s lookahead window")
		fun convergingPathProjections_triggersTemporalConflictEvent_withinThirtySecondLookaheadWindow() {
			val ctx = createShuntingLoopContext()
			extraContext = ctx

			// Get the real TemporalConflictDetector from the production DI scope.
			// Subscribe directly on the detector — env wiring for onBlockEvent runs in
			// the init block. handleBlockEvent is called directly below, so ctx.run() is
			// not needed and ctx.onTemporalConflictEvent (which buffers until run()) is
			// intentionally bypassed in favour of registering directly on the detector.
			val detector = ctx.getTemporalConflictDetector()
			assertThat(detector.lookaheadWindowSeconds)
				.isEqualTo(TemporalConflictDetector.DEFAULT_LOOKAHEAD_WINDOW_SECONDS)

			val temporalConflicts = mutableListOf<TemporalConflictEvent>()
			detector.onTemporalConflictEvent { e -> temporalConflicts.add(e) }

			// Obtain a real DynamicTrackBlock from the network graph.
			val sharedBlock = ctx.getGraph().values().first()

			// Register projection provider: Train-Converge-A and Train-Converge-B both
			// project onto sharedBlock with overlapping windows within the 30 s lookahead.
			//   Train-Converge-A: enters at offset 10 s, exits at offset 25 s
			//   Train-Converge-B: enters at offset 15 s, exits at offset 30 s
			//   Overlap interval: [15, 25) → predictedConflictTime ≈ detectionTime + 15 s
			detector.registerProjectionProvider { trainId ->
				when (trainId) {
					"Train-Converge-A" ->
						listOf(ProjectedOccupancy(sharedBlock, enterOffsetSeconds = 10.0, exitOffsetSeconds = 25.0))
					"Train-Converge-B" ->
						listOf(ProjectedOccupancy(sharedBlock, enterOffsetSeconds = 15.0, exitOffsetSeconds = 30.0))
					else -> null
				}
			}

			// Drive the detector with synthetic BlockReserved events to make both
			// trains active simultaneously and trigger the lookahead scan.
			// handleBlockEvent is `internal` and accessible from within the same package.
			detector.handleBlockEvent(BlockEvent.BlockReserved(sharedBlock, "Train-Converge-A", time = 0.0))
			detector.handleBlockEvent(BlockEvent.BlockReserved(sharedBlock, "Train-Converge-B", time = 0.5))

			// The scan runs after the second BlockReserved event (both trains now active).
			assertThat(temporalConflicts).isNotEmpty()

			val event = temporalConflicts.first()
			// Both trains must be identified in the event.
			val trainIds = setOf(event.trainId, event.otherTrainId)
			assertThat(trainIds.contains("Train-Converge-A")).isTrue()
			assertThat(trainIds.contains("Train-Converge-B")).isTrue()
			assertThat(event.conflictBlock).isEqualTo(sharedBlock)

			// Predicted conflict time must be within the 30-second lookahead window.
			val timeUntilConflict = event.predictedConflictTime - event.detectionTime
			assertThat(timeUntilConflict)
				.isLessThanOrEqualTo(TemporalConflictDetector.DEFAULT_LOOKAHEAD_WINDOW_SECONDS)
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// AC3: Hold-train and reroute resolutions are generated and correctly
	//      ranked by ConflictResolutionRanker.
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC3 — HoldTrain and Reroute resolutions are generated and correctly ranked")
	inner class Ac3HoldTrainAndRerouteResolutions {
		/**
		 * [DefaultConflictResolver] produces both a [ConflictResolution.HoldTrain] and at
		 * least one [ConflictResolution.Reroute] for a contested block on the shunting-loop
		 * network, which has two alternative routes.  The ranked list confirms that
		 * [ConflictResolutionRanker] orders them from least to most disruptive.
		 *
		 * Verification plan (Issue #531):
		 * > "Verify hold-train and reroute options both generated and correctly ranked."
		 *
		 * @since Issue #593 (Goal 9 SP7)
		 */
		@Test
		@Timeout(value = 60, unit = TimeUnit.SECONDS)
		@DisplayName("DefaultConflictResolver generates HoldTrain and Reroute candidates ranked by ConflictResolutionRanker")
		fun holdTrainAndReroute_areBothGeneratedAndRankedByConflictResolutionRanker() {
			createShuntingLoopContext().use { simCtx ->
				val inOuts = simCtx.getInOuts().toList()

				// Obtain a contested block exclusive to one route so a reroute candidate
				// via the other route can bypass it.
				val allRoutes =
					simCtx
						.getRouteFinder()
						.findRoutes(inOuts[0].staticRef, inOuts[1].staticRef, simCtx)
				assertThat(allRoutes.size).isGreaterThan(1)

				val contestedBlock =
					allRoutes[0]
						.segments
						.filterIsInstance<DynamicTrackBlock>()
						.first { it !in allRoutes[1].segments.toSet() }

				val event =
					ConflictDetectedEvent(
						block = contestedBlock,
						trainId = "Train-Ranked-1",
						conflictingTrainId = "Train-Ranked-2",
						time = 0.0
					)

				// Obtain the production-scoped resolver.
				// generateResolutions() returns candidates already sorted by ConflictResolutionRanker.
				val resolver = simCtx.scope.get<ConflictResolver>()
				val ranked = resolver.generateResolutions(event)

				assertThat(ranked).isNotEmpty()

				// Both HoldTrain and Reroute must be present.
				val holdCandidates = ranked.filterIsInstance<ConflictResolution.HoldTrain>()
				val rerouteCandidates = ranked.filterIsInstance<ConflictResolution.Reroute>()

				assertThat(holdCandidates).isNotEmpty()
				assertThat(rerouteCandidates).isNotEmpty()

				// All reroute candidates must avoid the contested block.
				rerouteCandidates.forEach { reroute ->
					assertThat(reroute.alternativeRoute.segments.none { it == contestedBlock }).isTrue()
				}

				// The list is already ranked by ConflictResolutionRanker: verify each
				// consecutive pair satisfies score(i) ≤ score(i+1).
				for (i in 0 until ranked.size - 1) {
					assertThat(ConflictResolutionRanker.score(ranked[i]))
						.isLessThanOrEqualTo(ConflictResolutionRanker.score(ranked[i + 1]) + 0.0001)
				}
			}
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// AC4: Selecting REROUTE 3 times via StrategyPreferenceStore causes it
	//      to rank above HOLD_TRAIN in subsequent similar conflicts.
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC4 — StrategyPreferenceStore: selecting REROUTE 3× promotes it above HOLD_TRAIN")
	inner class Ac4StrategyPreferenceStoreLearning {
		/**
		 * Demonstrates the preference-learning acceptance criterion:
		 *
		 * 1. Build a pair of candidates where [ConflictResolution.HoldTrain] initially ranks
		 *    above [ConflictResolution.Reroute] (hold delay = 30 s → score 10 030;
		 *    reroute length = 400 m → score 10 040 — hold wins by 10 points).
		 * 2. Record [ConflictResolution.Strategy.REROUTE] three times via
		 *    [StrategyPreferenceStore.recordChoice] for a fixed conflict-type key.
		 * 3. Re-rank with the preference-weighted overload:
		 *    [ConflictResolutionRanker.rank]`(candidates, store, key)`.
		 * 4. Verify [ConflictResolution.Reroute] now appears first (the 3 × 100-point
		 *    boost lowers its adjusted score to 9 740, below hold's unchanged 10 030).
		 *
		 * Uses [StrategyPreferenceStore] (Issue #592), **not** [DispatcherPreferenceStore]
		 * (Issue #568) — the two classes are distinct and were renamed to avoid a collision.
		 *
		 * Verification plan (Issue #531):
		 * > "Dispatcher selects reroute 3 times; verify reroute moves up in ranking."
		 *
		 * @since Issue #593 (Goal 9 SP7)
		 */
		@Test
		@DisplayName("selecting REROUTE 3× via StrategyPreferenceStore causes it to rank above HOLD_TRAIN")
		fun selectingReroute3Times_viaStrategyPreferenceStore_promotesRerouteAboveHoldTrain() {
			// Score layout (1 affected train each; AFFECTED_TRAIN_WEIGHT = 10 000):
			//   HoldTrain   → delaySeconds=30   → score = 10 000 + 30×1.0  = 10 030
			//   Reroute     → routeLength=400 m → score = 10 000 + 400×0.1 = 10 040
			// Initial ranking (without preferences): HoldTrain (10 030) < Reroute (10 040)
			// → HoldTrain is first.
			val holdCandidate =
				ConflictResolution.HoldTrain(
					trainId = "T-Pref",
					holdDurationSeconds = 30.0,
					affectedTrains = listOf("T-Pref"),
					estimatedImpact = ConflictResolution.EstimatedImpact(30.0, "Hold 30 s")
				)
			val rerouteCandidate =
				ConflictResolution.Reroute(
					trainId = "T-Pref",
					alternativeRoute = mockRoute(lengthMeters = 400.0),
					affectedTrains = listOf("T-Pref"),
					estimatedImpact = ConflictResolution.EstimatedImpact(0.0, "Reroute via 400 m alternative")
				)
			val candidates = listOf(holdCandidate, rerouteCandidate)

			// --- STEP 1: baseline ranking without preferences ---
			val baselineRanked = ConflictResolutionRanker.rank(candidates)
			// HoldTrain has a lower base score → must rank first.
			assertThat(baselineRanked).containsExactly(holdCandidate, rerouteCandidate)

			// --- STEP 2: record REROUTE three times via StrategyPreferenceStore ---
			val strategyStore = StrategyPreferenceStore()
			val conflictTypeKey = "junction-S1"
			repeat(3) {
				strategyStore.recordChoice(conflictTypeKey, ConflictResolution.Strategy.REROUTE)
			}
			assertThat(strategyStore.selectionCount(conflictTypeKey, ConflictResolution.Strategy.REROUTE))
				.isEqualTo(3)

			// --- STEP 3: preference-weighted ranking ---
			// After 3 selections of REROUTE:
			//   REROUTE boost = 3 × 100 = 300
			//   Adjusted Reroute score = 10 040 - 300 = 9 740
			//   Adjusted HoldTrain score = 10 030 - 0   = 10 030
			// → Reroute (9 740) now ranks first.
			val preferenceRanked = ConflictResolutionRanker.rank(candidates, strategyStore, conflictTypeKey)
			assertThat(preferenceRanked).containsExactly(rerouteCandidate, holdCandidate)
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// AC5: Trains on separate non-conflicting paths produce no ConflictDetectedEvent
	//      and no TemporalConflictEvent (false-positive validation).
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC5 — Non-conflicting paths produce no ConflictDetectedEvent (false-positive validation)")
	inner class Ac5NonConflictingPathsFalsePositiveValidation {
		/**
		 * Two trains reserving and releasing the same path **sequentially** (no temporal
		 * overlap) must never produce a [ConflictDetectedEvent].
		 *
		 * Train-NoConflict-A reserves A→B and releases it; then Train-NoConflict-B takes
		 * the same path.  Because the reservations are disjoint in time there is no spatial
		 * or temporal conflict to detect.
		 *
		 * Verification plan (Issue #531):
		 * > "Trains on separate non-conflicting paths; verify no conflict events fired."
		 *
		 * @since Issue #593 (Goal 9 SP7)
		 */
		@Test
		@Timeout(value = 60, unit = TimeUnit.SECONDS)
		@DisplayName("sequential same-path reservations produce no ConflictDetectedEvent")
		fun sequentialSamePathReservations_produceNoConflictDetectedEvent() {
			val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
			extraContext = ctx

			val conflictEvents = mutableListOf<ConflictDetectedEvent>()
			val temporalConflicts = mutableListOf<TemporalConflictEvent>()
			ctx.onConflictDetectedEvent { e -> conflictEvents.add(e) }
			ctx.onTemporalConflictEvent { e -> temporalConflicts.add(e) }

			val reservationService = ctx.getRoutingServices().getPathReservationService()
			val inOuts = ctx.getInOuts()

			val process =
				object : Interlocking(ctx) {
					override suspend fun iteration() {
						val inA = inOuts.first { it.name == "A" }
						val inB = inOuts.first { it.name == "B" }

						// Train-NoConflict-A reserves A→B and releases it before Train-NoConflict-B arrives.
						val r1 = reservationService.reservePath("Train-NoConflict-A", inA, inB)
						assertThat(r1).isInstanceOf(PathReservationService.ReservationResult.Success::class)
						reservationService.releasePath("Train-NoConflict-A")

						// No conflict event so far.
						assertThat(conflictEvents).isEmpty()

						// Train-NoConflict-B now reserves the same path — blocks are free again.
						val r2 = reservationService.reservePath("Train-NoConflict-B", inA, inB)
						assertThat(r2).isInstanceOf(PathReservationService.ReservationResult.Success::class)

						env.stop()
					}

					override suspend fun interLoopSleep() {
						terminate()
					}
				}

			ctx.setMainProcess(process)
			ctx.run()

			// No conflict events must have been produced for sequential, non-overlapping reservations.
			assertThat(conflictEvents).isEmpty()
			// Also guard against temporal-detector false positives on the run-wired path.
			assertThat(temporalConflicts).isEmpty()
		}

		/**
		 * Validates that the [TemporalConflictDetector] produces no [TemporalConflictEvent]
		 * when two trains have **non-overlapping** projected occupancies on the same block
		 * (Train-NoOverlap-A exits before Train-NoOverlap-B enters).
		 *
		 * This is the false-positive guard for the temporal detector: back-to-back
		 * occupancies that do not overlap must never fire the lookahead alarm.
		 *
		 * @since Issue #593 (Goal 9 SP7)
		 */
		@Test
		@Timeout(value = 30, unit = TimeUnit.SECONDS)
		@DisplayName("non-overlapping projected occupancies on the same block produce no TemporalConflictEvent")
		fun nonOverlappingProjectedOccupancies_produceNoTemporalConflictEvent() {
			val ctx = createShuntingLoopContext()
			extraContext = ctx

			val detector = ctx.getTemporalConflictDetector()
			val block = ctx.getGraph().values().first()

			// Subscribe directly on the detector (handleBlockEvent is called directly
			// below, no ctx.run() is needed).
			val temporalConflicts = mutableListOf<TemporalConflictEvent>()
			detector.onTemporalConflictEvent { e -> temporalConflicts.add(e) }

			// Non-overlapping: Train-NoOverlap-A exits at 10 s; Train-NoOverlap-B enters at 15 s.
			detector.registerProjectionProvider { trainId ->
				when (trainId) {
					"Train-NoOverlap-A" ->
						listOf(ProjectedOccupancy(block, enterOffsetSeconds = 0.0, exitOffsetSeconds = 10.0))
					"Train-NoOverlap-B" ->
						listOf(ProjectedOccupancy(block, enterOffsetSeconds = 15.0, exitOffsetSeconds = 25.0))
					else -> null
				}
			}

			// Drive both trains active to trigger the scan.
			detector.handleBlockEvent(BlockEvent.BlockReserved(block, "Train-NoOverlap-A", time = 0.0))
			detector.handleBlockEvent(BlockEvent.BlockReserved(block, "Train-NoOverlap-B", time = 0.5))

			// No temporal conflict must have been emitted: occupancies are disjoint.
			assertThat(temporalConflicts).isEmpty()
		}
	}
}
