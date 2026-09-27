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
	fun `an empty root still renders one row per arm with zero runs`(
		@TempDir tempDir: Path
	) {
		val markdown = Files.readString(renderDispatcherReliabilityReport(tempDir))

		for (arm in DispatcherArm.entries) {
			assertThat(markdown).contains("| $arm | 0 |")
		}
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
}
