/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEqualTo
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RunParameters]' Issue #834 (SP2c.11) additions: [RunParameters.inferenceTimeoutSeconds]
 * and [RunParameters.promptVariant].
 *
 * A run JSON's [RunParameters] is what a report groups and labels runs by
 * ([RunReportAggregator.appendParameterSweep]), so these tests cover both directions: a fully
 * populated [RunParameters] round-trips its new fields intact, and a JSON document that predates
 * them (kotlinx.serialization decoding an absent key) still decodes — pinning the "no defaults on
 * the original six fields" hazard documented on [RunParameters] itself.
 *
 * @since Issue #834 (SP2c.11 — inferenceTimeoutSeconds/promptVariant threading)
 */
@DisplayName("#834 (SP2c.11) — RunParameters: inferenceTimeoutSeconds + promptVariant")
class RunParametersTest {
	private val json =
		Json {
			prettyPrint = true
			encodeDefaults = true
		}

	private fun paramsWith(
		inferenceTimeoutSeconds: Long = 90L,
		promptVariant: String = "control"
	): RunParameters =
		RunParameters(
			tickPeriodMs = 500L,
			historyN = 10,
			temperature = 0.5,
			maxActionsPerTick = 3,
			model = "qwen2.5:7b-instruct",
			seed = null,
			inferenceTimeoutSeconds = inferenceTimeoutSeconds,
			promptVariant = promptVariant
		)

	@Test
	@DisplayName("serialization round-trips a fully populated RunParameters, including the new fields")
	fun roundTripsBothNewFields() {
		val params = paramsWith(inferenceTimeoutSeconds = 90L, promptVariant = "control")

		val encoded = json.encodeToString(RunParameters.serializer(), params)
		val decoded = json.decodeFromString(RunParameters.serializer(), encoded)

		assertThat(decoded).isEqualTo(params)
		assertThat(decoded.inferenceTimeoutSeconds).isEqualTo(90L)
		assertThat(decoded.promptVariant).isEqualTo("control")
	}

	@Test
	@DisplayName("two cells differing only in inferenceTimeoutSeconds are distinguishable RunParameters")
	fun distinguishesCellsByInferenceTimeout() {
		// This is the gap #834 closes: PR #896 measured the LLM arm's success rate hinging on
		// this value, so two runs that only differ here must not collapse into one report cell.
		val thirty = paramsWith(inferenceTimeoutSeconds = 30L)
		val ninety = paramsWith(inferenceTimeoutSeconds = 90L)

		assertThat(thirty).isNotEqualTo(ninety)
	}

	@Test
	@DisplayName("a run JSON predating inferenceTimeoutSeconds/promptVariant still decodes, via their defaults")
	fun decodesAbsentNewFieldsToDefaults() {
		// Exactly the params object shape written before Issue #834 (SP2c.11) added the two new
		// keys — no inferenceTimeoutSeconds, no promptVariant.
		val legacyParamsJson =
			"""
			{
				"tickPeriodMs": 500,
				"historyN": 10,
				"temperature": 0.0,
				"maxActionsPerTick": 3,
				"model": "",
				"seed": null
			}
			""".trimIndent()

		val decoded = json.decodeFromString(RunParameters.serializer(), legacyParamsJson)

		assertThat(decoded.inferenceTimeoutSeconds).isEqualTo(KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS)
		assertThat(decoded.promptVariant).isEqualTo(RunParameters.DEFAULT_PROMPT_VARIANT)
	}

	@Test
	@DisplayName("DEFAULT_PROMPT_VARIANT is distinct from the rule-based arm's empty-string sentinel")
	fun defaultPromptVariantIsNotEmpty() {
		// The whole point of keeping these apart: "" means "no prompt at all" (rule-based arm,
		// mirrors RunParameters.model); DEFAULT_PROMPT_VARIANT means "had a prompt, untracked
		// variant". Collapsing them would make a report unable to tell the two cases apart.
		assertThat(RunParameters.DEFAULT_PROMPT_VARIANT.isEmpty()).isFalse()
	}

	@Test
	@DisplayName("the Kotlin-level default for inferenceTimeoutSeconds matches KoogAgentPlanAdapter's own default")
	fun kotlinDefaultMatchesAdapterDefault() {
		val params =
			RunParameters(
				tickPeriodMs = 0L,
				historyN = 0,
				temperature = 0.0,
				maxActionsPerTick = 1,
				model = "",
				seed = null
			)

		assertThat(params.inferenceTimeoutSeconds).isEqualTo(KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS)
		assertThat(params.promptVariant).isEqualTo(RunParameters.DEFAULT_PROMPT_VARIANT)
	}
}
