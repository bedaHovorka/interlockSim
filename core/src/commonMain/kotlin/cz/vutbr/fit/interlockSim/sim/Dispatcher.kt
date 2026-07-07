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
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock

/**
 * Control seam between the kDisco simulation kernel and dispatch policy.
 *
 * A [Dispatcher] encapsulates **what to do** (train admission and forward-path
 * reservation policy) independently of **how the simulation steps** (kDisco
 * process scheduling and `hold()`/`passivate()` mechanics).
 *
 * Implementations receive a [DispatcherTickContext] on each call to [approve] and
 * [advancePaths] that exposes the current observable network state and the actuator
 * callbacks needed to effect decisions.  The contract intentionally avoids exposing
 * mutable simulation internals so that alternative dispatcher implementations (LLM-backed,
 * search-based, …) can be plugged in behind this seam without touching `:core`
 * simulation code.
 *
 * ## Two-phase tick
 * The shell calls [approve] **before** the per-iteration polling `hold()` and
 * [advancePaths] **after** it.  Splitting the tick keeps train admission aligned
 * with the polling interval so that path-advancement checks observe block state
 * after newly admitted trains have had a chance to move — the original
 * `ShuntingLoop` ordering (Issue #540 review).  A single combined entry would
 * force both decisions to observe the same pre-hold state.
 *
 * ## Thread-safety
 * Both methods are always called from the single kDisco dispatcher thread — no
 * synchronisation is required inside an implementation.
 *
 * @see RuleBasedDispatcher
 * @see DispatcherTickContext
 * @since Issue #540 (SP0.1 — Goal 10)
 */
interface Dispatcher {
	/**
	 * Admit queued trains into the simulation.
	 *
	 * Called once per iteration, **before** the polling `hold()`.  The
	 * implementation must use only the callbacks exposed by [context] (typically
	 * [DispatcherTickContext.approveTrain]) to effect admission; it must not hold
	 * references to [context] beyond the duration of the call.
	 *
	 * @param context Read-only view of the current dispatch state plus actuator
	 *   callbacks that enact decisions.
	 */
	fun approve(context: DispatcherTickContext)

	/**
	 * Advance forward-path reservations on the monitored track blocks.
	 *
	 * Called once per iteration, **after** the polling `hold()`, so block-state
	 * checks observe the simulation state after newly admitted trains have moved.
	 * The implementation must use only the callbacks exposed by [context]
	 * (typically [DispatcherTickContext.reservePath]) and must not hold references
	 * to [context] beyond the duration of the call.
	 *
	 * @param context Read-only view of the current dispatch state plus actuator
	 *   callbacks that enact decisions.
	 */
	fun advancePaths(context: DispatcherTickContext)
}

/**
 * Snapshot of the dispatch-relevant simulation state plus actuator callbacks.
 *
 * Passed to [Dispatcher.approve] and [Dispatcher.advancePaths] on each iteration.
 * The counts and block lists reflect the state **at the start of the phase** (after
 * terminated trains have been removed by the shell process).  The callbacks mutate
 * simulation state; their effects are visible within the same phase.
 *
 * @since Issue #540 (SP0.1 — Goal 10)
 */
interface DispatcherTickContext {
	/** Number of trains currently approved (active in the simulation). */
	val approvedTrainCount: Int

	/**
	 * Current simulation time in seconds at the start of this tick.
	 *
	 * Provided so dispatchers can make time-aware decisions (e.g. "this train has
	 * been waiting since [simTime] − N seconds") without reaching into kDisco
	 * internals.  The value is taken once per tick from `Process.time()` and
	 * remains constant for the duration of a single [approve] or [advancePaths]
	 * call.
	 *
	 * @since Issue #543 (SP0.4 — Goal 10 observable simulation state)
	 */
	val simTime: Double

	/**
	 * Read-only view of the trains queued but not yet approved, in admission order.
	 *
	 * Exposing the full queue (rather than only a FIFO pop) lets a non-rule-based
	 * dispatcher select a train by priority, destination, or cargo.  Admission is
	 * still effected through [approveTrain]; this list must not be mutated directly.
	 */
	val unapprovedTrains: List<Train>

	/**
	 * Read-only view of the trains currently approved and running in the simulation.
	 *
	 * Exposes the live approved-train list so the dispatcher — and the
	 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort] built from it — can read
	 * position, velocity, and timetable data for every active train.
	 *
	 * The list must not be mutated directly; use [approveTrain] to admit new trains.
	 *
	 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
	 */
	val approvedTrains: List<Train>

	/**
	 * Perception port that reflects the network state observed at the start of this
	 * tick.
	 *
	 * Provides a read-only snapshot interface for signal aspects, block occupancy,
	 * train positions, and timetables.  Backed by the live simulation environment
	 * so queries always return the current state (not a frozen snapshot from tick
	 * start — callers should be aware that mutations from earlier in the same tick
	 * are visible).
	 *
	 * @since Issue #541 (SP0.2 — Goal 10 sensor ports)
	 */
	val perception: cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort

	/** Inner track blocks (both ends are [DynamicRailSemaphore]). */
	val innerBlocks: List<DynamicTrackBlock>

	/** Outer track blocks mapped to the [DynamicRailSemaphore] at their outer end. */
	val outerBlocks: Map<DynamicTrackBlock, DynamicRailSemaphore>

	/**
	 * Moves [train] from the unapproved queue into the approved set and
	 * activates it in the kDisco simulation.
	 *
	 * [train] must be present in [unapprovedTrains]; the shell removes that exact
	 * train from the queue.
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
	 * Returns `true` if the path through [block] is already set up toward [to]
	 * (i.e. [block] is RESERVED from the end opposite [to]).
	 *
	 * High-level replacement for the former `toDynamic` callback: the static-to-
	 * dynamic wrapper conversion and `getSecondEnd` lookup stay inside the shell,
	 * so dispatchers reason in terms of blocks and semaphores, not internal
	 * wrapper objects.
	 */
	fun isPathSetUp(
		block: DynamicTrackBlock,
		to: DynamicRailSemaphore
	): Boolean

	/**
	 * Returns `true` if the reserved path for [trainName] already extends
	 * beyond [sem], meaning a further reservation attempt would be a no-op.
	 */
	fun isPathExtendedBeyond(
		trainName: String,
		sem: DynamicRailSemaphore
	): Boolean
}
