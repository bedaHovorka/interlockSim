/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.CleanRunRule
import cz.vutbr.fit.interlockSim.dispatcher.planner.CompositeActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A decision the applier carries out without a correlation entry has no known author (Issue #1477,
 * Goal 9B SP5.4). It is counted in the unattributed-action counter (`unattributedApplies`) and under
 * no author, `LLM` included, so the clean-run rule of #1476 judges the run not clean.
 *
 * Before #1477 the applier credited such a decision to `LLM` (`correlation?.author ?: LLM`), which
 * let an LLM run with an unknown actor look clean on its author counts.
 */
@DisplayName("A decision applied without a correlation is unattributed, never LLM (Issue #1477)")
class UnattributedDecisionIsNotLlmTest {
	@Test
	fun `a decision applied without a correlation is not counted as LLM and the run is not clean`() {
		val recorder =
			DefaultDispatcherRunRecorder(
				runId = "unattributed",
				arm = DispatcherArm.LLM_TOOL_CALLING,
				params =
					RunParameters(
						tickPeriodMs = 0L,
						historyN = 0,
						temperature = 0.28,
						maxActionsPerTick = 3,
						model = "test-model",
						seed = null
					)
			)
		val outcomes = mutableListOf<ActionOutcome>()
		// The queue is not wired with the correlation map, so the decision reaches the applier with
		// no entry: the applier does not know who posted it.
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = mockk(relaxed = true),
				onApproveTrain = {},
				correlationMap = CommandCorrelationMap(),
				actionOutcomeSink =
					CompositeActionOutcomeSink.of(
						ActionOutcomeSink(recorder::onActionOutcome),
						ActionOutcomeSink {
							outcomes.add(it)
						}
					)
			)

		queue.postAll(listOf(DispatchDecision.ApproveTrain("T1")), ActionAuthor.LLM)
		applier.onControlStep()
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(outcomes).hasSize(1)
		assertThat(outcomes.single().authored.author, name = "author of the applied decision").isNull()
		assertThat(snapshot.actionsByAuthor[ActionAuthor.LLM.name], name = "LLM counter").isEqualTo(0L)
		assertThat(snapshot.actionsByAuthor.values.sum(), name = "all author counters").isEqualTo(0L)
		assertThat(snapshot.unattributedApplies, name = "unattributed counter").isEqualTo(1L)
		assertThat(CleanRunRule.isClean(snapshot)).isFalse()
		assertThat(CleanRunRule.violations(snapshot)).containsExactly(CleanRunRule.Violation.UNATTRIBUTED_ACTIONS)
	}
}
