/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

/**
 * UI-agnostic controller interface for pause and single-step simulation control.
 *
 * This interface is the architectural bridge between the simulation engine (`:core`)
 * and the GUI-side runner (`:desktop-ui`). It allows the simulation loop to query
 * and respect pause/step state without depending on any UI framework.
 *
 * ## Threading Contract
 *
 * The kDisco simulation kernel runs on its own single thread. The methods below
 * are invoked from one of several outside callers, which may run concurrently
 * with each other and with the kernel:
 * - the **simulation coroutine** — the controlled event loop in
 *   `DefaultSimulationContext` (GUI/animated runs) — calls [awaitIfPaused],
 *   [throttle], [isPaused], [pollStepEvent], [pollStepTime];
 * - the **external driver coroutine** — the SP0.10 drive-loop driver (#732), which
 *   paces its own sense→decide→act cycle independently of the kernel — calls
 *   [awaitIfPaused] and [throttle];
 * - [requestPause] is called by external agents (e.g. the collision detection
 *   service) from arbitrary threads.
 *
 * Implementations must be thread-safe across all of these callers: the
 * GUI/control thread sets pause state and enqueues step requests, while the
 * simulation coroutine and the external driver coroutine poll. The interface
 * itself stays KMP-pure (see below); platform-specific synchronisation lives in
 * implementations such as `SimulationRunner` / `NoOpSimulationController`.
 *
 * ## KMP Purity
 *
 * This interface is defined in `commonMain` and must remain free of any platform-specific
 * imports (`java.*`, Swing, `Thread`, `Object.wait`). Implementations may use
 * platform-specific synchronization primitives.
 *
 * @see NoOpSimulationController
 * @since 2026-06 (Issue #498, Goal 8 Phase 1.1)
 */
interface SimulationController {
	/**
	 * Suspends the simulation coroutine while the controller is in paused state.
	 *
	 * Called from the simulation coroutine or the external driver coroutine at each
	 * iteration of the controlled loop. When paused, this function suspends until
	 * the controller resumes or a step is requested. Uses `suspend` (not blocking)
	 * so it cooperates with kDisco's coroutine-based dispatcher.
	 */
	suspend fun awaitIfPaused()

	/**
	 * Applies wall-clock throttling to pace the simulation relative to real time.
	 *
	 * Called from the simulation coroutine or the external driver coroutine after
	 * advancing the simulation clock. The implementation may delay execution to
	 * match the desired speed multiplier.
	 *
	 * @param simDeltaSeconds the simulation time that has just elapsed (in seconds)
	 */
	fun throttle(simDeltaSeconds: Double)

	/**
	 * Returns the current pause state.
	 *
	 * Called from the simulation coroutine or the external driver coroutine to
	 * determine whether to enter the pause-wait loop.
	 *
	 * @return `true` if the simulation is currently paused
	 */
	fun isPaused(): Boolean

	/**
	 * Polls for a single-event step request.
	 *
	 * Returns `true` exactly once after a step-event has been requested by the
	 * control thread, then resets to `false`. This allows the simulation loop to
	 * advance by exactly one event when paused.
	 *
	 * **Implementation invariant:** When this method returns `true`, the
	 * controller's [isPaused] MUST still return `true` on the immediately
	 * following call. This ensures the simulation loop re-enters pause state
	 * after processing exactly one step event. The loop does not enforce this
	 * invariant itself; all [SimulationController] implementations must maintain it.
	 *
	 * @return `true` if a step-event was requested (consumed on read), `false` otherwise
	 */
	fun pollStepEvent(): Boolean

	/**
	 * Polls for a time-based step request.
	 *
	 * Returns the requested time-delta exactly once after a time-step has been
	 * requested by the control thread, then resets to `null`. This allows the
	 * simulation loop to advance by a specific time amount when paused.
	 *
	 * @return the time-delta in seconds if a time-step was requested (consumed on read),
	 *         `null` otherwise
	 */
	fun pollStepTime(): Double?

	/**
	 * Request an immediate pause of the simulation.
	 *
	 * Called by external agents (e.g., the collision detection service) when a hazardous
	 * state is detected and the operator must review the situation before the simulation
	 * continues.
	 *
	 * **Thread safety:** implementations must be thread-safe; this method may be called
	 * from the simulation thread while the control thread reads pause state.
	 *
	 * @since Issue #611 (Goal 3 SP1)
	 */
	fun requestPause()

	/**
	 * Release a previously requested pause, allowing the simulation to continue.
	 *
	 * Counterpart to [requestPause]: once the caller that paused the simulation has
	 * finished its work (e.g. an LLM inference step bracketed by pause/resume), it
	 * calls this method to unpark the simulation thread.
	 *
	 * **Thread safety:** implementations must be thread-safe; this method may be called
	 * from any thread, including a thread different from the one that called [requestPause].
	 *
	 * **Contract C4:** the resume must be placed in a `finally` block so that a throwing
	 * emission does not leave the simulation parked indefinitely:
	 * ```kotlin
	 * controller.requestPause()
	 * try { emission.emit(prompt, obs) } finally { controller.requestResume() }
	 * ```
	 * Calling [requestResume] when not paused is a harmless no-op.
	 *
	 * @since Issue #872 (SP2c.26 follow-up I1)
	 */
	fun requestResume()
}

/**
 * No-op implementation of [SimulationController] that never pauses or throttles.
 *
 * Used as the default controller for headless runs (`fast-sim`), tests, and any
 * simulation execution that does not require interactive pause/step control.
 *
 * All methods return immediately with neutral values:
 * - [awaitIfPaused] returns immediately (never suspends)
 * - [throttle] does nothing
 * - [isPaused] always returns `false`
 * - [pollStepEvent] always returns `false`
 * - [pollStepTime] always returns `null`
 * - [requestPause] does nothing
 * - [requestResume] does nothing
 */
object NoOpSimulationController : SimulationController {
	override suspend fun awaitIfPaused() {
		// No-op: never paused
	}

	override fun throttle(simDeltaSeconds: Double) {
		// No-op: no wall-clock pacing
	}

	override fun isPaused(): Boolean = false

	override fun pollStepEvent(): Boolean = false

	override fun pollStepTime(): Double? = null

	override fun requestPause() {
		// No-op: headless / no-GUI runs do not support pause requests
	}

	override fun requestResume() {
		// No-op: headless / no-GUI runs are never paused
	}
}
