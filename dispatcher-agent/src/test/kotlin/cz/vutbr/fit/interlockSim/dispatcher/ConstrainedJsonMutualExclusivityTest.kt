/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import ai.koog.prompt.llm.LLMCapability
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.agents.DefaultAgentService
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogDispatchAgentImpl
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaModelFactory
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Proves the SP2c.13 (Issue #836) mutual-exclusivity-by-construction requirement: the
 * tool-calling arm and the constrained-JSON arm cannot simultaneously be active because they are
 * separate code paths with separate model descriptors, not a runtime flag.
 *
 * ## What is being protected
 *
 * SP2c.13 requires that `format` and `tools` are **never** sent in the same request. The
 * failure mode would be silent quality degradation on `qwen2.5:7b-instruct` (SP2c.27 spike 2
 * finding, Issue #850). This test enforces the separation at the model-descriptor level:
 *
 * 1. **Tool-calling model** (`OllamaModelFactory.toolCapableModel`) declares [LLMCapability.Tools]
 *    — appropriate for [DefaultAgentService]'s `AIAgent` loop and meaningless for
 *    [ConstrainedJsonEmissionStrategy].
 * 2. **Constrained-JSON model** (`OllamaModelFactory.constrainedJsonCapableModel`) does **not**
 *    declare [LLMCapability.Tools] — it cannot be used with Koog's `AIAgent` tool-calling
 *    machinery, and [ConstrainedJsonEmissionStrategy] never passes it to `AIAgent`.
 * 3. **[ConstrainedJsonEmissionStrategy]** is **not** a Koog `AIAgent` implementation — it
 *    produces no `ToolRegistry`, passes no `tools` JSON field, and never calls
 *    `DefaultAgentService.createDispatchAgent`.
 * 4. **[DefaultAgentService]/[KoogDispatchAgentImpl]** is **not** a [EmissionStrategy] — it
 *    does not implement the [cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy]
 *    interface and therefore cannot be installed in [DispatchTickLoop] alongside a
 *    [ConstrainedJsonEmissionStrategy].
 *
 * @since Issue #836 (SP2c.13 — Goal 10 constrained-JSON A/B arm)
 */
@DisplayName("SP2c.13 mutual exclusivity — tool-calling and constrained-JSON are separate builds")
class ConstrainedJsonMutualExclusivityTest {
	// ── Model descriptor separation ───────────────────────────────────────────

	@Test
	@DisplayName("toolCapableModel declares LLMCapability.Tools")
	fun toolCapableModelHasToolsCapability() {
		val model = OllamaModelFactory.toolCapableModel("qwen2.5:7b-instruct", contextLength = 8192L)
		assertThat(model.capabilities).isNotNull().contains(LLMCapability.Tools)
	}

	@Test
	@DisplayName("constrainedJsonCapableModel does NOT declare LLMCapability.Tools")
	fun constrainedJsonModelLacksToolsCapability() {
		val model =
			OllamaModelFactory.constrainedJsonCapableModel("qwen2.5:7b-instruct", contextLength = 8192L)
		assertThat(model.capabilities).isNotNull().doesNotContain(LLMCapability.Tools)
	}

	@Test
	@DisplayName("constrainedJsonCapableModel still declares Schema.JSON.Basic")
	fun constrainedJsonModelHasJsonSchemaCapability() {
		val model =
			OllamaModelFactory.constrainedJsonCapableModel("qwen2.5:7b-instruct", contextLength = 8192L)
		assertThat(model.capabilities).isNotNull().contains(LLMCapability.Schema.JSON.Basic)
	}

	@Test
	@DisplayName("the two model builders produce descriptors with different capability sets")
	fun toolCallingAndConstrainedJsonModelsHaveDifferentCapabilities() {
		val toolModel = OllamaModelFactory.toolCapableModel("qwen2.5:7b-instruct", contextLength = 8192L)
		val jsonModel =
			OllamaModelFactory.constrainedJsonCapableModel("qwen2.5:7b-instruct", contextLength = 8192L)
		assertThat(toolModel.capabilities).isEqualTo((jsonModel.capabilities ?: emptyList()) + LLMCapability.Tools)
	}

	// ── Type-system separation ────────────────────────────────────────────────

	@Test
	@DisplayName("ConstrainedJsonEmissionStrategy implements EmissionStrategy (can go into DispatchTickLoop)")
	fun constrainedJsonStrategyImplementsEmissionStrategy() {
		val strategy =
			ConstrainedJsonEmissionStrategy(
				config = OllamaExecutorConfig(),
				systemPrompt = "test",
				seed = 0L
			)
		// Type-system proof: if this assignment compiles, it implements EmissionStrategy.
		val asInterface: cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy = strategy
		assertThat(asInterface).isInstanceOf(ConstrainedJsonEmissionStrategy::class)
	}

	@Test
	@DisplayName("KoogDispatchAgentImpl does NOT implement EmissionStrategy (cannot go into DispatchTickLoop)")
	fun koogDispatchAgentIsNotEmissionStrategy() {
		val config = OllamaExecutorConfig.forLocalTesting()
		val service = DefaultAgentService(OllamaSimpleExecutor(config), config)

		val agent =
			runBlocking { service.createDispatchAgent(config.modelName, emptyList(), null) }
				as KoogDispatchAgentImpl

		// KoogDispatchAgentImpl must NOT implement EmissionStrategy — that is the type-system
		// enforcement of mutual exclusivity: you cannot accidentally install a KoogDispatchAgent
		// in a DispatchTickLoop slot (which takes an EmissionStrategy), so format + tools can
		// never co-exist in the same request by construction.
		val isEmissionStrategy = agent is cz.vutbr.fit.interlockSim.dispatcher.agents.EmissionStrategy
		assertThat(isEmissionStrategy).isFalse()
	}

	// ── Schema presence in constrained-JSON request ───────────────────────────

	@Test
	@DisplayName("ACTION_BATCH_SCHEMA is a non-empty JsonObject suitable for the Ollama 'format' field")
	fun actionBatchSchemaIsNonEmpty() {
		val schema = ConstrainedJsonEmissionStrategy.ACTION_BATCH_SCHEMA
		assertThat(schema.isEmpty()).isFalse()
		// Verify the key that distinguishes it from an empty schema
		assertThat(schema.containsKey("properties")).isTrue()
	}
}
