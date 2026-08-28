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

import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameter
import cz.vutbr.fit.interlockSim.dispatcher.agents.DomainToolParameterType
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool that explicitly signals "no action this tick" (SP2c.6, Issue #829).
 *
 * The LLM calls `no_op` when it has examined the current state and determined that no
 * dispatch action is needed this cycle. Emitting [DispatchAction.NoOp] via the [sinkHolder]
 * lets [DispatchTickLoop] distinguish "the LLM decided to do nothing" from "the LLM produced
 * no output at all" (which would indicate a tool-calling failure, not a deliberate no-op).
 *
 * The optional `reason` parameter is logged at DEBUG but not acted on — it exists so the LLM
 * can explain its reasoning in a structured way without the explanation being treated as output.
 *
 * @param sinkHolder Shared sink holder for this agent instance; [DispatchAction.NoOp] is
 *   emitted to the active sink on every successful execution.
 *
 * @since Issue #829 (SP2c.6 — Goal 10)
 */
class NoOpTool(
	private val sinkHolder: SinkHolder
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "no_op"

	override val description: String =
		"Signal that no dispatch action is needed this tick. Call this when you have examined " +
			"the current state and determined there is nothing to do. Accepts an optional reason " +
			"field for your explanation (the reason is logged but not acted on)."

	override val parameters: List<DomainToolParameter> =
		listOf(
			DomainToolParameter(
				name = "reason",
				description = "Optional human-readable explanation of why no action is needed this tick.",
				type = DomainToolParameterType.String,
				required = false
			)
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		args["reason"]?.toString()?.takeIf { it.isNotBlank() }?.let { reason ->
			logger.debug { "no_op: $reason" }
		}
		sinkHolder.emit(DispatchAction.NoOp)
		return ToolResult.Success("emitted no_op")
	}
}
