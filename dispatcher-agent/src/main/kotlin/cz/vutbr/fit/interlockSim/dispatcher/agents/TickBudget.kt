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

import cz.vutbr.fit.interlockSim.context.SimulationController
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deadline wrapper for [EmissionStrategy.emit] inside [DispatchTickLoop] (SP2c.5, Issue #828).
 *
 * The loop calls `budget.withBudget { emission.emit(prompt, obs) }`. If the emission strategy
 * exceeds the configured deadline, [withBudget] returns `null` and the loop substitutes a
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp] with author
 * [ActionAuthor.TIMEOUT_NOOP].
 *
 * ## Timing regimes (SP2c.10, Issue #833)
 *
 * Two configurable modes address the reproducibility (P8) vs honesty (F2 real-time ratio) split:
 *
 * - **F1 paused-clock** ([PausedClockTickBudget]) — the simulation clock is frozen for the
 *   entire emission window via [SimulationController.requestPause]/[SimulationController.requestResume].
 *   Sim time is provably unchanged across a slow emission; this is the **acceptance mode** for P8.
 *   The feasibility and binding constraints are established by Issue #849 (SP2c.26) and recorded
 *   in `docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md`.
 *
 * - **F2 wall-clock deadline** ([DeadlineTickBudget]) — enforces a hard wall-clock deadline via
 *   [kotlinx.coroutines.withTimeoutOrNull]. A miss yields `null` → [ActionAuthor.TIMEOUT_NOOP].
 *   The real-time ratio (sim seconds / emission wall-clock seconds) is measured and **reported**
 *   (logged) by [DispatchTickLoop] but **not used to gate** the LLM arm's run. This is the
 *   **reporting mode** for the LLM arm's honest real-time performance.
 *
 * ## Implementations
 *
 * | Implementation | Mode | Description |
 * |---|---|---|
 * | [PausedClockTickBudget] | F1 | Pauses sim clock; resumes in `finally`. P8 acceptance mode. |
 * | [DeadlineTickBudget] | F2 | Hard wall-clock deadline; null on timeout. LLM reporting mode. |
 * | [NoTimeoutBudget] | N/A | No deadline; passes block through. Rule-based strategies. |
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
interface TickBudget {
	/**
	 * Executes [block] within the configured time budget.
	 *
	 * @param block The suspend block to execute (typically [EmissionStrategy.emit]).
	 * @return The result of [block], or `null` if the deadline was exceeded.
	 */
	suspend fun <T> withBudget(block: suspend () -> T?): T?
}

/**
 * **F1 paused-clock** [TickBudget] (SP2c.10, Issue #833).
 *
 * Pauses the simulation clock for the entire emission window, preventing sim time from advancing
 * while the LLM (or any other strategy) produces a decision. The simulation is resumed in a
 * `finally` block so a throwing or timed-out emission cannot park the sim indefinitely.
 *
 * ## P8 reproducibility guarantee
 *
 * **Prompt determinism** is delivered: with the sim clock frozen, the [EmissionStrategy] receives
 * the same immutable `obs0` snapshot that was captured before the pause, and no further sim-thread
 * events can alter observable state. A recorded snapshot sequence therefore always produces a
 * byte-identical prompt sequence. This is the acceptance-mode half of P8 (Issue #532 §A4).
 *
 * **Decode determinism** is NOT delivered by this class alone: the sampling random state inside
 * the Ollama inference engine is not seeded by the pinned Koog version (see SP2c.27, Issue #850,
 * `docs/GOAL_10_SP2C27_OLLAMA_CAPABILITY_AUDIT.md`). P8's decode half is conditional on SP2c.27
 * delivering a seed path.
 *
 * ## Binding constraints (SP2c.26, `docs/GOAL_10_SP2C26_F1_PAUSED_CLOCK_RULING.md`)
 *
 * - **C1** — brackets **only** `EmissionStrategy.emit`. Nothing else runs inside the paused window.
 * - **C2** — intra-tick re-validation stays on the optimistic in-process projection (SP2c.5 option A).
 *   Requesting a fresh sim-thread capture while paused deadlocks (the pause parks the only thread
 *   that could publish it).
 * - **C3** — never requests or awaits a sim-thread capture while paused, directly or transitively.
 * - **C4** — releases the pause in a `finally` so a throwing emission cannot park the simulation
 *   indefinitely. Verified failure mode: without `finally` the sim silently stops advancing.
 *
 * @param controller The [SimulationController] used to pause and resume the simulation. Must be
 *   the same controller that the simulation thread polls — typically a
 *   [cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController] backed by the live
 *   `SimulationRunner`.
 *
 * @since Issue #833 (SP2c.10 — Goal 10 timing regimes F1+F2)
 */
class PausedClockTickBudget(
	private val controller: SimulationController
) : TickBudget {
	/**
	 * Pauses the simulation clock, runs [block], and resumes the clock in a `finally` (C4).
	 *
	 * Calling [SimulationController.requestResume] when the simulation is not paused is a
	 * harmless no-op, so a double-resume caused by an unusual call sequence will not corrupt state.
	 *
	 * @return The result of [block]. Unlike [DeadlineTickBudget], this implementation never returns
	 *   `null` from a deadline: there is no deadline. The only way to get `null` here is if [block]
	 *   itself returns `null`.
	 */
	override suspend fun <T> withBudget(block: suspend () -> T?): T? {
		controller.requestPause()
		return try {
			block()
		} finally {
			controller.requestResume()
		}
	}
}

/**
 * **F2 wall-clock deadline** [TickBudget] (SP2c.10, Issue #833).
 *
 * Enforces a hard wall-clock deadline via [kotlinx.coroutines.withTimeoutOrNull]. A miss yields
 * `null`, which [DispatchTickLoop] maps to a [cz.vutbr.fit.interlockSim.dispatcher.DispatchAction.NoOp]
 * attributed to [ActionAuthor.TIMEOUT_NOOP].
 *
 * ## Real-time ratio reporting (F2, A6 split)
 *
 * [DispatchTickLoop] measures the wall-clock time of each [EmissionStrategy.emit] call and logs
 * the real-time ratio (`simDelta / emissionWallClockSeconds`). For the LLM arm this ratio is
 * **reported only — it does not gate the run**. For the rule-based arm the ratio is intrinsically
 * ≥ 1× (synchronous, sub-millisecond), so the existing [cz.vutbr.fit.interlockSim.dispatcher.planner.assertPlannerPacingCompatible]
 * guard and [cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER]
 * remain the binding enforcement.
 *
 * @property timeoutMillis Maximum time allowed in milliseconds. Returns `null` on timeout.
 *   Default for LLM strategies is 3 000 ms (3 s hard deadline, SP2c.10 §design).
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
class DeadlineTickBudget(
	private val timeoutMillis: Long
) : TickBudget {
	override suspend fun <T> withBudget(block: suspend () -> T?): T? = withTimeoutOrNull(timeoutMillis) { block() }
}

/**
 * [TickBudget] implementation that applies no deadline — the block always runs to completion.
 *
 * Use this for synchronous strategies (e.g. [cz.vutbr.fit.interlockSim.dispatcher.RuleBasedEmissionStrategy])
 * where a deadline makes no sense: rule-based dispatch returns immediately and
 * `withTimeoutOrNull` overhead would add noise without value.
 *
 * @since Issue #828 (SP2c.5 — Goal 10 DispatchTickLoop)
 */
object NoTimeoutBudget : TickBudget {
	override suspend fun <T> withBudget(block: suspend () -> T?): T? = block()
}
