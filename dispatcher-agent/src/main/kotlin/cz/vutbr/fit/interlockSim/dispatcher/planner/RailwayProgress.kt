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
 * - [trainsExited][RailwayOutcome.trainsExited] present → **it decides alone**: zero is
 *   [RailwayProgress.STARVED], anything else is [RailwayProgress.MADE_PROGRESS].
 * - [trainsExited][RailwayOutcome.trainsExited] absent → fall back to the journey count, since
 *   the exit count is absent for any example whose main process is not a `ShuntingLoop` and an
 *   absent count cannot contradict a measured zero.
 * - Otherwise → [RailwayProgress.MADE_PROGRESS].
 *
 * ## Why the exit count outranks the journey count (Issue #895)
 *
 * The rule was originally "no journey completed **and** no train exited", which let a single
 * journey veto the verdict. The #895 re-baseline sweep measured what that costs: one run admitted
 * five trains, ran for 228 simulated seconds, let **none** of them leave, and still recorded
 * `journeysCompleted = 1` — so it was classified [RailwayProgress.MADE_PROGRESS].
 *
 * `journeysCompleted` increments when a train's reservation count reaches zero, with no
 * termination and no movement predicate (Issue #906), so it can fire for a train that never
 * finished. `trainsExited` is termination-gated. Where the two disagree the termination-gated
 * counter is the one to believe.
 *
 * ## Why it is still threshold-free
 *
 * A *partially* starved run — the reporter's original journeys 2/7 case — is still
 * [RailwayProgress.MADE_PROGRESS]. #895 asked whether a fraction should decide this and the
 * measurement said no: across its 40 LLM runs, healthy runs that completed naturally went as low
 * as 33 % of admitted trains while runs that deadlocked reached 45 %, so the two populations
 * overlap and no fraction separates them. Zero-versus-nonzero needs no measurement to justify it;
 * a fraction would need one, and there is none.
 *
 * @return what the railway achieved, or [RailwayProgress.UNMEASURED] when it was not measured.
 */
fun RailwayOutcome.progress(): RailwayProgress {
	journeysCompleted ?: return RailwayProgress.UNMEASURED
	val exited = trainsExited
	return if (exited != null) {
		if (exited == 0L) RailwayProgress.STARVED else RailwayProgress.MADE_PROGRESS
	} else if (journeysCompleted == 0L) {
		RailwayProgress.STARVED
	} else {
		RailwayProgress.MADE_PROGRESS
	}
}
