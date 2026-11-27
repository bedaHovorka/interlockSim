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

import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor

/**
 * The clean-run rule of Goal 9B (Issue #1476; rulings Q1 and Q2 of #1475, word for word):
 *
 * > A run is clean when its end cause is natural completion, its run JSON has the #1476 schema or later,
 * > every author other than `LLM` and `TIMEOUT_NOOP` has zero actions, and the unattributed-action count
 * > of #1477 is zero. A silent cycle (`TIMEOUT_NOOP`, an LLM `no_op`, an empty plan) is not a counted
 * > action and does not break a clean run; silent cycles are counted by cause.
 *
 * Read from the run JSON alone: [DispatcherRunSnapshot.actionsByAuthor] (which holds the counted
 * automatics, [CountedAutomatic]), [DispatcherRunSnapshot.unattributedApplies] (the unattributed-action
 * counter #1477 fills) and [DispatcherRunSnapshot.endCause].
 *
 * Two readings of the rule are made explicit as violations of their own:
 * - An absent counter means not clean: a file before the #1476 schema, and a `TIMEOUT_ABORT` or `CRASH`
 *   snapshot (which also fails the end cause).
 * - The rule judges dispatcher runs: a run whose [DispatcherRunSnapshot.commandSource] is
 *   [CommandSourceKind.RULE_BASED] or [CommandSourceKind.NONE] is never clean. The rule-based control arm
 *   (row C15) is judged by `ExitPassBars` and #895, and a run without a dispatcher has no author evidence.
 *
 * `c7Clean` stays as it was (no `RULE_FALLBACK` and no `SAFETY_NET` action) for the existing gate; SP6
 * moves the gate to this rule.
 *
 * @since Issue #1476 (Goal 9B SP5.3)
 */
object CleanRunRule {
	/** The authors whose actions a clean run may hold. */
	val ALLOWED_AUTHORS: Set<ActionAuthor> = setOf(ActionAuthor.LLM, ActionAuthor.TIMEOUT_NOOP)

	/** Why a run is not clean; [violations] lists every one that applies. */
	enum class Violation {
		/** The end cause is not natural completion (or the snapshot is in progress). */
		NOT_NATURAL_COMPLETION,

		/** The run JSON predates the #1476 schema: its counters are absent. */
		SCHEMA_BEFORE_CLEAN_RUN_RULE,

		/** An author other than `LLM` and `TIMEOUT_NOOP` acted, a counted automatic included. */
		COUNTED_AUTHOR_ACTED,

		/** At least one applied action had no author (the unattributed-action counter is not zero). */
		UNATTRIBUTED_ACTIONS,

		/** The run had no LLM and no scripted command source. */
		NO_COMMAND_SOURCE
	}

	/** Whether [snapshot] is a clean run. */
	fun isClean(snapshot: DispatcherRunSnapshot): Boolean = violations(snapshot).isEmpty()

	/** Every condition of the rule [snapshot] fails, in the order of [Violation]; empty for a clean run. */
	fun violations(snapshot: DispatcherRunSnapshot): List<Violation> =
		buildList {
			if (snapshot.endCause != RunEndCause.NATURAL_COMPLETION) add(Violation.NOT_NATURAL_COMPLETION)
			if (snapshot.schemaVersion < DispatcherRunSnapshot.SCHEMA_VERSION_INTRODUCING_CLEAN_RUN_RULE ||
				snapshot.countedAutomatics == null
			) {
				add(Violation.SCHEMA_BEFORE_CLEAN_RUN_RULE)
			}
			val counted = snapshot.actionsByAuthor.filterKeys { it !in ALLOWED_AUTHORS.map(ActionAuthor::name) }
			if (counted.values.any { it != 0L }) add(Violation.COUNTED_AUTHOR_ACTED)
			if (snapshot.unattributedApplies != 0L) add(Violation.UNATTRIBUTED_ACTIONS)
			val source = snapshot.commandSource
			if (source != null && source.kind !in setOf(CommandSourceKind.LLM, CommandSourceKind.SCRIPTED)) {
				add(Violation.NO_COMMAND_SOURCE)
			}
		}
}
