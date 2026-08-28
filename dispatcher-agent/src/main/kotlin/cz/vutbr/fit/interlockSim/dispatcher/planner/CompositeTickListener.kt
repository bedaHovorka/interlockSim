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

import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PlannerTickListener] that forwards each [TickRecord] to several delegates in registration
 * order.
 *
 * ## Why this exists (Issue #713 Task 9, traffic-simulation-expert review of Task 10)
 *
 * `KoogAgentPlanAdapter.tickListener` used to be a single nullable slot. [AgentLoopDriver]
 * claims it unconditionally in its `init` block, so a caller that registered its own listener
 * beforehand had it silently discarded — there was no seam at all onto which a run recorder could
 * be wired, which is exactly the mechanism Issue #843 fixed once already (every per-run JSON
 * reported `totalTicks = 0`). Moving [MeasuringPlanAdapter] off the deprecated
 * [PlannerCycleListener] slot onto that single [PlannerTickListener] slot would recreate the
 * regression, because `AgentLoopDriver` claims it too. This class is the same "one slot, two
 * owners" fix [CompositeActionOutcomeSink] applied to [ActionOutcomeSink] in Issue #847 round 4,
 * mirrored here for [PlannerTickListener].
 *
 * ## Thread-safety
 *
 * [PlannerTickListener.onTick]'s KDoc requires implementations to be thread-safe — it may be
 * called from multiple coroutines simultaneously. Delegate registration
 * ([addListener]) and delivery ([onTick]) are backed by a [CopyOnWriteArrayList], the same
 * structure [CompositeActionOutcomeSink] uses for its own thread-safety, so a listener can be
 * added concurrently with in-flight delivery without external synchronization.
 *
 * Exceptions are **not** swallowed, mirroring [CompositeActionOutcomeSink]: a delegate that
 * throws aborts the fan-out and propagates to the caller, which is the caller entitled to decide
 * what a broken tick observer means.
 *
 * @since Issue #713 (Task 9 — compilation warnings elimination round; prerequisite for Task 10)
 */
class CompositeTickListener(
	private val delegates: MutableList<PlannerTickListener> = CopyOnWriteArrayList()
) : PlannerTickListener {
	/** Registers [listener] to receive every future [TickRecord] alongside all others already added. */
	fun addListener(listener: PlannerTickListener) {
		delegates.add(listener)
	}

	override fun onTick(record: TickRecord) {
		delegates.forEach { it.onTick(record) }
	}

	companion object {
		/**
		 * Builds a composite from possibly-absent delegates, dropping the nulls.
		 *
		 * Mirrors [CompositeActionOutcomeSink.of] — most dispatcher components are resolved with
		 * `scope.getOrNull(...)` because a context may legitimately lack them; this spares every
		 * call site a null branch.
		 */
		fun of(vararg delegates: PlannerTickListener?): CompositeTickListener =
			CompositeTickListener(CopyOnWriteArrayList(delegates.filterNotNull()))
	}
}
