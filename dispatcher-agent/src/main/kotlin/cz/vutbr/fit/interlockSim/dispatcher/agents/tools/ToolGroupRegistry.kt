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
	 * ### Current state (SP1.6)
	 *
	 * Accepts perception and actuator ports from Koin scope (scoped per context).
	 * Returns a complete list of perception and actuator tools.
	 *
	 * ### Tools included (SP1.6)
	 *
	 * **Perception tools** (read-only network queries):
	 * - `signal_aspect` — query signal aspect of one semaphore
	 * - `all_signal_aspects` — query all semaphore signals
	 * - `block_occupancy` — query occupancy of one track block
	 * - `all_block_occupancies` — query all block occupancies
	 * - `train_position` — query kinematics of one train
	 * - `all_train_positions` — query all train positions
	 * - `train_timetable` — query timetable of one train
	 * - `all_train_timetables` — query all train timetables
	 *
	 * **Actuator tools** (network commands):
	 * - `request_route` — request route reservation for a train
	 * - `release_route` — release reserved blocks for a train
	 * - `set_switch_position` — command a switch to MAIN or BRANCH
	 * - `set_signal_aspect` — command a semaphore to a signal aspect
	 *
	 * Each tool is constructed using the provided [perceptionPort] or [actuatorPort]
	 * so it can query/actuate the current context's network state.
	 *
	 * @param perceptionPort Scoped perception port for this context (SP1.4)
	 * @param actuatorPort Scoped actuator port for this context (SP1.4)
	 * @return All tools available in this context (SP1.6: 12 tools)
	 *
	 * @since Issue #548 (SP1.3 — skeleton); SP1.4 (#549) adds port parameters; SP1.6 (#551) implements tools
	 */
	fun assembleAllTools(
		perceptionPort: NetworkPerceptionPort,
		actuatorPort: NetworkActuatorPort
	): List<DomainTool> {
		logger.debug {
			"ToolGroupRegistry.assembleAllTools: assembling perception + actuator tools (SP1.6 full implementation)"
		}

		return mutableListOf<DomainTool>().apply {
			// Perception tools (read-only network queries)
			addAll(assemblePerceptionTools(perceptionPort))
			// Actuator tools (network commands)
			addAll(assembleActuatorTools(actuatorPort))
		}
	}

	/**
	 * Get perception-only tools (read-only network queries).
	 *
	 * Subgroup of [assembleAllTools]. Useful for testing or agents that only sense
	 * (no decision/actuation).
	 *
	 * ### Tools included
	 *
	 * - `signal_aspect(semaphoreName)` — query signal aspect of one semaphore
	 * - `all_signal_aspects()` — query all semaphore signals in one call
	 * - `block_occupancy(blockId)` — query occupancy of one track block
	 * - `all_block_occupancies()` — query all block occupancies
	 * - `train_position(trainId)` — query kinematics of one train
	 * - `all_train_positions()` — query all train positions
	 * - `train_timetable(trainId)` — query timetable of one train
	 * - `all_train_timetables()` — query all train timetables
	 *
	 * @param perceptionPort Scoped perception port for this context (SP1.4)
	 * @return Perception tools (SP1.6: 8 tools)
	 * @since Issue #549 (SP1.4); SP1.6 (#551) implements tools
	 */
	fun assemblePerceptionTools(perceptionPort: NetworkPerceptionPort): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assemblePerceptionTools: creating 8 perception tools (SP1.6)" }
		return listOf(
			SignalAspectTool(perceptionPort),
			AllSignalAspectsTool(perceptionPort),
			BlockOccupancyTool(perceptionPort),
			AllBlockOccupanciesTool(perceptionPort),
			TrainPositionTool(perceptionPort),
			AllTrainPositionsTool(perceptionPort),
			TrainTimetableTool(perceptionPort),
			AllTrainTimetablesTool(perceptionPort)
		)
	}

	/**
	 * Get actuator-only tools (commands that affect network state).
	 *
	 * Subgroup of [assembleAllTools]. Useful for testing or agents that only act
	 * (no perception/decision).
	 *
	 * ### Tools included
	 *
	 * - `request_route(trainName, fromEndpointName, toEndpointName)` — request route reservation
	 * - `release_route(trainName)` — release reserved blocks for a train
	 * - `set_switch_position(switchName, position)` — command a switch to MAIN or BRANCH
	 * - `set_signal_aspect(semaphoreName, signal)` — command a semaphore to a signal aspect
	 *
	 * @param actuatorPort Scoped actuator port for this context (SP1.4)
	 * @return Actuator tools (SP1.6: 4 tools)
	 * @since Issue #549 (SP1.4); SP1.6 (#551) implements tools
	 */
	fun assembleActuatorTools(actuatorPort: NetworkActuatorPort): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleActuatorTools: creating 4 actuator tools (SP1.6)" }
		return listOf(
			RequestRouteTool(actuatorPort),
			ReleaseRouteTool(actuatorPort),
			SetSwitchPositionTool(actuatorPort),
			SetSignalAspectTool(actuatorPort)
		)
	}
}
