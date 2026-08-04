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
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
@Serializable
enum class DispatcherArm {
	/** Deterministic rule-based dispatcher — no LLM inference. */
	RULE_BASED,

	/** LLM dispatcher using Koog tool-calling mode. */
	LLM_TOOL_CALLING,

	/** LLM dispatcher using constrained-JSON output mode. */
	LLM_CONSTRAINED_JSON
}
