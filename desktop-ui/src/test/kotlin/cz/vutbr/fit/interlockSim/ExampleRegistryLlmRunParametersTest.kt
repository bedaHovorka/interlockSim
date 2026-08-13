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
import assertk.assertions.isNotEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.agents.PromptVariant
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

	/**
	 * Task 11 (#834) replaced the untracked-variant placeholder with the real seam: an LLM run
	 * now records the name of the [PromptVariant] its `KoogAgentFactory` was actually built
	 * with. An unconfigured run gets [PromptVariant.DEFAULT], which is BASELINE — so the JSON
	 * says "this run used the baseline prompt" rather than "a prompt was used, unknown which".
	 */
	@Test
	@DisplayName("promptVariant records the run config's variant, not the untracked-variant sentinel")
	fun promptVariantRecordsTheConfiguredVariant() {
		val params = registry.llmRunParameters(OllamaExecutorConfig(), DispatcherRunConfig())

		assertThat(params.promptVariant).isEqualTo(PromptVariant.DEFAULT.name)
		assertThat(params.promptVariant).isNotEqualTo(RunParameters.DEFAULT_PROMPT_VARIANT)
	}

	@Test
	@DisplayName("a non-default promptVariant is carried through from DispatcherRunConfig")
	fun carriesNonDefaultPromptVariantFromRunConfig() {
		val params =
			registry.llmRunParameters(
				OllamaExecutorConfig(),
				DispatcherRunConfig(promptVariant = PromptVariant.REVISED)
			)

		assertThat(params.promptVariant).isEqualTo("REVISED")
	}

	/**
	 * Agreement with [SweepCell.runParameters]: an LLM cell's abort snapshot must match what the
	 * live LLM example would record for the equivalent settings, or an aborted sweep run groups
	 * into a different report cell than its completed siblings.
	 *
	 * The live path is built from [DispatcherRunConfig.fromProperties] + [OllamaExecutorConfig.default]
	 * (the same file-tier resolution the abort path now uses for an omitted axis, review finding #7,
	 * Issue #834), with the cell's pinned axes applied as overrides. Building the live path from code
	 * defaults instead would only agree on the one configuration where the committed file happens to
	 * equal the code constants — exactly the blind spot the previous test had.
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

		// The live path is the forked child's own resolution, and the child inherits no parent -D
		// flags — so for an omitted axis it resolves file > code. fromProperties(properties = { null })
		// mirrors that; the abort path (SweepCell.runParameters) uses the same call.
		val baseRunConfig = DispatcherRunConfig.fromProperties(properties = { null })

		val fromAbortPath = cell.runParameters()
		val fromLivePath =
			registry.llmRunParameters(
				OllamaExecutorConfig(modelName = cell.model!!, temperature = cell.temperature!!.toFloat()),
				DispatcherRunConfig(
					tickPeriodMs = cell.tickPeriodMs,
					historyN = cell.historyN,
					maxActionsPerTick = cell.maxActionsPerTick,
					inferenceTimeoutSeconds = 90L,
					promptVariant = baseRunConfig.promptVariant
				)
			)

		assertThat(fromAbortPath).isEqualTo(fromLivePath)
	}

	/**
	 * The omitted-axis case (review finding #7, Issue #834): an LLM cell that pins *none* of
	 * model/temperature/inferenceTimeoutSeconds/promptVariant must still agree with the live path,
	 * because both resolve every omitted axis through the same file-tier resolution
	 * ([DispatcherRunConfig.fromProperties] + [OllamaExecutorConfig.default]). The previous test
	 * could not detect this — it pinned model and temperature, the only configuration where the old
	 * hardcoded `""`/`0.0`/`PromptVariant.DEFAULT` abort path coincidentally agreed with the live path.
	 */
	@Test
	@DisplayName("agreement holds for an LLM cell that omits model, temperature, timeout and promptVariant")
	fun agreesWithLiveLlmPathForAllAxesOmitted() {
		val cell =
			SweepCell(
				example = "shuntingLoopAI",
				model = null,
				temperature = null,
				tickPeriodMs = 250L,
				historyN = 5,
				maxActionsPerTick = 2,
				inferenceTimeoutSeconds = null,
				promptVariant = null
			)

		val baseExecutor = OllamaExecutorConfig.default()
		// Mirrors the forked child (no parent -D inheritance) — see agreesWithLiveLlmPath above.
		val baseRunConfig = DispatcherRunConfig.fromProperties(properties = { null })

		val fromAbortPath = cell.runParameters()
		val fromLivePath =
			registry.llmRunParameters(
				OllamaExecutorConfig(modelName = baseExecutor.modelName, temperature = baseExecutor.temperature),
				DispatcherRunConfig(
					tickPeriodMs = cell.tickPeriodMs,
					historyN = cell.historyN,
					maxActionsPerTick = cell.maxActionsPerTick,
					inferenceTimeoutSeconds = baseRunConfig.inferenceTimeoutSeconds,
					promptVariant = baseRunConfig.promptVariant
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
