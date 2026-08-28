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
import ai.koog.agents.core.tools.ToolRegistry
import cz.vutbr.fit.interlockSim.dispatcher.AppliedOutcomeFeed
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaModelFactory
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Real [AgentService] implementation wiring the Koog LLM tool-calling runtime to a local Ollama
 * model (SP2b.9, Issue #566).
 *
 * ## History
 *
 * Earlier phases (SP1.2–SP1.7, Issues #547–#774) built the pieces this class now wires together:
 * [DomainTool] implementations, [ai.koog.agents.core.tools.ToolRegistry] assembly via
 * [KoogToolAdapter], and the [OllamaSimpleExecutor]/[OllamaExecutorConfig] Ollama backend — but no
 * production code ever called [OllamaSimpleExecutor.getExecutor] or constructed a real Koog
 * `AIAgent`. [createDispatchAgent] closes that gap: it builds a real
 * `ai.koog.agents.core.agent.AIAgent<String, String>` (Koog's `singleRunStrategy()` tool-calling
 * loop) and wraps it in [KoogDispatchAgentImpl].
 *
 * @property ollamaExecutor Shared Koin-singleton Ollama [ai.koog.prompt.executor.model.PromptExecutor]
 *   provider (heavyweight, lazily initialized on first [OllamaSimpleExecutor.getExecutor] call).
 * @property ollamaConfig Shared Koin-singleton model/inference configuration (model name,
 *   temperature, `maxAgentIterations`, `contextWindowTokens`).
 *
 * @since Issue #547 (SP1.2 — Goal 10 skeleton); real Koog wiring added in Issue #566 (SP2b.9)
 */
class DefaultAgentService(
	private val ollamaExecutor: OllamaSimpleExecutor,
	private val ollamaConfig: OllamaExecutorConfig
) : AgentService {
	companion object {
		private val logger = KotlinLogging.logger {}
	}

	override suspend fun createDispatchAgent(
		modelName: String,
		tools: List<DomainTool>,
		systemPrompt: String?,
		cycleHistory: CycleHistory,
		outcomeFeed: AppliedOutcomeFeed?
	): KoogDispatchAgent {
		logger.debug {
			"DefaultAgentService.createDispatchAgent: modelName=$modelName, tools=${tools.size}, " +
				"systemPrompt=${systemPrompt != null} (SP2b.9 real Koog wiring)"
		}

		val toolRegistry = ToolRegistry { tools(tools.map { KoogToolAdapter(it) }) }
		val model = OllamaModelFactory.toolCapableModel(modelName, ollamaConfig.contextWindowTokens)

		val aiAgent =
			AIAgent(
				promptExecutor = ollamaExecutor.getExecutor(),
				llmModel = model,
				toolRegistry = toolRegistry,
				systemPrompt = systemPrompt,
				temperature = ollamaConfig.temperature.toDouble(),
				maxIterations = ollamaConfig.maxAgentIterations
			)

		logger.debug { "DefaultAgentService: created AIAgent with ${tools.size} Koog-wrapped tools" }
		return KoogDispatchAgentImpl(aiAgent, cycleHistory, outcomeFeed)
	}
}
