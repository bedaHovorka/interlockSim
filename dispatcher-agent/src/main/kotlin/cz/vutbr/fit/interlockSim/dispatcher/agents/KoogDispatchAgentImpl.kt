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

import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Skeleton implementation of a Koog-based railway dispatch agent (Issue #547).
 *
 * ## Current State (SP1.2)
 *
 * This is a minimal skeleton that:
 * - Accepts a list of [DomainTool]s for Koog integration
 * - Implements [KoogDispatchAgent] interface
 * - Returns empty [DispatchDecision] list (no-op — real logic in SP1.6)
 *
 * ## Future (SP1.6, Issue #551)
 *
 * Will be expanded to:
 * - Construct Koog agent with tool definitions
 * - Call Koog LLM executor with the observation
 * - Parse tool invocations and results
 * - Return actual dispatch decisions
 *
 * ## Design (SP1 phasing)
 *
 * - SP1.2 (this class): Agent skeleton, tool registration, no LLM calls
 * - SP1.3 (#548): Koin DI wiring
 * - SP1.4 (#549): Perception/actuator tool implementations
 * - SP1.5 (#550): Ollama executor backend
 * - SP1.6 (#551): Full Koog tool definitions and LLM decision logic
 *
 * @param tools Domain tools this agent can invoke (e.g. signal state, block occupancy)
 * @param modelName Ollama model to use once LLM integration is complete (SP1.5+)
 * @param systemPrompt Agent personality and domain knowledge for the LLM
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
class KoogDispatchAgentImpl(
	private val tools: List<DomainTool>,
	private val modelName: String,
	private val systemPrompt: String?
) : KoogDispatchAgent {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	init {
		logger.debug {
			"KoogDispatchAgentImpl initialized: modelName=$modelName, " +
				"tools=${tools.size}, systemPrompt=${systemPrompt != null}"
		}
	}

	/**
	 * Decide on dispatch actions asynchronously.
	 *
	 * ### Current behavior (SP1.2)
	 *
	 * Returns an empty list (no-op). The real LLM integration happens in SP1.6
	 * ([AgentService.createDispatchAgent] will construct a proper Koog agent
	 * with tool definitions at that time).
	 *
	 * ### Future behavior (SP1.6)
	 *
	 * 1. Construct observation prompt from railway network state
	 * 2. Call Koog LLM executor with tools + observation
	 * 3. Parse tool invocations (agent decides which tools to call)
	 * 4. Execute each tool (on this agent driver thread, not kDisco)
	 * 5. Return parsed dispatch decisions
	 *
	 * @param observation Current network state (signals, blocks, trains, timetables)
	 * @return List of [DispatchDecision]s (empty in SP1.2, populated in SP1.6)
	 */
	override suspend fun decideAsync(observation: DispatchObservation): List<DispatchDecision> {
		logger.debug {
			"KoogDispatchAgentImpl.decideAsync: simTime=${observation.snapshot.simTime}, " +
				"unapprovedTrains=${observation.unapprovedTrains.size}, " +
				"tools=${tools.size} (SP1.2 skeleton: returning empty list)"
		}

		// SP1.2 skeleton: no-op (returns empty list)
		// SP1.6: Will invoke Koog LLM executor with observation and tools,
		// parse results, and return dispatch decisions
		return emptyList()
	}
}
