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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
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
 * Wires the spatial detector, resolution generator and ranking engine together
 * and validates the Goal 9 flow against the parent-issue verification plan (#531).
 *
 * ## Acceptance criteria verified
 *
 * - AC1: Two trains reserving the same block trigger a [ConflictDetectedEvent] before
 *   any collision; the scoped [ConflictResolver] turns the event into a non-empty, ranked
 *   candidate list ordered exactly as the base [ConflictResolutionRanker.rank] orders it.
 * - AC2 (removed by Issue #1455): converging-path projections triggered a
 *   `TemporalConflictEvent` of the inert `TemporalConflictDetector`. Goal 9B SP2.3 deleted the
 *   detector (owner ruling R1, #1434); its provider contract, overlap and dedup rules are kept
 *   in #1497 (Goal 3B).
 * - AC3: [DefaultConflictResolver] generates both [ConflictResolution.HoldTrain] and
 *   [ConflictResolution.Reroute] candidates, ranked correctly by
 *   [ConflictResolutionRanker].
 * - AC4 (converted by Issue #1454): the ranking has no choice history. It used to pin that
 *   selecting [ConflictResolution.Strategy.REROUTE] 3 times via the `StrategyPreferenceStore`
 *   ranks it above [ConflictResolution.Strategy.HOLD_TRAIN]; Goal 9B SP2.2 removed that
 *   in-memory learning (owner rulings R1 and R14, #1434; learning from run records is Goal 9C,
 *   #1490), so AC4 now pins that the base order is the same on every ranking.
 * - AC5: Trains on separate non-conflicting paths produce no [ConflictDetectedEvent]. Its
 *   temporal half (no `TemporalConflictEvent`, and the non-overlapping projection case) went
 *   with the detector in Issue #1455.
 *
 * ## Scope note
 *
 * AC4 exercises [ConflictResolutionRanker] in isolation (it does not flow through a live `run()`).
 *
 * Until Issue #1453 (Goal 9B SP2.1) AC1 consumed the event through the headless
 * auto-resolution picker, which took the top candidate and recorded it with source `AUTO`.
 * The picker had no production caller and was deleted under owner ruling R1 (#1434:
 * conflict resolution moves to the dispatcher thread); AC1 keeps the detection, generation
 * and ranking steps. Issue #1454 then removed the `sim.conflict` preference stores, so AC1 no
 * longer checks the empty choice ledger; it pins the base ranking instead.
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
	// AC1: Same-block reservation triggers ConflictDetectedEvent; the scoped
	//      ConflictResolver generates ranked candidates for it.
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC1 — Same-block reservation triggers ConflictDetectedEvent and ranked resolution candidates")
	inner class Ac1SameBlockConflictDetectionPipeline {
		/**
		 * Two trains reserving the same block emit a [ConflictDetectedEvent] **before any
		 * collision** (mid-run), and the production-scoped [ConflictResolver] generates a
		 * non-empty candidate list for it, ordered exactly as the base [ConflictResolutionRanker.rank]
		 * orders it (no preference re-weighting, Issue #1454).
		 *
		 * Verification plan (Issue #531):
		 * > "Two trains reserve the same block; verify [ConflictDetectedEvent] fired before
		 * > collision."
		 *
		 * @since Issue #593 (Goal 9 SP7)
		 */
		@Test
		@Timeout(value = 10, unit = TimeUnit.SECONDS)
		@DisplayName(
			"same-block reservation emits ConflictDetectedEvent and the scoped resolver ranks candidates for it"
		)
		fun sameBlockReservation_emitsConflictDetectedEvent_andScopedResolverRanksCandidates() {
			val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
			extraContext = ctx

			val conflictEvents = mutableListOf<ConflictDetectedEvent>()
			val resolver = ctx.scope.get<ConflictResolver>()
			val generatedCandidates = mutableListOf<List<ConflictResolution>>()

			// Detection → generation → ranking via the production-scoped beans.
			ctx.onConflictDetectedEvent { event ->
				conflictEvents.add(event)
				generatedCandidates.add(resolver.generateResolutions(event))
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

			// Post-run: exactly one conflict event, and the resolver generated a non-empty
			// candidate list for it, ordered by ConflictResolutionRanker (score ascending;
			// the base ranker is the only ranking since Issue #1454).
			assertThat(conflictEvents).hasSize(1)
			assertThat(generatedCandidates).hasSize(1)
			val ranked = generatedCandidates[0]
			assertThat(ranked).isNotEmpty()
			for (i in 0 until ranked.size - 1) {
				assertThat(ConflictResolutionRanker.score(ranked[i]))
					.isLessThanOrEqualTo(ConflictResolutionRanker.score(ranked[i + 1]) + 0.0001)
			}

			// The scoped resolver ranks with the base ranker only (Issue #1454): ranking the
			// resolver's candidates again must reproduce their natural (ascending-score)
			// order, not any preference order.
			assertThat(ConflictResolutionRanker.rank(ranked))
				.isEqualTo(ranked.sortedBy { ConflictResolutionRanker.score(it) })
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
		@Timeout(value = 10, unit = TimeUnit.SECONDS)
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
	// AC4 (converted by Issue #1454): the ranking has no choice history; the
	//      base order is the only order.
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("AC4 — the ranking has no choice history: the base order holds on every ranking")
	inner class Ac4RankingHasNoChoiceHistory {
		/**
		 * Until Issue #1454 this case recorded [ConflictResolution.Strategy.REROUTE] three times
		 * in the `StrategyPreferenceStore` and pinned that the preference-weighted overload of
		 * [ConflictResolutionRanker.rank] then ranked the reroute first. Goal 9B SP2.2 removed
		 * the store and the overload: the base order (hold 30 s → score 10 030; reroute 400 m →
		 * score 10 040) is the only order, whatever the input order.
		 *
		 * @since Issue #593 (Goal 9 SP7); converted by Issue #1454
		 */
		@Test
		@DisplayName("both input orders keep HOLD_TRAIN above REROUTE")
		fun bothInputOrders_keepsHoldTrainAboveReroute() {
			// Score layout (1 affected train each; AFFECTED_TRAIN_WEIGHT = 10 000):
			//   HoldTrain   → delaySeconds=30   → score = 10 000 + 30×1.0  = 10 030
			//   Reroute     → routeLength=400 m → score = 10 000 + 400×0.1 = 10 040
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

			assertThat(ConflictResolutionRanker.rank(listOf(holdCandidate, rerouteCandidate)))
				.containsExactly(holdCandidate, rerouteCandidate)
			assertThat(ConflictResolutionRanker.rank(listOf(rerouteCandidate, holdCandidate)))
				.containsExactly(holdCandidate, rerouteCandidate)
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// AC5: Trains on separate non-conflicting paths produce no ConflictDetectedEvent
	//      (false-positive validation).
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
		@Timeout(value = 10, unit = TimeUnit.SECONDS)
		@DisplayName("sequential same-path reservations produce no ConflictDetectedEvent")
		fun sequentialSamePathReservations_produceNoConflictDetectedEvent() {
			val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false)
			extraContext = ctx

			val conflictEvents = mutableListOf<ConflictDetectedEvent>()
			ctx.onConflictDetectedEvent { e -> conflictEvents.add(e) }

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
		}
	}
}
