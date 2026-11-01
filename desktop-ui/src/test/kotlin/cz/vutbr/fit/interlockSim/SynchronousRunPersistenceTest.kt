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
import assertk.assertions.endsWith
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.toRailwayOutcome
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.koin.core.module.Module
import org.koin.test.get
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs without a dispatcher agent -- the synchronous wiring of `cervenyUjezd`, and `multiTrainLoop`
 * with no dispatcher at all -- are persisted when a sweep configured a store for them (Issue #676,
 * Goal 6 SP5). Before, only runs with an `AgentDriverLoop` wrote a JSON, so the sweep found no file
 * for such a run and recorded it as `CRASH` (the #1276 `cervenyUjezdRuleBased` control-arm
 * workaround). The persisted figures are the run record's.
 *
 * @since Issue #676 (Goal 6 SP5)
 */
@DisplayName("A run without a dispatcher agent is persisted from its run record when a sweep configured a store")
class SynchronousRunPersistenceTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	/** Builds [exampleName] as `Main.runExample` does, with the run id and runs root a sweep child gets. */
	private fun sweepChild(
		exampleName: String,
		root: Path
	): DefaultSimulationContext {
		val factory = checkNotNull(get<ExampleRegistry>().examples[exampleName])
		val context =
			factory(get<SimulationContextFactory>(), arrayOf("example", exampleName, END_TIME)) as DefaultSimulationContext
		context.scope.declare(DispatcherRunConfig(runId = RUN_ID, runsRoot = root.toString()))
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))
		return context
	}

	private fun assertPersistedFromTheRecord(
		context: DefaultSimulationContext,
		root: Path,
		expected: RunEndClassification
	) {
		val record = context.getMetricsServices().getRunRecordSource().runRecord()
		assertThat(record, name = "run record").isNotNull()
		checkNotNull(record)

		val persisted = DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)

		val file = persisted.file
		assertThat(file, name = "written path").isNotNull()
		checkNotNull(file)
		// AiSweepDriver finds a child's result by the run id at the end of the file name.
		assertThat(file.fileName.toString(), name = "file name").endsWith("-$RUN_ID.json")
		val snapshot = DefaultRunSnapshotStore(root).readAll(root).single()
		assertThat(snapshot.runId, name = "run id").isEqualTo(RUN_ID)
		assertThat(snapshot.endCause, name = "end cause").isEqualTo(RunEndCause.NATURAL_COMPLETION)
		assertThat(snapshot.railwayOutcome, name = "persisted outcome").isEqualTo(record.toRailwayOutcome())
		assertThat(snapshot.railwayOutcome.endClassification, name = "end classification").isEqualTo(expected)
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("cervenyUjezd on the synchronous wiring writes its run JSON from the run record")
	fun cervenyUjezdSynchronousRunIsPersisted(
		@TempDir root: Path
	) {
		val context = sweepChild(ExampleNames.CERVENY_UJEZD, root)
		context.use {
			it.run()
			assertPersistedFromTheRecord(it, root, RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS)
		}
	}

	@Test
	@Tag("integration-test")
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("multiTrainLoop without a dispatcher writes its run JSON from the run record")
	fun multiTrainLoopRunIsPersisted(
		@TempDir root: Path
	) {
		val context = sweepChild(ExampleNames.MULTI_TRAIN_LOOP, root)
		context.use {
			it.run()
			assertPersistedFromTheRecord(it, root, RunEndClassification.NATURAL_COMPLETION)
		}
	}

	@Test
	@DisplayName("a run with no store configured by a sweep still writes nothing")
	fun noConfiguredStoreWritesNothing(
		@TempDir root: Path
	) {
		val factory = checkNotNull(get<ExampleRegistry>().examples[ExampleNames.MULTI_TRAIN_LOOP])
		val context = factory(get<SimulationContextFactory>(), arrayOf("example", ExampleNames.MULTI_TRAIN_LOOP, "5"))
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))
		context.use {
			val persisted = DispatcherRunSummaries.finishAndPersist(it.scope, RunEndCause.NATURAL_COMPLETION)
			assertThat(persisted.file, name = "written path").isNull()
		}
	}

	private companion object {
		const val END_TIME = "600"
		const val RUN_ID = "sweep-ex-cervenyUjezd_r01"
	}
}
