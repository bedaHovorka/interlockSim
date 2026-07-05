/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 SP4: Resolution ranking engine (Issue #588).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.objects.paths.SegmentCost
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConflictResolutionRanker] — Goal 9 SP4 (#588).
 *
 * Expected orders below are hand-calculated from the documented weights:
 * [ConflictResolutionRanker.DELAY_WEIGHT_SECONDS] = 1.0,
 * [ConflictResolutionRanker.AFFECTED_TRAIN_WEIGHT] = 10 000.0,
 * [ConflictResolutionRanker.PATH_LENGTH_WEIGHT_PER_METER] = 0.1.
 *
 * @since Issue #588 (Goal 9 SP4)
 */
@DisplayName("ConflictResolutionRanker — Goal 9 SP4 (#588)")
class ConflictResolutionRankerTest {
	// ── Helpers ──────────────────────────────────────────────────────────────

	private fun impact(delaySeconds: Double) = ConflictResolution.EstimatedImpact(delaySeconds, "test")

	private fun hold(
		trainId: String = "T1",
		holdDurationSeconds: Double,
		affectedTrains: List<String> = listOf(trainId)
	) = ConflictResolution.HoldTrain(
		trainId = trainId,
		holdDurationSeconds = holdDurationSeconds,
		affectedTrains = affectedTrains,
		estimatedImpact = impact(holdDurationSeconds)
	)

	private fun speedAdjust(
		trainId: String = "T1",
		speedReductionFactor: Double = 0.5,
		delaySeconds: Double = 0.0,
		affectedTrains: List<String> = listOf(trainId)
	) = ConflictResolution.SpeedAdjust(
		trainId = trainId,
		speedReductionFactor = speedReductionFactor,
		affectedTrains = affectedTrains,
		estimatedImpact = impact(delaySeconds)
	)

	private fun route(
		lengthMeters: Double,
		cost: Double = lengthMeters
	): Route {
		val inA = mockk<InOut>(relaxed = true)
		val inB = mockk<InOut>(relaxed = true)
		val section = mockk<TrackSection>(relaxed = true)
		every { section.length() } returns lengthMeters
		return Route(
			start = inA,
			target = inB,
			segments = listOf(section),
			cost = cost,
			costBreakdown = listOf(SegmentCost(section, cost))
		)
	}

	private fun reroute(
		trainId: String = "T1",
		lengthMeters: Double,
		cost: Double = lengthMeters,
		delaySeconds: Double = 0.0,
		affectedTrains: List<String> = listOf(trainId)
	) = ConflictResolution.Reroute(
		trainId = trainId,
		alternativeRoute = route(lengthMeters, cost),
		affectedTrains = affectedTrains,
		estimatedImpact = impact(delaySeconds)
	)

	// ── score() ──────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("score()")
	inner class ScoreTests {
		@Test
		@DisplayName("HoldTrain score = delaySeconds + affectedTrains.size * 10_000")
		fun holdTrainScore() {
			// 45.0 * 1.0 + 1 * 10_000.0 = 10_045.0
			val candidate = hold(holdDurationSeconds = 45.0)

			assertThat(ConflictResolutionRanker.score(candidate)).isEqualTo(10_045.0)
		}

		@Test
		@DisplayName("SpeedAdjust score = delaySeconds + affectedTrains.size * 10_000, path length contributes 0")
		fun speedAdjustScore() {
			// 0.0 * 1.0 + 1 * 10_000.0 + 0.0 = 10_000.0
			val candidate = speedAdjust()

			assertThat(ConflictResolutionRanker.score(candidate)).isEqualTo(10_000.0)
		}

		@Test
		@DisplayName("Reroute score includes path length change weighted by 0.1")
		fun rerouteScoreIncludesPathLength() {
			// 0.0 * 1.0 + 1 * 10_000.0 + 200.0 * 0.1 = 10_020.0
			val candidate = reroute(lengthMeters = 200.0)

			assertThat(ConflictResolutionRanker.score(candidate)).isEqualTo(10_020.0)
		}

		@Test
		@DisplayName("affected-train count dominates: 2-train HoldTrain outscores 1-train Reroute with huge delay/length")
		fun affectedTrainCountDominates() {
			// HoldTrain: 5.0 + 2 * 10_000.0 = 20_005.0
			val twoTrainHold = hold(holdDurationSeconds = 5.0, affectedTrains = listOf("T1", "T2"))
			// Reroute: 500.0 + 1 * 10_000.0 + 5_000.0 * 0.1 = 10_500.0 + 500 = 11_000.0
			val oneTrainReroute = reroute(lengthMeters = 5_000.0, delaySeconds = 500.0)

			assertThat(ConflictResolutionRanker.score(twoTrainHold) > ConflictResolutionRanker.score(oneTrainReroute))
				.isEqualTo(true)
		}

		@Test
		@DisplayName("pathLengthChange() is 0.0 for HoldTrain and SpeedAdjust, route length for Reroute")
		fun pathLengthChangeBySubtype() {
			assertThat(ConflictResolutionRanker.pathLengthChange(hold(holdDurationSeconds = 10.0))).isEqualTo(0.0)
			assertThat(ConflictResolutionRanker.pathLengthChange(speedAdjust())).isEqualTo(0.0)
			assertThat(ConflictResolutionRanker.pathLengthChange(reroute(lengthMeters = 123.0))).isEqualTo(123.0)
		}
	}

	// ── rank() — hand-calculated orders ────────────────────────────────────

	@Nested
	@DisplayName("rank() — hand-calculated orders")
	inner class RankTests {
		@Test
		@DisplayName("orders a mix of HoldTrain, Reroute and SpeedAdjust by ascending score")
		fun ordersMixedCandidatesByScore() {
			// Scores (hand-calculated):
			// speedAdjust:            0 + 10_000 + 0        = 10_000.0  (least disruptive)
			// reroute(len=100):       0 + 10_000 + 10.0      = 10_010.0
			// hold(20s):             20 + 10_000 + 0         = 10_020.0
			// hold(45s):             45 + 10_000 + 0         = 10_045.0  (most disruptive)
			val speed = speedAdjust()
			val shortReroute = reroute(lengthMeters = 100.0)
			val shortHold = hold(holdDurationSeconds = 20.0)
			val longHold = hold(holdDurationSeconds = 45.0)

			val ranked = ConflictResolutionRanker.rank(listOf(longHold, shortHold, shortReroute, speed))

			assertThat(ranked).containsExactly(speed, shortReroute, shortHold, longHold)
		}

		@Test
		@DisplayName("candidates affecting fewer trains always rank before ones affecting more, regardless of delay")
		fun fewerAffectedTrainsRanksFirst() {
			// oneTrainHold: 1_000.0 + 1 * 10_000.0 = 11_000.0
			val oneTrainHold = hold(holdDurationSeconds = 1_000.0)
			// twoTrainSpeedAdjust: 0.0 + 2 * 10_000.0 = 20_000.0
			val twoTrainSpeedAdjust = speedAdjust(delaySeconds = 0.0, affectedTrains = listOf("T1", "T2"))

			val ranked = ConflictResolutionRanker.rank(listOf(twoTrainSpeedAdjust, oneTrainHold))

			assertThat(ranked).containsExactly(oneTrainHold, twoTrainSpeedAdjust)
		}

		@Test
		@DisplayName("reroute path length change breaks ties among equal-delay candidates")
		fun rerouteLengthOrdersAmongEqualDelay() {
			// shortRoute: 0 + 10_000 + 50.0 * 0.1  = 10_005.0
			// longRoute:  0 + 10_000 + 300.0 * 0.1 = 10_030.0
			val shortRoute = reroute(lengthMeters = 50.0)
			val longRoute = reroute(lengthMeters = 300.0)

			val ranked = ConflictResolutionRanker.rank(listOf(longRoute, shortRoute))

			assertThat(ranked).containsExactly(shortRoute, longRoute)
		}

		@Test
		@DisplayName("tie-break: equal score orders HoldTrain before Reroute before SpeedAdjust")
		fun tieBreakOrdersByStrategyOrdinal() {
			// All three candidates are engineered to score exactly 10_030.0 (single
			// affected train each):
			// hold30:      30.0 + 10_000.0                     = 10_030.0
			// reroute300:   0.0 + 10_000.0 + 300.0 * 0.1        = 10_030.0
			// speed30:     30.0 + 10_000.0                     = 10_030.0
			// With scores tied, the strategy-ordinal tie-break decides the order:
			// HOLD_TRAIN (0) < REROUTE (1) < SPEED_ADJUST (2).
			val hold30 = hold(holdDurationSeconds = 30.0)
			val reroute300 = reroute(lengthMeters = 300.0, delaySeconds = 0.0)
			val speed30 = speedAdjust(delaySeconds = 30.0)

			assertThat(ConflictResolutionRanker.score(hold30)).isEqualTo(10_030.0)
			assertThat(ConflictResolutionRanker.score(reroute300)).isEqualTo(10_030.0)
			assertThat(ConflictResolutionRanker.score(speed30)).isEqualTo(10_030.0)

			val ranked = ConflictResolutionRanker.rank(listOf(speed30, reroute300, hold30))

			assertThat(ranked).containsExactly(hold30, reroute300, speed30)
		}

		@Test
		@DisplayName("empty list ranks to an empty list")
		fun emptyListRanksEmpty() {
			assertThat(ConflictResolutionRanker.rank(emptyList())).containsExactly()
		}
	}
}
