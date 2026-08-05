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

	/**
	 * The built Koog agent, exposed `internal` for the SP2c.6 build-contract test (Issue #829 /
	 * #850) to reflect on its strategy + [ai.koog.agents.core.agent.config.AIAgentConfig] without
	 * driving a live LLM run. Production code must not reach past [decideAsync]; this getter is
	 * a test-only seam, not a public API.
	 *
	 * @since Issue #829 (SP2c.6 — Goal 10 four-actuator tool surface)
	 */
	internal val builtAgent: AIAgent<String, String> get() = aiAgent

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
	 * Builds the per-cycle user prompt. The static station topology is already in the system prompt
	 * ([cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer], SP2b.8), so only
	 * live state belongs here.
	 *
	 * ## This message is the model's only source of train identity (Issue #847 round 2)
	 *
	 * Until SP2c.6 (#829) the agent had perception tools (`queued_trains`, `all_train_positions`, …)
	 * and this prompt could stay minimal and simply point at them. Those tools are gone — the
	 * surface is four actuators
	 * ([cz.vutbr.fit.interlockSim.dispatcher.agents.ActuatorToolSurface]) — but the prompt kept
	 * telling the model to "use the perception tools" while reporting active trains as a bare
	 * *count*. The model consequently had no way to name a running train and invented ids (a live
	 * run produced `trainId=4`), and burned its `maxAgentIterations` budget reaching for tools that
	 * do not exist.
	 *
	 * Both lists are therefore rendered in full here. The data needs no new plumbing: queued trains
	 * come from [DispatchObservation.unapprovedTrains] and active ones from
	 * [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot.trainPositions], both already on the
	 * observation. Keep it that way — if a future change needs more live state, add it to the
	 * observation and render it here rather than reintroducing an LLM-facing query tool.
	 *
	 * ## Never render a name the model cannot use as an argument
	 *
	 * The first cut of the active-train list also rendered each train's position as
	 * `front at <frontSectionName>`. [cz.vutbr.fit.interlockSim.ports.TrainPositionReading.frontSectionName]
	 * is a **block** name, and blocks are the one category of name that is never a valid
	 * `request_route` endpoint — so the message was handing the model a forbidden name in the
	 * middle of its live state. A verification run confirmed the obvious outcome: the model reused
	 * those block names as endpoints (48 rejected calls naming block `k1`), and even copied the
	 * literal `"unknown section"` null-fallback string as an endpoint 18 more times — together the
	 * dominant source of bad calls in that run.
	 *
	 * Position is decorative here anyway: none of the four actuator tools accepts a block. The
	 * list is therefore ids only. If position is wanted later, render something the tool surface
	 * can actually consume — the *signal ahead* of the train is both a legal `request_route`
	 * endpoint and what `ActionValidator.ORIGIN_NOT_AT_TRAIN_POSITION` expects — and never a bare
	 * placeholder string that can be copied verbatim.
	 */
	private fun buildUserPrompt(observation: DispatchObservation): String =
		buildString {
			appendLine("Dispatch cycle at simTime=${observation.snapshot.simTime}.")
			// Goal 10 SP2b.9 follow-up: state the active count/cap directly so the admission
			// precondition can be evaluated in one shot — this stateless cycle has no memory of
			// the same check from last time.
			appendLine(
				"Active (approved) trains right now: ${observation.approvedTrainCount} / " +
					"${RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS}"
			)
			// Ids only — deliberately no position. See "Never render a name the model cannot use"
			// in this method's KDoc: TrainPositionReading.frontSectionName is a *block* name, and
			// rendering it here got it copied straight back as a request_route endpoint.
			observation.snapshot.trainPositions.forEach {
				appendLine("- ${it.trainId} (active)")
			}
			appendLine("Queued (unapproved) trains: ${observation.unapprovedTrains.size}")
			observation.unapprovedTrains.forEach {
				appendLine("- ${it.trainId} (queued) -> exit via ${it.destinationInOutName}")
			}
			// Constrains the *data* the model may name, and keeps the switch/signal side-effect rule
			// (SP2b.9, PR #811 Minor #1). It deliberately makes no claim about which tools exist:
			// that belongs in the system prompt (KoogAgentFactory), which is built alongside the
			// tool surface and knows it, whereas this method is generic. An earlier cut asserted
			// "no tool to query state" here and contradicted callers that legitimately supply one —
			// see KoogRealOllamaToolCallingTest's failing-tool harness.
			appendLine(
				"The two lists above are the complete set of trains you may name this cycle; pass a " +
					"trainId/trainName exactly as written there, never one you inferred. Then use the " +
					"tools available to you to reserve routes, release routes, or approve queued trains " +
					"as needed. Switch and signal aspects change as a side effect of requesting and " +
					"canceling routes — there is no tool to set them directly. Respond with plain text " +
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
