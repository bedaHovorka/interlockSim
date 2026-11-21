/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isNotNull
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.agentDriverLoopOrNull
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContextFromMap
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.test.get
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Per-context visibility of the [AgentDriverLoop] within one Koin application (Issue #1317).
 *
 * Every test builds two contexts in the same Koin application, the way a GUI session does when the
 * user starts one example after another. The first is the agent-driven `shuntingLoop`, the second
 * is either the dispatcher-free `multiTrainLoop` or another agent-driven `shuntingLoop`.
 *
 * Before the fix, the loop was published with Koin's `Scope.declare`. In Koin 3.5 that registers
 * one definition per scope *qualifier* — shared by every [DefaultSimulationContext] scope — whose
 * builder returns the instance of the first declaring scope. A later scope that never declared a
 * loop therefore resolved the first context's loop, even after that context was closed, and
 * `multiTrainLoop` was persisted as a dispatcher run.
 */
@DisplayName("A context sees only its own AgentDriverLoop (#1317)")
class AgentDriverLoopScopeTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun createConsoleExample(exampleName: String): DefaultSimulationContext =
		createExampleContextFromMap(
			get<ExampleRegistry>().examples,
			get<SimulationContextFactory>(),
			exampleName
		).tracked()

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	@DisplayName("a dispatcher-free context built after a closed agent context sees no driver loop")
	fun closedAgentContextLoopIsNotVisibleToTheNextContext() {
		val agentContext = createConsoleExample(ExampleNames.SHUNTING_LOOP)
		assertThat(agentContext.scope.agentDriverLoopOrNull(), "agent context's loop").isNotNull()
		agentContext.close()

		val dispatcherFree = createConsoleExample(ExampleNames.MULTI_TRAIN_LOOP)

		assertThat(dispatcherFree.scope.agentDriverLoopOrNull(), "dispatcher-free context's loop").isNull()
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	@DisplayName("a dispatcher-free context does not see the loop of an agent context that is still open")
	fun openAgentContextLoopIsNotVisibleToASecondContext() {
		val agentContext = createConsoleExample(ExampleNames.SHUNTING_LOOP)
		val dispatcherFree = createConsoleExample(ExampleNames.MULTI_TRAIN_LOOP)

		assertThat(agentContext.scope.agentDriverLoopOrNull(), "agent context's loop").isNotNull()
		assertThat(dispatcherFree.scope.agentDriverLoopOrNull(), "dispatcher-free context's loop").isNull()
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	@DisplayName("two agent contexts each see their own driver loop")
	fun twoAgentContextsSeeDistinctLoops() {
		val first = createConsoleExample(ExampleNames.SHUNTING_LOOP)
		val second = createConsoleExample(ExampleNames.SHUNTING_LOOP)

		val firstLoop = checkNotNull(first.scope.agentDriverLoopOrNull()) { "first context has no loop" }
		val secondLoop = checkNotNull(second.scope.agentDriverLoopOrNull()) { "second context has no loop" }

		assertThat(secondLoop, "second context's loop").isNotSameInstanceAs(firstLoop)
	}

	/**
	 * The effect the issue reports: [DispatcherRunSummaries.finishAndPersist] writes a run JSON only
	 * when the context ran a dispatcher, and decides that from the loop's presence. A stale loop
	 * makes a dispatcher-free run look like a rule-based dispatcher run.
	 */
	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	@DisplayName("a dispatcher-free run after a closed agent run persists no dispatcher run file")
	fun dispatcherFreeRunAfterAnAgentRunPersistsNothing(
		@TempDir root: Path
	) {
		createConsoleExample(ExampleNames.SHUNTING_LOOP).close()
		val dispatcherFree = createConsoleExample(ExampleNames.MULTI_TRAIN_LOOP)
		dispatcherFree.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		val persisted = DispatcherRunSummaries.finishAndPersist(dispatcherFree.scope, RunEndCause.NATURAL_COMPLETION)

		assertThat(persisted.file, "written path").isNull()
		assertThat(DefaultRunSnapshotStore(root).readAll(root), "snapshots on disk").hasSize(0)
	}
}
