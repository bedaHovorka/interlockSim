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
import cz.vutbr.fit.interlockSim.dispatcher.agents.PromptVariant
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

	/**
	 * Like [configOf] but also injects the committed-file tier, so #834's three-way precedence
	 * (system property > file > code constant) can be tested without touching the real classpath
	 * resource.
	 */
	private fun configOf(
		fileProperties: Map<String, String>,
		vararg systemProperties: Pair<String, String>
	): DispatcherRunConfig {
		val sysMap = systemProperties.toMap()
		return DispatcherRunConfig.fromProperties(
			fileProperties = { fileProperties[it] },
			properties = { sysMap[it] }
		)
	}

	@Test
	fun `absent properties reproduce the pre-847 defaults`() {
		// Pins the committed-file tier to empty explicitly (#834 fix-round-1 review finding):
		// plain configOf() would let fileProperties silently default to the real shipped
		// dispatcher-defaults.properties resource, which coincidentally matches today's compiled
		// constants but stops doing so the moment #834 Task 14 commits the sweep's chosen values —
		// at which point this test's name ("pre-847 defaults", i.e. the compiled constants) would
		// stop matching what it actually verifies. Pinning fileProperties to empty here makes the
		// code-constant fallback path unambiguous and independent of the file's contents.
		val config = configOf(emptyMap())

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

	// #834 (SP2c.11): committed-file tier tests. The seam mirrors the existing system-property
	// seam above so none of these mutate real JVM state or the real classpath resource.

	// ── Issue #834 (SP2c.11): the prompt-variant axis ─────────────────────────────────────

	/**
	 * The seam's default must reproduce today's behaviour: an unconfigured run keeps the
	 * prompt PR #896 shipped, so adding the axis changes no run that does not ask to change.
	 */
	@Test
	@DisplayName("#834: an unset promptVariant is PromptVariant.DEFAULT (BASELINE)")
	fun promptVariantDefaultsToBaseline() {
		assertThat(configOf(emptyMap()).promptVariant).isEqualTo(PromptVariant.BASELINE)
	}

	@Test
	@DisplayName("#834: promptVariant resolves system property > committed file > code constant")
	fun promptVariantFollowsThePrecedenceChain() {
		val fromProperty =
			configOf(
				mapOf(DispatcherRunConfig.PROP_PROMPT_VARIANT to "BASELINE"),
				DispatcherRunConfig.PROP_PROMPT_VARIANT to "REVISED"
			)
		val fromFile = configOf(mapOf(DispatcherRunConfig.PROP_PROMPT_VARIANT to "REVISED"))

		assertThat(fromProperty.promptVariant).isEqualTo(PromptVariant.REVISED)
		assertThat(fromFile.promptVariant).isEqualTo(PromptVariant.REVISED)
	}

	/**
	 * Same discipline as every other knob: a malformed value is logged and ignored, never an
	 * aborted run. A typo in a forked-JVM `-D` must not cost an unattended sweep — the sweep
	 * *grid* is where an unknown variant name fails loudly instead (`SweepAxes`'s `init`).
	 */
	@Test
	@DisplayName("#834: an unparseable promptVariant WARNs and falls back rather than failing the run")
	fun unparseablePromptVariantFallsBack() {
		val fromProperty = configOf(DispatcherRunConfig.PROP_PROMPT_VARIANT to "REVISD")
		val fromFile = configOf(mapOf(DispatcherRunConfig.PROP_PROMPT_VARIANT to "not-a-variant"))
		val blank = configOf(mapOf(), DispatcherRunConfig.PROP_PROMPT_VARIANT to "   ")

		assertThat(fromProperty.promptVariant).isEqualTo(DispatcherRunConfig.DEFAULT_PROMPT_VARIANT)
		assertThat(fromFile.promptVariant).isEqualTo(DispatcherRunConfig.DEFAULT_PROMPT_VARIANT)
		assertThat(blank.promptVariant).isEqualTo(DispatcherRunConfig.DEFAULT_PROMPT_VARIANT)
	}

	@Test
	@DisplayName("#834: promptVariant is a recognized key of the committed defaults resource")
	fun promptVariantIsARecognizedFileKey() {
		assertThat(DispatcherDefaultsResource.RECOGNIZED_KEYS.contains(DispatcherRunConfig.PROP_PROMPT_VARIANT))
			.isEqualTo(true)
	}

	@Test
	@DisplayName("#834: a system property beats a committed-file value for the same key")
	fun systemPropertyBeatsFile() {
		val config =
			configOf(
				mapOf(DispatcherRunConfig.PROP_HISTORY_N to "5"),
				DispatcherRunConfig.PROP_HISTORY_N to "7"
			)

		assertThat(config.historyN).isEqualTo(7)
	}

	@Test
	@DisplayName("#834: a committed-file value beats the code fallback constant")
	fun fileBeatsCodeFallback() {
		val config =
			configOf(
				mapOf(
					DispatcherRunConfig.PROP_TICK_PERIOD_MS to "500",
					DispatcherRunConfig.PROP_HISTORY_N to "5",
					DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK to "2",
					DispatcherRunConfig.PROP_INFERENCE_TIMEOUT_SECONDS to "60"
				)
			)

		assertThat(config.tickPeriodMs).isEqualTo(500L)
		assertThat(config.historyN).isEqualTo(5)
		assertThat(config.maxActionsPerTick).isEqualTo(2)
		assertThat(config.inferenceTimeoutSeconds).isEqualTo(60L)
	}

	@Test
	@DisplayName("#834: an absent committed file falls back to the code constant, no exception")
	fun absentFileFallsBackToCodeConstant() {
		val config = configOf(emptyMap())

		assertThat(config.tickPeriodMs).isEqualTo(DispatcherRunConfig.DEFAULT_TICK_PERIOD_MS)
		assertThat(config.historyN).isEqualTo(DispatcherRunConfig.DEFAULT_HISTORY_N)
		assertThat(config.maxActionsPerTick).isEqualTo(DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK)
		assertThat(config.inferenceTimeoutSeconds).isEqualTo(DispatcherRunConfig.DEFAULT_INFERENCE_TIMEOUT_SECONDS)
	}

	@Test
	@DisplayName("#834: a malformed committed-file value falls back to the code constant, no exception")
	fun malformedFileValueFallsBackToCodeConstant() {
		val config =
			configOf(
				mapOf(
					DispatcherRunConfig.PROP_HISTORY_N to "three",
					DispatcherRunConfig.PROP_MAX_ACTIONS_PER_TICK to "0"
				)
			)

		assertThat(config.historyN).isEqualTo(DispatcherRunConfig.DEFAULT_HISTORY_N)
		// 0 is out of range for a cap, not merely unparseable — same treatment as the -D case.
		assertThat(config.maxActionsPerTick).isEqualTo(DispatcherRunConfig.DEFAULT_MAX_ACTIONS_PER_TICK)
	}

	@Test
	@DisplayName(
		"#834 regression lock: the shipped properties file yields exactly the values the sweep chose"
	)
	fun shippedFileYieldsChosenDefaults() {
		// No system properties, no injected file map: exercises the real classpath resource end to
		// end. The lock still forbids silent drift; its expectation for historyN moved once, when
		// #834's sweep chose 0 over 3 (c7Clean 8/10 vs 0/10, across all four factorial cells).
		// See docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md §12.1.
		val config = DispatcherRunConfig.fromProperties()

		assertThat(config.tickPeriodMs).isEqualTo(0L)
		assertThat(config.historyN).isEqualTo(0)
		assertThat(config.maxActionsPerTick).isEqualTo(3)
		assertThat(config.inferenceTimeoutSeconds).isEqualTo(30L)
	}
}
