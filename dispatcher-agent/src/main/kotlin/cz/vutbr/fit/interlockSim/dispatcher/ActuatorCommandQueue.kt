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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe handoff queue for dispatcher decisions.
 *
 * The future driver thread (SP0.10) posts [DispatchDecision]s via [postAll]; the
 * sim-thread applier (SP0.9) drains them via [drain] and applies each decision
 * through the actuator ports. This component never touches simulation state — it
 * only moves immutable [DispatchDecision] values across the driver/sim thread
 * boundary.
 *
 * ## Backpressure
 *
 * The queue is unbounded by design. kDisco is single-threaded and the applier
 * drains the queue synchronously on the sim thread, so the queue is expected to
 * be nearly empty in normal operation. A configurable [capacity] is provided for
 * tests and future backpressure policies; [postAll] rejects new decisions once
 * the capacity is exceeded.
 *
 * @param capacity Maximum number of decisions the queue may hold. Defaults to
 *   [DEFAULT_CAPACITY]. A negative capacity disables the limit.
 *
 * @since Issue #730 (SP0.8 — Goal 10)
 */
class ActuatorCommandQueue(
	private val capacity: Int = DEFAULT_CAPACITY
) {
	companion object {
		/** Default maximum queue capacity. */
		const val DEFAULT_CAPACITY: Int = 10_000
	}

	init {
		require(capacity != 0) { "Capacity must be positive (limited) or negative (unlimited); got: 0" }
	}

	private val queue: ConcurrentLinkedQueue<DispatchDecision> = ConcurrentLinkedQueue()
	private val size: AtomicInteger = AtomicInteger(0)
	private val lock = Any()

	/**
	 * Atomically posts all [decisions] to the queue.
	 *
	 * The call does not block waiting for queue space. Under bounded [capacity] the
	 * only synchronization is a short critical section for capacity accounting, so a
	 * producer may briefly contend with a concurrent [drain] but never waits for the
	 * consumer to make room. If adding all decisions would exceed [capacity], none
	 * are added and the method returns `false`. On success the method returns `true`.
	 *
	 * ## Size/queue ordering note
	 *
	 * The capacity check increments [size] before the decisions are inserted into
	 * [queue]. This means [approximateSize] may briefly report a value larger than
	 * the actual queue contents while a producer is mid-post (including during a
	 * rollback after a failed capacity check). This is intentional: it prevents
	 * overshoot under concurrency and is acceptable for approximate monitoring.
	 * Callers that need an exact count should drain the queue.
	 *
	 * @param decisions Decisions to post; may be empty.
	 * @return `true` if all decisions were accepted; `false` if backpressure rejected them.
	 */
	fun postAll(decisions: List<DispatchDecision>): Boolean {
		if (decisions.isEmpty()) {
			return true
		}

		if (capacity > 0) {
			synchronized(lock) {
				if (size.get() + decisions.size > capacity) {
					return false
				}
				size.addAndGet(decisions.size)
				queue.addAll(decisions)
			}
		} else {
			size.addAndGet(decisions.size)
			queue.addAll(decisions)
		}
		return true
	}

	/**
	 * Drains all currently queued decisions into a list.
	 *
	 * The returned list preserves the FIFO order of posting. The applier calls this
	 * from the single kDisco simulation thread and then applies each decision.
	 *
	 * This method is single-consumer: only the sim thread may drain. The
	 * synchronization around [size] is defensive and keeps the counter consistent
	 * if a future change allows multiple consumers.
	 *
	 * @return All queued decisions in FIFO order; empty if the queue was empty.
	 */
	fun drain(): List<DispatchDecision> {
		val result = mutableListOf<DispatchDecision>()
		var decision: DispatchDecision? = queue.poll()
		while (decision != null) {
			result.add(decision)
			decision = queue.poll()
		}
		if (result.isNotEmpty()) {
			synchronized(lock) {
				size.addAndGet(-result.size)
			}
		}
		return result
	}

	/**
	 * Returns the current number of queued decisions.
	 *
	 * This is a best-effort estimate for monitoring and tests. Because [postAll]
	 * increments [size] before inserting into [queue], the counter may briefly
	 * exceed the actual queue contents while a producer is mid-post; conversely,
	 * [drain] decrements [size] after polling, so the counter may briefly lag.
	 * These windows are acceptable for approximate monitoring but not for
	 * precise queue-state assertions under concurrency.
	 */
	fun approximateSize(): Int = size.get()
}
