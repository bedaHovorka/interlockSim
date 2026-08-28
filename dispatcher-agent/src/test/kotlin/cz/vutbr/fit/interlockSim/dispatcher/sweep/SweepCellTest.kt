/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.sweep

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
import cz.vutbr.fit.interlockSim.dispatcher.agents.PromptVariant
import cz.vutbr.fit.interlockSim.dispatcher.di.ruleBasedRunParameters
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SweepCell.runParameters] (Issue #834, SP2c.11).
 *
 * The property under test is agreement: [SweepCell.runParameters] is used for a killed/aborted
 * run's snapshot, and the live recording path is used for one that finishes naturally. If the two
 * ever produced different [RunParameters] for the same cell,
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.RunReportAggregator.appendParameterSweep]'s
 * `groupBy { it.params }` would split a single grid cell's runs into two report rows, silently
 * halving each row's sample size.
 *
 * @since Issue #834 (SP2c.11 — inferenceTimeoutSeconds/promptVariant threading)
 */
@DisplayName("SP2c.11 — SweepCell.runParameters(): agreement with the live rule-based path (#834)")
class SweepCellTest {
	private fun llmCell(promptVariant: PromptVariant? = null): SweepCell =
		SweepCell(
			example = "shuntingLoopAI",
			model = "qwen2.5:7b-instruct",
			temperature = 0.5,
			tickPeriodMs = 250L,
			historyN = 5,
			maxActionsPerTick = 2,
			inferenceTimeoutSeconds = 90L,
			promptVariant = promptVariant
		)

	private fun ruleBasedCell(inferenceTimeoutSeconds: Long? = null): SweepCell =
		SweepCell(
			example = "shuntingLoop",
			model = null,
			temperature = null,
			tickPeriodMs = 250L,
			historyN = 5,
			maxActionsPerTick = 2,
			inferenceTimeoutSeconds = inferenceTimeoutSeconds
		)

	@Test
	@DisplayName("a null inferenceTimeoutSeconds records KoogAgentPlanAdapter's own default, not null")
	fun nullInferenceTimeoutRecordsTheAdapterDefault() {
		val params = ruleBasedCell(inferenceTimeoutSeconds = null).runParameters()

		assertThat(params.inferenceTimeoutSeconds).isEqualTo(KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS)
	}

	@Test
	@DisplayName("a non-null inferenceTimeoutSeconds is recorded as-is")
	fun explicitInferenceTimeoutIsRecorded() {
		val params = ruleBasedCell(inferenceTimeoutSeconds = 90L).runParameters()

		assertThat(params.inferenceTimeoutSeconds).isEqualTo(90L)
	}

	/**
	 * The `""` sentinel means "this run assembled no prompt at all", and Task 11 (#834) moved
	 * that decision off `model == null` and onto [SweepCell.arm] — the same signal the live
	 * recorder is armed from. A rule-based cell that happens to pin a model must still record
	 * the no-prompt sentinel, which is exactly what the old stand-in got wrong.
	 */
	@Test
	@DisplayName("a rule-based cell records promptVariant as \"\", even when it pins a model")
	fun ruleBasedCellRecordsEmptyPromptVariant() {
		assertThat(ruleBasedCell().runParameters().promptVariant).isEqualTo("")
		assertThat(ruleBasedCell().runParameters().model).isEqualTo("")

		val ruleBasedWithModel =
			SweepCell(
				example = "shuntingLoop",
				model = "qwen2.5:7b-instruct",
				temperature = null,
				tickPeriodMs = 250L,
				historyN = 5,
				maxActionsPerTick = 2
			)

		assertThat(ruleBasedWithModel.runParameters().promptVariant).isEqualTo("")
	}

	/**
	 * An LLM cell that names no variant resolves [DispatcherRunConfig.promptVariant] through the
	 * same file-tier resolution the live path uses (`DispatcherRunConfig.fromProperties()`, which
	 * reads the committed `dispatcher-defaults.properties`), so the recorded value is the variant
	 * the forked child actually ran with — not a hardcoded [PromptVariant.DEFAULT] that the committed
	 * file has since diverged from (review finding #7, Issue #834). The shipped default is `REVISED`,
	 * so this asserts agreement with the live resolution rather than pinning a constant that would
	 * break the moment the file's committed default changes.
	 */
	@Test
	@DisplayName("an LLM cell with no variant records the file-tier-resolved variant, not a hardcoded constant")
	fun llmCellRecordsFileTierPromptVariant() {
		val params = llmCell().runParameters()

		val liveResolved = DispatcherRunConfig.fromProperties().promptVariant.name
		assertThat(params.promptVariant).isEqualTo(liveResolved)
		assertThat(params.promptVariant).isNotEqualTo(RunParameters.DEFAULT_PROMPT_VARIANT)
	}

	@Test
	@DisplayName("an LLM cell that names a variant records that variant")
	fun llmCellRecordsItsExplicitPromptVariant() {
		val params = llmCell(promptVariant = PromptVariant.REVISED).runParameters()

		assertThat(params.promptVariant).isEqualTo("REVISED")
	}

	/**
	 * The slug is the resumption key, so two cells differing only in variant must not collide:
	 * a colliding id would make the driver skip the second arm as already-run, and the A/B
	 * comparison would silently be a comparison of one arm with itself.
	 */
	@Test
	@DisplayName("cells differing only in promptVariant get distinct slugs and run ids")
	fun promptVariantDiscriminatesTheSlug() {
		val baseline = llmCell(promptVariant = PromptVariant.BASELINE)
		val revised = llmCell(promptVariant = PromptVariant.REVISED)

		assertThat(baseline.slug).isNotEqualTo(revised.slug)
		assertThat(baseline.runId(1)).isNotEqualTo(revised.runId(1))
		assertThat(llmCell().slug).contains("pv-default")
		assertThat(revised.slug).contains("pv-REVISED")
	}

	/**
	 * `null` means "leave the run config's own resolution in place", so no `-D` may be emitted
	 * for it — emitting [PromptVariant.DEFAULT] explicitly would override a committed
	 * `dispatcher-defaults.properties` value the grid never asked to override. Mirrors the
	 * identical handling of `model`/`temperature`/`inferenceTimeoutSeconds`.
	 */
	@Test
	@DisplayName("promptVariant reaches the forked run as a -D only when the cell names one")
	fun promptVariantIsPassedAsASystemPropertyOnlyWhenSet() {
		val unset = llmCell().systemProperties(runId = "r", runsRoot = "/tmp")
		val set =
			llmCell(promptVariant = PromptVariant.REVISED)
				.systemProperties(runId = "r", runsRoot = "/tmp")

		assertThat(unset.containsKey(DispatcherRunConfig.PROP_PROMPT_VARIANT)).isEqualTo(false)
		assertThat(set[DispatcherRunConfig.PROP_PROMPT_VARIANT]).isEqualTo("REVISED")
	}

	/**
	 * The abort-snapshot path ([SweepCell.runParameters]) must agree with the live recording path
	 * ([ruleBasedRunParameters], what the DI-built recorder actually writes) for a rule-based cell
	 * with equivalent settings — see this class's KDoc for why disagreement here is a report bug,
	 * not just a cosmetic mismatch.
	 */
	@Test
	@DisplayName("SweepCell.runParameters() agrees with the live rule-based DI path for an equivalent cell")
	fun agreesWithLiveRuleBasedPath() {
		val cell = ruleBasedCell(inferenceTimeoutSeconds = 90L)

		val fromAbortPath = cell.runParameters()
		val fromLivePath =
			ruleBasedRunParameters(
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
	@DisplayName("agreement holds for a default (null) inferenceTimeoutSeconds too")
	fun agreesWithLiveRuleBasedPathAtDefaultTimeout() {
		val cell = ruleBasedCell(inferenceTimeoutSeconds = null)

		val fromAbortPath = cell.runParameters()
		val fromLivePath =
			ruleBasedRunParameters(
				DispatcherRunConfig(
					tickPeriodMs = cell.tickPeriodMs,
					historyN = cell.historyN,
					maxActionsPerTick = cell.maxActionsPerTick
				)
			)

		assertThat(fromAbortPath).isEqualTo(fromLivePath)
	}
}
