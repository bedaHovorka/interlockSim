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
import assertk.assertions.doesNotContain
import assertk.assertions.exists
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.ExampleNames
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests the `dispatcherReliabilityReport` CLI path — [main], [reportRootFrom] and
 * [renderDispatcherReliabilityReport] — against an injected temp root, so no test reads or writes
 * [DefaultRunSnapshotStore.DEFAULT_ROOT].
 *
 * @since Issue #1022 (build: track the two permanent sonar.coverage.exclusions)
 */
class DispatcherReliabilityReportTest {
	@Test
	fun `main writes the report under the root given as its first argument`(
		@TempDir tempDir: Path
	) {
		val root = tempDir.resolve("dispatcher-runs")

		main(arrayOf(root.toString()))

		assertThat(root.resolve("report.md")).exists()
	}

	@Test
	fun `reportRootFrom falls back to the production default without an argument`() {
		assertThat(reportRootFrom(emptyArray())).isEqualTo(DefaultRunSnapshotStore.DEFAULT_ROOT)
	}

	@Test
	fun `an empty root still renders one row per dispatcher arm with zero runs`(
		@TempDir tempDir: Path
	) {
		val markdown = Files.readString(renderDispatcherReliabilityReport(tempDir))

		for (arm in DispatcherArm.entries.filter { it.hasDispatcher }) {
			assertThat(markdown).contains("| $arm | 0 |")
		}
		// Issue #676: the arm of runs without a dispatcher is listed only when it has runs.
		assertThat(markdown).doesNotContain("| ${DispatcherArm.NO_DISPATCHER} |")
	}

	@Test
	fun `aggregates a snapshot written under the injected root into the rendered markdown`(
		@TempDir tempDir: Path
	) {
		val root = tempDir.resolve("dispatcher-runs")
		DefaultRunSnapshotStore(root).write(runSnapshot(runId = "temp-root-run"))

		val reportFile = renderDispatcherReliabilityReport(root)

		assertThat(reportFile).isEqualTo(root.resolve("report.md"))
		val markdown = Files.readString(reportFile)
		assertThat(markdown).contains("| ${DispatcherArm.RULE_BASED} | 1 |")
		assertThat(markdown).contains("| ${DispatcherArm.RULE_BASED} | temp-root-run |")
	}

	/**
	 * Issue #1333: the example is read back from the run JSON, so two networks run with equal
	 * parameters get a Railway Outcomes row each; Arm Comparison still pools them.
	 */
	@Test
	fun `two examples with equal parameters read from disk give one Railway Outcomes row each`(
		@TempDir tempDir: Path
	) {
		val root = tempDir.resolve("dispatcher-runs")
		val store = DefaultRunSnapshotStore(root)
		store.write(runSnapshot(runId = "shunting-run", example = ExampleNames.SHUNTING_LOOP))
		store.write(runSnapshot(runId = "cerveny-run", example = ExampleNames.CERVENY_UJEZD))

		val markdown = Files.readString(renderDispatcherReliabilityReport(root))

		assertThat(markdown).contains("| ${DispatcherArm.RULE_BASED} | 2 |")
		val outcomeRows =
			markdown
				.substringAfter("### Railway Outcomes")
				.substringBefore("### Performance")
				.lines()
				.filter { it.startsWith("| ${DispatcherArm.RULE_BASED} ") }
		assertThat(outcomeRows).hasSize(2)
		assertThat(outcomeRows.single { it.contains("| ${ExampleNames.SHUNTING_LOOP} | ") })
			.contains("| ${ExampleNames.SHUNTING_LOOP} | 1 | ")
		assertThat(outcomeRows.single { it.contains("| ${ExampleNames.CERVENY_UJEZD} | ") })
			.contains("| ${ExampleNames.CERVENY_UJEZD} | 1 | ")
	}
}
