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
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Actuator tool exposing route cancellation to Koog agents (SP2c.6, Issue #829).
 *
 * Cancels all track blocks reserved for a named train. Symmetric counterpart of
 * `request_route`: when a train completes its journey or needs to reverse, call this
 * to release the reservation so blocks return to free.
 *
 * ## Threading contract
 *
 * `execute()` runs on the agent driver thread. It emits [DispatchAction.CancelRoute] to the
 * active [sinkHolder] (fire-and-forget). The [cz.vutbr.fit.interlockSim.dispatcher.DispatchTickLoop]
 * collects the emission, validates it through [cz.vutbr.fit.interlockSim.dispatcher.ActionValidator],
 * and converts it to [cz.vutbr.fit.interlockSim.sim.DispatchDecision.ReleaseRoute] for the
 * kDisco sim thread.
 *
 * Replaces `release_route` (SP1.7/Issue #774) in the four-tool actuator surface (SP2c.6).
 * The tool name change from `release_route` to `cancel_route` aligns with the sealed
 * [DispatchAction.CancelRoute] vocabulary; the underlying
 * [cz.vutbr.fit.interlockSim.sim.DispatchDecision.ReleaseRoute] applied on the sim thread is
 * unchanged.
 *
 * @param sinkHolder Shared sink holder for this agent instance; [DispatchAction.CancelRoute]
 *   is emitted to the active sink on every successful execution.
 *
 * @since Issue #829 (SP2c.6 — Goal 10)
 */
class CancelRouteTool(
	private val sinkHolder: SinkHolder
) : DomainTool {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override val name: String = "cancel_route"

	override val description: String =
		"Release all track blocks reserved for a named train. " +
			"Fire-and-forget: returns once the cancellation is emitted; observe the freed blocks " +
			"on the next tick."

	override val parameters: List<DomainToolParameter> =
		listOf(
			trainIdParameter("Name of the train whose route should be cancelled (non-blank)")
		)

	override suspend fun execute(args: Map<String, Any?>): ToolResult {
		val trainId =
			args.stringParam(TRAIN_ID_PARAM) ?: return ToolResult.Error(TRAIN_ID_REQUIRED_MSG)

		logger.debug { "CancelRouteTool.execute: trainId=$trainId" }
		sinkHolder.emit(DispatchAction.CancelRoute(trainId))
		return ToolResult.Success("emitted cancel_route train=$trainId")
	}
}
