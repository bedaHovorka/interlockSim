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

/**
 * The admission cap the running loop enforces, declared into the context's Koin scope by the
 * wiring layer that owns the loop (Issue #1155).
 *
 * `:dispatcher-agent` cannot read `ShuntingLoop.stationCapacity` itself: the loop is a kDisco
 * `Process`, and this module's main source set does not depend on kDisco. The wiring layer
 * (`ExampleRegistry.wireDispatcherAgent` in `:desktop-ui`) declares the loop's resolved value —
 * the topology capacity, or the scenario override when one is set — and [KoogAgentFactory] reads
 * it when it builds the agent, so the prompt states exactly the cap the simulation enforces.
 *
 * @property value Maximum number of simultaneously admitted trains; positive.
 */
class StationCapacity(
	val value: Int
) {
	init {
		require(value > 0) { "Station capacity must be positive, got: $value" }
	}
}
