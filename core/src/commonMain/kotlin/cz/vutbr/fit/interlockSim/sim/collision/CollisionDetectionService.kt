/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.collision

/**
 * Service that monitors simulation events and emits [CollisionWarning]s when hazardous
 * situations are detected.
 *
 * Listeners must be registered via [onCollisionWarning] **before**
 * [cz.vutbr.fit.interlockSim.context.SimulationContext.run] is called; registrations
 * after run has started are silently ignored (enforced by
 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.getCollisionServices]).
 *
 * Detected warnings are delivered synchronously on the simulation thread.
 * On each warning the service also calls [PauseController.requestPause] so the operator
 * can inspect the hazardous state before the simulation advances further.
 *
 * ## Listener exception contract
 *
 * Listener invocation is isolated: if a listener throws, the exception is logged and
 * delivery continues to the remaining listeners; the [PauseController.requestPause] call
 * is always made. A misbehaving listener therefore cannot prevent the pause or starve
 * later listeners.
 *
 * @see CollisionWarning
 * @see PauseController
 * @since Issue #611 (Goal 3 SP1)
 */
interface CollisionDetectionService {
	/**
	 * Register a listener to be notified when a [CollisionWarning] is emitted.
	 *
	 * The listener is called synchronously on the simulation thread. Listener
	 * invocation is isolated — see the class-level KDoc for the exception contract.
	 *
	 * @param listener The callback to invoke on each detected warning.
	 */
	fun onCollisionWarning(listener: (CollisionWarning) -> Unit)
}
