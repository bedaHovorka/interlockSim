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
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Sensor tool exposing combined inner and outer block-input observations to agent consumers
 * (SP4.1, Issue #563).
 *
 * Returns all directional block-input observations for both inner blocks (semaphore–semaphore)
 * and outer blocks (InOut–semaphore) in the ShuntingLoop network. Each observation carries:
 * - block id and the semaphore name at this input
 * - block occupancy state and owner train id
 * - whether a train is approaching from this direction
 * - whether a path has already been set up toward this input
 * - whether the path has been extended beyond this semaphore
 *
 * This combined view gives a dispatch agent the full directional state it needs to decide
 * whether to extend or release route reservations on each side of every block.
 *
 * The result is a **snapshot** from the latest tick published by the simulation shell; it may
 * be one tick stale relative to current simulation time, which is acceptable for dispatch
 * decision making.
 *
 * @param sensorPort Dispatch-loop sensor port for this context (injected per simulation)
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
class BlockInputsTool(
	private val sensorPort: DispatchLoopSensorPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "block_inputs"

	override val description: String =
		"Return all directional block-input observations for inner (semaphore–semaphore) and outer " +
			"(InOut–semaphore) blocks in the ShuntingLoop network. Each entry carries the block id, " +
			"semaphore name, occupancy state, owner train, approach direction flag, and path-setup flags. " +
			"Use this together with queued_trains to reason about route reservations needed for each train."

	override val parameters: List<DomainToolParameter> = emptyList()

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		logger.debug { "BlockInputsTool.execute" }
		return runCatching {
			sensorPort.getInnerBlockInputs() + sensorPort.getOuterBlockInputs()
		}.fold({ ToolResult.Success(it) }, { ToolResult.Error("block_inputs failed: ${it.message}", it) })
	}
}
