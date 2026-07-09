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
 * Generic hook invoked synchronously on the kDisco simulation thread at the
 * start of each [ShuntingLoop] control step (i.e. each [ShuntingLoop.iteration]
 * call).
 *
 * Implementations **must not** block — the kDisco simulation thread is
 * cooperative and blocking it stalls the whole simulation.  All simulation-state
 * mutation performed inside [onControlStep] is safe because it originates on the
 * single sim thread.
 *
 * ## Usage (SP0.9)
 *
 * [DispatchDecisionApplier][cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
 * implements this interface and is registered on [ShuntingLoop] before the
 * simulation starts.  It drains the
 * [ActuatorCommandQueue][cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue]
 * and applies each [DispatchDecision] via the SP0.6 actuator ports, ensuring that
 * all simulation mutation — regardless of which thread posted the decisions —
 * executes on the sim thread.
 *
 * @since Issue #731 (SP0.9 — Goal 10)
 */
fun interface ControlStepListener {
	/**
	 * Called at the start of each simulation control step, on the kDisco
	 * simulation thread.  Implementations should return quickly; any
	 * simulation mutation performed here is serialised with all other sim-thread
	 * operations by the single-threaded kDisco scheduler.
	 */
	fun onControlStep()
}
