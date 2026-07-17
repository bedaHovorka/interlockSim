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
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameterType
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Perception tool exposing [NetworkPerceptionPort.blockOccupancy] to Koog agents (SP1.6, Issue #551).
 *
 * Query the occupancy state of a named track block: whether it is FREE, OCCUPIED, or RESERVED,
 * and which train (if any) owns it.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class BlockOccupancyTool(
	private val perceptionPort: NetworkPerceptionPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "block_occupancy"

	override val description: String =
		"Query the occupancy state of a named track block. " +
			"Returns the block state (FREE, OCCUPIED, RESERVED) and the owning train ID if occupied/reserved."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "blockId",
				description = "Block identifier (e.g. \"k1\", \"kA\") or auto-generated \"sep1-sep2\" label for unnamed blocks",
				type = DomainToolParameterType.String,
				required = true
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val blockId =
			args.stringParam("blockId")
				?: return ToolResult.Error("blockId parameter is required and must be a non-blank string")

		logger.debug { "BlockOccupancyTool.execute: blockId=$blockId" }

		return runCatching { perceptionPort.blockOccupancy(blockId) }
			.fold({ ToolResult.Success(it) }, { ToolResult.Error("block_occupancy failed: ${it.message}", it) })
	}
}
