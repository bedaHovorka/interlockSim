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
 *
 * At run end, [logFinalSummary] emits a structured INFO log with per-author action counts,
 * so B2's "why this route?" can also answer "who decided this?" without inspecting raw logs.
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
 * @since Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance)
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

	/**
	 * Appends every [AttributedAction] in [record] to the internal attribution log.
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
	 * Format example:
	 * ```
	 * [DispatcherPreferenceStore] final summary — totalActions=42 {LLM=30, TIMEOUT_NOOP=5, RULE_BASED=7, RULE_FALLBACK=0, SAFETY_NET=0, OPERATOR=0}
	 * ```
	 *
	 * Safe to call with zero recorded actions — the counts will all be zero.
	 * Read-only: does not mutate any state, so it is safe to call more than once.
	 *
	 * @since Issue #832 (SP2c.9 — Goal 10 decision attribution + provenance)
	 */
	fun logFinalSummary() {
		val counts = getAuthorCounts()
		val total = records.size.toLong()
		val byAuthorStr =
			ActionAuthor.entries.joinToString(", ") { author ->
				"${author.name}=${counts[author] ?: 0L}"
			}
		logger.info {
			"[DispatcherPreferenceStore] final summary — totalActions=$total {$byAuthorStr}"
		}
	}
}
