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
 * Implemented by main-loop processes that track which trains have been approved onto
 * the network. Lets callers outside `:core` (e.g. `:dispatcher-agent`'s Koin wiring) read
 * approved trains via [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext.getMainProcess]
 * without depending on kDisco's `Process` type to name [ShuntingLoop]/[MultiTrainLoop] directly.
 */
fun interface ApprovesTrains {
	/** Currently approved (active) trains, snapshotted at call time. */
	fun getApprovedTrains(): List<Train>
}
