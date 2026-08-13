/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.di

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ruleBasedRunParameters], the mapping the `scoped<DispatcherRunRecorder>`
 * binding in [dispatcherAgentModule] uses to record what a rule-based-arm run was actually given
 * (Issue #834, SP2c.11).
 *
 * [DispatcherRunConfig] never needs Koin to construct, so — following the same pattern
 * `DispatcherRunConfigTest` uses for property parsing — these tests inject the property lookup
 * directly into [DispatcherRunConfig.fromProperties] rather than mutating real JVM system
 * properties, and exercise the exact function the DI binding calls without needing a live Koin
 * scope at all.
 *
 * @since Issue #834 (SP2c.11 — inferenceTimeoutSeconds/promptVariant threading)
 */
@DisplayName("SP2c.11 — ruleBasedRunParameters: DispatcherRunConfig -> RunParameters (#834)")
class RuleBasedRunParametersTest {
	private fun configOf(vararg entries: Pair<String, String>): DispatcherRunConfig {
		val map = entries.toMap()
		return DispatcherRunConfig.fromProperties(fileProperties = { null }, properties = { map[it] })
	}

	@Test
	@DisplayName("inferenceTimeoutSeconds is carried through from DispatcherRunConfig's default")
	fun carriesDefaultInferenceTimeout() {
		val params = ruleBasedRunParameters(configOf())

		assertThat(params.inferenceTimeoutSeconds).isEqualTo(DispatcherRunConfig.DEFAULT_INFERENCE_TIMEOUT_SECONDS)
	}

	@Test
	@DisplayName("inferenceTimeoutSeconds is carried through when set to a non-default value via -D")
	fun carriesNonDefaultInferenceTimeoutFromSystemProperty() {
		val runConfig = configOf(DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS to "90")

		val params = ruleBasedRunParameters(runConfig)

		assertThat(params.inferenceTimeoutSeconds).isEqualTo(90L)
	}

	@Test
	@DisplayName("tickPeriodMs, historyN and maxActionsPerTick are also carried through from DispatcherRunConfig")
	fun carriesOtherLiveParameters() {
		val runConfig =
			configOf(
				DispatcherRunConfig.PROP_TICK_PERIOD_MS to "250",
				DispatcherRunConfig.PROP_HISTORY_N to "5",
				DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK to "2"
			)

		val params = ruleBasedRunParameters(runConfig)

		assertThat(params.tickPeriodMs).isEqualTo(250L)
		assertThat(params.historyN).isEqualTo(5)
		assertThat(params.maxActionsPerTick).isEqualTo(2)
	}

	@Test
	@DisplayName("model and promptVariant record the rule-based arm's 'no prompt at all' sentinel, not the LLM default")
	fun recordsRuleBasedSentinels() {
		val params = ruleBasedRunParameters(configOf())

		assertThat(params.model).isEqualTo("")
		assertThat(params.promptVariant).isEqualTo("")
		assertThat(params.temperature).isEqualTo(0.0)
	}
}
