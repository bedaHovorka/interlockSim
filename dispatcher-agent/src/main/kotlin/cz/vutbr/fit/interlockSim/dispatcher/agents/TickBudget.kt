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

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deadline wrapper for [EmissionStrategy.emit] inside [DispatchTickLoop] (SP2c.5, Issue #828).
 *
 * The loop calls `budget.withBudget { emission.emit(prompt, obs) }`. If the emission strategy
 * exceeds the configured deadline, [withBudget] returns `null` and the loop substitutes a
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp] with author
 * [ActionAuthor.TIMEOUT_NOOP].
 *
 * ## Implementations
 *
 * - [DeadlineTickBudget] — wraps [kotlinx.coroutines.withTimeoutOrNull]; use for LLM strategies.
 * - [NoTimeoutBudget] — passes the block through directly with no deadline;
 *   use for synchronous / rule-based strategies.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
interface TickBudget {
	/**
	 * Executes [block] within the configured time budget.
	 *
	 * @param block The suspend block to execute (typically [EmissionStrategy.emit]).
	 * @return The result of [block], or `null` if the deadline was exceeded.
	 */
	suspend fun <T> withBudget(block: suspend () -> T?): T?
}

/**
 * [TickBudget] implementation that enforces a hard deadline via
 * [kotlinx.coroutines.withTimeoutOrNull].
 *
 * @property timeoutMillis Maximum time allowed in milliseconds. Returns `null` on timeout.
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
class DeadlineTickBudget(
	private val timeoutMillis: Long
) : TickBudget {
	override suspend fun <T> withBudget(block: suspend () -> T?): T? =
		withTimeoutOrNull(timeoutMillis) { block() }
}

/**
 * [TickBudget] implementation that applies no deadline — the block always runs to completion.
 *
 * Use this for synchronous strategies (e.g. [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy])
 * where a deadline makes no sense: rule-based dispatch returns immediately and
 * `withTimeoutOrNull` overhead would add noise without value.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
object NoTimeoutBudget : TickBudget {
	override suspend fun <T> withBudget(block: suspend () -> T?): T? = block()
}
