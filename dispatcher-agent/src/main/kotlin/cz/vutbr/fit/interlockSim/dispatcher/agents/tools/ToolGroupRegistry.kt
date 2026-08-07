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
 * Registry of the actuator tools available to Koog agents (SP2c.6, Issue #829).
 *
 * ## SP2c.6 actuator surface (Issue #829)
 *
 * [assembleAllTools] produces the **four-tool actuator surface** only
 * (`approve_train`, `request_route`, `cancel_route`, `no_op`) using a [SinkHolder]. The former
 * perception tools (`signal_aspect`, `block_occupancy`, `train_position`, …) and dispatch-loop
 * sensor tools (`queued_trains`, `block_inputs`) were removed from the LLM tool surface in SP2c.6
 * — perception now flows through the single sim-thread-captured
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector] value, not
 * through LLM-queried tools. Their tool classes were deleted; the perception/sensor port
 * interfaces themselves remain (used by the projector and by
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory] for static topology reads).
 *
 * @since Issue #548 (SP1.3 — Goal 10); SP1.4 (#549), SP1.7 (#774), SP2c.6 (#829) narrows to 4 tools
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
	 * @param perceptionPort Optional off-thread-safe perception port supplying the **active**
	 *   trains for in-turn `trainId`/`trainName` validation in [CancelRouteTool] (Issue #847
	 *   cleanup pass) and [RequestRouteTool] (Issue #847 round 2); `null` (the default) preserves
	 *   the prior no-pre-check behavior.
	 * @param blockIds Static Block IDs, letting [RequestRouteTool] classify a rejected endpoint as
	 *   `ENDPOINT_IS_BLOCK_ID` rather than `UNKNOWN_ENDPOINT` (Issue #847 round 4) — the `kA` vs `A`
	 *   confusion the anti-hallucination work targets, which must be countable on its own.
	 * @param sensorPort Optional dispatch-loop sensor port supplying the **queued** trains, for
	 *   the same validation in [ApproveTrainTool] and [RequestRouteTool] (Issue #847 round 2);
	 *   `null` (the default) preserves the prior no-pre-check behavior.
	 * @param inOutNames Static InOut names, letting [RequestRouteTool] reject an end-to-end route
	 *   aimed at the wrong end of the station while still allowing Signal-to-Signal section hops.
	 *   Empty (the default) disables that check.
	 * @return Four actuator tools. Neither port is an LLM-facing tool — the four-tool actuator
	 *   surface is unchanged, as [cz.vutbr.fit.interlockSim.dispatcher.agents.ActuatorToolSurface]
	 *   asserts at agent construction.
	 *
	 * @since Issue #548 (SP1.3 skeleton); SP2c.6 (#829) reduces to the 4-tool surface
	 */
	fun assembleAllTools(
		validEndpointNames: Set<String>,
		sinkHolder: SinkHolder = SinkHolder(),
		perceptionPort: NetworkPerceptionPort? = null,
		sensorPort: DispatchLoopSensorPort? = null,
		blockIds: Set<String> = emptySet(),
		inOutNames: Set<String> = emptySet()
	): List<DomainTool> {
		logger.debug { "ToolGroupRegistry.assembleAllTools: assembling 4-tool actuator surface (SP2c.6)" }
		return listOf(
			ApproveTrainTool(sinkHolder, sensorPort, perceptionPort),
			RequestRouteTool(sinkHolder, validEndpointNames, perceptionPort, sensorPort, blockIds, inOutNames),
			CancelRouteTool(sinkHolder, perceptionPort),
			NoOpTool(sinkHolder)
		)
	}
}
