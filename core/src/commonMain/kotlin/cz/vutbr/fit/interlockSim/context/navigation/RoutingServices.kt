/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

/**
 * Groups the routing/navigation services available to the simulation environment.
 *
 * Previously these accessors were flattened directly onto
 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment], causing it to grow into a
 * kitchen-sink facade (Interface Segregation concern, Issue #153). Segregating the routing
 * accessors behind this focused sub-interface keeps [SimulationEnvironment] cohesive: callers
 * reach routing via the single [SimulationEnvironment.getRoutingServices] accessor.
 *
 * ## Services
 *
 * - [getTopologyNavigator] - Static topology navigation (no dynamic-state dependency)
 * - [getPathReservationService] - Atomic path reservation with train ownership tracking
 * - [getTrainNavigationService] - Train-specific navigation over RESERVED paths only
 *
 * @see cz.vutbr.fit.interlockSim.context.SimulationEnvironment.getRoutingServices
 * @since Issue #651 (routing accessor segregation)
 */
interface RoutingServices {
	/**
	 * Get topology navigator for pure topology navigation (no state dependencies).
	 *
	 * The TopologyNavigator provides static graph traversal without any dependency on
	 * dynamic state (block reservations, occupancy, etc.). Use this for finding the next
	 * track section based purely on network topology.
	 *
	 * ## Use Cases
	 *
	 * - InOutWorker finding initial track section from InOut
	 * - Network validation and connectivity analysis
	 * - Editor features requiring topology queries
	 *
	 * @return TopologyNavigator instance for this simulation context
	 * @see TopologyNavigator
	 * @since Issue #296 Phase 5 (InOutWorker dependency)
	 */
	fun getTopologyNavigator(): TopologyNavigator

	/**
	 * Get path reservation service for dispatcher/interlocking path reservation.
	 *
	 * The PathReservationService provides atomic path reservation with train ownership
	 * tracking. Used by dispatchers and interlocking logic to reserve paths before
	 * trains enter the network.
	 *
	 * ## Use Cases
	 *
	 * - InOutWorker reserves path for incoming train
	 * - Interlocking reserves continuation path when train approaches semaphore
	 * - Dispatcher pre-reserves paths for scheduled trains
	 *
	 * @return PathReservationService instance for this simulation context
	 * @see PathReservationService
	 * @since Issue #296 (ShuntingLoop refactoring)
	 */
	fun getPathReservationService(): PathReservationService

	/**
	 * Get train navigation service for train-specific path following.
	 *
	 * The TrainNavigationService provides train-specific path navigation that validates
	 * block ownership. It only returns paths through blocks RESERVED for the specific train.
	 *
	 * ## Use Cases
	 *
	 * - Train requests path to next semaphore (only through owned blocks)
	 * - Train waits when blocks are reserved for different train
	 * - Train resumes when path becomes available
	 *
	 * @return TrainNavigationService instance for this simulation context
	 * @see TrainNavigationService
	 * @since Issue #295 (Phase 3 of Issue #292)
	 */
	fun getTrainNavigationService(): TrainNavigationService
}
