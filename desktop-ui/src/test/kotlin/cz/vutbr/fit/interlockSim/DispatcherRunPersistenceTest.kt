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
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.LlmCircuitBreaker
import cz.vutbr.fit.interlockSim.dispatcher.planner.RailwayOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsSnapshot
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import cz.vutbr.fit.interlockSim.sim.metrics.toRunRecord
import cz.vutbr.fit.interlockSim.testutil.FakeMetricsCollectionService
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContext
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.module.Module
import org.koin.test.get
import org.slf4j.LoggerFactory
import java.io.File
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

	private companion object {
		/**
		 * The Logback logger the no-record path logs its snapshot notice at: [DispatcherRunSummaries]'s
		 * `KotlinLogging.logger {}`, which kotlin-logging names after the enclosing object — the same
		 * naming rule `TrainJourneyEventEmitTest` records for its delivery-guard logger.
		 */
		const val SUMMARIES_LOGGER = "cz.vutbr.fit.interlockSim.DispatcherRunSummaries"
	}

	private fun createAiContext(): DefaultSimulationContext =
		createExample("createShuntingLoopAIExample", ExampleNames.SHUNTING_LOOP_AI)

	private fun createExample(
		factoryMethod: String,
		exampleName: String
	): DefaultSimulationContext {
		val registry = get<ExampleRegistry>()
		return createExampleContext(registry, get<SimulationContextFactory>(), factoryMethod, exampleName, "60")
	}

	@Test
	@DisplayName("a run from an XML file is named by the file's base name with its extension, never a path (#1328)")
	fun fileRunNameIsBaseNameWithExtension() {
		val name = DispatcherRunSummaries.fileRunName(File("/some/dir/vyhybna.xml"))

		assertThat(name, "run name").isEqualTo("vyhybna.xml")
	}

	/**
	 * Tripwire for the #1328 rule: a run from a file with an extension can never be pooled with an
	 * example in the sweep report only because no registered example name contains a dot.
	 */
	@Test
	@DisplayName("no registered example name contains a dot, so a file run with an extension never equals one (#1328)")
	fun noExampleNameContainsADot() {
		val registry = get<ExampleRegistry>()
		val names = registry.examples.keys + registry.guiExamples.keys

		assertThat(names.filter { '.' in it }, "example names with a dot").isEmpty()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
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
	@Tag("integration-test")
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("an LLM run JSON records its circuit breaker end state and counters")
	fun persistedLlmRunCarriesCircuitBreakerStats(
		@TempDir root: Path
	) {
		val context = createAiContext()
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)

		val snapshot = DefaultRunSnapshotStore(root).readAll(root).single()
		assertThat(snapshot.circuitBreakerState, "circuit breaker state").isEqualTo(LlmCircuitBreaker.State.CLOSED)
		assertThat(snapshot.circuitBreakerTotalSkips, "circuit breaker total skips").isEqualTo(0L)
		assertThat(snapshot.circuitBreakerOpenCount, "circuit breaker open count").isEqualTo(0L)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
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
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
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
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
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
				ExampleNames.SHUNTING_LOOP_SYNC,
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
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("a run persisted before it ended carries the snapshot figures and no record figures")
	fun persistedRunCarriesRailwayOutcomes(
		@TempDir root: Path
	) {
		val context = createAiContext()
		context.scope.declare<RunSnapshotStore>(DefaultRunSnapshotStore(root))

		DispatcherRunSummaries.finishAndPersist(context.scope, RunEndCause.NATURAL_COMPLETION)

		val outcome = DefaultRunSnapshotStore(root).readAll(root).first().railwayOutcome
		assertThat(outcome.journeysCompleted, "journeys completed").isNotNull()
		assertThat(outcome.conflicts, "conflicts").isNotNull()
		assertThat(outcome.unresolvedAtRunEnd, "unresolved at run end").isNotNull()
		// Nothing ran, so there is no run record and the accounting is not read: absent, never zero.
		assertThat(outcome.trainsEntered, "trains entered").isNull()
		assertThat(outcome.trainsExited, "trains exited").isNull()
		assertThat(outcome.endClassification, "end classification").isNull()
	}

	/**
	 * `multiTrainLoop` runs a `MultiTrainLoop`, which keeps none of `ShuntingLoop`'s counters. Until
	 * Issue #676 its loop-sourced figures came out absent; now every figure of a finished run comes from
	 * the run record, whatever the main process (`MultiTrainLoopRunRecordTest`, `SynchronousRunPersistenceTest`).
	 * Read before the run has ended, there is no record: only the snapshot figures are present.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("an example that is not a ShuntingLoop reports only snapshot figures before the run ends")
	fun nonShuntingLoopExampleReportsSnapshotFiguresBeforeItsRecord() {
		val context = createExample("createMultiTrainLoopExample", ExampleNames.MULTI_TRAIN_LOOP)

		val outcome = DispatcherRunSummaries.railwayOutcomeFrom(context.scope)

		assertThat(outcome.journeysCompleted, "journeys completed").isNotNull()
		assertThat(outcome.conflicts, "conflicts").isNotNull()
		assertThat(outcome.unresolvedAtRunEnd, "unresolved at run end").isNotNull()
		assertThat(outcome.trainsEntered, "trains entered").isNull()
		assertThat(outcome.blockTransitions, "block transitions").isNull()
		assertThat(outcome.endClassification, "end classification").isNull()
		assertThat(outcome.wrongExits, "wrong exits").isNull()
		assertThat(outcome.endTime, "end time").isNull()
	}

	/**
	 * Issue #1280: an end-time cutoff with trains still queued flushes their open contentions, and
	 * the run summary must not report them as conflicts. The cervenyUjezd run that motivated it
	 * printed `conflicts=2` although nothing double-booked; with the split, the same metrics read
	 * as `conflicts=0` and two contentions unresolved at run end.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("railwayOutcomeFrom reports run-end contentions apart from conflicts (Issue #1280)")
	fun railwayOutcomeFromKeepsRunEndContentionsApartFromConflicts() {
		val context = createAiContext()
		context.scope.declare<MetricsCollectionService>(
			FakeMetricsCollectionService(conflictCount = 0, unresolvedAtRunEndCount = 2)
		)

		val outcome = DispatcherRunSummaries.railwayOutcomeFrom(context.scope)

		assertThat(outcome.conflicts, "conflicts").isEqualTo(0L)
		assertThat(outcome.unresolvedAtRunEnd, "unresolved at run end").isEqualTo(2L)
	}

	/**
	 * A manual stop does not join the simulation thread, so a run with no record yet is read on the EDT
	 * while the run-end accounting is still being written. Only the thread-safe snapshot figures are
	 * read then; every other figure stays absent (Issue #676, review thread on `railwayOutcomeFrom`).
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("railwayOutcomeFrom of a run without a record reads only the latest snapshot figures (Issue #676)")
	fun railwayOutcomeFromUnfinishedRunReadsOnlyTheSnapshot() {
		val context = createAiContext()
		context.scope.declare<MetricsCollectionService>(
			FakeMetricsCollectionService(completedTrains = 4, conflictCount = 1)
		)

		val outcome = DispatcherRunSummaries.railwayOutcomeFrom(context.scope)

		assertThat(outcome.journeysCompleted, "journeys completed").isEqualTo(4L)
		assertThat(outcome.conflicts, "conflicts").isEqualTo(1L)
		assertThat(outcome.trainsExited, "trains exited").isNull()
		assertThat(outcome.blockTransitions, "block transitions").isNull()
	}

	/**
	 * Issue #1325: after a manual stop the Stop handler reads the outcome on the EDT while the
	 * simulation thread may still write the two maps the leak gauge copies. Without a record the gauge
	 * must not be read at all; the outcome comes from the thread-safe snapshot alone. The stand-in
	 * gauge throws when called, as the real one can on a concurrent write.
	 *
	 * The snapshot read is pinned too: the fake's published figure set differs from its fixed
	 * `getSnapshot()` fallback, so a regression back to the racy record-less read — the exact hazard
	 * #1325 removes — fails on the figures instead of passing on identical content.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("railwayOutcomeFrom after a manual stop does not read the leak gauge (Issue #1325)")
	fun railwayOutcomeFromManualStopSkipsLeakGauge() {
		val context = createAiContext()
		val service = FakeMetricsCollectionService(completedTrains = 3, conflictCount = 1)
		service.published =
			MetricsSnapshot(
				time = 0.0,
				conflictCount = 5,
				completedTrains = 9,
				throughput = 0.0,
				totalWaitSeconds = 0.0,
				averageWaitSeconds = 0.0,
				occupiedBlocks = 0,
				totalBlocks = 0,
				utilization = 0.0,
				unresolvedAtRunEndCount = 2
			)
		service.leakGaugeFailure = ConcurrentModificationException("leak gauge read during a manual stop")
		context.scope.declare<MetricsCollectionService>(service)

		val outcome = DispatcherRunSummaries.railwayOutcomeFrom(context.scope)

		assertThat(outcome.journeysCompleted, "journeys completed").isEqualTo(9L)
		assertThat(outcome.conflicts, "conflicts").isEqualTo(5L)
		assertThat(outcome.unresolvedAtRunEnd, "unresolved at run end").isEqualTo(2L)
		assertThat(outcome.trainsExited, "trains exited").isNull()
		assertThat(service.reportUnreleasedReservationsInvoked, "leak gauge invoked").isFalse()
	}

	/**
	 * Issue #1325: with no record the gauge is skipped, and what would have been its input is logged
	 * at INFO instead — the snapshot's trains holding reservations and held blocks. The test config
	 * (`logback-test.xml`) pins the root logger to WARN, so that INFO block never executed under
	 * tests — the exact reason Sonar's new-code coverage of the branch sits at 80.0. Capturing this
	 * logger at INFO runs the block and pins the message: the planted `published` snapshot's 7 and 4
	 * differ from its journey and conflict figures, and the fake's fixed `getSnapshot()` fallback
	 * carries no reservation counts at all, so a message quoting anything else fails on the text.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("railwayOutcomeFrom after a manual stop logs the snapshot reservation counts at INFO (Issue #1325)")
	fun railwayOutcomeFromManualStopLogsSkippedLeakGauge() {
		val context = createAiContext()
		val service = FakeMetricsCollectionService(completedTrains = 2, conflictCount = 1)
		service.published =
			MetricsSnapshot(
				time = 0.0,
				conflictCount = 3,
				completedTrains = 6,
				throughput = 0.0,
				totalWaitSeconds = 0.0,
				averageWaitSeconds = 0.0,
				occupiedBlocks = 0,
				totalBlocks = 0,
				utilization = 0.0,
				trainsHoldingReservations = 7,
				heldBlocks = 4,
				unresolvedAtRunEndCount = 2
			)
		service.leakGaugeFailure = ConcurrentModificationException("leak gauge read during a manual stop")
		context.scope.declare<MetricsCollectionService>(service)

		var recordedOutcome: RailwayOutcome? = null
		withLogOf(SUMMARIES_LOGGER) { appender ->
			recordedOutcome = DispatcherRunSummaries.railwayOutcomeFrom(context.scope)

			val infoMessages = appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
			assertThat(infoMessages, "captured INFO messages").hasSize(1)
			val message = infoMessages.single()
			assertThat(message, "leak-gauge skip notice").contains("leak gauge is skipped")
			assertThat(message, "trains holding reservations").contains("7 trains holding reservations")
			assertThat(message, "held blocks").contains("4 held blocks")
		}
		val outcome = checkNotNull(recordedOutcome)

		assertThat(outcome.journeysCompleted, "journeys completed").isEqualTo(6L)
		assertThat(outcome.conflicts, "conflicts").isEqualTo(3L)
		assertThat(outcome.unresolvedAtRunEnd, "unresolved at run end").isEqualTo(2L)
		assertThat(service.reportUnreleasedReservationsInvoked, "leak gauge invoked").isFalse()
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
	 * not folded into `RailwayOutcome` (see the PR), so it is not otherwise observable. The spy carries
	 * a run record: since Issue #1325 the gauge is read only for a run that has ended.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("railwayOutcomeFrom invokes the leak gauge on the scoped metrics service (Issue #936)")
	fun railwayOutcomeFromInvokesLeakGauge() {
		val context = createAiContext()
		val spy = FakeMetricsCollectionService()
		spy.record = endedRunRecord()
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

	/**
	 * Runs [body] with a Logback [ListAppender] attached to the logger named [loggerName]; the
	 * appender is detached and the logger's level restored in `finally`, also when an assertion
	 * inside [body] throws.
	 *
	 * File-local adaptation of the capture `:core`'s jvmTest tests use (`testutil/LogCapture.kt`,
	 * Issue #1394), which is unreachable from desktop-ui tests. The one difference: this copy raises
	 * the logger's level to INFO first, because desktop-ui's `logback-test.xml` pins the root logger
	 * to WARN and has no logger entry for this package, so the INFO event would be dropped before any
	 * appender sees it — the same programmatic raise `:dispatcher-agent`'s log-capture tests apply on
	 * exactly that config. Promote this to desktop-ui's `testutil` once a second test needs it.
	 */
	private fun withLogOf(
		loggerName: String,
		body: (ListAppender<ILoggingEvent>) -> Unit
	) {
		val logger = LoggerFactory.getLogger(loggerName) as Logger
		val appender = ListAppender<ILoggingEvent>().also { it.start() }
		val originalLevel: Level? = logger.level
		logger.level = Level.INFO
		logger.addAppender(appender)
		try {
			body(appender)
		} finally {
			logger.detachAppender(appender)
			// Logback's setLevel throws on null, so a logger whose level was inherited (null) is
			// reset to the root's explicit level — the level it effectively had before the raise.
			logger.level = originalLevel ?: (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).level
		}
	}

	/** A record of an ended run, built from the fake's own run-end figures (Issue #1325). */
	private fun endedRunRecord(): RunRecord =
		checkNotNull(
			FakeMetricsCollectionService()
				.runEndSummary()
				.copy(classification = RunEndClassification.NATURAL_COMPLETION, endTime = 60.0)
				.toRunRecord()
		)
}
