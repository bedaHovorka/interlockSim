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
 * Lifecycle recorder for a single dispatcher run.
 *
 * One [DispatcherRunRecorder] instance maps to exactly one [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext]
 * lifetime — the Koin `scoped` binding ensures a fresh instance per context and makes `reset()`
 * unnecessary. The run conceptually starts at the first [onTick] call and ends at [finish].
 *
 * ## Thread safety
 *
 * - [onTick] is called on the dispatcher-agent-driver thread.
 * - [onActionOutcome] is called on the kDisco simulation thread.
 * - [snapshot] and [finish] may be called from any thread (e.g. the EDT or a sweep driver).
 *
 * All implementations must be thread-safe.
 *
 * ## Idempotency
 *
 * [finish] is idempotent: the first call freezes the terminal snapshot; later calls return the
 * already-frozen snapshot without side effects.  This makes double-calling from the GUI's
 * `STOPPED` handler and from a headless sweep driver harmless.
 *
 * @see DefaultDispatcherRunRecorder
 * @see RunSnapshotStore
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
interface DispatcherRunRecorder {
	/**
	 * Opaque unique identifier for this run (UUID or timestamp-based string).
	 * Set at construction; never changes.
	 */
	val runId: String

	/**
	 * Records a completed planner tick.
	 *
	 * Called on the dispatcher-agent-driver thread after each tick completes.
	 * A tick still in flight at the moment [finish] is called is **not** counted — this
	 * is the documented in-flight caveat: the recorder reports state at the moment of the
	 * call, not a strict post-mortem of every tick ever started.
	 *
	 * @param record The completed tick's outcome record (from [PlannerTickListener]).
	 */
	fun onTick(record: TickRecord)

	/**
	 * Records the outcome of one action through the full validate-and-apply pipeline.
	 *
	 * Called on the kDisco simulation thread (same contract as [ActionOutcomeSink]).
	 *
	 * @param outcome Complete outcome record for one dispatching action.
	 */
	fun onActionOutcome(outcome: ActionOutcome)

	/**
	 * Returns an immutable snapshot of the current accumulated metrics.
	 *
	 * May be called at any time during the run; the returned snapshot is independent of
	 * subsequent updates.  After [finish] this always returns the terminal frozen snapshot.
	 */
	fun snapshot(): DispatcherRunSnapshot

	/**
	 * Freezes the run and returns the terminal [DispatcherRunSnapshot].
	 *
	 * Idempotent: the first call freezes the snapshot and sets [endCause]; subsequent calls
	 * return the already-frozen snapshot without any side effect.
	 *
	 * @param cause Why the run ended.
	 * @return The terminal frozen snapshot.
	 */
	fun finish(cause: RunEndCause): DispatcherRunSnapshot

	/**
	 * Logs a final structured summary of the run metrics at INFO level.
	 *
	 * Safe to call more than once (read-only, no counter mutations).
	 * Called by `Frame`'s `STOPPED` handler; must preserve the existing signature so the
	 * Frame call site does not change.
	 */
	fun logFinalSummary()
}
