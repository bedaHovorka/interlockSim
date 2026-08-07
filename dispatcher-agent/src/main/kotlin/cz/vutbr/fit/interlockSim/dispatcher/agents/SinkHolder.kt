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
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import java.util.concurrent.CopyOnWriteArrayList
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
 * ## Per-cycle action cap (Issue #847, SP2c.24)
 *
 * [maxActionsPerTick] is §5.5's "0–3 actions per step" made real on the live path. Before #847 the
 * limit existed only inside [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator], which is
 * constructed solely by tests, so `maxActionsPerTick` was written into every run JSON as the `-1`
 * "not applicable" sentinel and the model could emit an unbounded number of actions per cycle.
 *
 * The semantics deliberately mirror [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator.validateBatch]:
 * only **non-NoOp** emissions count against the cap, because `no_op` is a first-class decision (C6)
 * and capping it would make "do nothing" fail on a busy tick. [tryEmit] refuses past the cap and
 * the calling tool returns [cz.vutbr.fit.interlockSim.dispatcher.RejectionCode.ACTION_LIMIT_EXCEEDED],
 * which the existing [RejectionRecordingTool] decorator already feeds to the run recorder — so the
 * cap shows up in `rejectionsByCode` with no new plumbing and without altering the four-tool
 * surface.
 *
 * ## Thread safety
 *
 * [current] is `@Volatile` — a single write from the tick controller is immediately visible to
 * the agent driver thread that reads it inside tool `execute()`. [emissionCount] and
 * [actionCount] are [AtomicInteger]s for the same cross-thread visibility; only the driver thread
 * mutates them during a cycle, but the planner reads them after the cycle from a potentially
 * different context. The per-cycle cap in [tryEmit] is enforced with a `compareAndSet` loop on
 * [actionCount], so the check-then-increment is atomic and the cap holds even if a future change
 * allows concurrent tool execution.
 *
 * @param initial Initial sink, defaults to [EmittedActionSink.NO_OP].
 * @param maxActionsPerTick Maximum non-NoOp emissions accepted per cycle. Defaults to
 *   [cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK].
 *
 * @since Issue #829 (SP2c.6 — Goal 10); per-cycle cap added in Issue #847 (SP2c.24)
 */
class SinkHolder(
	initial: EmittedActionSink = EmittedActionSink.NO_OP,
	val maxActionsPerTick: Int = DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK
) {
	init {
		require(maxActionsPerTick >= 1) { "maxActionsPerTick must be >= 1, was $maxActionsPerTick" }
	}

	@Volatile
	var current: EmittedActionSink = initial

	private val emissionCount = AtomicInteger(0)

	/** Non-NoOp emissions accepted so far this cycle; the quantity [maxActionsPerTick] bounds. */
	private val actionCount = AtomicInteger(0)

	/**
	 * Actions emitted this cycle, in emission order — the source for [CycleHistory] (Issue #847).
	 *
	 * The emission counters cannot serve that purpose: they are integers, and the history block
	 * has to name what was done, not how much. Bounded by [maxActionsPerTick] plus any NoOps, so
	 * it cannot grow without limit within a cycle, and it is cleared by [resetCycleEmissionCount].
	 */
	private val emittedThisCycle = CopyOnWriteArrayList<DispatchAction>()

	/**
	 * Delegates [action] to [current] and records that an emission happened this cycle.
	 *
	 * Actuator tools call this (not `current.emit(...)`) so the per-cycle counter is maintained
	 * regardless of which sink is currently installed.
	 *
	 * Prefer [tryEmit], which additionally enforces [maxActionsPerTick]. This method remains for
	 * callers that must emit unconditionally.
	 */
	fun emit(action: DispatchAction) {
		if (action !is DispatchAction.NoOp) {
			actionCount.incrementAndGet()
		}
		emitInternal(action)
	}

	/**
	 * Sink delegation + cycle bookkeeping without the [actionCount] increment, so [tryEmit] can
	 * reserve its cap slot via CAS *before* emitting and then delegate without double-counting.
	 */
	private fun emitInternal(action: DispatchAction) {
		current.emit(action)
		emissionCount.incrementAndGet()
		emittedThisCycle.add(action)
	}

	/**
	 * Emits [action] unless it would exceed [maxActionsPerTick].
	 *
	 * The cap check and the [actionCount] increment are one atomic CAS step: a non-NoOp action
	 * reserves its slot with `compareAndSet` before delegating, so two concurrent emitters cannot
	 * both pass the cap. Under the current single-driver-thread model this is uncontended, but the
	 * holder's fields are atomic/`@Volatile` for cross-thread visibility, so the cap is enforced
	 * against the same visibility contract rather than relying on a single-writer assumption.
	 *
	 * @return `true` if the action was emitted, `false` if the per-cycle cap refused it. A
	 *   [DispatchAction.NoOp] is always emitted and never counts against the cap.
	 */
	fun tryEmit(action: DispatchAction): Boolean {
		if (action is DispatchAction.NoOp) {
			emitInternal(action)
			return true
		}
		while (true) {
			val currentCount = actionCount.get()
			if (currentCount >= maxActionsPerTick) {
				return false
			}
			if (actionCount.compareAndSet(currentCount, currentCount + 1)) {
				emitInternal(action)
				return true
			}
		}
	}

	/** `true` iff [emit] was called at least once since the last [resetCycleEmissionCount]. */
	fun actedThisCycle(): Boolean = emissionCount.get() > 0

	/** Non-NoOp actions emitted since the last [resetCycleEmissionCount]. */
	fun actionsThisCycle(): Int = actionCount.get()

	/** Actions emitted since the last [resetCycleEmissionCount], in emission order. */
	fun emittedActionsThisCycle(): List<DispatchAction> = emittedThisCycle.toList()

	/** Zeroes the per-cycle emission counters. Call immediately before an LLM cycle runs. */
	fun resetCycleEmissionCount() {
		emissionCount.set(0)
		actionCount.set(0)
		emittedThisCycle.clear()
	}
}
