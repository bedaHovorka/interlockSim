/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

/**
 * Snapshot of the railway network state used by the routing engine.
 *
 * In this slice the interface is intentionally empty: route search is purely
 * topological and does not consult reservations, occupancy, or signal aspects.
 * Future slices can add query methods (e.g. `isReserved`, `isOccupied`,
 * `semaphoreAspect`) without changing [RouteFinder] method signatures.
 */
interface NetworkState
