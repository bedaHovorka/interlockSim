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

import java.nio.file.Path

/**
 * Persistence contract for [DispatcherRunSnapshot] values.
 *
 * The default implementation ([DefaultRunSnapshotStore]) writes one JSON file per run under:
 * ```
 * build/reports/dispatcher-runs/<arm>/<yyyyMMdd-HHmmss>-<runId>.json
 * ```
 *
 * Schema is versioned via [DispatcherRunSnapshot.schemaVersion] so the SP2c.23 aggregator can
 * handle older JSON files without crashing.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
interface RunSnapshotStore {
	/**
	 * Serialises [snapshot] to a JSON file and returns the [Path] to the written file.
	 *
	 * @param snapshot The terminal run snapshot to persist.
	 * @return Absolute path to the written JSON file.
	 */
	fun write(snapshot: DispatcherRunSnapshot): Path

	/**
	 * Reads all [DispatcherRunSnapshot] JSON files found under [root] (recursively).
	 *
	 * Files that cannot be parsed (wrong schema version, corrupt JSON) are silently skipped
	 * with a WARN log so a single bad file does not block aggregation of the rest.
	 *
	 * @param root Directory to search. Typically the `dispatcher-runs/` report directory.
	 * @return All successfully parsed snapshots, in no guaranteed order.
	 */
	fun readAll(root: Path): List<DispatcherRunSnapshot>
}
