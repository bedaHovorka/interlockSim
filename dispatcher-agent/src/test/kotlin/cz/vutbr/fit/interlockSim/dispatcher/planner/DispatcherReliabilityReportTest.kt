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
import assertk.assertions.contains
import assertk.assertions.exists
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests [renderDispatcherReliabilityReport] against an injected temp root, proving that the
 * CLI no longer needs to read [DefaultRunSnapshotStore.DEFAULT_ROOT] directly.
 *
 * @since Issue #1022 (build: track the two permanent sonar.coverage.exclusions)
 */
class DispatcherReliabilityReportTest {
	@Test
	fun `renders the report under the injected root, not the production default`(
		@TempDir tempDir: Path
	) {
		val root = tempDir.resolve("dispatcher-runs")

		val reportFile = renderDispatcherReliabilityReport(root)

		assertThat(reportFile).isEqualTo(root.resolve("report.md"))
		assertThat(reportFile).exists()
		assertThat(Files.exists(DefaultRunSnapshotStore.DEFAULT_ROOT.resolve("report.md")))
			.isEqualTo(false)
	}

	@Test
	fun `aggregates a snapshot written under the injected root into the rendered markdown`(
		@TempDir tempDir: Path
	) {
		val root = tempDir.resolve("dispatcher-runs")
		val store = DefaultRunSnapshotStore(root)
		store.write(snapshot(runId = "temp-root-run"))

		val reportFile = renderDispatcherReliabilityReport(root)

		val markdown = Files.readString(reportFile)
		assertThat(markdown).isNotEmpty()
		assertThat(markdown).contains(DispatcherArm.RULE_BASED.name)
	}

	private fun snapshot(runId: String): DispatcherRunSnapshot {
		val outcomes = TickOutcome.entries.associate { it.name to 0L }.toMutableMap()
		outcomes[TickOutcome.LLM_ACTIONS.name] = 1L

		return DispatcherRunSnapshot(
			runId = runId,
			arm = DispatcherArm.RULE_BASED,
			params =
				RunParameters(
					tickPeriodMs = 500L,
					historyN = 10,
					temperature = 0.0,
					maxActionsPerTick = 3,
					model = "",
					seed = null
				),
			totalTicks = 1L,
			ticksByOutcome = outcomes,
			timeoutNoOpByCause = TimeoutNoOpCause.entries.associate { it.name to 0L },
			llmSuccessRate = 1.0,
			actionableTickRate = 1.0,
			noOpRate = 0.0,
			invalidOutputRate = 0.0,
			repairSuccessRate = 0.0,
			emittedByActionType = emptyMap(),
			rejectionsByCode = emptyMap(),
			applyFailuresByCode = emptyMap(),
			validAt1 = 1.0,
			correctAt1 = null,
			oracleAgreementAt1 = null,
			latencyP50Ms = 100L,
			latencyP95Ms = 200L,
			latencyMaxMs = 300L,
			actionsByAuthor = emptyMap(),
			unattributedApplies = 0L,
			terminalFallbackEngaged = false,
			terminalFallbackTickIndex = null,
			c7Clean = true,
			completedNaturally = true,
			endCause = RunEndCause.NATURAL_COMPLETION
		)
	}
}
