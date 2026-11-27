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
 * The cause of a silent dispatcher cycle (Issue #1476, the silent-cycle table of #1475 §1b.1). A silent
 * cycle is not a counted action and does not break a clean run; the run record holds how many there were
 * per cause in [DispatcherRunSnapshot.silentCyclesByCause]. Each silent cycle goes under exactly one cause.
 *
 * The causes are read from existing keys of [DispatcherRunSnapshot.ticksByOutcome] and
 * [DispatcherRunSnapshot.timeoutNoOpByCause], by name, so [THINKING_TRUNCATED] is counted as soon as #1342
 * adds that `TimeoutNoOpCause` value, without a change here.
 *
 * Today a silent non-idle cycle still runs the rule fallback (C8, until #1413): its fallback actions are
 * counted under `RULE_FALLBACK` whatever the cycle's silent cause.
 *
 * @since Issue #1476 (Goal 9B SP5.3)
 */
enum class SilentCycleCause {
	/** [TickOutcome.TIMEOUT_NOOP] with [TimeoutNoOpCause.DEADLINE_MISS]. */
	TIMEOUT,

	/**
	 * [TickOutcome.TIMEOUT_NOOP] cut by the `num_predict` cap (`done_reason=length`), the #1342 sub-cause
	 * `THINKING_TRUNCATED` of `TIMEOUT_NOOP`; never also counted as [TIMEOUT]. A cut cycle that already
	 * emitted a command is not silent: its commands are `LLM` actions.
	 */
	THINKING_TRUNCATED,

	/**
	 * An empty plan: [TimeoutNoOpCause.EMPTY_UNPARSEABLE] and [TimeoutNoOpCause.UNREPAIRABLE_OUTPUT], and the
	 * outcomes [TickOutcome.LLM_SILENT_ALL_MOVING] and [TickOutcome.LLM_SILENT_NONACTIONABLE].
	 */
	EMPTY_PLAN,

	/** An LLM `no_op` cycle, [TickOutcome.LLM_NO_OP]. */
	NO_OP;

	companion object {
		/** The silent cycles of a run by cause, every cause present, from its two string-keyed tick maps. */
		fun countsFrom(
			ticksByOutcome: Map<String, Long>,
			timeoutNoOpByCause: Map<String, Long>
		): Map<String, Long> {
			fun outcome(value: TickOutcome) = ticksByOutcome[value.name] ?: 0L

			fun cause(name: String) = timeoutNoOpByCause[name] ?: 0L
			return mapOf(
				TIMEOUT.name to cause(TimeoutNoOpCause.DEADLINE_MISS.name),
				THINKING_TRUNCATED.name to cause(THINKING_TRUNCATED.name),
				EMPTY_PLAN.name to
					cause(TimeoutNoOpCause.EMPTY_UNPARSEABLE.name) + cause(TimeoutNoOpCause.UNREPAIRABLE_OUTPUT.name) +
					outcome(TickOutcome.LLM_SILENT_ALL_MOVING) + outcome(TickOutcome.LLM_SILENT_NONACTIONABLE),
				NO_OP.name to outcome(TickOutcome.LLM_NO_OP)
			)
		}
	}
}
