/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents.tools

import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Registry of tool groups (perception and actuator tools) available to Koog agents (SP1.3 skeleton, SP1.4-updated, Issue #548/#549).
 *
 * ## Responsibility
 *
 * Organizes domain tools into logical groups:
 * - **Perception tools** - Read-only queries on network state (signal state, block occupancy, train positions)
 * - **Actuator tools** - Commands that mutate network state (set signal, reserve path, release route)
 *
 * The registry is a factory that assembles tool groups per context. Each tool is a [DomainTool]
 * that bridges the agent's decision to either a sensor (perception) or an action queue (actuator).
 *
 * ## SP1 phasing
 *
 * - SP1.3: Tool registry skeleton, empty groups (tools deferred to SP1.4)
 * - SP1.4 (#549): Accept port parameters, populate perception/actuator tool implementations via [NetworkPerceptionPort] / [NetworkActuatorPort]
 * - SP1.6 (#551): Wire tools into Koog tool definitions with JSON schemas
 *
 * ## Design rationale
 *
 * Tool groups are assembled **per context** (not globally) because:
 * 1. Each [DefaultSimulationContext] has its own [NetworkPerceptionPort] and [NetworkActuatorPort]
 * 2. Tools must access network state via ports (scoped per context)
 * 3. Actuators queue commands to ActuatorCommandQueue (one per context)
 * 4. Creating tools at module load time is premature (context not yet created)
 *
 * Therefore, tools are a **factory function** that receives ports from the Koin scope:
 * ```kotlin
 * scope<DefaultSimulationContext> {
 *     scoped<KoogAgentFactory> {
 *         KoogAgentFactory(
 *             …
 *             perceptionPort = get(),  // Scoped per context (SP1.4)
 *             actuatorPort = get()     // Scoped per context (SP1.4)
 *         )
 *     }
 * }
 * ```
 *
 * The factory then calls `toolRegistry.assembleAllTools(perceptionPort, actuatorPort)` to
 * create tools specific to that context.
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549) adds port parameters
 */
class ToolGroupRegistry {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Assemble all available tools (perception + actuators) for a given simulation context.
	 *
	 * This is called **once per context** during factory creation. The resulting list is
	 * passed to [AgentService.createDispatchAgent].
	 *
	 * ### Current state (SP1.4)
	 *
	 * Accepts perception and actuator ports from Koin scope (scoped per context).
	 * Returns an empty list (skeleton). Real tool implementations will be added in SP1.6+.
	 *
	 * ### Future (SP1.6+)
	 *
	 * Will include:
	 * - Perception tools: `signal_aspect`, `block_occupancy`, `train_position`, …
	 * - Actuator tools: `request_route`, `release_route`, `hold_train`, …
	 *
	 * Each tool is constructed using the provided [perceptionPort] or [actuatorPort]
	 * so it can query/actuate the current context's network state.
	 *
	 * @param perceptionPort Scoped perception port for this context (SP1.4)
	 * @param actuatorPort Scoped actuator port for this context (SP1.4)
	 * @return All tools available in this context (empty in SP1.4, populated in SP1.6+)
	 *
	 * @since Issue #548 (SP1.3 — skeleton); SP1.4 (#549) adds port parameters
	 */
	fun assembleAllTools(
		perceptionPort: NetworkPerceptionPort,
		actuatorPort: NetworkActuatorPort
	): List<DomainTool> {
		logger.debug {
			"ToolGroupRegistry.assembleAllTools: ports injected, returning empty list " +
				"(SP1.4 port infrastructure ready, tools added in SP1.6)"
		}

		// SP1.4: Ports are now available per context.
		// SP1.6+: Assemble perception tools from network perception port
		//         Assemble actuator tools from network actuator port
		return emptyList()
	}

	/**
	 * Get perception-only tools (read-only network queries).
	 *
	 * Subgroup of [assembleAllTools]. Useful for testing or agents that only sense
	 * (no decision/actuation).
	 *
	 * @param perceptionPort Scoped perception port for this context (SP1.4)
	 * @return Perception tools (empty in SP1.4)
	 * @since Issue #549 (SP1.4)
	 */
	fun assemblePerceptionTools(perceptionPort: NetworkPerceptionPort): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assemblePerceptionTools: port injected, returning empty (SP1.4)" }
		return emptyList()
	}

	/**
	 * Get actuator-only tools (commands that affect network state).
	 *
	 * Subgroup of [assembleAllTools]. Useful for testing or agents that only act
	 * (no perception/decision).
	 *
	 * @param actuatorPort Scoped actuator port for this context (SP1.4)
	 * @return Actuator tools (empty in SP1.4)
	 * @since Issue #549 (SP1.4)
	 */
	fun assembleActuatorTools(actuatorPort: NetworkActuatorPort): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleActuatorTools: port injected, returning empty (SP1.4)" }
		return emptyList()
	}
}
