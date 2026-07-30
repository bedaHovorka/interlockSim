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
 * Result of projecting a legacy [FallbackReason] onto the new [TickOutcome] taxonomy via
 * [toTickOutcome].
 *
 * @param outcome The [TickOutcome] the legacy [FallbackReason] projects onto.
 * @param timeoutNoOpCause The [TimeoutNoOpCause] to pair with [outcome] on a [TickRecord],
 *   non-`null` if and only if [outcome] is [TickOutcome.TIMEOUT_NOOP] — mirrors the
 *   [TickRecord] invariant.
 *
 * @see FallbackReason.toTickOutcome
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
data class TickOutcomeProjection(
	val outcome: TickOutcome,
	val timeoutNoOpCause: TimeoutNoOpCause? = null
) {
	init {
		val expectsCause = outcome == TickOutcome.TIMEOUT_NOOP
		val hasCause = timeoutNoOpCause != null
		require(expectsCause == hasCause) {
			"timeoutNoOpCause must be set if and only if outcome is TIMEOUT_NOOP, " +
				"but outcome=$outcome timeoutNoOpCause=$timeoutNoOpCause"
		}
	}
}

/**
 * Projects a legacy [FallbackReason] onto the new [TickOutcome] taxonomy (Issue #842).
 *
 * This is a **lossy, one-directional** migration bridge, documented here per the acceptance
 * criteria of Issue #842. [FallbackReason] predates the no-op/timeout split introduced by
 * [TickOutcome] and cannot express it on its own:
 *
 * | Legacy [FallbackReason] | Fate | New encoding |
 * |---|---|---|
 * | [FallbackReason.EMPTY_NO_TOOLS] | **splits** | [TickOutcome.LLM_NO_OP] (success) if [explicitNoOp], else [TickOutcome.TIMEOUT_NOOP] with [TimeoutNoOpCause.EMPTY_UNPARSEABLE] (degraded) |
 * | [FallbackReason.TIMEOUT] | renamed, semantics changed | [TickOutcome.TIMEOUT_NOOP] with [TimeoutNoOpCause.DEADLINE_MISS] |
 * | [FallbackReason.EXCEPTION] | renamed | [TickOutcome.LLM_EXCEPTION] |
 *
 * [FallbackReason.EMPTY_NO_TOOLS] alone is ambiguous — it could mean either "the LLM correctly
 * decided to do nothing" ([TickOutcome.LLM_NO_OP]) or "the LLM produced nothing at all"
 * ([TickOutcome.TIMEOUT_NOOP] with [TimeoutNoOpCause.EMPTY_UNPARSEABLE]). Disambiguating the two
 * requires knowing whether the cycle carried an *explicit* `no_op` emission — information
 * [FallbackReason] itself does not carry, which is exactly why this projection takes an extra
 * [explicitNoOp] parameter instead of being a pure enum-to-enum lookup.
 *
 * The split is only trustworthy once the loop makes `no_op` an explicit, mandatory emission
 * (SP2c.6's "exactly one action per tick, `no_op` included" contract). Until a caller can
 * independently prove that contract was satisfied for a given cycle, it MUST pass
 * `explicitNoOp = false` (the default) — an empty response can never be distinguished from a
 * dead model, so it must never be scored as a success.
 *
 * @param explicitNoOp `true` only when the caller has independently verified the LLM emitted an
 *   explicit `no_op` action for this cycle. Ignored for [FallbackReason.TIMEOUT] and
 *   [FallbackReason.EXCEPTION], which are unambiguous on their own.
 * @return the [TickOutcome] (and, for [TickOutcome.TIMEOUT_NOOP], the [TimeoutNoOpCause]) this
 *   [FallbackReason] projects onto.
 *
 * @see FallbackReason
 * @see TickOutcome
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
fun FallbackReason.toTickOutcome(explicitNoOp: Boolean = false): TickOutcomeProjection =
	when (this) {
		FallbackReason.EMPTY_NO_TOOLS ->
			if (explicitNoOp) {
				TickOutcomeProjection(outcome = TickOutcome.LLM_NO_OP)
			} else {
				TickOutcomeProjection(
					outcome = TickOutcome.TIMEOUT_NOOP,
					timeoutNoOpCause = TimeoutNoOpCause.EMPTY_UNPARSEABLE
				)
			}

		FallbackReason.TIMEOUT ->
			TickOutcomeProjection(
				outcome = TickOutcome.TIMEOUT_NOOP,
				timeoutNoOpCause = TimeoutNoOpCause.DEADLINE_MISS
			)

		FallbackReason.EXCEPTION ->
			TickOutcomeProjection(outcome = TickOutcome.LLM_EXCEPTION)
	}
