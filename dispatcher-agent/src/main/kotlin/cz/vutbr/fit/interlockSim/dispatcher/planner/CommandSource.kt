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
 * What issued a run's high-level commands (Issue #1476, ADR part 1 §3.4 of #1474): the run-level
 * `commandSource` field of the run JSON. Scripted test commands carry author `LLM` like the model's, so
 * this field is what tells a scripted run from an LLM run.
 *
 * @property kind Which kind of source.
 * @property name The model tag for [CommandSourceKind.LLM], the script name for
 *   [CommandSourceKind.SCRIPTED], empty for the other kinds.
 * @since Issue #1476 (Goal 9B SP5.3)
 */
@Serializable
data class CommandSource(
	val kind: CommandSourceKind,
	val name: String
) {
	companion object {
		/**
		 * The source a run of [arm] had when nothing recorded another one: an LLM arm's model tag
		 * [model], the rule-based arm, or none. Also the mapping of a file written before the field
		 * existed (see [commandSourceOrDerived]). A scripted run is never derived: #1479 records it.
		 */
		fun forArm(
			arm: DispatcherArm,
			model: String
		): CommandSource =
			when (arm) {
				DispatcherArm.LLM_TOOL_CALLING, DispatcherArm.LLM_CONSTRAINED_JSON ->
					CommandSource(CommandSourceKind.LLM, model)
				DispatcherArm.RULE_BASED -> CommandSource(CommandSourceKind.RULE_BASED, "")
				DispatcherArm.NO_DISPATCHER -> CommandSource(CommandSourceKind.NONE, "")
			}
	}
}

/** The kinds of [CommandSource]; every run JSON of schema 17 or later carries one. */
enum class CommandSourceKind {
	/** An LLM dispatcher; the name is the model tag. */
	LLM,

	/** A scripted `HighLevelCommandSource` (#1479); the name is the script name. */
	SCRIPTED,

	/** The rule-based dispatcher, inline or synchronous. */
	RULE_BASED,

	/** No dispatcher at all. */
	NONE
}

/**
 * The [DispatcherRunSnapshot.commandSource] of this run, or, for a file written before the field existed
 * (schema 16 and older), the one its arm and model imply ([CommandSource.forArm]), so old campaign JSONs
 * still group by source. The clean-run rule never reads the derived value: such a file is not clean.
 */
fun DispatcherRunSnapshot.commandSourceOrDerived(): CommandSource =
	commandSource ?: CommandSource.forArm(arm, params.model)
