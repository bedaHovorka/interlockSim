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

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Skeleton implementation of [AgentService] for creating Koog dispatch agents (Issue #547).
 *
 * ## Current State (SP1.2)
 *
 * Creates [KoogDispatchAgentImpl] instances with:
 * - Tool registration (stored but not yet wired to Koog)
 * - Model configuration
 * - System prompt storage
 *
 * Does NOT yet:
 * - Construct Koog agent objects
 * - Register tools in Koog's framework
 * - Set up LLM model connections
 *
 * ## Future (SP1.6, Issue #551)
 *
 * Will be expanded to:
 * - Construct Koog [KoogAgent] with tool definitions
 * - Register each [DomainTool] as a Koog tool
 * - Initialize Ollama model connection
 * - Configure agent personality via system prompt
 *
 * ## Design (SP1 phasing)
 *
 * - SP1.2 (this class): Agent service skeleton, tool collection
 * - SP1.3 (#548): Koin DI wiring (module provides this service)
 * - SP1.4 (#549): Perception/actuator tool implementations (fed into this service)
 * - SP1.5 (#550): Ollama executor backend initialization
 * - SP1.6 (#551): Full Koog tool definitions and agent personality tuning
 *
 * ## No Spring Boot
 *
 * Unlike roi-hunter-assignment, this service:
 * - Has NO Spring/SpringBoot dependencies
 * - Uses Koin for DI (lightweight, Kotlin-native)
 * - Integrates directly with kDisco and domain ports
 *
 * @since Issue #547 (SP1.2 — Goal 10)
 */
class DefaultAgentService : AgentService {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override suspend fun createDispatchAgent(
		modelName: String,
		tools: List<DomainTool>,
		systemPrompt: String?
	): KoogDispatchAgent {
		logger.debug {
			"DefaultAgentService.createDispatchAgent: modelName=$modelName, " +
				"tools=${tools.size}, systemPrompt=${systemPrompt != null} (SP1.2 skeleton)"
		}

		// SP1.2 skeleton: create agent with tools and configuration stored
		// SP1.6: Will construct Koog agent, register tools, wire model
		val agent =
			KoogDispatchAgentImpl(
				tools = tools,
				modelName = modelName,
				systemPrompt = systemPrompt
			)

		logger.debug { "DefaultAgentService: created KoogDispatchAgentImpl" }
		return agent
	}
}
