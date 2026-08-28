/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic, opaque identifier issued at queue-post time for every [DispatchDecision]
 * that flows through [ActuatorCommandQueue.postAll].
 *
 * The value is an ever-increasing [Long] per [CommandCorrelationMap] instance (one per
 * simulation context). Inline so it carries no heap allocation overhead on the
 * driver-thread hot path.
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
 */
@JvmInline
value class CommandId(
	val value: Long
)

/**
 * Identity-keyed side map that correlates a [DispatchDecision] instance (posted by the
 * driver thread) with the [CommandId] and tick index assigned at post time, so the sim
 * thread can attribute the applied outcome back to the originating agent cycle.
 *
 * ## Why identity, not `equals`
 *
 * [DispatchDecision] subtypes are Kotlin data classes. Two equal-valued instances posted
 * in different ticks would share a `HashMap` entry if keyed by `equals`, causing
 * tick N+1's route request to overwrite tick N's entry and make one of them
 * unattributable. [IdentityHashMap] keys on JVM object reference (`===`), so each
 * posted instance gets its own slot regardless of its field values.
 *
 * ## Threading contract
 *
 * - [register] is called on the **driver thread** from [ActuatorCommandQueue.postAll].
 * - [correlate] is called on the **kDisco simulation thread** from
 *   [DispatchDecisionApplier.onControlStep] before applying each drained decision.
 * - [newCycle] is called on the **driver thread** from
 *   [ActuatorCommandQueue.resetCycleActuatorCount].
 *
 * The underlying [IdentityHashMap] is wrapped in [Collections.synchronizedMap] so
 * driver-thread writes are visible to sim-thread reads even in the absence of a
 * happens-before between the two threads stronger than the `ConcurrentLinkedQueue`
 * visibility guarantee of [ActuatorCommandQueue.postAll]/[ActuatorCommandQueue.drain].
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
 */
class CommandCorrelationMap {
	private val idCounter: AtomicLong = AtomicLong(0L)
	private val cycleCounter: AtomicLong = AtomicLong(0L)
	private val _unattributedApplies: AtomicLong = AtomicLong(0L)

	// IdentityHashMap: reference-equality keying prevents equal-valued instances from
	// colliding. Synchronised for driver-thread register / sim-thread correlate access.
	private val map: MutableMap<DispatchDecision, CommandAndTick> =
		Collections.synchronizedMap(IdentityHashMap())

	/**
	 * A [CommandId] paired with the [tickIndex] at which the command was queued, plus the
	 * [author] and [reason] of the decision that produced it (SP2c.20, Issue #843).
	 *
	 * [author] and [reason] default to [ActionAuthor.LLM] / `""` for backwards-compatibility
	 * with callers that do not supply attribution (e.g. the `SinkHolder` queue-posting wrapper
	 * in [KoogAgentFactory][cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory]).
	 *
	 * The tick index is the value of the internal [cycleCounter] at registration time.
	 */
	data class CommandAndTick(
		val id: CommandId,
		val tickIndex: Long,
		/** Attribution author for this command. Defaults to [ActionAuthor.LLM]. */
		val author: ActionAuthor = ActionAuthor.LLM,
		/** Human-readable reason from the decision-maker. Defaults to empty string. */
		val reason: String = ""
	)

	/**
	 * Total number of apply-time lookups that found no registered entry.
	 *
	 * Non-zero in a correctly-wired system only if an outcome arrived for a decision
	 * that was posted without a [CommandCorrelationMap] attached to the queue, or after
	 * the entry was already evicted (should not happen in normal operation).
	 */
	val unattributedApplies: Long get() = _unattributedApplies.get()

	/**
	 * Advances the internal cycle counter so every decision posted in the next LLM
	 * cycle receives a tick index one higher than the previous cycle's decisions.
	 *
	 * Called from [ActuatorCommandQueue.resetCycleActuatorCount] before each LLM cycle.
	 */
	fun newCycle() {
		cycleCounter.incrementAndGet()
	}

	/**
	 * Issues a fresh [CommandId] for each decision in [decisions] and registers the
	 * pair `(identity → CommandAndTick(id, currentCycleIndex, author, reason))` in the map.
	 *
	 * Called on the **driver thread** from [ActuatorCommandQueue.postAll].
	 *
	 * [author] and [reason] default to [ActionAuthor.LLM] / `""` for callers that do not
	 * supply attribution (backwards-compatible with pre-SP2c.20 code).
	 */
	fun register(
		decisions: List<DispatchDecision>,
		author: ActionAuthor = ActionAuthor.LLM,
		reason: String = ""
	) {
		val tick = cycleCounter.get()
		for (decision in decisions) {
			map[decision] = CommandAndTick(CommandId(idCounter.incrementAndGet()), tick, author, reason)
		}
	}

	/**
	 * Looks up [decision] by reference identity, evicts the entry, and returns the
	 * [CommandAndTick] that was registered at post time.
	 *
	 * Returns `null` and increments [unattributedApplies] when no entry is found —
	 * a silently dropped outcome is how this class of bug hides, so the counter is
	 * surfaced rather than swallowed.
	 *
	 * Called on the **kDisco simulation thread** from [DispatchDecisionApplier].
	 */
	fun correlate(decision: DispatchDecision): CommandAndTick? {
		val result = map.remove(decision)
		if (result == null) {
			_unattributedApplies.incrementAndGet()
		}
		return result
	}

	/** Current number of registered-but-not-yet-correlated entries (diagnostics/tests only). */
	fun size(): Int = map.size
}
