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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mutable holder for the active [EmittedActionSink], shared across all actuator tools in one
 * agent instance (SP2c.6, Issue #829).
 *
 * ## Why a holder?
 *
 * All four actuator tools are constructed once and reused across ticks. The sink they delegate to
 * must change per-tick (list-collecting during a tick, [EmittedActionSink.NO_OP] otherwise).
 * Rather than rebuilding the tools on every tick, the tools hold a reference to a [SinkHolder]
 * and call [emit] at execution time. The tick controller swaps [current] atomically before
 * running the agent, then resets it after.
 *
 * ## Per-cycle emission counter (the double-dispatch guard)
 *
 * [emit] delegates to [current] and increments an [emissionCount]. [resetCycleEmissionCount] is
 * called immediately before an LLM cycle and [actedThisCycle] immediately after: a non-zero count
 * means the LLM acted via its actuator tools during the cycle (the tool calls already posted the
 * decisions to the queue through [current]), so a downstream planner must **not** also run its
 * rule-based fallback — doing so would double-dispatch the same cycle's decisions to the same
 * queue. Counting a [DispatchAction.NoOp] emission as "acted" is correct: `no_op` is a deliberate
 * decision (the LLM chose to do nothing), so the fallback must not run on top of it (SP2c.19).
 *
 * This counter is the SP2c.6 replacement for the per-cycle actuator-post counter that used to live
 * on [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] (`resetCycleActuatorCount` /
 * `actedViaToolsThisCycle`, deleted in #829). It counts [emit] **calls**, not queue **contents**,
 * so it is immune to the kDisco sim thread draining the queue between the two samples — the
 * false-negative window a queue-content delta could not close under the decoupled driver/sim
 * threading model.
 *
 * ## Thread safety
 *
 * [current] is `@Volatile` — a single write from the tick controller is immediately visible to
 * the agent driver thread that reads it inside tool `execute()`. [emissionCount] is an
 * [AtomicInteger] for the same cross-thread visibility; only the driver thread mutates it during a
 * cycle, but the planner reads it after the cycle from a potentially different context.
 *
 * @param initial Initial sink, defaults to [EmittedActionSink.NO_OP].
 *
 * @since Issue #829 (SP2c.6 — Goal 10)
 */
class SinkHolder(
	initial: EmittedActionSink = EmittedActionSink.NO_OP
) {
	@Volatile
	var current: EmittedActionSink = initial

	private val emissionCount = AtomicInteger(0)

	/**
	 * Delegates [action] to [current] and records that an emission happened this cycle.
	 *
	 * Actuator tools call this (not `current.emit(...)`) so the per-cycle counter is maintained
	 * regardless of which sink is currently installed.
	 */
	fun emit(action: DispatchAction) {
		current.emit(action)
		emissionCount.incrementAndGet()
	}

	/** `true` iff [emit] was called at least once since the last [resetCycleEmissionCount]. */
	fun actedThisCycle(): Boolean = emissionCount.get() > 0

	/** Zeroes the per-cycle emission counter. Call immediately before an LLM cycle runs. */
	fun resetCycleEmissionCount() {
		emissionCount.set(0)
	}
}
