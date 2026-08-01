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

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction

/**
 * Single-method sink that receives an emitted [DispatchAction] from an actuator tool.
 *
 * Tools call [emit] instead of posting directly to the [cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue]
 * (SP2c.6, Issue #829). The active implementation is swapped per-tick by [SinkHolder]:
 * - During a `KoogEmissionStrategy` tick: a list-collecting sink captures all emitted actions.
 * - Outside of a tick (old path / no-op): [NO_OP] discards all emissions.
 *
 * @since Issue #829 (SP2c.6 — Goal 10)
 */
fun interface EmittedActionSink {
	/**
	 * Receive one [action] emitted by an actuator tool.
	 *
	 * Implementations must be thread-safe: the Koog agent driver thread calls this
	 * inside `AIAgent.run`, which may overlap with other lifecycle activity.
	 */
	fun emit(action: DispatchAction)

	companion object {
		/** Sink implementation that silently discards all emissions (default/no-op). */
		val NO_OP: EmittedActionSink = EmittedActionSink { }
	}
}
