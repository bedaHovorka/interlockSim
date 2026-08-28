/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CycleHistory] — the live-path bounded history (SP2c.24, Issue #847).
 *
 * @since Issue #847 (SP2c.24 — parameter grid; `historyN` made live)
 */
@DisplayName("SP2c.24 — CycleHistory: bounded per-cycle history in the prompt (#847)")
class CycleHistoryTest {
	private fun record(
		history: CycleHistory,
		simTime: Double,
		vararg actions: DispatchAction
	) = history.record(simTime, TickOutcome.LLM_ACTIONS, actions.toList())

	@Test
	fun `keeps at most capacity entries, dropping the oldest`() {
		val history = CycleHistory(capacity = 2)
		record(history, 1.0, DispatchAction.ApproveTrain("Train #1"))
		record(history, 2.0, DispatchAction.ApproveTrain("Train #2"))
		record(history, 3.0, DispatchAction.ApproveTrain("Train #3"))

		assertThat(history.snapshot()).hasSize(2)
		assertThat(history.snapshot().first().simTime).isEqualTo(2.0)
		assertThat(history.snapshot().last().simTime).isEqualTo(3.0)
	}

	@Test
	@DisplayName("capacity 0 renders nothing at all — the control arm differs by absence, not by a bare header")
	fun disabledRendersNothing() {
		val history = CycleHistory(capacity = 0)
		record(history, 1.0, DispatchAction.ApproveTrain("Train #1"))

		assertThat(history.snapshot()).isEmpty()
		assertThat(history.renderPromptBlock()).isEqualTo("")
	}

	@Test
	fun `renders nothing before the first cycle`() {
		assertThat(CycleHistory(capacity = 3).renderPromptBlock()).isEqualTo("")
	}

	@Test
	fun `renders each recorded action with the arguments the tools accept`() {
		val history = CycleHistory(capacity = 3)
		record(
			history,
			12.0,
			DispatchAction.ApproveTrain("Train #1"),
			DispatchAction.RequestRoute("Train #1", "doB1", "B")
		)
		val block = history.renderPromptBlock()

		assertThat(block).contains("approve_train(Train #1)")
		assertThat(block).contains("request_route(Train #1, doB1 -> B)")
		assertThat(block).contains("t=12.0")
	}

	@Test
	@DisplayName("a cycle that emitted nothing says so rather than rendering an empty line")
	fun emptyCycleIsExplicit() {
		val history = CycleHistory(capacity = 3)
		history.record(4.0, TickOutcome.RULE_FALLBACK, emptyList())

		val block = history.renderPromptBlock()
		assertThat(block).contains("no action emitted")
		assertThat(block).contains(TickOutcome.RULE_FALLBACK.name)
	}

	@Test
	@DisplayName("the same recorded sequence always renders the same text (P8)")
	fun renderingIsDeterministic() {
		fun build(): String {
			val history = CycleHistory(capacity = 3)
			record(history, 1.0, DispatchAction.ApproveTrain("Train #1"))
			record(history, 2.0, DispatchAction.CancelRoute("Train #1"))
			return history.renderPromptBlock()
		}

		assertThat(build()).isEqualTo(build())
	}

	@Test
	fun `clear drops every recorded cycle`() {
		val history = CycleHistory(capacity = 3)
		record(history, 1.0, DispatchAction.NoOp)
		history.clear()

		assertThat(history.snapshot()).isEmpty()
		assertThat(history.renderPromptBlock()).doesNotContain("no_op")
	}
}
