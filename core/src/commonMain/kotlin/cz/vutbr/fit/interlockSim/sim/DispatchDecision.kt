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

import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal

/**
 * Represents a single dispatcher decision returned by [Dispatcher.decide].
 *
 * This sealed class is the **result type** of the pure [Dispatcher] seam
 * (Issue #729 / SP0.7): a decision is a *request*, not yet an accomplished fact —
 * the caller ([ShuntingLoop]) applies it afterward, and application can still fail
 * (e.g. a path reservation conflict), so subtype documentation is worded as
 * "the dispatcher decided to …", not "… was …".
 *
 * Moved from `:dispatcher-agent` into `:core` by Issue #729, since [Dispatcher]
 * and [RuleBasedDispatcher] now live in `:core` and `:dispatcher-agent` depends on
 * `:core` (never the reverse).
 *
 * ## Intended subtypes
 *
 * - [ApproveTrain] — a queued train should be admitted to the simulation.
 * - [ReservePath] — a forward path should be reserved.
 * - [NoAction] — nothing to dispatch this tick.
 * - [SetSignalAspect] — set a named semaphore to a given signal aspect (SP1.7 tool-driven).
 * - [SetSwitchPosition] — set a named switch to MAIN or BRANCH (SP1.7 tool-driven).
 * - [ReleaseRoute] — release all reserved blocks for a train (SP1.7 tool-driven).
 * - [RequestRoute] — atomically reserve an end-to-end path for a train (SP1.7 tool-driven).
 *
 * `HoldTrain` and remaining train-lifecycle subtypes are deferred to Issue #556 (SP2b.1).
 * The four tool-driven subtypes ([SetSignalAspect], [SetSwitchPosition], [ReleaseRoute],
 * [RequestRoute]) are added here in SP1.7 (Issue #774) to satisfy the kDisco threading
 * contract: actuator tools running on the agent driver thread can now marshal their commands
 * through [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] and have the
 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] apply them on the kDisco
 * simulation thread.
 *
 * @since Issue #540 (SP0.1 — Goal 10), moved to `:core` and reworded in Issue #729
 *   (SP0.7 — Goal 10); SP1.7 tool-driven subtypes added in Issue #774
 */
sealed class DispatchDecision {
	/**
	 * The dispatcher decided to approve a queued train (move it from the
	 * unapproved queue to the active set and activate it in the kDisco
	 * simulation).
	 *
	 * @property trainId The name/identifier of the train to approve.
	 */
	data class ApproveTrain(
		val trainId: String
	) : DispatchDecision()

	/**
	 * The dispatcher decided to reserve a forward path of **one section** from
	 * [fromSemaphoreName] to [toSeparatorName] for [trainId].
	 *
	 * [fromSemaphoreName] is the semaphore the train is approaching (the value the
	 * shell carried as [cz.vutbr.fit.interlockSim.sim.BlockInputObservation.towardSemaphoreName]);
	 * [toSeparatorName] is the next separator one section ahead toward the train's
	 * destination — a semaphore, or the destination InOut for the final section. The
	 * shell pre-computes `to` as the first FREE next separator
	 * ([BlockInputObservation.toSeparatorName]) so the applier can call
	 * [cz.vutbr.fit.interlockSim.context.navigation.PathReservationService.reservePath]
	 * directly. `to` is never the far destination as a multi-section shortcut: one
	 * section is reserved per decision, matching the pre-#729
	 * `reservePathToAnyNextSemaphore` outcome.
	 *
	 * @property trainId The train to reserve a path for.
	 * @property fromSemaphoreName The semaphore to reserve the path from (the
	 *   semaphore the train is approaching).
	 * @property toSeparatorName The separator to reserve the path to (the next
	 *   separator toward the destination — a semaphore, or the destination InOut
	 *   for the final section).
	 */
	data class ReservePath(
		val trainId: String,
		val fromSemaphoreName: String,
		val toSeparatorName: String
	) : DispatchDecision()

	/**
	 * No dispatch action should be taken this tick.
	 */
	data object NoAction : DispatchDecision()

	// ── SP1.7 tool-driven actuator subtypes (Issue #774) ─────────────────
	//
	// These subtypes allow actuator tools running on the Koog agent driver thread to
	// marshal their commands through ActuatorCommandQueue and have DispatchDecisionApplier
	// apply them on the kDisco simulation thread — satisfying the threading contract
	// documented on DomainTool.execute().
	//
	// The results of these commands (success/failure) are observable in the next
	// SimulationSnapshot captured by the sim thread; the driver thread does not wait for
	// synchronous confirmation (fire-and-forget semantics, same as the existing
	// ReservePath/ApproveTrain subtypes).

	/**
	 * The agent decided to set a named semaphore to a specific signal aspect.
	 *
	 * Applied via [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.setSignalAspect]
	 * on the kDisco simulation thread by [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier].
	 * The effect is observable in the next [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot]
	 * captured after this decision is applied.
	 *
	 * @property semaphoreName Name of the semaphore (must exist in the network; case-sensitive).
	 * @property signal Target signal aspect.
	 *
	 * @since Issue #774 (SP1.7 — Goal 10 threading contract)
	 */
	data class SetSignalAspect(
		val semaphoreName: String,
		val signal: Signal
	) : DispatchDecision()

	/**
	 * The agent decided to set a named rail switch to a specific position.
	 *
	 * Applied via [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.setSwitchPosition]
	 * on the kDisco simulation thread by [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier].
	 * The switch is set only if it is not currently locked (no train occupying or reserved
	 * through it).
	 *
	 * @property switchName Name of the switch (must exist in the network; case-sensitive).
	 * @property position Target position: [RailSwitch.Conf.MAIN] or [RailSwitch.Conf.BRANCH].
	 *
	 * @since Issue #774 (SP1.7 — Goal 10 threading contract)
	 */
	data class SetSwitchPosition(
		val switchName: String,
		val position: RailSwitch.Conf
	) : DispatchDecision()

	/**
	 * The agent decided to release all track blocks reserved for the named train.
	 *
	 * Applied via [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.releaseRoute]
	 * on the kDisco simulation thread by [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier].
	 * The operation is idempotent — if the train holds no reservation, the call is a no-op.
	 *
	 * @property trainName Name of the train whose route should be released (non-blank).
	 *
	 * @since Issue #774 (SP1.7 — Goal 10 threading contract)
	 */
	data class ReleaseRoute(
		val trainName: String
	) : DispatchDecision()

	/**
	 * The agent decided to atomically reserve an end-to-end path for the named train.
	 *
	 * Applied via [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort.requestRoute]
	 * on the kDisco simulation thread by [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier].
	 * Unlike [ReservePath] (which reserves exactly one section using pre-computed
	 * semaphore/separator names from the block-input observation), [RequestRoute] asks
	 * the interlocking to find and reserve a complete end-to-end path using InOut or
	 * Semaphore names — this is the tool-callable variant intended for LLM agent use.
	 *
	 * @property trainName Identifier of the train that will use the reserved route.
	 * @property fromEndpointName Name of the entry InOut or Semaphore.
	 * @property toEndpointName Name of the exit InOut or Semaphore.
	 *
	 * @since Issue #774 (SP1.7 — Goal 10 threading contract)
	 */
	data class RequestRoute(
		val trainName: String,
		val fromEndpointName: String,
		val toEndpointName: String
	) : DispatchDecision()
}
