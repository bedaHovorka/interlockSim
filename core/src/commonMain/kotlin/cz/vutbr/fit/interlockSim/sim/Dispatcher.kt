/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Control seam between the kDisco simulation kernel and dispatch policy.
 *
 * A [Dispatcher] encapsulates **what to do** (train admission and forward-path
 * reservation policy) independently of **how the simulation steps** (kDisco
 * process scheduling and `hold()`/`passivate()` mechanics).
 *
 * Implementations receive a [DispatcherTickContext] on each call to [tick] that
 * exposes the current observable network state and the actuator callbacks needed
 * to effect decisions.  The contract intentionally avoids exposing mutable
 * simulation internals so that alternative dispatcher implementations (LLM-backed,
 * search-based, …) can be plugged in behind this seam without touching `:core`
 * simulation code.
 *
 * ## Thread-safety
 * [tick] is always called from the single kDisco dispatcher thread — no
 * synchronisation is required inside an implementation.
 *
 * @see RuleBasedDispatcher
 * @see DispatcherTickContext
 * @since Issue #540 (SP0.1 — Goal 10)
 */
interface Dispatcher {
	/**
	 * Called once per simulation iteration to perform dispatch decisions.
	 *
	 * The implementation must use only the callbacks exposed by [context] to
	 * effect any state changes; it must not hold references to [context] beyond
	 * the duration of the call.
	 *
	 * @param context Read-only view of the current dispatch state plus actuator
	 *   callbacks that enact decisions.
	 */
	fun tick(context: DispatcherTickContext)
}

/**
 * Snapshot of the dispatch-relevant simulation state plus actuator callbacks.
 *
 * Passed to [Dispatcher.tick] on each iteration.  The counts and block lists
 * reflect the state **at the start of the iteration** (after terminated trains
 * have been removed by the shell process).  The callbacks mutate simulation
 * state; their effects are visible within the same tick.
 *
 * @since Issue #540 (SP0.1 — Goal 10)
 */
interface DispatcherTickContext {
	/** Number of trains currently approved (active in the simulation). */
	val approvedTrainCount: Int

	/** Number of trains queued but not yet approved. */
	val unapprovedTrainCount: Int

	/** Inner track blocks (both ends are [DynamicRailSemaphore]). */
	val innerBlocks: List<DynamicTrackBlock>

	/** Outer track blocks mapped to the [DynamicRailSemaphore] at their outer end. */
	val outerBlocks: Map<DynamicTrackBlock, DynamicRailSemaphore>

	/**
	 * Removes and returns the next unapproved train, or `null` if the queue is
	 * empty.
	 */
	fun pollUnapproved(): Train?

	/**
	 * Moves [train] from the unapproved queue into the approved set and
	 * activates it in the kDisco simulation.
	 *
	 * The train must have been obtained from [pollUnapproved] in the same tick.
	 */
	fun approveTrain(train: Train)

	/**
	 * Attempts to reserve a forward path from [sem] for [trainName] via
	 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService].
	 *
	 * @return `true` if a path was successfully reserved, `false` otherwise.
	 */
	fun reservePath(
		sem: DynamicRailSemaphore,
		trainName: String
	): Boolean

	/**
	 * Converts the static [separator] to its dynamic simulation wrapper.
	 *
	 * Delegates to [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.toDynamic].
	 */
	fun toDynamic(separator: PathSeparator): DynamicPathSeparator

	/**
	 * Returns `true` if the reserved path for [trainName] already extends
	 * beyond [sem], meaning a further reservation attempt would be a no-op.
	 */
	fun isPathExtendedBeyond(
		trainName: String,
		sem: DynamicRailSemaphore
	): Boolean
}
