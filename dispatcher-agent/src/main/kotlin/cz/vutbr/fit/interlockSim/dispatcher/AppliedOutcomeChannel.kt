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
 * method — a log call on the sim thread injects latency into the physics loop and is
 * rejected by the SP2c.17 review checklist.
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
 */
fun interface AppliedOutcomeSink {
	/** Called on the kDisco simulation thread. Allocation-light; no logging. */
	fun publish(outcome: AppliedOutcome)
}

/**
 * Source for draining [AppliedOutcome]s accumulated since a given tick index.
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
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
 * Both [publish] and [drainSince] are called **on the kDisco simulation thread only**:
 * - [publish] is called from [DispatchDecisionApplier.onControlStep] (sim thread).
 * - [drainSince] is called from [DispatcherObservationProjector.captureOnSimThread] (sim thread).
 *
 * Because both operations occur on the same thread, the underlying [ArrayDeque] requires
 * no synchronisation.  The no-synchronisation contract is documented here rather than
 * hidden so that a future refactor cannot silently move one of the call sites off-thread
 * without noticing.
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
 *
 * @since Issue #840 (SP2c.17 — correlated async outcome channel)
 */
class AppliedOutcomeChannel(
	private val ringCapacity: Int = DEFAULT_RING_CAPACITY
) : AppliedOutcomeSink,
	AppliedOutcomeFeed {
	companion object {
		/** Default ring capacity: 512 outcomes, far more than any realistic burst. */
		const val DEFAULT_RING_CAPACITY: Int = 512
	}

	// Sim-thread-only — see class KDoc "Threading contract". No synchronisation needed.
	private val ring = ArrayDeque<AppliedOutcome>(ringCapacity)

	/** Called on the sim thread. Allocation-light; no logging (per SP2c.17 review checklist). */
	override fun publish(outcome: AppliedOutcome) {
		if (ring.size >= ringCapacity) {
			ring.removeFirst() // Evict oldest on overflow
		}
		ring.addLast(outcome)
	}

	/** Called on the sim thread by [DispatcherObservationProjector.captureOnSimThread]. */
	override fun drainSince(fromTickIndex: Long): List<AppliedOutcome> {
		if (ring.isEmpty()) return emptyList()
		val result = mutableListOf<AppliedOutcome>()
		val it = ring.iterator()
		while (it.hasNext()) {
			val outcome = it.next()
			if (outcome.tickIndex >= fromTickIndex) {
				result.add(outcome)
				it.remove()
			}
		}
		return result
	}
}
