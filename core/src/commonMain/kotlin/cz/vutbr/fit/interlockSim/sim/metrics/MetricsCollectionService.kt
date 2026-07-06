/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

/**
 * Service for collecting and querying simulation performance metrics.
 *
 * Implementations subscribe to simulation events and maintain running KPI
 * counters that are summarised into a [MetricsSnapshot] on demand.
 *
 * ## Usage
 *
 * ```kotlin
 * val metrics = env.getMetricsServices().getMetricsCollectionService()
 *
 * // Query the current snapshot at any time during the simulation
 * val snapshot = metrics.getSnapshot()
 * println("Conflicts: ${snapshot.conflictCount}, Throughput: ${snapshot.throughput}")
 *
 * // Or register a listener to receive a snapshot after every change
 * metrics.onSnapshot { snap -> log.info { "Metrics update: $snap" } }
 * ```
 *
 * ## Thread Safety
 *
 * Listeners are invoked synchronously on the simulation thread.
 * [getSnapshot] is safe to call from the simulation thread; external callers
 * must synchronise externally if they read the snapshot from a different thread.
 *
 * @since Issue #672 (Goal 6 SP1)
 */
interface MetricsCollectionService {
	/**
	 * Returns a [MetricsSnapshot] reflecting the current accumulated KPIs.
	 *
	 * May be called at any point during or after a simulation run.
	 * The returned snapshot is an immutable value object; subsequent calls
	 * return independent instances that reflect the state at the time of the call.
	 *
	 * @return Current performance KPI snapshot.
	 */
	fun getSnapshot(): MetricsSnapshot

	/**
	 * Register a listener that is invoked whenever the internal KPI state changes.
	 *
	 * The listener receives a fresh [MetricsSnapshot] after each state-modifying
	 * simulation event (block reservation, occupancy change, conflict, etc.).
	 *
	 * Listeners are called synchronously on the simulation thread in registration
	 * order. Exceptions thrown by a listener are caught and logged; they do not
	 * prevent remaining listeners from being called.
	 *
	 * @param listener Callback invoked with the updated snapshot.
	 */
	fun onSnapshot(listener: (MetricsSnapshot) -> Unit)
}
