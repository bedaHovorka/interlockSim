/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * In-memory store for decision attribution data, satisfying Principle P7 (SP2c.9, Issue #832).
 *
 * ## Purpose
 *
 * Every applied [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction] carries an
 * [ActionAuthor] and a [AttributedAction.reason]. [DispatcherPreferenceStore] persists those
 * tags for the lifetime of one simulation run and answers:
 * - "Who authored how many actions?" — via [getAuthorCounts]
 * - "Who decided this specific route?" — via [getRecords] (all per-tick records)
 * - "Was the C7 constraint respected?" — via [c7Clean]
 *
 * At run end, [logFinalSummary] emits a structured INFO log with per-author action counts,
 * so B2's "why this route?" can also answer "who decided this?" without inspecting raw logs.
 * When [c7Clean] is `false`, the summary line is prefixed `!! C7_VIOLATION !!` and a one-time
 * WARN with the literal token `C7_VIOLATION` was already emitted at the first violation (SP2c.20).
 *
 * ## C7 gate (SP2c.20, Issue #843)
 *
 * C7 requires that every applied dispatching action originate from the LLM (or from an
 * intentional rule-based run — [ActionAuthor.RULE_BASED]).  A C7 violation occurs when
 * [ActionAuthor.RULE_FALLBACK] or [ActionAuthor.SAFETY_NET] actions appear, because those
 * authors indicate that the LLM was absent or ineffective and the deterministic fallback
 * stepped in to keep the simulation alive.
 *
 * Three signals make a C7 violation unmissable:
 * 1. [c7Clean] — a single top-level boolean; `false` if any violation has been observed.
 * 2. A WARN log with the literal token `C7_VIOLATION` on the **first** occurrence per run.
 * 3. The [logFinalSummary] line is prefixed `!! C7_VIOLATION !!` when `!c7Clean`.
 *
 * ## Threading
 *
 * Not thread-safe. Intended to be called only from the single agent-driver thread that owns
 * the [DispatchTickLoop], matching the ownership model of [TerminalFallbackGuard].
 *
 * ## Wiring
 *
 * Pass an instance to [DispatchTickLoop] at construction time:
 * ```kotlin
 * val store = DispatcherPreferenceStore()
 * val loop  = DispatchTickLoop(..., preferenceStore = store)
 * // At simulation end:
 * store.logFinalSummary()
 * ```
 *
 * **Production wiring status (SP2c.9):** the store is currently exercised only via tests —
 * [DispatchTickLoop] is constructed only in test code (`PausedClockSpikeHarness`,
 * `RuleBasedDispatcherDeterminismRunner`, `HeadlessPacingFeasibilityTest`,
 * `DispatchTickLoopTest`); production `ExampleRegistry.wireDispatcherAgent` still uses the
 * older `agentDriverAction` loop and does not construct `DispatchTickLoop`. Production
 * wiring of this store therefore awaits `DispatchTickLoop` production promotion (a separate
 * SP2c effort). The store API and log format are stable and tested so the wiring is a
 * one-line addition once the loop is promoted.
 *
 * @since Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance);
 *        [c7Clean] / C7 gate added Issue #843 (SP2c.20)
 */
class DispatcherPreferenceStore {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * A single persisted attribution record for one emitted action.
	 *
	 * @property tick Dispatcher tick on which the action was produced.
	 * @property simTime Simulation time (seconds) at the start of that tick.
	 * @property actionKind [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.kind] of the
	 *   action (e.g. `"approve_train"`, `"no_op"`).
	 * @property author Who produced the action.
	 * @property reason Why it was produced; empty string when not supplied.
	 */
	data class ActionAttributionRecord(
		val tick: Long,
		val simTime: Double,
		val actionKind: String,
		val author: ActionAuthor,
		val reason: String
	)

	private val records: MutableList<ActionAttributionRecord> = mutableListOf()

	/** `true` if the one-time C7_VIOLATION WARN has already been emitted this run. */
	private var c7ViolationLogged = false

	/**
	 * `true` when no [ActionAuthor.RULE_FALLBACK] or [ActionAuthor.SAFETY_NET] actions have
	 * been recorded for this run; `false` as soon as any such action is observed.
	 *
	 * A `false` value means the C7 constraint was violated: the deterministic fallback or
	 * forced-admission safety net stepped in, which should not happen in an autonomous LLM
	 * run.  When `false`, [logFinalSummary] prefixes the summary line with `!! C7_VIOLATION !!`
	 * and a one-time WARN with the literal token `C7_VIOLATION` was already emitted at the
	 * first violation (SP2c.20, Issue #843).
	 *
	 * @since Issue #843 (SP2c.20 — Goal 10 action attribution + C7 violation gate)
	 */
	val c7Clean: Boolean
		get() {
			val counts = getAuthorCounts()
			return (counts[ActionAuthor.RULE_FALLBACK] ?: 0L) == 0L &&
				(counts[ActionAuthor.SAFETY_NET] ?: 0L) == 0L
		}

	/**
	 * Appends every [AttributedAction] in [record] to the internal attribution log.
	 *
	 * On the first tick that introduces a [ActionAuthor.RULE_FALLBACK] or
	 * [ActionAuthor.SAFETY_NET] action, emits a one-time WARN with the literal token
	 * `C7_VIOLATION` so the violation is unmissable in the log (SP2c.20, Issue #843).
	 *
	 * Intended to be called from [DispatchTickLoop] after each completed tick (same position
	 * as [TerminalFallbackGuard.observe]).
	 *
	 * @param record The completed tick's action/verdict/outcome bundle.
	 */
	fun observe(record: TickRecord) {
		for (action in record.actions) {
			records.add(
				ActionAttributionRecord(
					tick = record.tick,
					simTime = record.simTime,
					actionKind = action.action.kind,
					author = action.author,
					reason = action.reason
				)
			)
		}
		// C7 gate (SP2c.20): emit a one-time WARN on the first tick that introduces a
		// RULE_FALLBACK or SAFETY_NET action.  Flood prevention: fires at most once per run.
		if (!c7ViolationLogged && !c7Clean) {
			c7ViolationLogged = true
			val violatingAuthors =
				record.actions
					.filter { it.author == ActionAuthor.RULE_FALLBACK || it.author == ActionAuthor.SAFETY_NET }
					.joinToString(", ") { "${it.author.name}:${it.action.kind}" }
			logger.warn {
				"C7_VIOLATION: deterministic component authored dispatching action(s) on tick ${record.tick} — $violatingAuthors"
			}
		}
	}

	/**
	 * Returns the accumulated attribution records as an immutable snapshot.
	 *
	 * The returned list is a defensive copy; subsequent [observe] calls do not affect it.
	 */
	fun getRecords(): List<ActionAttributionRecord> = records.toList()

	/**
	 * Returns a map of [ActionAuthor] → count of actions attributed to that author over the
	 * entire run observed so far.
	 *
	 * Authors with zero recorded actions are **not** included (the map only contains authors
	 * for which at least one action has been recorded). Callers that need a zero-filled map
	 * over all authors can expand it with `ActionAuthor.entries`.
	 */
	fun getAuthorCounts(): Map<ActionAuthor, Long> =
		records.groupingBy { it.author }.eachCount().mapValues { it.value.toLong() }

	/**
	 * Returns the total number of non-[cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp]
	 * dispatching actions attributed to [author].
	 *
	 * A "dispatching action" is any action whose kind is not `"no_op"` — i.e., an action that
	 * actually posts a [cz.vutbr.fit.interlockSim.sim.DispatchDecision] to the sim thread.
	 * [ActionAuthor.TIMEOUT_NOOP] is always paired with `no_op`, so this count is provably 0
	 * for that author.
	 *
	 * @param author The author to count dispatching actions for.
	 * @return Count of non-`no_op` actions attributed to [author].
	 */
	fun getDispatchingActionCount(author: ActionAuthor): Long =
		records.count { it.author == author && it.actionKind != DispatchAction.NoOp.kind }.toLong()

	/**
	 * Logs an unconditional INFO-level per-run author-count summary.
	 *
	 * Format example (c7Clean = true):
	 * ```
	 * [DispatcherPreferenceStore] final summary — totalActions=42 {LLM=30, TIMEOUT_NOOP=5, RULE_BASED=7, RULE_FALLBACK=0, SAFETY_NET=0, OPERATOR=0}
	 * ```
	 *
	 * Format example (c7Clean = false — C7 violation occurred):
	 * ```
	 * !! C7_VIOLATION !! [DispatcherPreferenceStore] final summary — totalActions=42 {LLM=30, TIMEOUT_NOOP=5, RULE_BASED=0, RULE_FALLBACK=5, SAFETY_NET=2, OPERATOR=0}
	 * ```
	 *
	 * Safe to call with zero recorded actions — the counts will all be zero.
	 * Read-only: does not mutate any state, so it is safe to call more than once.
	 *
	 * @since Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance);
	 *        `!! C7_VIOLATION !!` prefix added Issue #843 (SP2c.20)
	 */
	fun logFinalSummary() {
		val counts = getAuthorCounts()
		val total = records.size.toLong()
		val byAuthorStr =
			ActionAuthor.entries.joinToString(", ") { author ->
				"${author.name}=${counts[author] ?: 0L}"
			}
		val prefix = if (!c7Clean) "!! C7_VIOLATION !! " else ""
		logger.info {
			"${prefix}[DispatcherPreferenceStore] final summary — totalActions=$total {$byAuthorStr}"
		}
	}
}
