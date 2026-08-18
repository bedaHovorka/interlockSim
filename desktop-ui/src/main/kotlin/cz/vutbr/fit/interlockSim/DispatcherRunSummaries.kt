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

import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.dispatcher.planner.DecisionRateReport
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RailwayOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.scope.Scope
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * End-of-run dispatcher reporting for headless (`example` mode) runs.
 *
 * ## Why this exists (Issue #847 round 4)
 *
 * `MeasuringPlanAdapter.logFinalSummary()` had exactly one caller anywhere in the codebase: the GUI
 * `Frame`. A headless run therefore produced only the modulo-10 periodic summaries — with 20-29
 * cycles per run, two lines and no final one. Everything #847's unattended sweep wants to know about
 * a run was either GUI-only or not recorded at all.
 *
 * Every summary here is read from the context **scope**, so it must be called inside
 * `context.use { … }`: the scope is closed with the context and resolution afterwards yields
 * nothing.
 *
 * Each component is optional and reported independently. A dispatcher-free example (plain
 * `shuntingLoop`, `multiTrainLoop`) resolves none of them and prints nothing — deliberately, because
 * a row of zeroes would read as "the dispatcher did nothing" rather than "there was no dispatcher".
 *
 * @since Issue #847 round 4 (PR #891)
 */
object DispatcherRunSummaries {
	private val logger = KotlinLogging.logger {}

	/**
	 * Which summaries were actually emitted, for wiring regression tests.
	 *
	 * A test asserting only "no exception was thrown" would pass just as happily against a version
	 * that resolved nothing — which is the exact defect being fixed.
	 */
	data class Reported(
		val plannerSummary: Boolean,
		val sweeperSummary: Boolean,
		val decisionRateSummary: Boolean
	)

	/**
	 * Logs every dispatcher summary available in [scope].
	 *
	 * @return which summaries were emitted.
	 */
	fun log(scope: Scope): Reported {
		val planner = scope.getOrNull<MeasuringPlanAdapter>()
		val sweeper = scope.getOrNull<OrphanReservationSweeper>()
		val driverLoop = scope.getOrNull<AgentDriverLoop>()
		val signal = scope.getOrNull<DefaultSnapshotSignal>()

		planner?.logFinalSummary()
		sweeper?.logSummary()
		DecisionRateReport.log(signal, driverLoop, planner)

		return Reported(
			plannerSummary = planner != null,
			sweeperSummary = sweeper != null,
			decisionRateSummary = DecisionRateReport.line(signal, driverLoop, planner) != null
		)
	}

	/**
	 * Closes the run's recorder and persists its snapshot as one JSON under the arm directory.
	 *
	 * ## Why this is here (Issue #847 round 4, R4-5)
	 *
	 * SP2c.22 (#845) deferred the headless `finish()` to "the sweep driver" — but that driver is
	 * #847, the task blocked on this pipeline producing anything at all. So no headless run ever
	 * finished a recorder or wrote a snapshot, `build/reports/dispatcher-runs/` was never created,
	 * and SP2c.23's aggregator (#846) rendered an all-zero report over an empty directory.
	 *
	 * [DispatcherRunRecorder.finish] is idempotent, so calling this from both the GUI Stop handler
	 * and a sweep driver is harmless — but *writing* is not idempotent, and a duplicated run file
	 * would inflate the run count that #846's `runCount >= 10` gate gates on. The already-finished
	 * case therefore returns `null` without writing.
	 *
	 * Must be called inside `context.use { … }`: everything is read from the context scope, which
	 * closes with the context.
	 *
	 * Issue #834 (SP2c.11) also sources the run's [RailwayOutcome] here — see [railwayOutcomeFrom]
	 * — because this is the last point at which the scope still holds both the simulation context
	 * and its main process.
	 *
	 * @return the file written, or `null` if this context has no dispatcher recorder or store, or
	 *   the run was already persisted.
	 */
	fun finishAndPersist(
		scope: Scope,
		cause: RunEndCause
	): Path? {
		val recorder = scope.getOrNull<DispatcherRunRecorder>() ?: return null
		// Issue #834 (SP2c.11): hand the recorder what the railway achieved BEFORE finishing it.
		// finish() freezes the snapshot, so an outcome recorded afterwards would be dropped — and
		// this is the last moment at which the scope still holds both the simulation context and
		// its main process. Idempotent-safe: on a second finishAndPersist for the same run the
		// recorder is already frozen and this write cannot alter the frozen snapshot.
		recorder.recordRailwayOutcome(railwayOutcomeFrom(scope))
		// finish() is idempotent by contract (SP2c.22) precisely so several callers may invoke it,
		// so it needs no guard of its own — and an earlier version that guarded it silently stopped
		// the GUI finishing its recorder at all, which FrameDispatcherMetricsLogTest caught. Only
		// the *write* is deduped, further down, because writing twice would put the same run in the
		// sweep directory twice and inflate #846's `runCount >= 10` gate.
		val snapshot = recorder.finish(cause)
		recorder.logFinalSummary()

		// The recorder and store are bound `scoped` on DefaultSimulationContext, so EVERY context can
		// resolve them — including ones that never wired a dispatcher at all (`shuntingLoopSync`
		// uses wireSynchronousDispatcher; `multiTrainLoop` wires nothing). Resolving them is
		// therefore not evidence that a dispatcher ran, and writing on that basis would drop
		// zero-tick files into the sweep directory that are indistinguishable from a real run whose
		// dispatcher did nothing — while counting towards #846's `runCount >= 10` gate.
		//
		// AgentDriverLoop is declared only by wireDispatcherAgent, so its presence is the precise
		// signal that this context ran a dispatcher.
		if (scope.getOrNull<AgentDriverLoop>() == null) return null
		val store = scope.getOrNull<RunSnapshotStore>() ?: return null
		if (!writtenRecorders.add(recorder)) {
			logger.debug { "Run ${recorder.runId} already written; not writing it a second time" }
			return null
		}
		return try {
			store.write(snapshot)
		} catch (e: IOException) {
			// A run whose measurement file cannot be written is a lost data point, not a failed
			// simulation: the run itself completed and its console summary is already logged. #847's
			// sweep detects the gap from the missing file rather than from a crashed process.
			logger.error(e) { "Failed to persist dispatcher run ${recorder.runId}" }
			null
		}
	}

	/**
	 * Reads what the railway achieved during the run owned by [scope].
	 *
	 * ## Why here (Issue #834, SP2c.11)
	 *
	 * Both read-only sources hang off the simulation context, and this is the one place on the
	 * live path — shared by the headless `Main.runExample` and the GUI `Frame`'s Stop handler —
	 * that still holds the context's Koin scope at run end. Neither source is touched:
	 *
	 * - [MetricsCollectionService] is bound `scoped` per simulation context in `CoreModule`, so it
	 *   resolves for **every** context regardless of which process drives it.
	 * - [ShuntingLoop]'s counters exist only when the run's main process is a [ShuntingLoop]. For
	 *   any other example (`multiTrainLoop`, `threeTrainLoop`) those figures are genuinely
	 *   unobtainable and are left `null`.
	 *
	 * ## Absent, never zero
	 *
	 * A source that is not there yields `null`, not `0`. Substituting a zero would put a run that
	 * nothing measured into the sweep as a run in which no train moved — the misreading
	 * `docs/GOAL_10_SP2C24_SWEEP_REPORT.md` records under "Structurally empty columns". See
	 * [RailwayOutcome].
	 *
	 * ## Threading
	 *
	 * Called at run end, after the kDisco kernel has stopped, on the thread that ended the run
	 * (the `example` caller thread, or the EDT for the GUI). [MetricsCollectionService.getSnapshot]
	 * documents that external threads must synchronise while the simulation is live; here it is
	 * not, so the read is a quiescent one.
	 *
	 * Must be called inside `context.use { … }`, like every other read in this object.
	 *
	 * @return the figures available for this run, with `null` for every one that is not.
	 */
	fun railwayOutcomeFrom(scope: Scope): RailwayOutcome {
		val metricsService = scope.getOrNull<MetricsCollectionService>()
		// Issue #936: the one run-end, quiesced read shared by the GUI and headless paths, so it is
		// where a leaked reservation can still be reported. Deliberately not folded into
		// RailwayOutcome — that record is persisted, and its schema is not in this issue's scope.
		metricsService?.reportUnreleasedReservations()
		val metrics = metricsService?.getSnapshot()
		// getSource is how CoreModule's own scoped definitions reach the context; the run's main
		// process is only a ShuntingLoop for the shuntingLoop* examples, hence the safe cast.
		val loop = scope.getSource<DefaultSimulationContext>()?.getMainProcess() as? ShuntingLoop
		return RailwayOutcome(
			journeysCompleted = metrics?.completedTrains?.toLong(),
			trainsEntered = loop?.getTrainsEntered()?.toLong(),
			trainsExited = loop?.getTrainsExited()?.toLong(),
			maxConcurrentTrains = loop?.getMaxConcurrentTrains()?.toLong(),
			// One movement event per block-to-block step, summed over every train — the figure the
			// #847 sweep had to scrape from logs to tell a working railway from a busy dispatcher.
			blockTransitions = loop?.getAllBlockTransitions()?.values?.sumOf { it.toLong() },
			conflicts = metrics?.conflictCount?.toLong(),
			failedReservations = loop?.getFailedReservations()?.toLong()
		)
	}

	/**
	 * Recorders whose snapshot has already been written, so a second [finishAndPersist] for the
	 * same run does not write it twice.
	 *
	 * Keyed on the **recorder instance**, not on its `runId`. Two genuinely different runs sharing a
	 * run id is not merely theoretical: it is what a test double does by default, and keying on the
	 * id would let two consecutive runs collide. The instance is the run's real identity (one
	 * recorder per `DefaultSimulationContext` lifetime, per SP2c.22); the id is only its label.
	 *
	 * Process-wide because the two callers — the GUI `Frame`'s Stop handler and the headless
	 * `Main.runExample` — share no object other than the scope that is about to close. Growth is
	 * bounded by the number of runs in one process.
	 */
	private val writtenRecorders = ConcurrentHashMap.newKeySet<DispatcherRunRecorder>()
}
