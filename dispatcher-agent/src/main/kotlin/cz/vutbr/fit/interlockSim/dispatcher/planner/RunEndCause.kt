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
