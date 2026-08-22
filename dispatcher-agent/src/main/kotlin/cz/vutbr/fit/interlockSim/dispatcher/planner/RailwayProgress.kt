/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

/**
 * Whether the railway achieved anything during a run.
 *
 * Read from [RailwayOutcome] at run end and used to turn a [RunEndCause.NATURAL_COMPLETION]
 * into a [RunEndCause.STARVED] — see [progress].
 *
 * @since Issue #930 (Wave 3 — GUI starvation flag)
 */
enum class RailwayProgress {
	/** At least one train finished a journey or left through an exit point. */
	MADE_PROGRESS,

	/** The run measured the railway and found that nothing was achieved. */
	STARVED,

	/** Nothing in this run was in a position to measure the railway, so there is no verdict. */
	UNMEASURED
}

/**
 * Classifies what this run's railway achieved.
 *
 * ## Why this exists (Issue #930)
 *
 * Every other figure in [DispatcherRunSnapshot] describes decision hygiene. None of it says
 * whether a train moved, and the GUI arm had no liveness signal at all: `Frame` recorded every
 * run that was not stopped by hand as [RunEndCause.NATURAL_COMPLETION], so a fully starved
 * railway (measured: journeys 0/7, trains exited 0, 3 block transitions) was written with
 * `completedNaturally = true` and counted by [RunReportAggregator.runPassed] as a pass. The GUI
 * always reaches its end time in wall-clock terms, so the headless time-shortfall check in
 * `RunCompletionCheck` cannot detect this; the verdict has to come from the railway itself.
 *
 * ## The rule
 *
 * - [journeysCompleted][RailwayOutcome.journeysCompleted] absent → [RailwayProgress.UNMEASURED].
 *   Absent is not zero (see [RailwayOutcome]); a run nobody measured gets no verdict, and the
 *   caller leaves its end cause alone.
 * - No journey completed **and** no train exited → [RailwayProgress.STARVED].
 *   [trainsExited][RailwayOutcome.trainsExited] is absent for any example whose main process is
 *   not a `ShuntingLoop`; an absent exit count cannot contradict a measured zero journey count,
 *   so it does not block the verdict.
 * - Otherwise → [RailwayProgress.MADE_PROGRESS].
 *
 * ## Why it is threshold-free
 *
 * A *partially* starved run — the reporter's original journeys 2/7 case — is deliberately
 * reported as [RailwayProgress.MADE_PROGRESS] here. How many of the admitted trains must finish
 * before a run counts is a **gate threshold**, and a threshold that is not derived from a
 * measurement is exactly what [RunReportAggregator.MIN_ACTIONABLE_RATE] is already flagged as.
 * That question belongs to the #895 re-baseline sweep, not to this classifier. Zero-versus-
 * nonzero needs no measurement to justify it.
 *
 * @return what the railway achieved, or [RailwayProgress.UNMEASURED] when it was not measured.
 */
fun RailwayOutcome.progress(): RailwayProgress {
	val journeys = journeysCompleted ?: return RailwayProgress.UNMEASURED
	val exited = trainsExited ?: 0L
	return if (journeys == 0L && exited == 0L) {
		RailwayProgress.STARVED
	} else {
		RailwayProgress.MADE_PROGRESS
	}
}
