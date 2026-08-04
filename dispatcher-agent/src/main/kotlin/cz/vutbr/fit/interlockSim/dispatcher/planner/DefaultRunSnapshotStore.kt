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
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * File-system backed [RunSnapshotStore].
 *
 * Writes one JSON file per run under:
 * ```
 * <root>/<arm>/<yyyyMMdd-HHmmss>-<runId>.json
 * ```
 *
 * [root] defaults to `build/reports/dispatcher-runs` relative to the working directory.
 * The arm sub-directory is derived from [DispatcherRunSnapshot.arm] so run files are
 * grouped by dispatcher implementation for easy navigation.
 *
 * ## Schema versioning
 *
 * [DispatcherRunSnapshot.schemaVersion] is written to every file.  [readAll] skips files
 * whose `schemaVersion` exceeds [DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION] with a WARN
 * log, keeping aggregation resilient to forward-incompatible schema changes.
 *
 * ## Thread safety
 *
 * Thread-safe: [write] uses atomic `Files.writeString` semantics (write to temp then rename
 * not implemented here; `writeString` is effectively atomic on most file systems for the sizes
 * involved). [readAll] is read-only.
 *
 * @param root The base directory under which arm sub-directories are created.  Created on first
 *   write if absent.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
class DefaultRunSnapshotStore(
	private val root: Path = DEFAULT_ROOT
) : RunSnapshotStore {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Default report root: `build/reports/dispatcher-runs` in the working directory. */
		val DEFAULT_ROOT: Path = Path.of("build", "reports", "dispatcher-runs")

		private val TIMESTAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

		private val json =
			Json {
				prettyPrint = true
				encodeDefaults = true
			}
	}

	override fun write(snapshot: DispatcherRunSnapshot): Path {
		val armDir = root.resolve(snapshot.arm.name.lowercase())
		Files.createDirectories(armDir)

		val timestamp = LocalDateTime.now().format(TIMESTAMP_FMT)
		val fileName = "$timestamp-${snapshot.runId}.json"
		val file = armDir.resolve(fileName)

		val jsonText = json.encodeToString(DispatcherRunSnapshot.serializer(), snapshot)
		Files.writeString(file, jsonText)
		logger.info { "[RunSnapshotStore] written: $file" }
		return file
	}

	override fun readAll(root: Path): List<DispatcherRunSnapshot> {
		if (!Files.exists(root)) return emptyList()

		val results = mutableListOf<DispatcherRunSnapshot>()
		Files.walk(root).use { stream ->
			stream
				.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
				.forEach { file ->
					try {
						val text = Files.readString(file)
						val snapshot = json.decodeFromString(DispatcherRunSnapshot.serializer(), text)
						if (snapshot.schemaVersion > DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION) {
							logger.warn {
								"[RunSnapshotStore] skipping $file: schemaVersion=${snapshot.schemaVersion} " +
									"> current=${DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION}"
							}
						} else {
							results.add(snapshot)
						}
					} catch (e: Exception) {
						logger.warn(e) { "[RunSnapshotStore] skipping $file: parse error — ${e.message}" }
					}
				}
		}
		return results
	}
}
