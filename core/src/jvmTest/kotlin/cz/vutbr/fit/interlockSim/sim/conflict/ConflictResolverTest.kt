/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 SP3: ConflictResolution data model and candidate generation (Issue #585).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.NetworkState
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.objects.paths.SegmentCost
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultConflictResolver] — Goal 9 SP3 (#585).
 *
 * Verifies the three candidate-generation rules:
 *
 * 1. [ConflictResolution.HoldTrain] — always produced.
 * 2. [ConflictResolution.Reroute] — produced when [RouteFinder] returns at least one
 *    route whose segments do not include the contested block.
 * 3. [ConflictResolution.SpeedAdjust] — always produced.
 *
 * All tests use MockK to isolate [DefaultConflictResolver] from the real
 * [RouteFinder] and domain objects.
 *
 * @since Issue #585 (Goal 9 SP3)
 */
@DisplayName("DefaultConflictResolver — Goal 9 SP3 (#585)")
class ConflictResolverTest {
	// ── Shared test fixtures ─────────────────────────────────────────────────

	private lateinit var routeFinder: RouteFinder
	private lateinit var networkState: NetworkState
	private lateinit var inA: InOut
	private lateinit var inB: InOut
	private lateinit var contestedBlock: DynamicTrackBlock
	private lateinit var conflict: ConflictDetectedEvent

	@BeforeEach
	fun setUp() {
		routeFinder = mockk()
		networkState = mockk()
		inA = mockk(relaxed = true)
		inB = mockk(relaxed = true)
		contestedBlock = mockk(relaxed = true)

		// Stable InOut name stubs for readable test output
		every { inA.getName() } returns "A"
		every { inB.getName() } returns "B"

		// Default: RouteFinder returns no routes (overridden per test)
		every { routeFinder.findRoutes(any(), any(), any(), any(), any()) } returns emptyList()

		conflict =
			ConflictDetectedEvent(
				block = contestedBlock,
				trainId = "TrainBlocked",
				conflictingTrainId = "TrainHolder",
				time = 42.0
			)
	}

	// ── Helper: build a minimal Route ───────────────────────────────────────

	private fun makeRoute(
		from: InOut,
		to: InOut,
		vararg segments: TrackSection
	): Route =
		Route(
			start = from,
			target = to,
			segments = segments.toList(),
			cost = segments.size.toDouble(),
			costBreakdown = segments.map { SegmentCost(it, 1.0) }
		)

	// ── 1. HoldTrain candidate ───────────────────────────────────────────────

	@Nested
	@DisplayName("HoldTrain candidate")
	inner class HoldTrainCandidates {
		@Test
		@DisplayName("hold-train candidate is always generated even when no InOuts are provided")
		fun holdTrainIsAlwaysGenerated() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val holdCandidates = resolutions.filterIsInstance<ConflictResolution.HoldTrain>()
			assertThat(holdCandidates).hasSize(1)
		}

		@Test
		@DisplayName("hold-train candidate targets the blocked train (trainId from the conflict)")
		fun holdTrainTargetsBlockedTrain() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val hold = resolutions.filterIsInstance<ConflictResolution.HoldTrain>().first()
			assertThat(hold.trainId).isEqualTo("TrainBlocked")
		}

		@Test
		@DisplayName("hold-train candidate has the default hold duration")
		fun holdTrainHasDefaultDuration() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val hold = resolutions.filterIsInstance<ConflictResolution.HoldTrain>().first()
			assertThat(hold.holdDurationSeconds)
				.isEqualTo(DefaultConflictResolver.DEFAULT_HOLD_DURATION_SECONDS)
		}

		@Test
		@DisplayName("hold-train estimated impact delay equals hold duration")
		fun holdTrainImpactDelayEqualsHoldDuration() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val hold = resolutions.filterIsInstance<ConflictResolution.HoldTrain>().first()
			assertThat(hold.estimatedImpact.delaySeconds)
				.isEqualTo(DefaultConflictResolver.DEFAULT_HOLD_DURATION_SECONDS)
		}

		@Test
		@DisplayName("hold-train affected trains list contains the blocked train")
		fun holdTrainAffectedTrainsContainsBlockedTrain() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val hold = resolutions.filterIsInstance<ConflictResolution.HoldTrain>().first()
			assertThat(hold.affectedTrains.contains("TrainBlocked")).isTrue()
		}

		@Test
		@DisplayName("hold-train strategy is HOLD_TRAIN")
		fun holdTrainStrategyIsHoldTrain() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val hold = resolutions.filterIsInstance<ConflictResolution.HoldTrain>().first()
			assertThat(hold.strategy).isEqualTo(ConflictResolution.Strategy.HOLD_TRAIN)
		}
	}

	// ── 2. SpeedAdjust candidate ─────────────────────────────────────────────

	@Nested
	@DisplayName("SpeedAdjust candidate")
	inner class SpeedAdjustCandidates {
		@Test
		@DisplayName("speed-adjust candidate is always generated even when no InOuts are provided")
		fun speedAdjustIsAlwaysGenerated() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val speedCandidates = resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>()
			assertThat(speedCandidates).hasSize(1)
		}

		@Test
		@DisplayName("speed-adjust candidate targets the blocked train")
		fun speedAdjustTargetsBlockedTrain() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val speedAdjust = resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>().first()
			assertThat(speedAdjust.trainId).isEqualTo("TrainBlocked")
		}

		@Test
		@DisplayName("speed-adjust factor is within (0.0, 1.0]")
		fun speedAdjustFactorIsValid() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val speedAdjust = resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>().first()
			assertThat(speedAdjust.speedReductionFactor > 0.0).isTrue()
			assertThat(speedAdjust.speedReductionFactor <= 1.0).isTrue()
		}

		@Test
		@DisplayName("speed-adjust factor equals the default reduction factor")
		fun speedAdjustFactorEqualsDefault() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val speedAdjust = resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>().first()
			assertThat(speedAdjust.speedReductionFactor)
				.isEqualTo(DefaultConflictResolver.DEFAULT_SPEED_REDUCTION_FACTOR)
		}

		@Test
		@DisplayName("speed-adjust strategy is SPEED_ADJUST")
		fun speedAdjustStrategyIsSpeedAdjust() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val speedAdjust = resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>().first()
			assertThat(speedAdjust.strategy).isEqualTo(ConflictResolution.Strategy.SPEED_ADJUST)
		}
	}

	// ── 3. Reroute candidate ─────────────────────────────────────────────────

	@Nested
	@DisplayName("Reroute candidate")
	inner class RerouteCandidates {
		@Test
		@DisplayName("no reroute candidate when RouteFinder returns no routes")
		fun noRerouteWhenNoRoutesFromFinder() {
			// RouteFinder already stubbed to return empty list in setUp()
			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val rerouteCandidates = resolutions.filterIsInstance<ConflictResolution.Reroute>()
			assertThat(rerouteCandidates).isEmpty()
		}

		@Test
		@DisplayName("no reroute candidate when all routes include the contested block")
		fun noRerouteWhenAllRoutesContainContestedBlock() {
			// Route containing the contested block
			val routeViaContestedBlock = makeRoute(inA, inB, contestedBlock)
			every { routeFinder.findRoutes(any(), any(), any(), any(), any()) } returns
				listOf(routeViaContestedBlock)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val rerouteCandidates = resolutions.filterIsInstance<ConflictResolution.Reroute>()
			assertThat(rerouteCandidates).isEmpty()
		}

		@Test
		@DisplayName("reroute candidate generated when RouteFinder returns route that avoids contested block")
		fun rerouteGeneratedWhenAlternativeRouteExists() {
			val alternativeSegment = mockk<TrackSection>(relaxed = true)
			val alternativeRoute = makeRoute(inA, inB, alternativeSegment)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(alternativeRoute)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val rerouteCandidates = resolutions.filterIsInstance<ConflictResolution.Reroute>()
			assertThat(rerouteCandidates.size).isGreaterThan(0)
		}

		@Test
		@DisplayName("reroute candidate carries the alternative route from RouteFinder")
		fun rerouteCandidateCarriesAlternativeRoute() {
			val alternativeSegment = mockk<TrackSection>(relaxed = true)
			val alternativeRoute = makeRoute(inA, inB, alternativeSegment)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(alternativeRoute)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val reroute = resolutions.filterIsInstance<ConflictResolution.Reroute>().first()
			assertThat(reroute.alternativeRoute).isEqualTo(alternativeRoute)
		}

		@Test
		@DisplayName("reroute candidate targets the blocked train")
		fun rerouteCandidateTargetsBlockedTrain() {
			val alternativeSegment = mockk<TrackSection>(relaxed = true)
			val alternativeRoute = makeRoute(inA, inB, alternativeSegment)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(alternativeRoute)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val reroute = resolutions.filterIsInstance<ConflictResolution.Reroute>().first()
			assertThat(reroute.trainId).isEqualTo("TrainBlocked")
		}

		@Test
		@DisplayName("reroute strategy is REROUTE")
		fun rerouteStrategyIsReroute() {
			val alternativeSegment = mockk<TrackSection>(relaxed = true)
			val alternativeRoute = makeRoute(inA, inB, alternativeSegment)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(alternativeRoute)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val reroute = resolutions.filterIsInstance<ConflictResolution.Reroute>().first()
			assertThat(reroute.strategy).isEqualTo(ConflictResolution.Strategy.REROUTE)
		}

		@Test
		@DisplayName("reroute uses Goal 2 pathfinding API: RouteFinder.findRoutes is called for each InOut pair")
		fun rerouteUsesGoal2PathfindingApi() {
			// Arrange: two InOuts, RouteFinder called for each directed pair
			every { routeFinder.findRoutes(any(), any(), any(), any(), any()) } returns emptyList()

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			resolver.generateResolutions(conflict)

			// RouteFinder.findRoutes must have been called with both directed pairs (A→B and B→A)
			verify(atLeast = 1) { routeFinder.findRoutes(inA, inB, any(), any(), any()) }
			verify(atLeast = 1) { routeFinder.findRoutes(inB, inA, any(), any(), any()) }
		}

		@Test
		@DisplayName("multiple reroute candidates generated when RouteFinder returns multiple routes")
		fun multipleRerouteCandidatesFromMultipleRoutes() {
			val seg1 = mockk<TrackSection>(relaxed = true)
			val seg2 = mockk<TrackSection>(relaxed = true)
			val route1 = makeRoute(inA, inB, seg1)
			val route2 = makeRoute(inA, inB, seg2)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(route1, route2)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			// Both routes avoid the contested block → two reroute candidates for A→B direction
			val rerouteCandidates = resolutions.filterIsInstance<ConflictResolution.Reroute>()
			assertThat(rerouteCandidates.size).isGreaterThan(1)
		}

		@Test
		@DisplayName("reroute candidate is not generated when no InOuts are provided")
		fun noRerouteWhenNoInOuts() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val rerouteCandidates = resolutions.filterIsInstance<ConflictResolution.Reroute>()
			assertThat(rerouteCandidates).isEmpty()
		}
	}

	// ── 4. ConflictResolution model fields ───────────────────────────────────

	@Nested
	@DisplayName("ConflictResolution model")
	inner class ConflictResolutionModel {
		@Test
		@DisplayName("HoldTrain estimated impact description is not blank")
		fun holdTrainImpactDescriptionNotBlank() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val hold = resolutions.filterIsInstance<ConflictResolution.HoldTrain>().first()
			assertThat(hold.estimatedImpact.description.isNotBlank()).isTrue()
		}

		@Test
		@DisplayName("SpeedAdjust estimated impact description is not blank")
		fun speedAdjustImpactDescriptionNotBlank() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val speedAdjust = resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>().first()
			assertThat(speedAdjust.estimatedImpact.description.isNotBlank()).isTrue()
		}

		@Test
		@DisplayName("Reroute estimated impact description is not blank when alternative route exists")
		fun rerouteImpactDescriptionNotBlankWhenRouteExists() {
			val alternativeSegment = mockk<TrackSection>(relaxed = true)
			val alternativeRoute = makeRoute(inA, inB, alternativeSegment)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(alternativeRoute)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val reroute = resolutions.filterIsInstance<ConflictResolution.Reroute>().first()
			assertThat(reroute.estimatedImpact.description.isNotBlank()).isTrue()
		}

		@Test
		@DisplayName("generateResolutions always returns at least hold-train and speed-adjust candidates")
		fun generateResolutionsAlwaysReturnsMinimumCandidates() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			assertThat(resolutions.filterIsInstance<ConflictResolution.HoldTrain>()).hasSize(1)
			assertThat(resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>()).hasSize(1)
			assertThat(resolutions.isNotEmpty()).isTrue()
		}

		@Test
		@DisplayName("all three candidate types are present when alternative route exists")
		fun allThreeStrategyTypesWhenAlternativeRouteExists() {
			val alternativeSegment = mockk<TrackSection>(relaxed = true)
			val alternativeRoute = makeRoute(inA, inB, alternativeSegment)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(alternativeRoute)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val strategies = resolutions.map { it.strategy }.toSet()
			assertThat(strategies.contains(ConflictResolution.Strategy.HOLD_TRAIN)).isTrue()
			assertThat(strategies.contains(ConflictResolution.Strategy.REROUTE)).isTrue()
			assertThat(strategies.contains(ConflictResolution.Strategy.SPEED_ADJUST)).isTrue()
		}

		@Test
		@DisplayName("HoldTrain affected trains list is non-empty")
		fun holdTrainAffectedTrainsNonEmpty() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val hold = resolutions.filterIsInstance<ConflictResolution.HoldTrain>().first()
			assertThat(hold.affectedTrains.isNotEmpty()).isTrue()
		}

		@Test
		@DisplayName("SpeedAdjust affected trains list is non-empty")
		fun speedAdjustAffectedTrainsNonEmpty() {
			val resolver = DefaultConflictResolver(routeFinder, emptyList(), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			val speedAdjust = resolutions.filterIsInstance<ConflictResolution.SpeedAdjust>().first()
			assertThat(speedAdjust.affectedTrains.isNotEmpty()).isTrue()
		}

		@Test
		@DisplayName("EstimatedImpact delaySeconds is non-negative for all candidates")
		fun estimatedImpactDelayIsNonNegative() {
			val alternativeSegment = mockk<TrackSection>(relaxed = true)
			val alternativeRoute = makeRoute(inA, inB, alternativeSegment)
			every { routeFinder.findRoutes(inA, inB, any(), any(), any()) } returns
				listOf(alternativeRoute)

			val resolver = DefaultConflictResolver(routeFinder, listOf(inA, inB), networkState)

			val resolutions = resolver.generateResolutions(conflict)

			resolutions.forEach { candidate ->
				assertThat(candidate.estimatedImpact.delaySeconds >= 0.0).isTrue()
			}
		}
	}

	// ── 5. ConflictResolution sealed class structure ─────────────────────────

	@Nested
	@DisplayName("ConflictResolution sealed class structure")
	inner class ConflictResolutionStructure {
		@Test
		@DisplayName("HoldTrain can be constructed directly with all required fields")
		fun holdTrainCanBeConstructed() {
			val impact = ConflictResolution.EstimatedImpact(30.0, "Hold for 30s")
			val holdTrain =
				ConflictResolution.HoldTrain(
					trainId = "TrainA",
					holdDurationSeconds = 30.0,
					affectedTrains = listOf("TrainA"),
					estimatedImpact = impact
				)

			assertThat(holdTrain.trainId).isEqualTo("TrainA")
			assertThat(holdTrain.holdDurationSeconds).isEqualTo(30.0)
			assertThat(holdTrain.strategy).isEqualTo(ConflictResolution.Strategy.HOLD_TRAIN)
			assertThat(holdTrain.estimatedImpact).isNotNull()
		}

		@Test
		@DisplayName("Reroute can be constructed directly with all required fields")
		fun rerouteCanBeConstructed() {
			val seg = mockk<TrackSection>(relaxed = true)
			val route = makeRoute(inA, inB, seg)
			val impact = ConflictResolution.EstimatedImpact(0.0, "Reroute via alt")
			val reroute =
				ConflictResolution.Reroute(
					trainId = "TrainA",
					alternativeRoute = route,
					affectedTrains = listOf("TrainA"),
					estimatedImpact = impact
				)

			assertThat(reroute.trainId).isEqualTo("TrainA")
			assertThat(reroute.alternativeRoute).isEqualTo(route)
			assertThat(reroute.strategy).isEqualTo(ConflictResolution.Strategy.REROUTE)
		}

		@Test
		@DisplayName("SpeedAdjust can be constructed directly with all required fields")
		fun speedAdjustCanBeConstructed() {
			val impact = ConflictResolution.EstimatedImpact(0.0, "Reduce to 50%")
			val speedAdjust =
				ConflictResolution.SpeedAdjust(
					trainId = "TrainA",
					speedReductionFactor = 0.5,
					affectedTrains = listOf("TrainA"),
					estimatedImpact = impact
				)

			assertThat(speedAdjust.trainId).isEqualTo("TrainA")
			assertThat(speedAdjust.speedReductionFactor).isEqualTo(0.5)
			assertThat(speedAdjust.strategy).isEqualTo(ConflictResolution.Strategy.SPEED_ADJUST)
		}

		@Test
		@DisplayName("EstimatedImpact holds both delaySeconds and description")
		fun estimatedImpactHoldsAllFields() {
			val impact = ConflictResolution.EstimatedImpact(15.0, "Delay of 15 seconds")

			assertThat(impact.delaySeconds).isEqualTo(15.0)
			assertThat(impact.description).isEqualTo("Delay of 15 seconds")
		}
	}
}
