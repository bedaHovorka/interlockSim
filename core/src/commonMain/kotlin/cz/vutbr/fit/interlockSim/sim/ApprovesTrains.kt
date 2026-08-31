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
 * approved trains via [cz.vutbr.fit.interlockSim.context.DefaultSimulationContext.mainProcess]
 * without depending on kDisco's `Process` type to name [ShuntingLoop]/[MultiTrainLoop] directly.
 */
fun interface ApprovesTrains {
	/**
	 * Currently approved (active) trains, snapshotted at call time.
	 *
	 * **Threading contract (Issue #994):** implementations must make this call safe from any
	 * thread. The returned list is frozen — it never changes afterwards; the simulation thread
	 * publishes a fresh replacement whenever trains are admitted or retired, and the list a
	 * caller already holds keeps its old content. The snapshot may already be stale by the time
	 * the caller reads it. Callers must not try to mutate the returned list.
	 */
	fun getApprovedTrains(): List<Train>
}
