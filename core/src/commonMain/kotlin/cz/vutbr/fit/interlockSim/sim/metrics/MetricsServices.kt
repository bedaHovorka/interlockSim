/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

/**
 * Grouped metrics-collection services facade, accessed via
 * [cz.vutbr.fit.interlockSim.context.SimulationEnvironment.getMetricsServices].
 *
 * Mirrors the [cz.vutbr.fit.interlockSim.context.navigation.RoutingServices] and
 * [cz.vutbr.fit.interlockSim.sim.collision.CollisionServices] segregation pattern:
 * metrics-related accessors are grouped behind this one sub-interface rather than
 * being flattened directly onto [cz.vutbr.fit.interlockSim.context.SimulationEnvironment].
 * Future Goal 6 sub-phases (SP2 historical recording, SP3 dashboard, SP4 configurable
 * selection) can grow this facade without bloating the top-level environment interface.
 *
 * ## Services
 *
 * - [getMetricsCollectionService] — Subscribe to metrics events, query current [MetricsSnapshot].
 *
 * @see cz.vutbr.fit.interlockSim.context.SimulationEnvironment.getMetricsServices
 * @since Issue #672 (Goal 6 SP1)
 */
fun interface MetricsServices {
	/**
	 * Get the [MetricsCollectionService] scoped to this simulation context.
	 *
	 * @return [MetricsCollectionService] instance for this simulation context.
	 * @see MetricsCollectionService
	 */
	fun getMetricsCollectionService(): MetricsCollectionService
}
