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
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Perception tool exposing [NetworkPerceptionPort.allSignalAspects] to Koog agents (SP1.6, Issue #551).
 *
 * Query the current signal aspects of **all** semaphores in the network in one call.
 *
 * @param perceptionPort Scoped perception port for this context (injected per simulation)
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class AllSignalAspectsTool(
	private val perceptionPort: NetworkPerceptionPort
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "all_signal_aspects"

	override val description: String =
		"Query the current signal aspects of all semaphores in the railway network. " +
			"Returns a list of all semaphore names and their current signal aspects."

	override val parameters: List<DomainToolParameter> = emptyList()

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		logger.debug { "AllSignalAspectsTool.execute" }
		return runCatching { perceptionPort.allSignalAspects() }
			.fold({ ToolResult.Success(it) }, { ToolResult.Error("all_signal_aspects failed: ${it.message}", it) })
	}
}
