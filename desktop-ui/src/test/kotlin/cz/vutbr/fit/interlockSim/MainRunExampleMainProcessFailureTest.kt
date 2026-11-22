/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
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
 * The headless `example` mode when the run's main process dies (Issue #1361, step (e)).
 *
 * Since #1361 such a run ends with a failure from `run()` instead of spinning. The example mode must
 * still report it as a run that ended early -- [RunOutcome.TERMINATED_EARLY], the one outcome `main`
 * exits non-zero on -- and persist its run JSON, so a sweep records the run instead of finding no file.
 * The run record exists: the context delivers the run end before it throws.
 *
 * @since Issue #1361
 */
@DisplayName("Main.runExample reports a run whose main process died as terminated early and persists it")
class MainRunExampleMainProcessFailureTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	/** A main process that throws on its second control step. */
	private class FailingMainProcess : LoopProcess() {
		private var steps = 0

		override suspend fun iteration() {
			steps++
			if (steps == 2) error("main process failure injected by the test")
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
	fun `a run whose main process dies reports TERMINATED_EARLY and writes its run JSON`(
		@TempDir root: Path
	) {
		val name = ExampleNames.MULTI_TRAIN_LOOP
		val factory = checkNotNull(get<ExampleRegistry>().examples[name])
		val context = factory(get<SimulationContextFactory>(), arrayOf("example", name, END_TIME)) as DefaultSimulationContext
		context.scope.declare(DispatcherRunConfig(runId = RUN_ID, runsRoot = root.toString()))
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))
		context.setMainProcess(FailingMainProcess())

		val outcome = get<Main>().runExampleContext(context, END_TIME.toDouble(), name)

		assertThat(outcome, name = "outcome").isEqualTo(RunOutcome.TERMINATED_EARLY)
		// The one non-zero exit code `main` forces (PR #1419): the sweep's caller sees 1.
		assertThat(outcome.exitCode, name = "exit code").isEqualTo(1)
		val snapshot = DefaultRunSnapshotStore(root).readAll(root).single()
		assertThat(snapshot.endCause, name = "end cause").isEqualTo(RunEndCause.TERMINATED_EARLY)
		assertThat(snapshot.railwayOutcome.endClassification, name = "end classification")
			.isEqualTo(RunEndClassification.TERMINATED_EARLY)
	}

	private companion object {
		const val END_TIME = "600"
		const val RUN_ID = "sweep-ex-multiTrainLoop_r01"
	}
}
