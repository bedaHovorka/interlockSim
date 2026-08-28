/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import ai.koog.agents.core.agent.GraphAIAgent
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Reflects on the Koog `AIAgent` built by [DefaultAgentService.createDispatchAgent] to lock the
 * SP2c.6 / SP2c.27 build contract (Issue #829 / #850) without driving a live LLM run.
 *
 * ## What is being protected
 *
 * Issue #829 originally required `maxIterations = 4` + a custom `fixed_tick` graph. SP2c.27
 * (#850) refuted that: a happy-path `shuntingLoopAI` cycle already needed more than 4 tool
 * round-trips, so the project settled on Koog's stock acyclic [singleRunStrategy][]
 * with `maxAgentIterations = 20`. This test fails the build if any of the three load-bearing
 * build choices silently regress:
 *
 * 1. **Strategy is the stock single-run graph** (`name == "single_run"`) — not a custom cyclic
 *    graph and not a planner/functional strategy. A cyclic strategy would re-enter the LLM loop
 *    and break the one-shot-per-cycle semantics [KoogAgentPlanAdapter] relies on.
 * 2. **`maxAgentIterations` matches the config** (20 by default) — the value Koog enforces as
 *    the tool-calling loop ceiling.
 * 3. **`responseProcessor` is null** — no history-compression processor is installed, so the full
 *    tool-call history reaches the LLM each cycle (the SP2c.27 decision; compression is a future
 *    option, not the current build).
 *
 * The agent is built but never run ([KoogDispatchAgentImpl.decideAsync] is not called), so no
 * Ollama connection is needed — Koog's `OllamaClient` is lazy and only connects on the first
 * `run`.
 *
 * @since Issue #829 (SP2c.6 — Goal 10 four-actuator tool surface)
 */
@DisplayName(
	"DefaultAgentService build contract — stock singleRunStrategy, maxAgentIterations, no compression (#829 / #850)"
)
class AgentBuildContractTest {
	@Test
	@DisplayName("the built AIAgent uses Koog's stock single_run strategy")
	fun builtAgentUsesSingleRunStrategy() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val agent =
			runBlocking { service.createDispatchAgent(config.modelName, emptyList(), null) }
				as KoogDispatchAgentImpl

		// `strategy` is declared on GraphAIAgent, not the abstract AIAgent — DefaultAgentService
		// always builds a GraphAIAgent via the AIAgent(...) factory (singleRunStrategy()).
		val graphAgent = agent.builtAgent as GraphAIAgent<String, String>
		assertThat(graphAgent.strategy.name).isEqualTo("single_run")
	}

	@Test
	@DisplayName("the built AIAgent's maxAgentIterations matches the config (default 20, per #850)")
	fun builtAgentMaxIterationsMatchesConfig() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val agent =
			runBlocking { service.createDispatchAgent(config.modelName, emptyList(), null) }
				as KoogDispatchAgentImpl

		assertThat(agent.builtAgent.agentConfig.maxAgentIterations).isEqualTo(config.maxAgentIterations)
		assertThat(config.maxAgentIterations).isEqualTo(20)
	}

	@Test
	@DisplayName("the built AIAgent has no response processor (no history compression, per SP2c.27)")
	fun builtAgentHasNoResponseProcessor() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val agent =
			runBlocking { service.createDispatchAgent(config.modelName, emptyList(), null) }
				as KoogDispatchAgentImpl

		assertThat(agent.builtAgent.agentConfig.responseProcessor).isNull()
	}
}
