/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.sweep.SweepCell
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ExampleRegistry.llmRunParameters] (Issue #834, SP2c.11), the LLM arm's real
 * `RunParameters`-recording site (`createShuntingLoopAIExample`/`createShuntingLoopAIGuiExample`).
 *
 * Constructs [OllamaExecutorConfig]/[DispatcherRunConfig] directly rather than through Koin —
 * both are plain data classes, so the exact mapping the LLM examples wire in
 * [ExampleRegistry.createShuntingLoopAIExample] can be exercised without a live simulation
 * context or Ollama.
 *
 * @since Issue #834 (SP2c.11 — inferenceTimeoutSeconds/promptVariant threading)
 */
@DisplayName("SP2c.11 — ExampleRegistry.llmRunParameters: inferenceTimeoutSeconds threading (#834)")
class ExampleRegistryLlmRunParametersTest {
	private val registry = ExampleRegistry()

	@Test
	@DisplayName("inferenceTimeoutSeconds is carried through from DispatcherRunConfig")
	fun carriesInferenceTimeoutFromRunConfig() {
		val params =
			registry.llmRunParameters(
				OllamaExecutorConfig(),
				DispatcherRunConfig(inferenceTimeoutSeconds = 90L)
			)

		assertThat(params.inferenceTimeoutSeconds).isEqualTo(90L)
	}

	@Test
	@DisplayName("promptVariant defaults to the untracked-variant sentinel, not the rule-based empty string")
	fun promptVariantDefaultsToUntracked() {
		val params = registry.llmRunParameters(OllamaExecutorConfig(), DispatcherRunConfig())

		assertThat(params.promptVariant).isEqualTo(RunParameters.DEFAULT_PROMPT_VARIANT)
	}

	/**
	 * Agreement with [SweepCell.runParameters]: an LLM cell's abort snapshot must match what the
	 * live LLM example would record for the equivalent settings, or an aborted sweep run groups
	 * into a different report cell than its completed siblings.
	 */
	@Test
	@DisplayName("SweepCell.runParameters() agrees with the live LLM path for an equivalent cell")
	fun agreesWithLiveLlmPath() {
		val cell =
			SweepCell(
				example = "shuntingLoopAI",
				model = "qwen2.5:7b-instruct",
				temperature = 0.5,
				tickPeriodMs = 250L,
				historyN = 5,
				maxActionsPerTick = 2,
				inferenceTimeoutSeconds = 90L
			)

		val fromAbortPath = cell.runParameters()
		val fromLivePath =
			registry.llmRunParameters(
				OllamaExecutorConfig(modelName = cell.model!!, temperature = cell.temperature!!.toFloat()),
				DispatcherRunConfig(
					tickPeriodMs = cell.tickPeriodMs,
					historyN = cell.historyN,
					maxActionsPerTick = cell.maxActionsPerTick,
					inferenceTimeoutSeconds = 90L
				)
			)

		assertThat(fromAbortPath).isEqualTo(fromLivePath)
	}

	@Test
	@DisplayName("a null (default) inferenceTimeoutSeconds records KoogAgentPlanAdapter's own default")
	fun defaultInferenceTimeoutMatchesAdapterDefault() {
		val params = registry.llmRunParameters(OllamaExecutorConfig(), DispatcherRunConfig())

		assertThat(params.inferenceTimeoutSeconds).isEqualTo(KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS)
	}
}
