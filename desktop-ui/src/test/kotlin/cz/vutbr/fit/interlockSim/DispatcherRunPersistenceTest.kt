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
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
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
 * End-of-run persistence tests for the dispatcher run pipeline
 * (Issue #847 round 4, finding R4-5).
 *
 * ## What this closes
 *
 * SP2c.22 (#845) states plainly that "headless runs call `finish()` from the sweep driver" — but
 * #847's sweep driver is the task *blocked on this pipeline working*, so the headless path had no
 * `finish()` and no `write()` at all, and `build/reports/dispatcher-runs/` was never created by any
 * run. SP2c.23's aggregator (#846) therefore had no producer and always rendered an all-zero report.
 *
 * The invariant `ticksByOutcome.values.sum() == totalTicks` is asserted in
 * `DispatcherRunSnapshot`'s `init`, so a wrongly-fed recorder does not fail loudly at write time —
 * it throws during *deserialisation*, and `readAll` catches parse errors and skips the file with a
 * WARN. A malformed producer would therefore look exactly like no producer at all, which is why the
 * round-trip (write → readAll → compare) is asserted here rather than just the write.
 *
 * @since Issue #847 (round 4)
 */
@DisplayName("A finished run is persisted as JSON the SP2c.23 aggregator can read")
class DispatcherRunPersistenceTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun createAiContext(): DefaultSimulationContext {
		val registry = get<ExampleRegistry>()
		val createMethod =
			ExampleRegistry::class.java.getDeclaredMethod(
				"createShuntingLoopAIExample",
				SimulationContextFactory::class.java,
				Array<String>::class.java
			)
		createMethod.isAccessible = true
		return createMethod.invoke(
			registry,
			get<SimulationContextFactory>(),
			arrayOf("example", "shuntingLoopAI", "60")
		) as DefaultSimulationContext
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("finishing a run writes one JSON that readAll can load back")
	fun finishedRunRoundTrips(
		@TempDir root: Path
	) {
		val context = createAiContext()
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		val written = DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)

		assertThat(written, "written path").isNotNull()
		val loaded = DefaultRunSnapshotStore(root).readAll(root)
		assertThat(loaded, "snapshots read back").hasSize(1)
		assertThat(loaded.first().arm, "arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(loaded.first().endCause, "end cause").isEqualTo(RunEndCause.NATURAL_COMPLETION)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("the JSON lands under the arm directory the aggregator groups by")
	fun writtenUnderTheArmDirectory() {
		val context = createAiContext()
		context.scope.declare<RunSnapshotStore>(
			DefaultRunSnapshotStore(
				java.nio.file.Files
					.createTempDirectory("runs")
			)
		)

		val written = checkNotNull(DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION))

		assertThat(written.parent.fileName.toString(), "arm directory").isEqualTo("llm_tool_calling")
	}

	/**
	 * `finish()` is idempotent by design (SP2c.22) so the GUI's Stop handler and a sweep driver may
	 * both call it. Persisting must not therefore write the same run twice — a duplicate would be
	 * counted twice by #846's aggregator and quietly inflate the N of an "N ≥ 10 runs" claim.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("persisting twice writes the run only once")
	fun persistingTwiceWritesOnce(
		@TempDir root: Path
	) {
		val context = createAiContext()
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)
		DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.MANUAL_STOP)

		assertThat(DefaultRunSnapshotStore(root).readAll(root), "snapshots on disk").hasSize(1)
	}

	/**
	 * A dispatcher-free example must not produce a run file. An all-zero snapshot in the sweep
	 * directory would be indistinguishable from a real run that did nothing, and #846's gate counts
	 * `runCount >= 10` off exactly these files.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a dispatcher-free example persists nothing")
	fun dispatcherFreeExamplePersistsNothing(
		@TempDir root: Path
	) {
		val registry = get<ExampleRegistry>()
		val createMethod =
			ExampleRegistry::class.java.getDeclaredMethod(
				"createShuntingLoopSyncExample",
				SimulationContextFactory::class.java,
				Array<String>::class.java
			)
		createMethod.isAccessible = true
		val context =
			createMethod.invoke(
				registry,
				get<SimulationContextFactory>(),
				arrayOf("example", "shuntingLoopSync", "60")
			) as DefaultSimulationContext
		// Point the store at the temp root regardless, so a regression here can never write into the
		// project's real build/reports/dispatcher-runs directory.
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		val written = DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)

		assertThat(written, "written path").isNull()
		assertThat(DefaultRunSnapshotStore(root).readAll(root), "snapshots on disk").hasSize(0)
	}
}
