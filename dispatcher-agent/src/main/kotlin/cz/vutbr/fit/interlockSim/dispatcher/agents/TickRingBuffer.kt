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
 * Bounded ring buffer that keeps the most recent N dispatcher tick records for prompt rendering
 * (SP2c.7, Issue #830 — bounded history, axis C5 / E3).
 *
 * ## Bounded by construction
 *
 * Pushing more than [capacity] records silently drops the oldest ones, so the rendered
 * `=== RECENT TICKS ===` prompt section has a **hard character ceiling** regardless of run
 * length. The ceiling is asserted by `TickRingBufferTest`.
 *
 * ## Not thread-safe
 *
 * [TickRingBuffer] is designed to be owned and mutated exclusively by the agent control-loop
 * thread (the same thread that builds [RenderContext]). External synchronisation is not
 * provided and is not needed in the normal control-loop design.
 *
 * ## Determinism (P8)
 *
 * [snapshot] returns a `List` in oldest-first order. Given the same sequence of [push] calls,
 * [snapshot] always returns the same list, so a [RenderContext] built from it renders to
 * byte-identical output.
 *
 * @param capacity Maximum number of [TickRecord]s to retain. The default value of 3 (N=3 from
 *   Issue #822 §5.1/C5) can be overridden for the SP2c.11 sweep.
 *
 * @see TickRecord
 * @see WorkingMemory
 * @since Issue #830 (SP2c.7 — Goal 10 ring buffer)
 */
class TickRingBuffer(val capacity: Int = 3) {

	init {
		require(capacity >= 1) { "capacity must be at least 1, got $capacity" }
	}

	private val buffer: ArrayDeque<TickRecord> = ArrayDeque(capacity)

	/**
	 * Appends [record] to the buffer. If the buffer is already at [capacity], the oldest
	 * record is silently dropped to make room.
	 *
	 * @param record The tick record to store.
	 */
	fun push(record: TickRecord) {
		if (buffer.size >= capacity) {
			buffer.removeFirst()
		}
		buffer.addLast(record)
	}

	/**
	 * Returns an immutable snapshot of the buffer in **oldest-first** order.
	 *
	 * The returned list is a defensive copy — subsequent [push] or [clear] calls do not affect
	 * previously returned snapshots.
	 *
	 * @return List of at most [capacity] records; may be empty before the first [push].
	 */
	fun snapshot(): List<TickRecord> = buffer.toList()

	/**
	 * Removes all records from the buffer. After this call [snapshot] returns an empty list.
	 * Typically used when the simulation is restarted or reset.
	 */
	fun clear() {
		buffer.clear()
	}
}
