/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Exhaustive mapping table for [TickOutcome.toActionAuthor] — the projection used by
 * [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] to attribute each dispatch cycle's
 * posted decisions to the correct [ActionAuthor] (SP2c.20 follow-up, Issue #843).
 */
@DisplayName("TickOutcome.toActionAuthor mapping (SP2c.20 follow-up)")
class TickOutcomeAuthorMappingTest {
	@ParameterizedTest
	@EnumSource(TickOutcome::class, names = ["LLM_ACTIONS", "LLM_NO_OP", "LLM_REPAIRED"])
	@DisplayName("LLM-success outcomes map to ActionAuthor.LLM")
	fun llmSuccessMapsToLlm(outcome: TickOutcome) {
		assertThat(outcome.toActionAuthor).isEqualTo(ActionAuthor.LLM)
	}

	@ParameterizedTest
	@EnumSource(TickOutcome::class, names = ["TIMEOUT_NOOP", "LLM_EXCEPTION"])
	@DisplayName("no-dispatching outcomes map to ActionAuthor.TIMEOUT_NOOP")
	fun noDispatchOutcomesMapToTimeoutNoop(outcome: TickOutcome) {
		assertThat(outcome.toActionAuthor).isEqualTo(ActionAuthor.TIMEOUT_NOOP)
	}

	@ParameterizedTest
	@EnumSource(TickOutcome::class, names = ["LLM_ABANDONED", "RULE_FALLBACK"])
	@DisplayName("deterministic-fallback outcomes map to ActionAuthor.RULE_FALLBACK")
	fun fallbackOutcomesMapToRuleFallback(outcome: TickOutcome) {
		assertThat(outcome.toActionAuthor).isEqualTo(ActionAuthor.RULE_FALLBACK)
	}

	@Test
	@DisplayName("mapping is exhaustive over every TickOutcome entry")
	fun mappingIsExhaustive() {
		TickOutcome.entries.forEach { outcome ->
			// Must not throw — every entry has a mapping.
			assertThat(outcome.toActionAuthor).isEqualTo(outcome.toActionAuthor)
		}
	}
}
