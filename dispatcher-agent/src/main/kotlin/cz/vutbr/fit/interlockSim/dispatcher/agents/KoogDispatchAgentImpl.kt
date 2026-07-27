/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import ai.koog.agents.core.agent.AIAgent
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Real Koog-based railway dispatch agent (SP2b.9, Issue #566).
 *
 * Wraps a Koog `AIAgent<String, String>` (built by [DefaultAgentService] via Koog's
 * `singleRunStrategy()` tool-calling loop: call the LLM, execute any requested tools, send tool
 * results back, repeat until the LLM replies with plain text).
 *
 * ## Where the actual dispatch decisions go
 *
 * Actuator [DomainTool]s (`RequestRouteTool`, `ReleaseRouteTool`, etc.) post
 * [DispatchDecision]s directly to the shared `ActuatorCommandQueue` as a side effect of their own
 * `execute()`, fire-and-forget, during [aiAgent]'s tool-calling loop. [decideAsync] therefore
 * always returns an empty list — that is the **normal, successful outcome**, not a sign the LLM
 * did nothing (see [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter], the only
 * place a non-empty/empty distinction is load-bearing, and it no longer treats empty as failure).
 *
 * @param aiAgent The real Koog agent, pre-wired with the Ollama executor, model, tool registry,
 *   and system prompt (topology + dispatcher persona) at construction time in
 *   [DefaultAgentService.createDispatchAgent].
 *
 * @since Issue #547 (SP1.2 — Goal 10 skeleton); real Koog wiring added in Issue #566 (SP2b.9)
 */
class KoogDispatchAgentImpl(
	private val aiAgent: AIAgent<String, String>
) : KoogDispatchAgent {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override suspend fun decideAsync(observation: DispatchObservation): List<DispatchDecision> {
		val userPrompt = buildUserPrompt(observation)
		val finalText = aiAgent.run(userPrompt)
		logger.debug {
			"KoogDispatchAgentImpl.decideAsync: simTime=${observation.snapshot.simTime}, " +
				"agent finished (reply length=${finalText.length}); any actuation already happened " +
				"as tool-call side effects during this call"
		}
		return emptyList()
	}

	/**
	 * Builds the per-cycle user prompt. Deliberately minimal: the static station topology is
	 * already in the system prompt ([cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer],
	 * SP2b.8), and all dynamic network state is available via the perception tools — duplicating
	 * live state here would raise token cost per cycle and risk it going stale by the time the LLM
	 * reads it.
	 */
	private fun buildUserPrompt(observation: DispatchObservation): String =
		buildString {
			appendLine("Dispatch cycle at simTime=${observation.snapshot.simTime}.")
			// Goal 10 SP2b.9 follow-up: state the active count/cap directly so the admission
			// precondition can be evaluated in one shot, without an extra perception-tool
			// round-trip — this stateless cycle has no memory of the same check from last time.
			appendLine(
				"Active (approved) trains right now: ${observation.approvedTrainCount} / " +
					"${RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS}"
			)
			appendLine("Queued (unapproved) trains: ${observation.unapprovedTrains.size}")
			observation.unapprovedTrains.forEach {
				appendLine("- ${it.trainId} -> exit via ${it.destinationInOutName}")
			}
			appendLine(
				"Use the perception tools to inspect current signal/block/train state, then use " +
					"the actuator tools to reserve routes, release routes, or approve queued trains as " +
					"needed this cycle. Switch and signal aspects change as a side effect of requesting " +
					"and canceling routes — there is no direct tool to set them. Respond with plain text " +
					"when finished."
			)
			if (observation.unapprovedTrains.isNotEmpty()) {
				appendLine(
					"Reminder: a granted route reservation alone does not move a train — call " +
						"approve_train(trainId) for each queued train above once you want it to depart."
				)
			}
		}
}
