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

/**
 * Mutable holder for the active [EmittedActionSink], shared across all actuator tools in one
 * agent instance (SP2c.6, Issue #829).
 *
 * ## Why a holder?
 *
 * All four actuator tools are constructed once and reused across ticks. The sink they delegate to
 * must change per-tick (list-collecting during a tick, [EmittedActionSink.NO_OP] otherwise).
 * Rather than rebuilding the tools on every tick, the tools hold a reference to a [SinkHolder]
 * and call [current] at execution time. The tick controller swaps [current] atomically before
 * running the agent, then resets it after.
 *
 * ## Thread safety
 *
 * [current] is `@Volatile` — a single write from the tick controller is immediately visible to
 * the agent driver thread that reads it inside tool `execute()`.
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
}
