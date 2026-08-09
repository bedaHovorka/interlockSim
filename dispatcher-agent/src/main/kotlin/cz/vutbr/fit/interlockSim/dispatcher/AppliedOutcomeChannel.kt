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

import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome

/**
 * Sink for applied outcomes published from the kDisco simulation thread.
 *
 * The implementation **must** be allocation-light and must **not** call any `logger.*`
 * method — a log call on the sim thread injects latency into the physics loop.
 */
fun interface AppliedOutcomeSink {
	/** Called on the kDisco simulation thread. Allocation-light; no logging. */
	fun publish(outcome: AppliedOutcome)
}

/**
 * Source for draining [AppliedOutcome]s accumulated since a given tick index.
 */
fun interface AppliedOutcomeFeed {
	/**
	 * Removes and returns every [AppliedOutcome] whose [AppliedOutcome.tickIndex] is
	 * greater than or equal to [fromTickIndex].
	 *
	 * The returned list is ordered by insertion (FIFO). Drained entries are not returned
	 * on subsequent calls.
	 */
	fun drainSince(fromTickIndex: Long): List<AppliedOutcome>
}

/**
 * Bounded ring buffer that implements both [AppliedOutcomeSink] and [AppliedOutcomeFeed].
 *
 * ## Threading contract
 *
 * [publish] is called **on the kDisco simulation thread only**, from
 * [DispatchDecisionApplier.onControlStep]. [drainSince] has **two** callers on
 * **two different threads**:
 * - [DispatcherObservationProjector.captureOnSimThread] — kDisco simulation thread (the
 *   test-only `DispatchTickLoop` path).
 * - [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl.buildUserPrompt] — the
 *   `dispatcher-agent-driver` thread ([AgentLoopDriver] runs on its own thread/coroutine, never
 *   the kDisco thread), the **live** path this outcome channel is built for.
 *
 * The underlying [ArrayDeque] is genuinely cross-thread, so every access is guarded by
 * `synchronized(ring)`. Kept allocation-light and logging-free inside the guarded sections — see
 * [publish]'s contract — so the lock is held only for simple deque operations, never for I/O.
 *
 * ## Overflow
 *
 * When [ringCapacity] is reached, the oldest entry is evicted before the new one is
 * inserted. Eviction is visible in the outcome ring — the consumer can detect gaps by
 * inspecting [CommandId] discontinuities if needed, though in practice the ring is sized
 * to be far larger than any burst of pending outcomes.
 *
 * @param ringCapacity Maximum number of outcomes held before the oldest is evicted.
 *   Defaults to [DEFAULT_RING_CAPACITY].
 */
class AppliedOutcomeChannel(
	private val ringCapacity: Int = DEFAULT_RING_CAPACITY
) : AppliedOutcomeSink,
	AppliedOutcomeFeed {
	companion object {
		/** Default ring capacity: 512 outcomes, far more than any realistic burst. */
		const val DEFAULT_RING_CAPACITY: Int = 512
	}

	// Cross-thread — see class KDoc "Threading contract". Every access below is
	// guarded by `synchronized(ring)`.
	private val ring = ArrayDeque<AppliedOutcome>(ringCapacity)

	/** Called on the sim thread. Allocation-light; no logging. */
	override fun publish(outcome: AppliedOutcome) {
		synchronized(ring) {
			if (ring.size >= ringCapacity) {
				ring.removeFirst() // Evict oldest on overflow
			}
			ring.addLast(outcome)
		}
	}

	/**
	 * Called by [DispatcherObservationProjector.captureOnSimThread] (sim thread) or
	 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl.buildUserPrompt]
	 * (`dispatcher-agent-driver` thread) — see class KDoc "Threading contract".
	 */
	override fun drainSince(fromTickIndex: Long): List<AppliedOutcome> =
		synchronized(ring) {
			// Empty-ring fast path: the common case on a freshly-drained or idle channel. Returns
			// the shared emptyList() singleton without allocating a MutableList — this method is on
			// a hot, per-tick, cross-thread path and the class contract (see KDoc) is allocation-light.
			if (ring.isEmpty()) {
				emptyList()
			} else {
				val result = mutableListOf<AppliedOutcome>()
				val it = ring.iterator()
				while (it.hasNext()) {
					val outcome = it.next()
					if (outcome.tickIndex >= fromTickIndex) {
						result.add(outcome)
						it.remove()
					}
				}
				result
			}
		}
}
