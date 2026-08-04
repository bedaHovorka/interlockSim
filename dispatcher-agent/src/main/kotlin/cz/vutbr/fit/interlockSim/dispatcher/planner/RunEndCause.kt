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

	/** A watchdog or wall-clock budget expired and the run was forcibly terminated. */
	TIMEOUT_ABORT,

	/** An unhandled exception or fatal error ended the run abnormally. */
	CRASH
}
