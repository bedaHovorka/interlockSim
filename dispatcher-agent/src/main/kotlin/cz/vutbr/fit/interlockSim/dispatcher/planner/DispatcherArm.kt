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
 * Identifies which dispatcher implementation arm was active during a run.
 *
 * Used in [DispatcherRunSnapshot] so that the SP2c.23 aggregator can group runs by arm and
 * compare success rates across them.
 *
 * @property hasDispatcher Whether runs of this arm had a dispatcher deciding; `false` only for
 *   [NO_DISPATCHER], whose runs are reported but never pass the arm gate (Issue #676).
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
@Serializable
enum class DispatcherArm(
	val hasDispatcher: Boolean = true
) {
	/** Deterministic rule-based dispatcher — no LLM inference. */
	RULE_BASED,

	/** LLM dispatcher using Koog tool-calling mode. */
	LLM_TOOL_CALLING,

	/**
	 * LLM dispatcher using constrained-JSON output mode.
	 *
	 * Retired unmeasured (Issue #991, 2026-09-25): no sweep campaign ever exercised it. Kept for
	 * the arm taxonomy only — [DispatcherRunSnapshot] serialization and cross-run aggregation
	 * still group runs by arm. Nothing constructs it, and the non-production reference loop's
	 * constrained-JSON occupant is the
	 * [ConstrainedJsonEmissionStrategy][cz.vutbr.fit.interlockSim.dispatcher.ConstrainedJsonEmissionStrategy]
	 * *type*, not this enum value — `DispatchTickLoop` has no arm parameter.
	 */
	LLM_CONSTRAINED_JSON,

	/**
	 * No dispatcher at all (Issue #676, Goal 6 SP5): a run whose main process admits its own trains,
	 * such as `multiTrainLoop`, persisted because a sweep configured a store for it. Its railway
	 * figures are reported, but it measures no dispatcher, so it never passes the arm gate and is
	 * listed in a report only when it has runs. Schema version 11.
	 */
	NO_DISPATCHER(hasDispatcher = false)
}
