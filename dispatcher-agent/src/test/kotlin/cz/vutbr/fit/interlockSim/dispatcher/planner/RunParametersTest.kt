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
 * Unit tests for [RunParameters]' added fields: [RunParameters.inferenceTimeoutSeconds] and
 * [RunParameters.promptVariant] (Issue #834, SP2c.11), plus [RunParameters.circuitBreakerFailureThreshold]
 * and [RunParameters.circuitBreakerCooldownSeconds] (Issue #1058).
 *
 * A run JSON's [RunParameters] is what a report groups and labels runs by
 * ([RunReportAggregator.appendParameterSweep]), so these tests cover both directions: a fully
 * populated [RunParameters] round-trips its new fields intact, and a JSON document that predates
 * them (kotlinx.serialization decoding an absent key) still decodes — pinning the "no defaults on
 * the original six fields" hazard documented on [RunParameters] itself.
 *
 * @since Issue #834 (SP2c.11 — inferenceTimeoutSeconds/promptVariant threading);
 *   circuit-breaker fields added in Issue #1058
 */
@DisplayName("RunParameters — inferenceTimeoutSeconds, promptVariant (#834) and circuit-breaker knobs (#1058)")
class RunParametersTest {
	private val json =
		Json {
			prettyPrint = true
			encodeDefaults = true
		}

	private fun paramsWith(
		inferenceTimeoutSeconds: Long = 90L,
		promptVariant: String = "control",
		circuitBreakerFailureThreshold: Int = LlmCircuitBreaker.DEFAULT_FAILURE_THRESHOLD,
		circuitBreakerCooldownSeconds: Double = LlmCircuitBreaker.DEFAULT_COOLDOWN_SECONDS
	): RunParameters =
		RunParameters(
			tickPeriodMs = 500L,
			historyN = 10,
			temperature = 0.5,
			maxActionsPerTick = 3,
			model = "qwen2.5:7b-instruct",
			seed = null,
			inferenceTimeoutSeconds = inferenceTimeoutSeconds,
			promptVariant = promptVariant,
			circuitBreakerFailureThreshold = circuitBreakerFailureThreshold,
			circuitBreakerCooldownSeconds = circuitBreakerCooldownSeconds
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

	// ── Circuit-breaker knobs (Issue #1058) ────────────────────────────────────

	@Test
	@DisplayName("serialization round-trips the circuit-breaker fields intact (#1058)")
	fun roundTripsCircuitBreakerFields() {
		val params =
			paramsWith(
				circuitBreakerFailureThreshold = 5,
				circuitBreakerCooldownSeconds = 120.0
			)

		val encoded = json.encodeToString(RunParameters.serializer(), params)
		val decoded = json.decodeFromString(RunParameters.serializer(), encoded)

		assertThat(decoded).isEqualTo(params)
		assertThat(decoded.circuitBreakerFailureThreshold).isEqualTo(5)
		assertThat(decoded.circuitBreakerCooldownSeconds).isEqualTo(120.0)
	}

	@Test
	@DisplayName("two cells differing only in a circuit-breaker knob are distinguishable RunParameters (#1058)")
	fun distinguishesCellsByCircuitBreakerKnobs() {
		// #834's run-identity reasoning, applied to the breaker: a run whose breaker opened on a
		// 120s cooldown is a different experiment from one that probed every 30s, so the report
		// must not collapse the two into one cell.
		val short = paramsWith(circuitBreakerCooldownSeconds = 30.0)
		val long = paramsWith(circuitBreakerCooldownSeconds = 120.0)

		assertThat(short).isNotEqualTo(long)

		val strict = paramsWith(circuitBreakerFailureThreshold = 3)
		val loose = paramsWith(circuitBreakerFailureThreshold = 8)

		assertThat(strict).isNotEqualTo(loose)
	}

	@Test
	@DisplayName("a run JSON predating the circuit-breaker fields still decodes, via their defaults (#1058)")
	fun decodesAbsentCircuitBreakerFieldsToDefaults() {
		// Exactly the params object shape written before Issue #1058 added the two breaker keys —
		// it already carries the #834 fields, so only the breaker axes are absent.
		val preBreakerJson =
			"""
			{
				"tickPeriodMs": 500,
				"historyN": 10,
				"temperature": 0.0,
				"maxActionsPerTick": 3,
				"model": "qwen2.5:7b-instruct",
				"seed": null,
				"inferenceTimeoutSeconds": 90,
				"promptVariant": "BASELINE"
			}
			""".trimIndent()

		val decoded = json.decodeFromString(RunParameters.serializer(), preBreakerJson)

		assertThat(decoded.circuitBreakerFailureThreshold)
			.isEqualTo(LlmCircuitBreaker.DEFAULT_FAILURE_THRESHOLD)
		assertThat(decoded.circuitBreakerCooldownSeconds)
			.isEqualTo(LlmCircuitBreaker.DEFAULT_COOLDOWN_SECONDS)
	}

	@Test
	@DisplayName("the Kotlin-level circuit-breaker defaults match LlmCircuitBreaker's own defaults (#1058)")
	fun kotlinCircuitBreakerDefaultsMatchBreakerDefaults() {
		val params =
			RunParameters(
				tickPeriodMs = 0L,
				historyN = 0,
				temperature = 0.0,
				maxActionsPerTick = 1,
				model = "",
				seed = null
			)

		assertThat(params.circuitBreakerFailureThreshold)
			.isEqualTo(LlmCircuitBreaker.DEFAULT_FAILURE_THRESHOLD)
		assertThat(params.circuitBreakerCooldownSeconds)
			.isEqualTo(LlmCircuitBreaker.DEFAULT_COOLDOWN_SECONDS)
	}
}
