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
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.RailwayOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.testutil.FakeMetricsCollectionService
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContext
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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

	private fun createAiContext(): DefaultSimulationContext =
		createExample("createShuntingLoopAIExample", "shuntingLoopAI")

	private fun createExample(
		factoryMethod: String,
		exampleName: String
	): DefaultSimulationContext {
		val registry = get<ExampleRegistry>()
		return createExampleContext(registry, get<SimulationContextFactory>(), factoryMethod, exampleName, "60")
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

		assertThat(written.file, "written path").isNotNull()
		val loaded = DefaultRunSnapshotStore(root).readAll(root)
		assertThat(loaded, "snapshots read back").hasSize(1)
		assertThat(loaded.first().arm, "arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		// No simulation is run in this test, so the railway genuinely achieved nothing: zero
		// journeys and zero exits. Issue #930 records that as STARVED rather than as a natural
		// completion, which is the literal truth about this context and is what stops such a run
		// counting as a passing data point. The healthy-path counterpart is
		// `starvationAdjusted leaves a run that made progress alone`.
		assertThat(loaded.first().endCause, "end cause").isEqualTo(RunEndCause.STARVED)
		assertThat(loaded.first().completedNaturally, "completed naturally").isFalse()
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

		val written =
			checkNotNull(DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION).file)

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
		val context =
			createExampleContext(
				registry,
				get<SimulationContextFactory>(),
				"createShuntingLoopSyncExample",
				"shuntingLoopSync",
				"60"
			)
		// Point the store at the temp root regardless, so a regression here can never write into the
		// project's real build/reports/dispatcher-runs directory.
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		val written = DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)

		assertThat(written.file, "written path").isNull()
		assertThat(DefaultRunSnapshotStore(root).readAll(root), "snapshots on disk").hasSize(0)
	}

	// ── Railway outcomes (Issue #834, SP2c.11) ───────────────────────────────

	/**
	 * The point of SP2c.11: the persisted run must say whether the railway moved, not only how
	 * tidily the dispatcher decided. Every figure has to survive the write → `readAll` round trip,
	 * because that file is the only thing #834's sweep ranks parameter cells on.
	 *
	 * No simulation is run here, so the counters are legitimately `0` — what is asserted is that
	 * they are **present** (`0`, a measurement) rather than absent, which is what distinguishes a
	 * wired source from an unwired one.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a persisted run carries the railway figures read from its loop and metrics service")
	fun persistedRunCarriesRailwayOutcomes(
		@TempDir root: Path
	) {
		val context = createAiContext()
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)

		val outcome = DefaultRunSnapshotStore(root).readAll(root).first().railwayOutcome
		assertThat(outcome.journeysCompleted, "journeys completed").isNotNull()
		assertThat(outcome.conflicts, "conflicts").isNotNull()
		assertThat(outcome.trainsEntered, "trains entered").isNotNull()
		assertThat(outcome.trainsExited, "trains exited").isNotNull()
		assertThat(outcome.maxConcurrentTrains, "max concurrent trains").isNotNull()
		assertThat(outcome.blockTransitions, "block transitions").isNotNull()
		assertThat(outcome.failedReservations, "failed reservations").isNotNull()
	}

	/**
	 * `multiTrainLoop` runs a `MultiTrainLoop`, which keeps none of `ShuntingLoop`'s counters. Its
	 * loop-sourced figures must therefore come out **absent**, not `0`: a zeroed column would say
	 * "this configuration moved no train", which is the misreading
	 * #847's sweep named under "Structurally empty columns".
	 *
	 * The metrics-sourced figures stay present, because `MetricsCollectionService` is bound for
	 * every simulation context regardless of which process drives it.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("an example that is not a ShuntingLoop reports its loop figures as absent, not zero")
	fun nonShuntingLoopExampleReportsLoopFiguresAsAbsent() {
		val context = createExample("createMultiTrainLoopExample", "multiTrainLoop")

		val outcome = DispatcherRunSummaries.railwayOutcomeFrom(context.scope)

		assertThat(outcome.trainsEntered, "trains entered").isNull()
		assertThat(outcome.trainsExited, "trains exited").isNull()
		assertThat(outcome.maxConcurrentTrains, "max concurrent trains").isNull()
		assertThat(outcome.blockTransitions, "block transitions").isNull()
		assertThat(outcome.failedReservations, "failed reservations").isNull()
		// Metrics are context-scoped, so they remain measurable for any main process.
		assertThat(outcome.journeysCompleted, "journeys completed").isNotNull()
		assertThat(outcome.conflicts, "conflicts").isNotNull()
	}

	// ── Leak-gauge wiring (Issue #936, review Minor #5) ──────────────────────

	/**
	 * The leak gauge has thorough unit tests on `DefaultMetricsCollectionService`, but the single
	 * production call site — `DispatcherRunSummaries.railwayOutcomeFrom` resolving the scoped
	 * `MetricsCollectionService` and calling `reportUnreleasedReservations()` once at run end — had
	 * no test. A regression that drops that one line would pass every other test silently: the gauge
	 * still works in isolation, it is just never consulted, so a leaked reservation reads as a slow
	 * run exactly as it did before #936.
	 *
	 * Rather than reproduce a real #936 leak (which needs a full LLM dispatcher run) this pins the
	 * *wiring*: a spy standing in for the scoped metrics service records whether the method was
	 * called at all, and `railwayOutcomeFrom` is invoked on its scope. The spy returns an empty leak
	 * set because the assertion is about the call, not the leak — the return value is deliberately
	 * not folded into `RailwayOutcome` (see the PR), so it is not otherwise observable.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("railwayOutcomeFrom invokes the leak gauge on the scoped metrics service (Issue #936)")
	fun railwayOutcomeFromInvokesLeakGauge() {
		val context = createAiContext()
		val spy = FakeMetricsCollectionService()
		context.scope.declare<MetricsCollectionService>(spy)

		DispatcherRunSummaries.railwayOutcomeFrom(context.scope)

		assertThat(spy.reportUnreleasedReservationsInvoked, "leak gauge invoked").isTrue()
	}

	// ── Starvation adjustment (Issue #930) ───────────────────────────────────

	/**
	 * The whole point of #930: `RunReportAggregator.runPassed` starts with `completedNaturally`,
	 * which is derived from the end cause, so a natural completion over a dead railway is a passing
	 * data point. The GUI produced exactly that for every unattended run.
	 */
	@Test
	@DisplayName("starvationAdjusted turns a natural completion over a dead railway into STARVED")
	fun starvationAdjustedFlagsDeadRailway() {
		val dead = RailwayOutcome(journeysCompleted = 0L, trainsEntered = 7L, trainsExited = 0L, blockTransitions = 3L)

		val adjusted = DispatcherRunSummaries.starvationAdjusted(RunEndCause.NATURAL_COMPLETION, dead)

		assertThat(adjusted, "adjusted cause").isEqualTo(RunEndCause.STARVED)
	}

	@Test
	@DisplayName("starvationAdjusted leaves a run that made progress alone")
	fun starvationAdjustedLeavesHealthyRun() {
		val healthy = RailwayOutcome(journeysCompleted = 7L, trainsEntered = 7L, trainsExited = 7L)

		val adjusted = DispatcherRunSummaries.starvationAdjusted(RunEndCause.NATURAL_COMPLETION, healthy)

		assertThat(adjusted, "adjusted cause").isEqualTo(RunEndCause.NATURAL_COMPLETION)
	}

	/**
	 * A run nobody measured gets no verdict — absent is not zero. Overwriting the cause here would
	 * invent a finding, which is the failure mode `RailwayOutcome`'s KDoc exists to prevent.
	 */
	@Test
	@DisplayName("starvationAdjusted leaves an unmeasured railway alone")
	fun starvationAdjustedLeavesUnmeasuredRun() {
		val adjusted =
			DispatcherRunSummaries.starvationAdjusted(RunEndCause.NATURAL_COMPLETION, RailwayOutcome.UNMEASURED)

		assertThat(adjusted, "adjusted cause").isEqualTo(RunEndCause.NATURAL_COMPLETION)
	}

	/**
	 * Every other cause already says something more specific about why the run ended, and all of
	 * them already yield `completedNaturally = false`. Overwriting them would lose information for
	 * no gain.
	 */
	@ParameterizedTest
	@EnumSource(value = RunEndCause::class, names = ["NATURAL_COMPLETION"], mode = EnumSource.Mode.EXCLUDE)
	@DisplayName("starvationAdjusted never rewrites a cause other than NATURAL_COMPLETION")
	fun starvationAdjustedOnlyTouchesNaturalCompletion(cause: RunEndCause) {
		val dead = RailwayOutcome(journeysCompleted = 0L, trainsExited = 0L)

		assertThat(DispatcherRunSummaries.starvationAdjusted(cause, dead), "adjusted cause").isEqualTo(cause)
	}
}
