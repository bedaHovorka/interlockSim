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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Entry point for the `dispatcherReliabilityReport` Gradle task (SP2c.23, Issue #846).
 *
 * Reads all [DispatcherRunSnapshot] JSON files from
 * `build/reports/dispatcher-runs/` (the [DefaultRunSnapshotStore] root), groups them by
 * [DispatcherArm], aggregates each arm via [RunReportAggregator], and writes the Markdown
 * report to `build/reports/dispatcher-runs/report.md`.
 *
 * No simulation is re-run; the task is purely a read-then-render operation.
 *
 * @since Issue #846 (SP2c.23 — cross-run aggregator + Markdown report + Gradle task)
 */
fun main() {
	val store = DefaultRunSnapshotStore()
	val root = DefaultRunSnapshotStore.DEFAULT_ROOT

	logger.info { "[dispatcherReliabilityReport] reading snapshots from $root" }

	val snapshots = store.readAll(root)

	if (snapshots.isEmpty()) {
		logger.warn { "[dispatcherReliabilityReport] no run snapshots found under $root — report will be empty" }
	} else {
		logger.info { "[dispatcherReliabilityReport] loaded ${snapshots.size} snapshot(s)" }
	}

	val aggregator = RunReportAggregator(store)

	val byArm = snapshots.groupBy { it.arm }

	// Produce one ArmReport per known arm (even if no snapshots exist for that arm yet)
	val reports =
		DispatcherArm.entries.map { arm ->
			aggregator.aggregate(byArm[arm] ?: emptyList()).let {
				// Override the arm field when the snapshot list was empty (aggregate returns RULE_BASED)
				if (it.runCount == 0) it.copy(arm = arm) else it
			}
		}

	val markdown = aggregator.renderMarkdown(reports)

	Files.createDirectories(root)
	val reportFile: Path = root.resolve("report.md")
	Files.writeString(reportFile, markdown)

	logger.info { "[dispatcherReliabilityReport] report written to $reportFile" }
}
