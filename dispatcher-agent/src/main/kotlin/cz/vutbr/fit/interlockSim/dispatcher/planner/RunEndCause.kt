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

import kotlinx.serialization.Serializable

/**
 * Reason why a dispatcher run ended.
 *
 * Carried in [DispatcherRunSnapshot] so that downstream aggregators can distinguish clean
 * completions from aborts when computing success-rate denominators.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
@Serializable
enum class RunEndCause {
	/** The simulation reached its scheduled end time without interruption. */
	NATURAL_COMPLETION,

	/** A human operator stopped the simulation via the GUI or API before natural end. */
	MANUAL_STOP,

	/**
	 * The simulation event queue drained before the requested end time — every process
	 * terminated or blocked permanently; a deadlock is the usual cause.
	 *
	 * This is distinct from [TIMEOUT_ABORT]: the simulation itself stopped short, not the
	 * sweep driver's wall-clock watchdog.  A railway deadlock is a simulation defect worth
	 * investigating; a wall-clock kill means the box was too slow or too loaded.
	 *
	 * @since Issue #909 (SP2c — disambiguate TERMINATED_EARLY from TIMEOUT_ABORT)
	 */
	TERMINATED_EARLY,

	/**
	 * The run reached its end time, but the railway achieved nothing: no train completed a
	 * journey and none left through an exit point.
	 *
	 * Distinct from [TERMINATED_EARLY], whose event queue drained early. A starved run looks
	 * healthy from the outside — the clock reached the horizon and every process was still
	 * alive — so without this value it is recorded as [NATURAL_COMPLETION] and counted by
	 * [RunReportAggregator.runPassed] as a **passing** data point. A measured GUI run at
	 * `tickPeriodMs=20000` did exactly that: journeys 0/7, trains exited 0, and
	 * `completedNaturally=true`.
	 *
	 * The verdict comes from [RailwayOutcome] alone, because the GUI always reaches its end
	 * time in wall-clock terms and so the headless time-shortfall check cannot see this at
	 * all. See [RailwayProgress] for the exact rule and for why it is threshold-free.
	 *
	 * @since Issue #930 (Wave 3 — GUI starvation flag)
	 */
	STARVED,

	/**
	 * A watchdog or wall-clock budget expired and the sweep driver forcibly killed the JVM.
	 *
	 * The simulation may have been making progress at the time of the kill; the failure
	 * reflects infrastructure capacity (slow or loaded machine), not a railway defect.
	 * Use [TERMINATED_EARLY] for a run whose own event queue drained prematurely.
	 */
	TIMEOUT_ABORT,

	/** An unhandled exception or fatal error ended the run abnormally. */
	CRASH
}
