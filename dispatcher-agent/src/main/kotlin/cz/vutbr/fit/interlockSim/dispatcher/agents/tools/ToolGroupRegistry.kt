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

import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DefaultDispatchLoopActuatorPort
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
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
 *             commandQueue = get()    // Scoped per context (SP1.7)
 *         )
 *     }
 * }
 * ```
 *
 * The factory then calls `toolRegistry.assembleAllTools(perceptionPort, commandQueue)` to
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
	 * Each tool is constructed using the provided [perceptionPort] or [commandQueue]
	 * so it can query/actuate the current context's network state.
	 *
	 * ## SP1.7 threading contract (Issue #774)
	 *
	 * The [perceptionPort] passed here MUST be off-thread-safe — i.e. a
	 * [cz.vutbr.fit.interlockSim.ports.SnapshotProjectionNetworkPerceptionPort] built from the
	 * live port's `snapshot()`, not the live
	 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort] itself (whose single-query /
	 * `allXxx()` methods read mutable state and are kDisco-thread-only). Actuator tools no
	 * longer take an actuator port at all: they post a [cz.vutbr.fit.interlockSim.sim.DispatchDecision]
	 * to the [commandQueue] (fire-and-forget), and
	 * [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier] applies it on the kDisco
	 * thread.
	 *
	 * @param perceptionPort Scoped perception port for this context (SP1.4); must be the
	 *   off-thread-safe projection in SP1.7+.
	 * @param commandQueue Scoped command queue for this context (SP1.7); actuator tools post
	 *   decisions here instead of calling the actuator port directly.
	 * @return All tools available in this context (SP1.6: 12 tools)
	 *
	 * @since Issue #548 (SP1.3 — skeleton); SP1.4 (#549) adds port parameters; SP1.6 (#551)
	 *   implements tools; SP1.7 (#774) switches actuator tools to the command queue
	 */
	fun assembleAllTools(
		perceptionPort: NetworkPerceptionPort,
		commandQueue: ActuatorCommandQueue
	): List<DomainTool> {
		logger.debug {
			"ToolGroupRegistry.assembleAllTools: assembling perception + actuator tools (SP1.7 queue-wired actuators)"
		}

		return mutableListOf<DomainTool>().apply {
			// Perception tools (read-only network queries)
			addAll(assemblePerceptionTools(perceptionPort))
			// Actuator tools (network commands — fire-and-forget over the queue, SP1.7)
			addAll(assembleActuatorTools(commandQueue))
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
	 * Each actuator tool posts a [cz.vutbr.fit.interlockSim.sim.DispatchDecision] to the
	 * [commandQueue] (fire-and-forget, SP1.7 Issue #774) instead of calling the actuator port
	 * directly — the agent driver thread must not touch live simulation state.
	 *
	 * @param commandQueue Scoped command queue for this context (SP1.7); actuator tools post
	 *   decisions here.
	 * @return Actuator tools (SP1.6: 4 tools)
	 * @since Issue #549 (SP1.4); SP1.6 (#551) implements tools; SP1.7 (#774) rewires to the queue
	 */
	fun assembleActuatorTools(commandQueue: ActuatorCommandQueue): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleActuatorTools: creating 4 actuator tools (SP1.7 queue-wired)" }
		return listOf(
			RequestRouteTool(commandQueue),
			ReleaseRouteTool(commandQueue),
			SetSwitchPositionTool(commandQueue),
			SetSignalAspectTool(commandQueue)
		)
	}

	// ── SP4.1 dispatch-loop tool groups (Issue #563) ──────────────────────────────────────────

	/**
	 * Assemble all dispatch-loop-specific tools (sensor + actuator) for a ShuntingLoop context.
	 *
	 * These tools are **separate** from the general-purpose network tools returned by
	 * [assembleAllTools]. They are added on top of [assembleAllTools] for agents that
	 * drive [cz.vutbr.fit.interlockSim.sim.ShuntingLoop] (SP4 and later milestones).
	 *
	 * ### Tools included (SP4.1: 3 tools)
	 *
	 * **Sensor tools** (dispatch-loop inputs):
	 * - `queued_trains` — pending trains awaiting approval
	 * - `block_inputs` — combined inner + outer block-input observations
	 *
	 * **Actuator tools** (dispatch-loop commands):
	 * - `approve_train(trainId)` — admit a queued train to the active network
	 *
	 * @param sensorPort Dispatch-loop sensor port for this context (SP4.1)
	 * @param commandQueue Scoped command queue for this context (fire-and-forget, SP4.1)
	 * @return All dispatch-loop tools (SP4.1: 3 tools)
	 *
	 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
	 */
	fun assembleDispatchLoopTools(
		sensorPort: DispatchLoopSensorPort,
		commandQueue: ActuatorCommandQueue
	): List<DomainTool> {
		logger.debug {
			"ToolGroupRegistry.assembleDispatchLoopTools: assembling dispatch-loop sensor + actuator tools (SP4.1)"
		}
		return mutableListOf<DomainTool>().apply {
			addAll(assembleDispatchLoopSensorTools(sensorPort))
			addAll(assembleDispatchLoopActuatorTools(commandQueue))
		}
	}

	/**
	 * Get dispatch-loop sensor tools (read-only ShuntingLoop dispatch inputs).
	 *
	 * Subgroup of [assembleDispatchLoopTools]. Useful for testing or agents that only observe
	 * dispatch-loop state without issuing approval commands.
	 *
	 * ### Tools included
	 *
	 * - `queued_trains()` — list of trains queued for approval
	 * - `block_inputs()` — all inner + outer block-input observations
	 *
	 * @param sensorPort Dispatch-loop sensor port for this context (SP4.1)
	 * @return Dispatch-loop sensor tools (SP4.1: 2 tools)
	 *
	 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
	 */
	fun assembleDispatchLoopSensorTools(sensorPort: DispatchLoopSensorPort): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleDispatchLoopSensorTools: creating 2 dispatch-loop sensor tools (SP4.1)" }
		return listOf(
			QueuedTrainsTool(sensorPort),
			BlockInputsTool(sensorPort)
		)
	}

	/**
	 * Get dispatch-loop actuator tools (train-admission commands for ShuntingLoop).
	 *
	 * Subgroup of [assembleDispatchLoopTools]. Useful for testing or agents that only
	 * admit trains (no perception).
	 *
	 * ### Tools included
	 *
	 * - `approve_train(trainId)` — admit a queued train to the active network (fire-and-forget)
	 *
	 * Posts a [cz.vutbr.fit.interlockSim.sim.DispatchDecision.ApproveTrain] to the
	 * [commandQueue]; [cz.vutbr.fit.interlockSim.dispatcher.DispatchDecisionApplier]
	 * applies it on the kDisco thread.
	 *
	 * @param commandQueue Scoped command queue for this context (SP4.1)
	 * @return Dispatch-loop actuator tools (SP4.1: 1 tool)
	 *
	 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
	 */
	fun assembleDispatchLoopActuatorTools(commandQueue: ActuatorCommandQueue): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleDispatchLoopActuatorTools: creating 1 dispatch-loop actuator tool (SP4.1)" }
		return listOf(
			ApproveTrainTool(DefaultDispatchLoopActuatorPort(commandQueue))
		)
	}
}
