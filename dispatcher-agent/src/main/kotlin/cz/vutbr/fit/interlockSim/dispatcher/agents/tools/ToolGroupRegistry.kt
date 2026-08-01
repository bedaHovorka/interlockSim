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
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Registry of tool groups (perception and actuator tools) available to Koog agents.
 *
 * ## SP2c.6 actuator surface (Issue #829)
 *
 * [assembleAllTools] now produces the **four-tool actuator surface** only
 * (`approve_train`, `request_route`, `cancel_route`, `no_op`) using a [SinkHolder].
 * Perception tools are no longer bundled with the actuator surface — agents that need
 * perception should call [assemblePerceptionTools] separately.
 *
 * ## Dispatch-loop sensor tools (SP4.1, Issue #563)
 *
 * [assembleDispatchLoopTools] returns only the two sensor tools (`queued_trains`,
 * `block_inputs`). `approve_train` moved into the four-tool actuator surface in SP2c.6.
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549), SP1.7 (#774), SP2c.6 (#829)
 */
class ToolGroupRegistry {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	/**
	 * Assemble the four-tool actuator surface for a Koog agent (SP2c.6, Issue #829).
	 *
	 * Returns exactly four actuator tools:
	 * - `approve_train` — admit a queued train
	 * - `request_route` — reserve an end-to-end route
	 * - `cancel_route` — cancel a reserved route
	 * - `no_op` — explicitly signal no action this tick
	 *
	 * @param validEndpointNames Exact InOut/Signal names `request_route` validates against.
	 * @param sinkHolder Shared sink holder for this agent instance; all four tools emit to it.
	 * @return Four actuator tools.
	 *
	 * @since Issue #548 (SP1.3 skeleton); SP2c.6 (#829) reduces to the 4-tool surface
	 */
	fun assembleAllTools(
		validEndpointNames: Set<String>,
		sinkHolder: SinkHolder = SinkHolder()
	): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleAllTools: assembling 4-tool actuator surface (SP2c.6)" }
		return listOf(
			ApproveTrainTool(sinkHolder),
			RequestRouteTool(sinkHolder, validEndpointNames),
			CancelRouteTool(sinkHolder),
			NoOpTool(sinkHolder)
		)
	}

	/**
	 * Get perception-only tools (read-only network queries).
	 *
	 * ### Tools included (9 tools)
	 *
	 * - `signal_aspect` — query signal aspect of one semaphore
	 * - `all_signal_aspects` — query all semaphore signals
	 * - `block_occupancy` — query occupancy of one track block
	 * - `all_block_occupancies` — query all block occupancies
	 * - `train_position` — query kinematics of one train
	 * - `all_train_positions` — query all train positions
	 * - `train_timetable` — query timetable of one train
	 * - `all_train_timetables` — query all train timetables
	 * - `train_perception` — query first-person perception of one train (SP2a.1)
	 *
	 * @param perceptionPort Scoped perception port (must be off-thread-safe in SP1.7+).
	 * @return 9 perception tools.
	 * @since Issue #549 (SP1.4); SP1.6 (#551) implements tools; SP2a.1 (#552) adds train_perception
	 */
	fun assemblePerceptionTools(perceptionPort: NetworkPerceptionPort): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assemblePerceptionTools: creating 9 perception tools (SP2a.1)" }
		return listOf(
			SignalAspectTool(perceptionPort),
			AllSignalAspectsTool(perceptionPort),
			BlockOccupancyTool(perceptionPort),
			AllBlockOccupanciesTool(perceptionPort),
			TrainPositionTool(perceptionPort),
			AllTrainPositionsTool(perceptionPort),
			TrainTimetableTool(perceptionPort),
			AllTrainTimetablesTool(perceptionPort),
			TrainPerceptionTool(perceptionPort)
		)
	}

	// ── SP4.1 dispatch-loop sensor tools (Issue #563) ────────────────────────────────────────

	/**
	 * Assemble dispatch-loop sensor tools for a ShuntingLoop context (SP4.1, Issue #563;
	 * updated in SP2c.6, Issue #829).
	 *
	 * Returns the two sensor tools only (`queued_trains`, `block_inputs`).
	 * `approve_train` moved to the four-tool actuator surface in SP2c.6 and is no longer
	 * included here.
	 *
	 * @param sensorPort Dispatch-loop sensor port for this context.
	 * @return 2 dispatch-loop sensor tools.
	 *
	 * @since Issue #563 (SP4.1); SP2c.6 (#829) removes approve_train from this group
	 */
	fun assembleDispatchLoopTools(sensorPort: DispatchLoopSensorPort): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleDispatchLoopTools: assembling 2 sensor tools (SP2c.6)" }
		return listOf(
			QueuedTrainsTool(sensorPort),
			BlockInputsTool(sensorPort)
		)
	}
}
