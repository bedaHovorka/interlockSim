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
 * The clean-run rule of Goal 9B (Issue #1476; rulings Q1 and Q2 of #1475), word for word:
 *
 * > A run is clean when its end cause is natural completion, its run JSON has the #1476 schema or later,
 * > every author other than `LLM` and `TIMEOUT_NOOP` has zero actions, and the unattributed-action count
 * > of #1477 is zero. A silent cycle (`TIMEOUT_NOOP`, an LLM `no_op`, an empty plan) is not a counted
 * > action and does not break a clean run; silent cycles are counted by cause. Whatever a silence lets
 * > happen is still counted (an expiry is `EXPIRY`). An action with no author goes to the
 * > unattributed-action counter, never to a new `ActionAuthor` value. Scripted test commands carry author
 * > `LLM`; the run-level command-source field of the #1476 schema tells a scripted run from an LLM run.
 *
 * Read from the run JSON alone: [DispatcherRunSnapshot.endCause], [DispatcherRunSnapshot.schemaVersion],
 * [DispatcherRunSnapshot.actionsByAuthor] (which holds the counted automatics, [CountedAutomatic]) and
 * [DispatcherRunSnapshot.unattributedApplies] (the unattributed-action counter #1477 fills). An absent
 * counter means not clean: a file before the #1476 schema, or a snapshot of that schema whose
 * [DispatcherRunSnapshot.countedAutomatics] is absent (a `TIMEOUT_ABORT` or `CRASH` snapshot, which also
 * fails the end cause), [Violation.SCHEMA_BEFORE_CLEAN_RUN_RULE]. A missing key is absent too: a snapshot of
 * the #1476 schema or later that lacks a key its schema carries -- a [CountedAutomatic] row, an
 * [ActionAuthor] key of [DispatcherRunSnapshot.actionsByAuthor] or a [SilentCycleCause] key -- or whose
 * author total is below the sum of that author's rows is [Violation.INCOMPLETE_COUNTERS]. The keys a file
 * must carry are pinned to the schema that introduced them ([CountedAutomatic.sinceSchema],
 * [AUTHOR_SINCE_SCHEMA]), so a file of the #1476 schema stays judgeable after a later schema adds a key.
 *
 * ## Scope: the rule judges LLM and scripted runs
 *
 * The rule applies to runs whose [DispatcherRunSnapshot.commandSource] is [CommandSourceKind.LLM] or
 * [CommandSourceKind.SCRIPTED]. This is the scope of the rule, not a sixth clause of it: row C15 of
 * #1475 rules that the rule-based control arm is "judged by `ExitPassBars` and #895, not by the clean
 * rule", and ADR part 1 §3.4 counts only `kind = LLM` in LLM campaigns. A run outside that scope
 * ([CommandSourceKind.RULE_BASED], [CommandSourceKind.NONE], or a #1476-schema file with no source) is
 * never judged clean, [Violation.NO_COMMAND_SOURCE]: a run with zero dispatcher actions has no evidence
 * about its authors, so leaving it out fails closed.
 *
 * ## Decided here: open point O3 of #1475
 *
 * - The counter key names of C1-C15: [CountedAutomatic] for the rows without an action path, the
 *   [cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor] names for the rest.
 * - Whether `LLM_EXCEPTION` and the open-breaker skip become silent-cycle causes after #1413: **decided in
 *   #1413.** Until #1413 both run the rule fallback, so their cycles are counted under `RULE_FALLBACK`
 *   (C8) and are not silent. #1413 adds the causes to [SilentCycleCause] if its cycles become silent.
 *
 * ## Keys spelled here, written by later leaves
 *
 * The #1474 ADR assigns the spelling of these keys to #1476. Each leaf adds its key with its producer and
 * bumps the schema; none exists before then.
 *
 * | Key | Meaning | Writer |
 * |---|---|---|
 * | `cyclesPartial`, `cyclesRefused`, `cyclesStale`, `cyclesUndrained`, `cyclesTimedOut` | cycle counters | #1482 |
 * | `drainLagDnDistribution`, `drainLagDoDistribution` | D−N and D−O (undrained left out of D−N) | #1482 |
 * | `staleRefusalsByAuthor` | `staleRefusals` by author (the total is #1463's) | #1463; `OPERATOR` #1440 |
 * | `pacingRateDistribution`, `secondsAtMinimumSpeed` | pacing rate, time at minimum speed | #1440 |
 * | `emergencyLatencyBreaches` | R9 latency breaches | #1484 |
 * | `operatorRejected` | SEMI_AUTO approvals the operator rejected | #1440 |
 * | `seq`, `cycleId`, `observedAt`, `snapshotAt`, `drainedAt` | seq, cycle id, O, N, D (own schema number) | #1482 |
 *
 * Also open elsewhere: O4 of #1475, the end cause of the live-conflict auto-pause, is #1485's.
 * When a C10 or C11 producer is added, its leaf adds its own [CountedAutomatic] row with a schema bump.
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

		/**
		 * A snapshot of the #1476 schema or later lacks a counter key its schema carries, or an author total
		 * is below the sum of its counted rows: an incomplete record, never clean.
		 */
		INCOMPLETE_COUNTERS,

		/** An author other than `LLM` and `TIMEOUT_NOOP` acted, a counted automatic included. */
		COUNTED_AUTHOR_ACTED,

		/** At least one applied action had no author (the unattributed-action counter is not zero). */
		UNATTRIBUTED_ACTIONS,

		/** Out of the rule's scope: the run's command source is not LLM or scripted, or is absent. */
		NO_COMMAND_SOURCE
	}

	/**
	 * The run JSON schema from which each author's `actionsByAuthor` key is required (Issue #1476). The
	 * floor is the #1476 schema, since an older file is refused whatever it carries; an author added later
	 * enters with the schema it bumps to. Every [ActionAuthor] has an entry (`CleanRunRuleTest`).
	 */
	internal val AUTHOR_SINCE_SCHEMA: Map<ActionAuthor, Int> =
		ActionAuthor.entries.associateWith { DispatcherRunSnapshot.SCHEMA_VERSION_INTRODUCING_CLEAN_RUN_RULE }

	/** The [CountedAutomatic] keys a file of [schemaVersion] must carry in `countedAutomatics`. */
	internal fun requiredCountedAutomaticKeys(schemaVersion: Int): Set<String> =
		CountedAutomatic.entries
			.filter { it.sinceSchema <= schemaVersion }
			.mapTo(mutableSetOf()) { it.name }

	/** The [ActionAuthor] keys a file of [schemaVersion] must carry in `actionsByAuthor`. */
	internal fun requiredAuthorKeys(schemaVersion: Int): Set<String> =
		AUTHOR_SINCE_SCHEMA
			.filterValues { it <= schemaVersion }
			.keys
			.mapTo(mutableSetOf()) { it.name }

	/** The [SilentCycleCause] keys a file of the #1476 schema or later must carry in `silentCyclesByCause`. */
	private val requiredSilentCycleCauseKeys: Set<String> = SilentCycleCause.entries.map { it.name }.toSet()

	/**
	 * Whether [snapshot], a file of the #1476 schema or later with a `countedAutomatics` map, lacks a key its
	 * schema carries or holds an author total below the sum of that author's rows.
	 */
	private fun incomplete(
		snapshot: DispatcherRunSnapshot,
		counted: Map<String, Long>
	): Boolean {
		val schema = snapshot.schemaVersion
		val silent = snapshot.silentCyclesByCause
		val missingKey =
			!counted.keys.containsAll(requiredCountedAutomaticKeys(schema)) ||
				!snapshot.actionsByAuthor.keys.containsAll(requiredAuthorKeys(schema)) ||
				silent == null ||
				!silent.keys.containsAll(requiredSilentCycleCauseKeys)
		val authorBelowRows =
			CountedAutomatic.entries
				.groupBy { it.author.name }
				.any { (author, rows) ->
					(snapshot.actionsByAuthor[author] ?: 0L) < rows.sumOf { counted[it.name] ?: 0L }
				}
		return missingKey || authorBelowRows
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
			} else if (incomplete(snapshot, snapshot.countedAutomatics)) {
				add(Violation.INCOMPLETE_COUNTERS)
			}
			val counted = snapshot.actionsByAuthor.filterKeys { it !in ALLOWED_AUTHORS.map(ActionAuthor::name) }
			if (counted.values.any { it != 0L }) add(Violation.COUNTED_AUTHOR_ACTED)
			if (snapshot.unattributedApplies != 0L) add(Violation.UNATTRIBUTED_ACTIONS)
			// Scope, not a clause (see the KDoc): a file before the #1476 schema has no source and is
			// already refused above, so the absent source is a violation only from that schema on.
			val inScope = snapshot.commandSource?.kind in setOf(CommandSourceKind.LLM, CommandSourceKind.SCRIPTED)
			val sourceExpected = snapshot.schemaVersion >= DispatcherRunSnapshot.SCHEMA_VERSION_INTRODUCING_CLEAN_RUN_RULE
			if (!inScope && (snapshot.commandSource != null || sourceExpected)) add(Violation.NO_COMMAND_SOURCE)
		}
}
