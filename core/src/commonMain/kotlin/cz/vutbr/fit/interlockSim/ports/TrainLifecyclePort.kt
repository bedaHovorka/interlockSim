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
 * Actuator port for dispatcher-issued train lifecycle commands.
 *
 * This port gives the dispatcher agent a stable, kernel-independent surface for
 * instructing individual trains to change their operating state — symmetric with
 * [NetworkActuatorPort] for railway infrastructure commands.  It forms the **"act"**
 * side of the dispatcher's sense → decide → act loop for the train-lifecycle class
 * of decisions (e.g. [cz.vutbr.fit.interlockSim.sim.DispatchDecision.HoldTrain]).
 *
 * ## Responsibility
 *
 * The dispatcher issues lifecycle commands that affect individual trains:
 * 1. **Hold** — instruct a train to stop for a given duration, creating spacing to
 *    resolve a conflict or let another train pass first.
 *
 * Additional commands (resume, proceed-to, etc.) may be added in future SP2b
 * iterations without changing existing implementations.
 *
 * ## String-based identifiers
 *
 * All methods identify trains by their string names rather than by live domain
 * objects — the same design choice as [NetworkActuatorPort]:
 * - Makes the interface naturally serialisable for LLM tool-call JSON payloads.
 * - Keeps `:dispatcher-agent` free of compile-time dependencies on internal wrapper
 *   types.
 * - Lets test doubles and LLM adapters be written without instantiating simulation
 *   objects.
 *
 * ## Thread-safety
 *
 * Implementations **must** be called on the kDisco simulation thread.  The
 * `:dispatcher-agent` `DispatchDecisionApplier` drains the command queue and calls
 * this port synchronously from [cz.vutbr.fit.interlockSim.sim.ControlStepListener.onControlStep],
 * which is always on the sim thread.
 *
 * @see NetworkActuatorPort
 * @see cz.vutbr.fit.interlockSim.sim.DispatchDecision.HoldTrain
 * @since Issue #556 (SP2b.1 — Goal 10)
 */
interface TrainLifecyclePort {
	/**
	 * Instruct [trainId] to hold in place for [holdDurationSeconds] simulation
	 * seconds.
	 *
	 * The implementation sets the train's target speed to 0 immediately; after
	 * [holdDurationSeconds] sim-seconds the train is released (target speed
	 * restored to the normal operating speed).  The physics model handles the
	 * actual deceleration and acceleration ramps.
	 *
	 * This call is **fire-and-forget** from the dispatcher's perspective: the
	 * dispatcher does not need to wait for the hold to expire.  The next
	 * [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot] will reflect the
	 * train's reduced speed.
	 *
	 * @param trainId The identifier of the train to hold (must be a currently
	 *   approved/active train; non-blank).
	 * @param holdDurationSeconds How long to hold the train, in simulation seconds.
	 *   Must be > 0.
	 * @return `true` if the hold command was accepted (the train exists and is
	 *   active); `false` if no active train with [trainId] was found (the command
	 *   is silently dropped — the dispatcher should log this).
	 * @throws IllegalArgumentException if [trainId] is blank or
	 *   [holdDurationSeconds] is ≤ 0.
	 */
	fun holdTrain(
		trainId: String,
		holdDurationSeconds: Double
	): Boolean
}
