/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * SP2b.2: RuleBasedDispatcher rule engine (Issue #557).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.navigation.PathCandidate
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [CandidatePathRuleEngine] — the deterministic route-scoring rule engine
 * introduced by SP2b.2 (Issue #557).
 *
 * The engine is a pure function over [PathCandidate] value objects, so tests build
 * candidates directly; sections are relaxed [TrackSection] mocks used only for their
 * count and identity.
 *
 * @since Issue #557 (SP2b.2 — Goal 10)
 */
@DisplayName("CandidatePathRuleEngine — SP2b.2 route-scoring rule engine")
@Timeout(30, unit = TimeUnit.SECONDS)
class CandidatePathRuleEngineTest {
	// ── Test data builders ────────────────────────────────────────────────────

	private fun sections(count: Int): List<TrackSection> = List(count) { mockk<TrackSection>(relaxed = true) }

	private fun candidate(
		sectionCount: Int = 1,
		switchMovementCount: Int = 0,
		conflictRiskWeight: Double = 0.0
	): PathCandidate =
		PathCandidate(
			sections = sections(sectionCount),
			switchMovementCount = switchMovementCount,
			conflictRiskWeight = conflictRiskWeight
		)

	// ── Default priority: conflict risk → shortest path → switch movements ────

	@Nested
	@DisplayName("default priority order")
	inner class DefaultPriority {
		private val engine = CandidatePathRuleEngine()

		@Test
		@DisplayName("ranks lowest conflict risk first, ahead of a shorter but riskier route")
		fun conflictRiskDominates() {
			val short = candidate(sectionCount = 1, conflictRiskWeight = 0.9)
			val safe = candidate(sectionCount = 5, conflictRiskWeight = 0.1)

			val ranked = engine.rank(listOf(short, safe))

			assertThat(ranked).containsExactly(safe, short)
		}

		@Test
		@DisplayName("breaks equal conflict risk by shortest path")
		fun shortestPathBreaksTie() {
			val long = candidate(sectionCount = 4, conflictRiskWeight = 0.3)
			val shortRoute = candidate(sectionCount = 2, conflictRiskWeight = 0.3)

			val ranked = engine.rank(listOf(long, shortRoute))

			assertThat(ranked).containsExactly(shortRoute, long)
		}

		@Test
		@DisplayName("breaks equal conflict risk and length by fewest switch movements")
		fun switchMovementsBreakTie() {
			val manySwitches = candidate(sectionCount = 3, switchMovementCount = 2, conflictRiskWeight = 0.0)
			val fewSwitches = candidate(sectionCount = 3, switchMovementCount = 1, conflictRiskWeight = 0.0)

			val ranked = engine.rank(listOf(manySwitches, fewSwitches))

			assertThat(ranked).containsExactly(fewSwitches, manySwitches)
		}

		@Test
		@DisplayName("DEFAULT_PRIORITY leads with conflict avoidance")
		fun defaultPriorityLeadsWithConflict() {
			assertThat(CandidatePathRuleEngine.DEFAULT_PRIORITY).containsExactly(
				CandidatePathRuleEngine.Rule.LOWEST_CONFLICT_RISK,
				CandidatePathRuleEngine.Rule.SHORTEST_PATH,
				CandidatePathRuleEngine.Rule.FEWEST_SWITCH_MOVEMENTS
			)
		}
	}

	// ── Custom priority ───────────────────────────────────────────────────────

	@Test
	@DisplayName("custom priority (shortest path first) overrides conflict-first default")
	fun customPriorityRespected() {
		val engine =
			CandidatePathRuleEngine(
				priority =
					listOf(
						CandidatePathRuleEngine.Rule.SHORTEST_PATH,
						CandidatePathRuleEngine.Rule.LOWEST_CONFLICT_RISK
					)
			)
		val shortRiskier = candidate(sectionCount = 1, conflictRiskWeight = 0.9)
		val longerSafer = candidate(sectionCount = 5, conflictRiskWeight = 0.1)

		val ranked = engine.rank(listOf(longerSafer, shortRiskier))

		assertThat(ranked).containsExactly(shortRiskier, longerSafer)
	}

	@Test
	@DisplayName("single-rule engine ranks purely by that rule")
	fun singleRuleEngine() {
		val engine = CandidatePathRuleEngine(priority = listOf(CandidatePathRuleEngine.Rule.FEWEST_SWITCH_MOVEMENTS))
		val a = candidate(switchMovementCount = 3)
		val b = candidate(switchMovementCount = 0)
		val c = candidate(switchMovementCount = 1)

		val ranked = engine.rank(listOf(a, b, c))

		assertThat(ranked).containsExactly(b, c, a)
	}

	// ── Determinism & stability ───────────────────────────────────────────────

	@Test
	@DisplayName("candidates equal under every rule keep their input order (stable)")
	fun stableForEqualCandidates() {
		val engine = CandidatePathRuleEngine()
		val first = candidate(sectionCount = 2, switchMovementCount = 1, conflictRiskWeight = 0.5)
		val second = candidate(sectionCount = 2, switchMovementCount = 1, conflictRiskWeight = 0.5)

		val ranked = engine.rank(listOf(first, second))

		assertThat(ranked).containsExactly(first, second)
	}

	@Test
	@DisplayName("ranking is repeatable across consecutive calls (Goal 10 A3 determinism)")
	fun repeatableRanking() {
		val engine = CandidatePathRuleEngine()
		val candidates =
			listOf(
				candidate(sectionCount = 3, switchMovementCount = 1, conflictRiskWeight = 0.4),
				candidate(sectionCount = 1, switchMovementCount = 2, conflictRiskWeight = 0.1),
				candidate(sectionCount = 2, switchMovementCount = 0, conflictRiskWeight = 0.4)
			)

		val first = engine.rank(candidates)
		val second = engine.rank(candidates)

		assertThat(first).isEqualTo(second)
	}

	@Test
	@DisplayName("rank does not mutate the input list")
	fun rankDoesNotMutateInput() {
		val engine = CandidatePathRuleEngine()
		val riskier = candidate(conflictRiskWeight = 0.9)
		val safer = candidate(conflictRiskWeight = 0.1)
		val input = listOf(riskier, safer)

		engine.rank(input)

		assertThat(input).containsExactly(riskier, safer)
	}

	// ── Edge cases ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("rank of an empty list is empty")
	fun rankEmpty() {
		assertThat(CandidatePathRuleEngine().rank(emptyList())).isEmpty()
	}

	@Test
	@DisplayName("rank of a single candidate returns it")
	fun rankSingle() {
		val only = candidate()
		assertThat(CandidatePathRuleEngine().rank(listOf(only))).containsExactly(only)
	}

	@Test
	@DisplayName("select returns the best candidate")
	fun selectReturnsBest() {
		val engine = CandidatePathRuleEngine()
		val riskier = candidate(conflictRiskWeight = 0.9)
		val safer = candidate(conflictRiskWeight = 0.1)

		assertThat(engine.select(listOf(riskier, safer))).isSameInstanceAs(safer)
	}

	@Test
	@DisplayName("select of an empty list is null")
	fun selectEmptyIsNull() {
		assertThat(CandidatePathRuleEngine().select(emptyList())).isNull()
	}

	// ── Conflict-risk enrichment ──────────────────────────────────────────────

	@Nested
	@DisplayName("assignConflictRisk")
	inner class AssignConflictRisk {
		private val engine = CandidatePathRuleEngine()

		@Test
		@DisplayName("sets conflictRiskWeight to the fraction of busy sections")
		fun fractionOfBusySections() {
			val busy = mockk<TrackSection>(relaxed = true)
			val free1 = mockk<TrackSection>(relaxed = true)
			val free2 = mockk<TrackSection>(relaxed = true)
			val candidate = PathCandidate(sections = listOf(busy, free1, free2), switchMovementCount = 0)

			val enriched = engine.assignConflictRisk(listOf(candidate)) { it === busy }

			assertThat(enriched).hasSize(1)
			assertThat(enriched[0].conflictRiskWeight).isEqualTo(1.0 / 3.0)
		}

		@Test
		@DisplayName("assigns 0.0 when no section is busy")
		fun allFree() {
			val candidate = PathCandidate(sections = sections(3), switchMovementCount = 0, conflictRiskWeight = 0.5)

			val enriched = engine.assignConflictRisk(listOf(candidate)) { false }

			assertThat(enriched[0].conflictRiskWeight).isEqualTo(0.0)
		}

		@Test
		@DisplayName("assigns 1.0 when every section is busy")
		fun allBusy() {
			val candidate = PathCandidate(sections = sections(2), switchMovementCount = 0)

			val enriched = engine.assignConflictRisk(listOf(candidate)) { true }

			assertThat(enriched[0].conflictRiskWeight).isEqualTo(1.0)
		}

		@Test
		@DisplayName("assigns 0.0 to a candidate with no sections")
		fun emptySections() {
			val candidate = PathCandidate(sections = emptyList(), switchMovementCount = 0, conflictRiskWeight = 0.7)

			val enriched = engine.assignConflictRisk(listOf(candidate)) { true }

			assertThat(enriched[0].conflictRiskWeight).isEqualTo(0.0)
		}

		@Test
		@DisplayName("does not mutate the original candidate")
		fun doesNotMutateOriginal() {
			val candidate = PathCandidate(sections = sections(2), switchMovementCount = 0, conflictRiskWeight = 0.0)

			engine.assignConflictRisk(listOf(candidate)) { true }

			assertThat(candidate.conflictRiskWeight).isEqualTo(0.0)
		}

		@Test
		@DisplayName("enriched candidates feed straight into rank for conflict-aware selection")
		fun enrichThenRank() {
			val busy = mockk<TrackSection>(relaxed = true)
			val free = mockk<TrackSection>(relaxed = true)
			// Same length/switch metrics: only occupancy differs.
			val throughBusy = PathCandidate(sections = listOf(busy), switchMovementCount = 0)
			val throughFree = PathCandidate(sections = listOf(free), switchMovementCount = 0)

			val enriched = engine.assignConflictRisk(listOf(throughBusy, throughFree)) { it === busy }
			val ranked = engine.rank(enriched)

			assertThat(ranked[0].sections).containsExactly(free)
		}
	}

	// ── Constructor guards ────────────────────────────────────────────────────

	@Test
	@DisplayName("empty priority is rejected")
	fun emptyPriorityRejected() {
		assertFailure { CandidatePathRuleEngine(priority = emptyList()) }
			.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	@DisplayName("duplicate rules in priority are rejected")
	fun duplicatePriorityRejected() {
		assertFailure {
			CandidatePathRuleEngine(
				priority =
					listOf(
						CandidatePathRuleEngine.Rule.SHORTEST_PATH,
						CandidatePathRuleEngine.Rule.SHORTEST_PATH
					)
			)
		}.isInstanceOf<IllegalArgumentException>()
	}
}
