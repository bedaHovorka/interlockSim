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

/**
 * Categorises why a [TickOutcome.TIMEOUT_NOOP] tick occurred — i.e. why the harness applied a
 * safe do-nothing instead of a usable LLM result.
 *
 * Carried alongside [TickOutcome.TIMEOUT_NOOP] on [TickRecord] (never on any other
 * [TickOutcome] — see [TickRecord]'s invariant).
 *
 * @see TickOutcome
 * @see TickRecord
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
enum class TimeoutNoOpCause {
	/**
	 * [kotlinx.coroutines.withTimeout] (or an equivalent per-tick deadline) expired before the
	 * LLM produced any response.
	 */
	DEADLINE_MISS,

	/**
	 * The LLM responded, the response was structurally parseable, but the single repair
	 * attempt still failed to turn it into a valid action set.
	 *
	 * Not yet emitted by any code path in Issue #842 — `FallbackReason.toTickOutcome` only
	 * produces [DEADLINE_MISS] and [EMPTY_UNPARSEABLE]. This value is forward-looking
	 * taxonomy for the SP2c.20/.22 repair-attempt wiring.
	 */
	UNREPAIRABLE_OUTPUT,

	/**
	 * The LLM response was empty or could not be parsed at all, so there was nothing to repair.
	 *
	 * This is also the cause used for the [TickOutcome.TIMEOUT_NOOP] branch of the legacy
	 * `FallbackReason.EMPTY_NO_TOOLS` split — see `FallbackReason.toTickOutcome`. An empty
	 * response can never be distinguished from a dead model without an explicit, mandatory
	 * `no_op` emission (SP2c.6), so it must never be scored as [TickOutcome.LLM_NO_OP].
	 */
	EMPTY_UNPARSEABLE
}
