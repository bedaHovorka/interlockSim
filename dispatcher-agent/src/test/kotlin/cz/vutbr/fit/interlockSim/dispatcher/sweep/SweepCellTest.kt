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
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.dispatcher.DispatcherRunConfig
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

	@Test
	@DisplayName("a rule-based cell (model == null) records promptVariant as \"\", mirroring model")
	fun ruleBasedCellRecordsEmptyPromptVariant() {
		val params = ruleBasedCell().runParameters()

		assertThat(params.promptVariant).isEqualTo("")
		assertThat(params.model).isEqualTo("")
	}

	@Test
	@DisplayName("an LLM cell (model != null) records the untracked-variant default, not the rule-based sentinel")
	fun llmCellRecordsDefaultPromptVariant() {
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

		val params = cell.runParameters()

		assertThat(params.promptVariant).isEqualTo(RunParameters.DEFAULT_PROMPT_VARIANT)
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
