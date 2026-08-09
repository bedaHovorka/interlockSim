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
import java.nio.file.StandardCopyOption
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
 * *Older* files are read, not skipped: a schema-version-1 document (written before SP2c.11 added
 * [DispatcherRunSnapshot.railwayOutcome]) decodes because the added field has a default, and it
 * loads with its railway figures absent — the truth about a run that never measured them. See
 * [DispatcherRunSnapshot]'s "Schema versioning" section for the full decision and its limits.
 *
 * ## Thread safety
 *
 * Thread-safe: [write] writes to a temp file then atomically moves it into place
 * ([java.nio.file.StandardCopyOption.ATOMIC_MOVE]), so a crash mid-write leaves no partial
 * JSON file that [readAll] would silently skip. [readAll] is read-only.
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
		// Sanitise runId before interpolating it into the filename: the default producer is a
		// UUID (safe), but a custom runId containing "/" or ".." would otherwise escape the
		// arm directory. Defence in depth.
		val safeRunId = snapshot.runId.replace(Regex("[^A-Za-z0-9._-]"), "_")
		val fileName = "$timestamp-$safeRunId.json"
		val file = armDir.resolve(fileName)

		val jsonText = json.encodeToString(DispatcherRunSnapshot.serializer(), snapshot)
		// Write to a temp file then atomically move into place so a crash mid-write leaves no
		// partial JSON file that readAll would silently skip. ATOMIC_MOVE guarantees readers
		// never observe a half-written file.
		val tmp = Files.createTempFile(armDir, "run-", ".json.tmp")
		try {
			Files.writeString(tmp, jsonText)
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
		} catch (e: Exception) {
			Files.deleteIfExists(tmp)
			throw e
		}
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
