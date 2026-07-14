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
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Registry of tool groups (perception and actuator tools) available to Koog agents (SP1.3 skeleton, Issue #548).
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
 * - SP1.3 (this class): Tool registry skeleton, empty groups (tools added in SP1.4)
 * - SP1.4 (#549): Populate perception/actuator tool implementations via [NetworkPerceptionPort] / [NetworkActuatorPort]
 * - SP1.6 (#551): Wire tools into Koog tool definitions with JSON schemas
 *
 * ## Design rationale
 *
 * Tool groups are assembled **per context** (not globally) because:
 * 1. Each [DefaultSimulationContext] has its own [NetworkPerceptionPort] and [NetworkActuatorPort]
 * 2. Tools must access network state via ports (scoped per context)
 * 3. Actuators queue commands to [ActuatorCommandQueue] (one per context)
 * 4. Creating tools at module load time is premature (context not yet created)
 *
 * Therefore, tools are a **factory function** in the Koin module that receives the context:
 * ```kotlin
 * scope<DefaultSimulationContext> {
 *     factory { toolRegistry: ToolGroupRegistry ->
 *         toolRegistry.assembleAllTools(get())  // get() = current context
 *     }
 * }
 * ```
 *
 * @since Issue #548 (SP1.3 — Goal 10)
 */
class ToolGroupRegistry {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Assemble all available tools (perception + actuators) for a given simulation context.
	 *
	 * This is called **once per context** during scope creation. The resulting list is
	 * passed to [AgentService.createDispatchAgent].
	 *
	 * ### Current state (SP1.3)
	 *
	 * Returns an empty list (skeleton). SP1.4 will populate this with real tool
	 * implementations bridging [NetworkPerceptionPort] and [NetworkActuatorPort].
	 *
	 * ### Future (SP1.4+)
	 *
	 * Will include:
	 * - Perception tools: `signal_aspect`, `block_occupancy`, `train_position`, …
	 * - Actuator tools: `request_route`, `release_route`, `hold_train`, …
	 *
	 * @return All tools available in this context (empty in SP1.3, populated in SP1.4)
	 *
	 * @since Issue #548 (SP1.3 — skeleton); full implementation in Issue #549 (SP1.4)
	 */
	fun assembleAllTools(): List<DomainTool> {
		logger.debug {
			"ToolGroupRegistry.assembleAllTools: returning empty list (SP1.3 skeleton, " +
				"tools added in SP1.4 #549)"
		}

		// SP1.3 skeleton: empty list
		// SP1.4: Assemble perception tools from network perception port
		//        Assemble actuator tools from network actuator port
		return emptyList()
	}

	/**
	 * Get perception-only tools (read-only network queries).
	 *
	 * Subgroup of [assembleAllTools]. Useful for testing or agents that only sense
	 * (no decision/actuation).
	 *
	 * @return Perception tools (empty in SP1.3)
	 * @since Issue #549 (SP1.4)
	 */
	fun assemblePerceptionTools(): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assemblePerceptionTools: returning empty (SP1.3)" }
		return emptyList()
	}

	/**
	 * Get actuator-only tools (commands that affect network state).
	 *
	 * Subgroup of [assembleAllTools]. Useful for testing or agents that only act
	 * (no perception/decision).
	 *
	 * @return Actuator tools (empty in SP1.3)
	 * @since Issue #549 (SP1.4)
	 */
	fun assembleActuatorTools(): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleActuatorTools: returning empty (SP1.3)" }
		return emptyList()
	}
}
