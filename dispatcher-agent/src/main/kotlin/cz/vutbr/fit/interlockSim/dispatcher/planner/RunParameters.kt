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
 * Fixed parameters that characterise a single dispatcher run.
 *
 * Immutable at run start; bundled into [DispatcherRunSnapshot] so that the SP2c.23 aggregator
 * can correlate metrics across runs with identical vs. different configurations.
 *
 * @property tickPeriodMs Wall-clock milliseconds between dispatcher ticks.
 * @property historyN Number of recent ticks kept in the ring buffer shown to the LLM.
 * @property temperature LLM sampling temperature (0.0 = greedy; only meaningful for LLM arms).
 * @property maxActionsPerTick Per-tick action cap enforced by [ActionValidator].
 * @property model LLM model name (e.g. `"qwen2.5:7b-instruct"`; empty string for rule-based runs).
 * @property seed Optional LLM seed for reproducible sampling; `null` when not set.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
@Serializable
data class RunParameters(
	val tickPeriodMs: Long,
	val historyN: Int,
	val temperature: Double,
	val maxActionsPerTick: Int,
	val model: String,
	val seed: Long?
)
