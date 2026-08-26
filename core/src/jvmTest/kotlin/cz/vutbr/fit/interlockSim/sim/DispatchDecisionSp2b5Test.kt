/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.testutil.testCandidate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for SP2b.5 (Issue #560 — Goal 10):
 * - [DispatchDecision.rationale] as `List<String>` with rule-evaluation content.
 * - [CandidatePathRuleEngine.selectWithRationale] returning `Pair<PathCandidate?, List<String>>`.
 *
 * These tests focus on the NEW functionality introduced in SP2b.5; backward-
 * compatibility tests for the existing subtypes live in [DispatchDecisionSp2b1Test].
 * Tests for the shared `toRationaleLogSuffix` formatter and for the
 * `DispatchDecisionApplier` log-line integration live in `:dispatcher-agent`
 * (`DispatchDecisionApplierSp2b5Test`).
 *
 * @since Issue #560 (SP2b.5 — Goal 10)
 */
@DisplayName("DispatchDecision — SP2b.5 rationale as List<String> (Issue #560)")
@Timeout(30, unit = TimeUnit.SECONDS)
class DispatchDecisionSp2b5Test {
	// ── Helpers ──────────────────────────────────────────────────────────────

	// ── CandidatePathRuleEngine.selectWithRationale ───────────────────────────

	@Nested
	@DisplayName("CandidatePathRuleEngine.selectWithRationale")
	inner class SelectWithRationale {
		private val engine = CandidatePathRuleEngine()

		@Test
		@DisplayName("returns null candidate and a single 'no candidates' entry when list is empty")
		fun emptyList_returnsNullCandidateAndRationale() {
			val (selected, rationale) = engine.selectWithRationale(emptyList())

			assertThat(selected).isNull()
			assertThat(rationale).hasSize(1)
			assertThat(rationale[0]).contains("No candidate")
		}

		@Test
		@DisplayName("returns the best candidate and a non-empty rationale list")
		fun nonEmptyList_returnsBestCandidateAndRationale() {
			val safer = testCandidate(conflictRiskWeight = 0.1)
			val riskier = testCandidate(conflictRiskWeight = 0.9)

			val (selected, rationale) = engine.selectWithRationale(listOf(riskier, safer))

			assertThat(selected).isSameInstanceAs(safer)
			assertThat(rationale).isNotEmpty()
		}

		@Test
		@DisplayName("rationale includes the active priority rules in order")
		fun rationaleListsActiveRules() {
			val only = testCandidate()

			val (_, rationale) = engine.selectWithRationale(listOf(only))

			val rulesEntry = rationale.first { it.contains("Rules") || it.contains("priority") }
			assertThat(rulesEntry).contains("LOWEST_CONFLICT_RISK")
			assertThat(rulesEntry).contains("SHORTEST_PATH")
			assertThat(rulesEntry).contains("FEWEST_SWITCH_MOVEMENTS")
		}

		@Test
		@DisplayName("rationale includes selected candidate's key metrics (sections, switches, risk)")
		fun rationaleContainsSelectedMetrics() {
			val c = testCandidate(sectionCount = 3, switchMovementCount = 1, conflictRiskWeight = 0.5)

			val (_, rationale) = engine.selectWithRationale(listOf(c))

			val metricsEntry = rationale.first { it.contains("section") }
			assertThat(metricsEntry).contains("3")
			assertThat(metricsEntry).contains("1") // switchMovements
			assertThat(metricsEntry).contains("0.5") // conflictRisk
		}

		@Test
		@DisplayName("rationale mentions candidate count when multiple candidates present")
		fun rationaleIncludesCandidateCount() {
			val a = testCandidate(sectionCount = 1)
			val b = testCandidate(sectionCount = 2)
			val c = testCandidate(sectionCount = 3)

			val (_, rationale) = engine.selectWithRationale(listOf(a, b, c))

			val countEntry = rationale.firstOrNull { it.contains("3") }
			assertThat(countEntry).isNotNull()
		}

		@Test
		@DisplayName("select() still delegates to selectWithRationale and returns the same candidate")
		fun selectDelegatesToSelectWithRationale() {
			val safer = testCandidate(conflictRiskWeight = 0.1)
			val riskier = testCandidate(conflictRiskWeight = 0.9)

			val fromSelect = engine.select(listOf(riskier, safer))
			val (fromWithRationale, _) = engine.selectWithRationale(listOf(riskier, safer))

			assertThat(fromSelect).isSameInstanceAs(fromWithRationale)
		}

		@Test
		@DisplayName("single candidate: rationale still lists rules and metrics")
		fun singleCandidate_rationaleComplete() {
			val only = testCandidate(sectionCount = 2, switchMovementCount = 0, conflictRiskWeight = 0.0)

			val (selected, rationale) = engine.selectWithRationale(listOf(only))

			assertThat(selected).isSameInstanceAs(only)
			assertThat(rationale).isNotEmpty()
			// "Ranked N testCandidate(s)" entry is only added when more than one candidate
			val countEntry = rationale.firstOrNull { it.startsWith("Ranked") }
			assertThat(countEntry).isNull()
		}

		@Test
		@DisplayName("custom priority reflected in rationale rules entry")
		fun customPriorityReflectedInRationale() {
			val customEngine =
				CandidatePathRuleEngine(
					priority =
						listOf(
							CandidatePathRuleEngine.Rule.SHORTEST_PATH,
							CandidatePathRuleEngine.Rule.FEWEST_SWITCH_MOVEMENTS
						)
				)
			val only = testCandidate()

			val (_, rationale) = customEngine.selectWithRationale(listOf(only))

			val rulesEntry = rationale.first { it.contains("Rules") || it.contains("priority") }
			assertThat(rulesEntry).contains("SHORTEST_PATH")
			assertThat(rulesEntry).contains("FEWEST_SWITCH_MOVEMENTS")
		}
	}

	// ── DispatchDecision.rationale as List<String> ────────────────────────────

	@Nested
	@DisplayName("DispatchDecision.rationale is a List<String>")
	inner class RationaleAsList {
		@Test
		@DisplayName("rationale can carry multiple rule-evaluation strings")
		fun rationaleCanCarryMultipleEntries() {
			val decision =
				DispatchDecision.ApproveTrain(
					trainId = "T1",
					rationale =
						listOf(
							"Rule 1: capacity not reached",
							"Rule 2: FIFO — T1 is first in queue"
						)
				)

			assertThat(decision.rationale).containsExactly(
				"Rule 1: capacity not reached",
				"Rule 2: FIFO — T1 is first in queue"
			)
		}

		@Test
		@DisplayName("rationale from CandidatePathRuleEngine is assignable to DispatchDecision.rationale")
		fun ruleEngineRationaleAssignableToDecision() {
			val engine = CandidatePathRuleEngine()
			val (_, rationale) = engine.selectWithRationale(listOf(testCandidate()))

			// Simulate what a future dispatcher would do: assign rule-engine rationale to a decision
			val decision = DispatchDecision.ApproveTrain("T1", rationale = rationale)

			assertThat(decision.rationale).isNotEmpty()
			assertThat(decision.rationale).isEqualTo(rationale)
		}

		@Test
		@DisplayName("HoldTrain.rationale accepts a multi-entry list")
		fun holdTrainAcceptsMultiEntryRationale() {
			val decision =
				DispatchDecision.HoldTrain(
					trainId = "T2",
					holdDurationSeconds = 60.0,
					rationale =
						listOf(
							"Conflict detected on block 'k1'",
							"Holding T2 for 60s to create spacing"
						)
				)

			assertThat(decision.rationale).hasSize(2)
			assertThat(decision.rationale[0]).contains("k1")
			assertThat(decision.rationale[1]).contains("60s")
		}
	}
}
