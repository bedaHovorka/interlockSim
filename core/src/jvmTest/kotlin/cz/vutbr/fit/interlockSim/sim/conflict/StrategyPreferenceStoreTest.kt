/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 9 SP6: StrategyPreferenceStore and preference learning (Issue #592).
 */
package cz.vutbr.fit.interlockSim.sim.conflict

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.paths.Route
import cz.vutbr.fit.interlockSim.objects.paths.SegmentCost
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StrategyPreferenceStore] and the preference-aware
 * [ConflictResolutionRanker.rank] overload — Goal 9 SP6 (#592).
 *
 * Covers:
 * - Recording choices increments selection counts.
 * - [StrategyPreferenceStore.preferenceAdjustment] scales linearly with count.
 * - Repeated selections of the same strategy for a conflict type raise its rank.
 * - Different conflict-type keys are independent.
 * - [StrategyPreferenceStore.reset] clears all learned preferences.
 * - [StrategyPreferenceStore.clearFor] clears one conflict type only.
 * - Acceptance criterion: choosing REROUTE 3 times for a conflict type causes it to
 *   rank above SPEED_ADJUST for that type.
 *
 * @since Issue #592 (Goal 9 SP6)
 */
@DisplayName("StrategyPreferenceStore — Goal 9 SP6 (#592)")
class StrategyPreferenceStoreTest {
	private lateinit var store: StrategyPreferenceStore

	@BeforeEach
	fun setUp() {
		store = StrategyPreferenceStore()
	}

	// ── Helpers (mirrors ConflictResolutionRankerTest) ────────────────────

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

	// ── selectionCount / recordChoice ────────────────────────────────────

	@Nested
	@DisplayName("selectionCount() and recordChoice()")
	inner class SelectionCountTests {
		@Test
		@DisplayName("initial count is 0 for any combination")
		fun initialCountIsZero() {
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.HOLD_TRAIN)).isEqualTo(0)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(0)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.SPEED_ADJUST)).isEqualTo(0)
		}

		@Test
		@DisplayName("recordChoice increments count by 1 each time")
		fun recordChoiceIncrementsCount() {
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(1)

			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(2)

			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(3)
		}

		@Test
		@DisplayName("counts for different strategies within the same conflict type are independent")
		fun countsPerStrategyAreIndependent() {
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			store.recordChoice("B1", ConflictResolution.Strategy.HOLD_TRAIN)
			store.recordChoice("B1", ConflictResolution.Strategy.HOLD_TRAIN)

			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(1)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.HOLD_TRAIN)).isEqualTo(2)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.SPEED_ADJUST)).isEqualTo(0)
		}

		@Test
		@DisplayName("counts for different conflict-type keys are isolated")
		fun countsForDifferentKeysAreIsolated() {
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			store.recordChoice("B2", ConflictResolution.Strategy.REROUTE)

			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(2)
			assertThat(store.selectionCount("B2", ConflictResolution.Strategy.REROUTE)).isEqualTo(1)
		}
	}

	// ── preferenceAdjustment ─────────────────────────────────────────────

	@Nested
	@DisplayName("preferenceAdjustment()")
	inner class PreferenceAdjustmentTests {
		@Test
		@DisplayName("zero selections → zero adjustment")
		fun zeroSelectionsGivesZeroAdjustment() {
			assertThat(store.preferenceAdjustment("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(0.0)
		}

		@Test
		@DisplayName("adjustment scales linearly: count * PREFERENCE_BOOST_PER_SELECTION")
		fun adjustmentScalesLinearlyWithCount() {
			repeat(3) { store.recordChoice("B1", ConflictResolution.Strategy.HOLD_TRAIN) }

			val expected = 3 * StrategyPreferenceStore.PREFERENCE_BOOST_PER_SELECTION
			assertThat(store.preferenceAdjustment("B1", ConflictResolution.Strategy.HOLD_TRAIN))
				.isEqualTo(expected)
		}

		@Test
		@DisplayName("adjustment for an unrecorded strategy within a known key is 0")
		fun adjustmentForUnrecordedStrategyIsZero() {
			store.recordChoice("B1", ConflictResolution.Strategy.HOLD_TRAIN)

			assertThat(store.preferenceAdjustment("B1", ConflictResolution.Strategy.REROUTE))
				.isEqualTo(0.0)
		}
	}

	// ── reset() ──────────────────────────────────────────────────────────

	@Nested
	@DisplayName("reset()")
	inner class ResetTests {
		@Test
		@DisplayName("reset() clears all recorded choices across all conflict types")
		fun resetClearsAllChoices() {
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			store.recordChoice("B2", ConflictResolution.Strategy.HOLD_TRAIN)

			store.reset()

			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(0)
			assertThat(store.selectionCount("B2", ConflictResolution.Strategy.HOLD_TRAIN)).isEqualTo(0)
		}

		@Test
		@DisplayName("reset() allows recording fresh choices afterwards")
		fun resetAllowsFreshChoices() {
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			store.reset()

			store.recordChoice("B1", ConflictResolution.Strategy.HOLD_TRAIN)

			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.HOLD_TRAIN)).isEqualTo(1)
			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(0)
		}
	}

	// ── clearFor() ───────────────────────────────────────────────────────

	@Nested
	@DisplayName("clearFor()")
	inner class ClearForTests {
		@Test
		@DisplayName("clearFor() removes preferences for the specified key only")
		fun clearForRemovesOnlySpecifiedKey() {
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
			store.recordChoice("B2", ConflictResolution.Strategy.HOLD_TRAIN)

			store.clearFor("B1")

			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(0)
			// B2 preferences must be intact
			assertThat(store.selectionCount("B2", ConflictResolution.Strategy.HOLD_TRAIN)).isEqualTo(1)
		}

		@Test
		@DisplayName("clearFor() on an unknown key is a no-op")
		fun clearForUnknownKeyIsNoOp() {
			store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)

			store.clearFor("unknown-key")

			assertThat(store.selectionCount("B1", ConflictResolution.Strategy.REROUTE)).isEqualTo(1)
		}
	}

	// ── Preference-weighted ranking (ConflictResolutionRanker integration) ──

	@Nested
	@DisplayName("ConflictResolutionRanker.rank() with preference store")
	inner class PreferenceRankingTests {
		/**
		 * Acceptance criterion (Issue #592):
		 * Choosing REROUTE 3 times for the same conflict type must move the reroute
		 * candidate **higher** in the ranking — above SPEED_ADJUST, which would
		 * otherwise rank first (lower base score).
		 *
		 * Base scores (single-train candidates, path 100 m):
		 * - speedAdjust: 0 + 10_000 = 10_000
		 * - reroute:     0 + 10_000 + 100 * 0.1 = 10_010
		 *
		 * After 3 REROUTE selections (boost = 3 × 100 = 300):
		 * - speedAdjust adjusted: 10_000 − 0 = 10_000
		 * - reroute adjusted:     10_010 − 300 = 9_710
		 *
		 * Therefore reroute ranks first (less disruptive in context).
		 */
		@Test
		@DisplayName("selecting REROUTE 3 times for a conflict type raises it above SPEED_ADJUST")
		fun choosing3ReroutesMovesItAboveSpeedAdjust() {
			val speed = speedAdjust()
			val rerouteCandidate = reroute(lengthMeters = 100.0)

			// Baseline (without preferences): speedAdjust (10_000) < reroute (10_010)
			val baselineRanked = ConflictResolutionRanker.rank(listOf(rerouteCandidate, speed))
			assertThat(baselineRanked).containsExactly(speed, rerouteCandidate)

			// Record 3 REROUTE choices for conflict type "SWITCH_S1"
			repeat(3) { store.recordChoice("SWITCH_S1", ConflictResolution.Strategy.REROUTE) }

			// With preferences: reroute adjusted (9_710) < speedAdjust (10_000)
			val preferenceRanked = ConflictResolutionRanker.rank(listOf(rerouteCandidate, speed), store, "SWITCH_S1")
			assertThat(preferenceRanked).containsExactly(rerouteCandidate, speed)
		}

		@Test
		@DisplayName("preference-weighted rank for unknown conflict type equals unweighted rank")
		fun unknownConflictTypeGivesUnweightedRank() {
			val speed = speedAdjust()
			val rerouteCandidate = reroute(lengthMeters = 100.0)

			store.recordChoice("OTHER_BLOCK", ConflictResolution.Strategy.REROUTE)

			// No preferences for "SWITCH_S1" → same order as base rank
			val baseRanked = ConflictResolutionRanker.rank(listOf(rerouteCandidate, speed))
			val preferenceRanked =
				ConflictResolutionRanker.rank(listOf(rerouteCandidate, speed), store, "SWITCH_S1")

			assertThat(preferenceRanked).containsExactly(*baseRanked.toTypedArray())
		}

		@Test
		@DisplayName("preferences from one conflict type do not affect ranking for another type")
		fun preferencesAreIsolatedByConflictType() {
			val speed = speedAdjust()
			val rerouteCandidate = reroute(lengthMeters = 100.0)

			repeat(5) { store.recordChoice("BLOCK_A", ConflictResolution.Strategy.REROUTE) }

			// BLOCK_B has no preferences — order must follow base score
			val ranked = ConflictResolutionRanker.rank(listOf(rerouteCandidate, speed), store, "BLOCK_B")
			assertThat(ranked).containsExactly(speed, rerouteCandidate)
		}

		@Test
		@DisplayName("preference boost for frequently chosen strategy grows with each additional selection")
		fun preferenceBoostGrowsWithSelections() {
			val speed = speedAdjust()
			val rerouteCandidate = reroute(lengthMeters = 100.0)

			// Capture adjusted score after each additional selection — must decrease monotonically
			val adjustedScores =
				(0..3).map { n ->
					if (n > 0) store.recordChoice("B1", ConflictResolution.Strategy.REROUTE)
					ConflictResolutionRanker.score(rerouteCandidate) -
						store.preferenceAdjustment("B1", ConflictResolution.Strategy.REROUTE)
				}

			// Each additional selection must lower the adjusted score
			for (i in 0 until adjustedScores.size - 1) {
				assertThat(adjustedScores[i]).isGreaterThan(adjustedScores[i + 1])
			}
		}

		@Test
		@DisplayName("after reset() the preference-weighted rank equals the unweighted rank")
		fun afterResetPreferenceRankEqualsBaseRank() {
			val speed = speedAdjust()
			val rerouteCandidate = reroute(lengthMeters = 100.0)

			repeat(5) { store.recordChoice("B1", ConflictResolution.Strategy.REROUTE) }
			store.reset()

			val baseRanked = ConflictResolutionRanker.rank(listOf(rerouteCandidate, speed))
			val preferenceRanked = ConflictResolutionRanker.rank(listOf(rerouteCandidate, speed), store, "B1")
			assertThat(preferenceRanked).containsExactly(*baseRanked.toTypedArray())
		}

		@Test
		@DisplayName("holds, reroutes and speed-adjusts compete correctly after multi-strategy learning")
		fun multiStrategyLearningRanksCorrectly() {
			// Without any learning the base order is:
			// speedAdjust (10_000) < hold(30s) (10_030) < reroute(500m) (10_050)
			val speed = speedAdjust()
			val holdCandidate = hold(holdDurationSeconds = 30.0)
			val rerouteCandidate = reroute(lengthMeters = 500.0)

			val baseRanked = ConflictResolutionRanker.rank(listOf(rerouteCandidate, holdCandidate, speed))
			assertThat(baseRanked).containsExactly(speed, holdCandidate, rerouteCandidate)

			// Dispatcher chose REROUTE twice and HOLD_TRAIN once for "JUNCTION_X"
			store.recordChoice("JUNCTION_X", ConflictResolution.Strategy.REROUTE)
			store.recordChoice("JUNCTION_X", ConflictResolution.Strategy.REROUTE)
			store.recordChoice("JUNCTION_X", ConflictResolution.Strategy.HOLD_TRAIN)

			// Adjusted scores:
			// speed:    10_000 − 0        = 10_000
			// hold:     10_030 − 100      = 9_930
			// reroute:  10_050 − 200      = 9_850  ← most preferred
			val preferenceRanked =
				ConflictResolutionRanker.rank(listOf(rerouteCandidate, holdCandidate, speed), store, "JUNCTION_X")
			assertThat(preferenceRanked).containsExactly(rerouteCandidate, holdCandidate, speed)
		}

		/**
		 * Regression guard for the [StrategyPreferenceStore.MAX_PREFERENCE_ADJUSTMENT] cap.
		 *
		 * A preferred strategy affecting MORE trains must never outrank a less-preferred
		 * one affecting FEWER trains, no matter how large the selection history grows.
		 *
		 * Base scores:
		 * - moreAffected (REROUTE, 2 trains, 100 m): 0 + 2×10_000 + 100×0.1 = 20_010
		 * - lessAffected (SPEED_ADJUST, 1 train):    0 + 1×10_000         = 10_000
		 *
		 * After 200 REROUTE selections for "B1":
		 * - with cap:    REROUTE boost = min(200×100, 5_000) = 5_000
		 *   → moreAffected adjusted = 20_010 − 5_000 = 15_010
		 *   → lessAffected adjusted = 10_000 − 0     = 10_000
		 *   → lessAffected still ranks first (invariant holds).
		 * - without cap: REROUTE boost = 20_000
		 *   → moreAffected adjusted = 10, which would wrongly outrank lessAffected (10_000).
		 */
		@Test
		@DisplayName("a preferred strategy affecting MORE trains never outranks one affecting FEWER, regardless of history")
		fun capPreservesAffectedTrainInvariant() {
			val moreAffected = reroute(lengthMeters = 100.0, affectedTrains = listOf("T1", "T2"))
			val lessAffected = speedAdjust(affectedTrains = listOf("T1"))

			// Baseline: fewer affected trains ranks first.
			val baseline = ConflictResolutionRanker.rank(listOf(moreAffected, lessAffected))
			assertThat(baseline).containsExactly(lessAffected, moreAffected)

			// Record a REROUTE history large enough to exceed AFFECTED_TRAIN_WEIGHT without the cap.
			repeat(200) { store.recordChoice("B1", ConflictResolution.Strategy.REROUTE) }

			val ranked = ConflictResolutionRanker.rank(listOf(moreAffected, lessAffected), store, "B1")
			assertThat(ranked).containsExactly(lessAffected, moreAffected)
		}
	}
}
