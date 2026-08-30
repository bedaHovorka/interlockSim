/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

/**
 * Actuator port for dispatch-loop commands issued by an agent to the simulation shell.
 *
 * This is one of two dispatch-loop port interfaces introduced by SP4.1 (Issue #563) to give
 * agents (algorithmic or LLM-driven) a stable, loop-independent surface for applying
 * dispatch commands to [cz.vutbr.fit.interlockSim.sim.ShuntingLoop].
 *
 * The port forms the **"act"** side of the agent's sense → decide → act loop for
 * ShuntingLoop-specific commands (train admission). Network-infrastructure commands
 * (route reservation, signal control, switch control) are covered by
 * [NetworkActuatorPort] (SP0.3, Issue #542).
 *
 * ## Threading contract
 *
 * Implementations **must** be safe to call from the agent driver thread (off the kDisco
 * simulation thread). The implementation posts a
 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision.ApproveTrain] to the
 * [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue] (fire-and-forget), which the
 * sim-thread [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] drains and
 * applies on the kDisco thread. Callers therefore never mutate simulation state directly.
 *
 * ## Usage
 *
 * ```kotlin
 * // In the agent driver loop (fire-and-forget):
 * actuatorPort.approveTrain("Train #1")   // admit a queued train to the active set
 * ```
 *
 * @see DispatchLoopSensorPort
 * @see NetworkActuatorPort
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
fun interface DispatchLoopActuatorPort {
	/**
	 * Approve a queued train by name, moving it from the pending admission queue into the
	 * active simulation.
	 *
	 * The call is **fire-and-forget**: the command is posted to the inter-thread command queue
	 * and returns immediately; the kDisco sim-thread applier applies it during the next control
	 * step. The agent observes the outcome (the train appearing in
	 * [cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort.allTrainPositions]) on the
	 * following tick's snapshot.
	 *
	 * Approving a train that is already active or that does not exist in the queue is a no-op
	 * (idempotent, silently ignored at the sim thread).
	 *
	 * @param trainId The name/identifier of the train to approve (non-blank; matched against the
	 *   unapproved queue by the simulation shell).
	 * @return `true` if the command was accepted by the queue; `false` if the queue is full
	 *   (backpressure — the command was dropped and the caller may retry on the next tick).
	 * @throws IllegalArgumentException if [trainId] is blank.
	 */
	fun approveTrain(trainId: String): Boolean
}
