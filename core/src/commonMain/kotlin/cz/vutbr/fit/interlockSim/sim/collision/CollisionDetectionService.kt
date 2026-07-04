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
 * For warnings with [CollisionWarning.Severity.CRITICAL], the service also calls
 * [PauseController.requestPause] when
 * [DefaultCollisionDetectionService.autoPauseOnCritical] is enabled (the default).
 *
 * ## Listener exception contract
 *
 * Listener invocation is isolated: if a listener throws, the exception is logged and
 * delivery continues to the remaining listeners; the [PauseController.requestPause] call
 * is always made for CRITICAL warnings. A misbehaving listener therefore cannot prevent
 * the pause or starve later listeners.
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

	/**
	 * Register a halt callback for a specific train.
	 *
	 * When [DefaultCollisionDetectionService.autoHaltTrainOnViolation] is `true` and a
	 * [CollisionWarning.BlockEntryViolation] is detected for [trainId], the [callback]
	 * is invoked immediately after warning delivery. Typically the callback calls
	 * [cz.vutbr.fit.interlockSim.sim.Train.requestHalt] on the entering train.
	 *
	 * Registering a new callback for the same [trainId] replaces the previous one.
	 * Pass a no-op lambda to effectively remove an existing callback.
	 *
	 * @param trainId The train identifier to associate with the callback.
	 * @param callback The action to take to halt the train (e.g., `train::requestHalt`).
	 * @since Issue #615 (Goal 3 SP5)
	 */
	fun registerHaltCallback(
		trainId: String,
		callback: () -> Unit
	)
}
