/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DispatcherRunConfig] (SP2c.24, Issue #847).
 *
 * The property lookup is injected rather than read from real JVM system properties, so these tests
 * mutate no global state and can run in parallel with everything else.
 */
@DisplayName("SP2c.24 — DispatcherRunConfig: per-run knobs from system properties (#847)")
class DispatcherRunConfigTest {
	private fun configOf(vararg entries: Pair<String, String>): DispatcherRunConfig {
		val map = entries.toMap()
		return DispatcherRunConfig.fromProperties { map[it] }
	}

	@Test
	fun `absent properties reproduce the pre-847 defaults`() {
		val config = configOf()

		assertThat(config.model).isNull()
		assertThat(config.temperature).isNull()
		assertThat(config.tickPeriodMs).isEqualTo(DispatcherRunConfig.DEFAULT_TICK_PERIOD_MS)
		assertThat(config.historyN).isEqualTo(DispatcherRunConfig.DEFAULT_HISTORY_N)
		assertThat(config.maxActionsPerTick).isEqualTo(DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK)
		assertThat(config.runId).isNull()
		assertThat(config.runsRoot).isNull()
		// Issue #893 iteration 2: production default stays 30s — only the grid may raise it.
		assertThat(config.inferenceTimeoutSeconds).isEqualTo(DispatcherRunConfig.DEFAULT_INFERENCE_TIMEOUT_SECONDS)
		assertThat(config.inferenceTimeoutSeconds).isEqualTo(30L)
	}

	@Test
	fun `every knob is read from its property`() {
		val config =
			configOf(
				DispatcherRunConfig.PROP_MODEL to "llama3.1:8b",
				DispatcherRunConfig.PROP_TEMPERATURE to "0.5",
				DispatcherRunConfig.PROP_TICK_PERIOD_MS to "250",
				DispatcherRunConfig.PROP_HISTORY_N to "0",
				DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK to "1",
				DispatcherRunConfig.PROP_RUN_ID to "sweep-cell-r01",
				DispatcherRunConfig.PROP_RUNS_ROOT to "build/reports/dispatcher-sweep",
				DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS to "90"
			)

		assertThat(config.model).isEqualTo("llama3.1:8b")
		assertThat(config.temperature).isEqualTo(0.5f)
		assertThat(config.tickPeriodMs).isEqualTo(250L)
		assertThat(config.historyN).isEqualTo(0)
		assertThat(config.maxActionsPerTick).isEqualTo(1)
		assertThat(config.runId).isEqualTo("sweep-cell-r01")
		assertThat(config.runsRoot).isEqualTo("build/reports/dispatcher-sweep")
		assertThat(config.inferenceTimeoutSeconds).isEqualTo(90L)
	}

	@Test
	@DisplayName("an unparseable value falls back to the default instead of failing the run")
	fun unparseableFallsBack() {
		// A malformed -D must not silently become a different measurement, hence the WARN in
		// parseOrDefault; but it must also not throw away an unattended sweep's remaining hours.
		val config =
			configOf(
				DispatcherRunConfig.PROP_TICK_PERIOD_MS to "soon",
				DispatcherRunConfig.PROP_HISTORY_N to "3.5",
				DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK to "0",
				DispatcherRunConfig.PROP_TEMPERATURE to "warm",
				DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS to "soon"
			)

		assertThat(config.tickPeriodMs).isEqualTo(DispatcherRunConfig.DEFAULT_TICK_PERIOD_MS)
		assertThat(config.historyN).isEqualTo(DispatcherRunConfig.DEFAULT_HISTORY_N)
		// 0 is out of range for a cap, not merely unparseable — same treatment.
		assertThat(config.maxActionsPerTick).isEqualTo(DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK)
		assertThat(config.temperature).isNull()
		assertThat(config.inferenceTimeoutSeconds).isEqualTo(DispatcherRunConfig.DEFAULT_INFERENCE_TIMEOUT_SECONDS)
	}

	@Test
	@DisplayName("a non-positive inferenceTimeoutSeconds is rejected as a value, not accepted as 'no timeout'")
	fun nonPositiveInferenceTimeoutRejected() {
		assertThat(configOf(DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS to "0").inferenceTimeoutSeconds)
			.isEqualTo(DispatcherRunConfig.DEFAULT_INFERENCE_TIMEOUT_SECONDS)
		assertThat(configOf(DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS to "-5").inferenceTimeoutSeconds)
			.isEqualTo(DispatcherRunConfig.DEFAULT_INFERENCE_TIMEOUT_SECONDS)
	}

	@Test
	@DisplayName("a blank value is treated as absent, not as a name")
	fun blankIsAbsent() {
		val config =
			configOf(
				DispatcherRunConfig.PROP_MODEL to "  ",
				DispatcherRunConfig.PROP_RUN_ID to "",
				DispatcherRunConfig.PROP_RUNS_ROOT to " "
			)

		assertThat(config.model).isNull()
		assertThat(config.runId).isNull()
		assertThat(config.runsRoot).isNull()
	}

	@Test
	@DisplayName("negative tickPeriodMs is rejected as a value, not accepted as 'no pacing'")
	fun negativeTickPeriodRejected() {
		assertThat(configOf(DispatcherRunConfig.PROP_TICK_PERIOD_MS to "-5").tickPeriodMs)
			.isEqualTo(DispatcherRunConfig.DEFAULT_TICK_PERIOD_MS)
	}
}
