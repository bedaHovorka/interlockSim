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

import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot

/**
 * Implemented by main-loop processes that publish per-tick dispatch-loop observation data
 * (queued trains, block-input state). Lets callers outside `:core` (e.g. `:dispatcher-agent`'s
 * Koin wiring) read the latest observation via
 * [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext.getMainProcess] without depending
 * on kDisco's `Process` type to name [ShuntingLoop] directly — mirrors [ApprovesTrains]'s
 * rationale for the same cross-module constraint.
 */
interface ProvidesDispatchLoopObservation {
	/** Most recently published dispatch-loop observation bundle, atomic for a single tick. */
	fun latestDispatchLoopSnapshot(): DispatchLoopSnapshot
}
