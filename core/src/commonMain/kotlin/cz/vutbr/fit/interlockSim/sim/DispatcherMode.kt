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

/**
 * Operating mode of the dispatcher, controlling whether decisions produced by the
 * planner are applied automatically, require human approval, or are ignored entirely.
 *
 * ## Modes (Issue #532 §B1)
 *
 * - [AUTO] — Autonomous operation. The planner's decisions are applied to the
 *   simulation without any human involvement.  This is the default mode.
 * - [SEMI_AUTO] — Propose-approve.  The planner produces decisions but each is
 *   presented to a human operator (reusing Goal 9's `ConflictResolutionPanel`
 *   propose-approve flow) and applied only after explicit approval.  In headless
 *   contexts without an approver wired in, actuating decisions are dropped and a
 *   warning is logged.
 * - [MANUAL] — Monitor only.  The planner may still run for observability (rationale
 *   recording, logging) but no actuating decisions are applied.  Only
 *   [DispatchDecision.NoAction] passes through as a legitimate no-op.
 *
 * ## Human override (Issue #532 §B1)
 *
 * The mode is exposed to the operator via [DispatcherModeState].  A human override
 * persists until it is explicitly cleared (it does not decay automatically after a
 * timeout or after the next decision).  This matches the safety expectation that
 * once a human takes control they retain it until they hand it back.
 *
 * ## Agent runtime speed constraint (Issue #187 cross-reference)
 *
 * When the effective mode is [AUTO] or [SEMI_AUTO] and the wired planner is
 * asynchronous (LLM-backed), the simulation runtime is capped at
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.PlannerCapabilities.AGENT_MAX_SPEED_MULTIPLIER]×
 * real-time so the planner has enough wall-clock time to produce decisions before
 * the simulation advances past the relevant state.  [MANUAL] does not need the cap:
 * no async planner is driving the simulation in that mode.
 *
 * @since Issue #559 (SP2b.4 — Goal 10)
 */
enum class DispatcherMode {
	/**
	 * Fully autonomous — planner decisions are applied without human involvement.
	 * This is the default operating mode.
	 */
	AUTO,

	/**
	 * Propose-approve — planner decisions are proposed to a human operator and only
	 * applied after explicit approval.  Actuating decisions are dropped in headless
	 * contexts without an approver wired in.
	 */
	SEMI_AUTO,

	/**
	 * Monitor only — the planner may still run for observability, but no actuating
	 * decisions are applied.  Only [DispatchDecision.NoAction] flows through as a
	 * legitimate no-op.
	 */
	MANUAL;

	companion object {
		/**
		 * The mode used when no human override is active.  All new [DispatcherModeState]
		 * instances start in this mode; [DispatcherModeState.clearOverride] returns to it.
		 */
		val DEFAULT: DispatcherMode = AUTO
	}
}
