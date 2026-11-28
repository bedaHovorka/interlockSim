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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.ActionOutcomeSink
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultDispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.testutil.DispatcherKoinTestBase
import cz.vutbr.fit.interlockSim.dispatcher.testutil.LiftedStackFixture
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The rule-based control arm records every action it carries out under [ActionAuthor.RULE_BASED]
 * (Issue #1477, Goal 9B SP5.4; row C15 of the #1475 table). The run drives `vyhybna.xml` through the
 * lifted stack — [AgentLoopDriver] with the rule-based planner, [ActuatorCommandQueue] registering
 * each post in a [CommandCorrelationMap], [DispatchDecisionApplier] reporting to a real
 * [DefaultDispatcherRunRecorder] — as the production wiring does, and reads the author counters of
 * the frozen run snapshot.
 */
@Tag("integration-test")
@DisplayName("The rule-based control arm records its actions as RULE_BASED (Issue #1477)")
class RuleBasedArmAuthorIntegrationTest : DispatcherKoinTestBase() {
	private val fixture = LiftedStackFixture()

	@Test
	@Timeout(10, unit = TimeUnit.SECONDS)
	fun `every action of the rule-based arm counts under RULE_BASED and none under LLM or unattributed`() {
		val context = fixture.loadShuntingLoopContext().tracked()
		context.getInOuts()
		val loop = ShuntingLoop(context, endTime = 300L)
		val recorder =
			DefaultDispatcherRunRecorder(
				runId = "rule-based-arm",
				arm = DispatcherArm.RULE_BASED,
				params =
					RunParameters(
						tickPeriodMs = 0L,
						historyN = 0,
						temperature = 0.0,
						maxActionsPerTick = 0,
						model = "",
						seed = null
					)
			)

		val run =
			fixture.run(
				loop,
				context,
				correlationMap = CommandCorrelationMap(),
				actionOutcomeSink = ActionOutcomeSink(recorder::onActionOutcome)
			)
		val snapshot = recorder.finish(RunEndCause.NATURAL_COMPLETION)

		assertThat(run.trainsExited(), name = "trains exited").isGreaterThanOrEqualTo(1)
		val ruleBased = snapshot.actionsByAuthor.getValue(ActionAuthor.RULE_BASED.name)
		assertThat(ruleBased, name = "RULE_BASED counter").isGreaterThan(0L)
		assertThat(snapshot.actionsByAuthor.values.sum(), name = "all author counters").isEqualTo(ruleBased)
		assertThat(snapshot.actionsByAuthor[ActionAuthor.LLM.name], name = "LLM counter").isEqualTo(0L)
		assertThat(snapshot.unattributedApplies, name = "unattributed counter").isEqualTo(0L)
	}
}
