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
 * @property inferenceTimeoutSeconds Per-cycle LLM inference deadline in seconds that produced
 *   this run (see [cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig.inferenceTimeoutSeconds]).
 *   PR #896 measured that the LLM arm's whole measured success rate hinges on this value (30 s →
 *   every cycle times out; 90 s → clean cycles and completed journeys), and #835 requires any
 *   measured rate to name the value it ran at — this field is what lets a run JSON answer that
 *   question on its own, and lets [RunReportAggregator.appendParameterSweep]'s grouping tell two
 *   cells that differ only in this axis apart. Defaults to [KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS]
 *   (30s), the deadline every run used before this field existed, so decoding a run JSON that
 *   predates the field records the deadline that actually applied rather than an unknown value.
 * @property promptVariant Which system-prompt revision produced this run. A plain `String` rather
 *   than an enum: Task 11 (#834) introduces the actual A/B seam, and a string keeps the serialized
 *   form stable and this recorder decoupled from the enum Task 11 owns — adding or renaming a
 *   variant there never touches this file. `""` for rule-based-arm runs, which have no prompt at
 *   all; mirrors [model]'s own `""` convention for the same case. Defaults to
 *   [DEFAULT_PROMPT_VARIANT] ("has a prompt, but which variant produced it was never tracked") for
 *   every LLM-arm run until Task 11 lands — kept a different string than the rule-based `""` on
 *   purpose, so a report can never conflate "no prompt" with "prompt, untracked variant".
 *
 * ## No defaults on the original six fields — and the hazard that creates
 *
 * [tickPeriodMs], [historyN], [temperature], [maxActionsPerTick], [model] and [seed] carry no
 * Kotlin default: this record exists to say exactly what configuration produced a run, and a
 * silently-supplied default here would misattribute an unmeasured value as measured. That
 * deliberate choice has a cost, spelled out in [DispatcherRunSnapshot]'s "Schema versioning"
 * KDoc: a *non-defaulted* addition to this class makes older run JSONs fail to decode, and
 * [DefaultRunSnapshotStore.readAll]'s catch-all turns that failure into a WARN-and-skip rather
 * than a crash — so such files are silently dropped from every aggregate instead of failing
 * loudly. [inferenceTimeoutSeconds] and [promptVariant] avoid that hazard by being defaulted, for
 * exactly this reason; any further field added to this class should keep doing the same unless
 * that field's absence genuinely needs to be caught immediately rather than tolerated.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence);
 *   [inferenceTimeoutSeconds] and [promptVariant] added in Issue #834 (SP2c.11)
 */
@Serializable
data class RunParameters(
	val tickPeriodMs: Long,
	val historyN: Int,
	val temperature: Double,
	val maxActionsPerTick: Int,
	val model: String,
	val seed: Long?,
	val inferenceTimeoutSeconds: Long = KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS,
	val promptVariant: String = DEFAULT_PROMPT_VARIANT
) {
	companion object {
		/**
		 * Recorded [promptVariant] for a run whose prompt-variant was never tracked — every
		 * LLM-arm run before Task 11 (#834) wires an actual selectable seam, and the value
		 * kotlinx.serialization backfills when decoding a run JSON written before this field
		 * existed. Deliberately distinct from the rule-based arm's `""`: this value still means
		 * "a prompt was used", just not which revision of it.
		 */
		const val DEFAULT_PROMPT_VARIANT: String = "unspecified"
	}
}
