/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */

/**
 * Dispatcher observation model (SP2c.1, #824).
 *
 * The canonical push-based snapshot published each tick by
 * [DispatcherObservationProjector] and consumed by the control loop and renderers.
 * All types in this package are immutable, deterministically ordered data classes
 * — see [DispatcherObservation] for the full contract.
 */
package cz.vutbr.fit.interlockSim.dispatcher.observation
