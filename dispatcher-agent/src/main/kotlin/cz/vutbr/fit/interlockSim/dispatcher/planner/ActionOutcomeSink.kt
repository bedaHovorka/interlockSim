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
 * Sink for [ActionOutcome] notifications produced by
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] on the kDisco simulation thread
 * when a queued command is drained and applied (or fails at apply time) (SP2c.20, Issue #843).
 *
 * ## No-logging contract (sim-thread path)
 *
 * Implementations of [onActionOutcome] **must not** call any logging method.  The applier runs
 * inside the kDisco event loop; every log call injects latency into the physics loop.  The
 * no-logging requirement is verified by a dedicated test that captures Logback output during
 * a real [onActionOutcome] invocation and asserts the appender received zero events.
 *
 * ## Relationship to [cz.vutbr.fit.interlockSim.dispatcher.agents.DispatcherPreferenceStore]
 *
 * [DispatcherPreferenceStore][cz.vutbr.fit.interlockSim.dispatcher.agents.DispatcherPreferenceStore]
 * observes [cz.vutbr.fit.interlockSim.dispatcher.agents.TickRecord] objects (produced on the
 * driver thread) to count per-author action totals and compute `c7Clean`.  [ActionOutcomeSink]
 * is the complementary sim-thread path: it receives individual apply-time outcomes as they
 * happen, enabling per-action attribution at apply granularity.  Both views are necessary —
 * [DispatcherPreferenceStore] for per-tick summaries, [ActionOutcomeSink] for per-apply detail.
 *
 * @since Issue #843 (SP2c.20 — Goal 10 action attribution + C7 violation gate)
 */
fun interface ActionOutcomeSink {
	/**
	 * Called by [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] on the kDisco
	 * simulation thread when a command has been processed (applied, blocked, or rejected at the
	 * apply-time layer).
	 *
	 * **Must not log** — see class-level KDoc for the rationale.  Any implementation that
	 * calls `logger.*` violates the sim-thread latency contract and will fail the no-logging test.
	 *
	 * @param outcome The complete outcome record, including phase, codes, and attribution.
	 */
	fun onActionOutcome(outcome: ActionOutcome)
}
