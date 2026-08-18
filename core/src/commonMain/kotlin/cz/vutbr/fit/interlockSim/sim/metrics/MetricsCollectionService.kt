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

	/**
	 * Remove a previously-registered listener.
	 *
	 * The caller must pass the exact same listener reference that was passed to
	 * [onSnapshot]; listeners are matched by identity (`===`) because lambda and
	 * function references have no meaningful structural equality. Removal during
	 * a listener callback is safe (the notification loop iterates a defensive copy).
	 *
	 * No-op if the listener was not registered.
	 *
	 * @param listener The callback to remove.
	 */
	fun removeSnapshotListener(listener: (MetricsSnapshot) -> Unit)

	/**
	 * Ids of trains that still hold at least one block reservation, logged at WARN when non-empty.
	 *
	 * Intended to be called **once, at run end, on a quiesced simulation**. A non-empty result is not
	 * by itself a fault: it is expected when the run was stopped early (manual stop, time limit, or a
	 * deadlock that left trains mid-journey), because those trains legitimately still hold their
	 * sections. It is a **leak** only on a natural completion — a run that reached its scheduled end
	 * yet still has outstanding reservations, meaning those trains' counts never drained, their
	 * journeys were never credited, and the blocks they hold were never returned (the Issue #936
	 * failure). The caller, which knows the run-end cause, is the one that can tell the two apart;
	 * this method only reports the observed state and logs it for visibility. Called mid-run it simply
	 * lists the trains under way, which is also not a fault.
	 *
	 * Issue #936 is the failure that motivated it: a granted full-span route froze a train's stored
	 * path, and every later hop-wise request was refused as non-contiguous, so the reservations were
	 * never released. Nothing in the metrics surfaced that — `completedTrains` merely failed to
	 * increment, which is indistinguishable from a slow run.
	 *
	 * Returns the set rather than only logging so the condition is assertable in tests without a
	 * log-capture harness.
	 *
	 * @return Train ids with outstanding reservations; empty when every train settled cleanly.
	 */
	fun reportUnreleasedReservations(): Set<String>
}
