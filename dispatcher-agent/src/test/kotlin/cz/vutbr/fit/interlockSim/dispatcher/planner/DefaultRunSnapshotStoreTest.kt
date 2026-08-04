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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [DefaultRunSnapshotStore].
 *
 * Verifies:
 * - JSON round-trip: `write` → `readAll` → equal snapshot
 * - Schema versioning: snapshots with a higher schemaVersion are silently skipped
 * - Corrupt/malformed JSON files are silently skipped without throwing
 * - Multiple snapshots from different arms land in the correct sub-directories
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
class DefaultRunSnapshotStoreTest {
	@Test
	fun `write and readAll round-trips a snapshot with equality`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		val snapshot = buildSnapshot(runId = "round-trip-001", arm = DispatcherArm.RULE_BASED)

		store.write(snapshot)

		val read = store.readAll(tmpDir)
		assertThat(read).hasSize(1)
		assertThat(read.first()).isEqualTo(snapshot)
	}

	@Test
	fun `write creates a JSON file under arm sub-directory`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		val snapshot = buildSnapshot(runId = "dir-test-001", arm = DispatcherArm.LLM_TOOL_CALLING)

		val written = store.write(snapshot)

		assertThat(Files.exists(written)).isTrue()
		// File must be under <root>/llm_tool_calling/
		val expected = tmpDir.resolve("llm_tool_calling")
		assertThat(written.parent).isEqualTo(expected)
	}

	@Test
	fun `readAll returns empty list when root does not exist`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir.resolve("nonexistent"))
		assertThat(store.readAll(tmpDir.resolve("nonexistent"))).hasSize(0)
	}

	@Test
	fun `readAll skips a future-schema-version file without throwing`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		// Write a snapshot with the current version first (this will be read successfully)
		val goodSnap = buildSnapshot(runId = "good-001")
		store.write(goodSnap)

		// Manually write a file with a future schemaVersion
		val futureJson = buildFutureSchemaJson(runId = "future-001")
		val futureDir = tmpDir.resolve("rule_based")
		Files.createDirectories(futureDir)
		Files.writeString(futureDir.resolve("future.json"), futureJson)

		// readAll should return only the good snapshot; the future one is silently skipped
		val results = store.readAll(tmpDir)
		assertThat(results).hasSize(1)
		assertThat(results.first().runId).isEqualTo("good-001")
	}

	@Test
	fun `readAll skips a corrupt JSON file without throwing`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		// Write a snapshot with valid JSON first (this will be read successfully)
		val goodSnap = buildSnapshot(runId = "good-002")
		store.write(goodSnap)

		// Manually write a file containing malformed JSON (not parseable at all)
		val corruptDir = tmpDir.resolve("rule_based")
		Files.createDirectories(corruptDir)
		Files.writeString(corruptDir.resolve("corrupt.json"), "{ this is not valid JSON ][")

		// readAll must not throw and must return only the good snapshot
		val results = store.readAll(tmpDir)
		assertThat(results).hasSize(1)
		assertThat(results.first().runId).isEqualTo("good-002")
	}

	@Test
	fun `readAll handles multiple snapshots from different arms`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		store.write(buildSnapshot(runId = "a001", arm = DispatcherArm.RULE_BASED))
		store.write(buildSnapshot(runId = "b001", arm = DispatcherArm.LLM_TOOL_CALLING))
		store.write(buildSnapshot(runId = "c001", arm = DispatcherArm.LLM_CONSTRAINED_JSON))

		val results = store.readAll(tmpDir)
		assertThat(results).hasSize(3)
	}

	@Test
	fun `round-trip preserves totalTicks and ticksByOutcome invariant`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		val snapshot = buildSnapshot(runId = "inv-test-001", totalTicks = 5L)

		store.write(snapshot)
		val read = store.readAll(tmpDir).first()

		assertThat(read.ticksByOutcome.values.sum()).isEqualTo(read.totalTicks)
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private fun buildSnapshot(
		runId: String,
		arm: DispatcherArm = DispatcherArm.RULE_BASED,
		totalTicks: Long = 3L
	): DispatcherRunSnapshot {
		val outcomes =
			TickOutcome.entries
				.associateWith { 0L }
				.toMutableMap()
				.also { map ->
					map[TickOutcome.LLM_ACTIONS] = totalTicks
				}.mapKeys { it.key.name }
		return DispatcherRunSnapshot(
			runId = runId,
			arm = arm,
			params =
				RunParameters(
					tickPeriodMs = 500L,
					historyN = 10,
					temperature = 0.0,
					maxActionsPerTick = 3,
					model = "",
					seed = null
				),
			totalTicks = totalTicks,
			ticksByOutcome = outcomes,
			timeoutNoOpByCause = TimeoutNoOpCause.entries.associate { it.name to 0L },
			llmSuccessRate = 1.0,
			noOpRate = 0.0,
			invalidOutputRate = 0.0,
			repairSuccessRate = 0.0,
			emittedByActionType = emptyMap(),
			rejectionsByCode = emptyMap(),
			applyFailuresByCode = emptyMap(),
			validAt1 = 0.0,
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

	/** Builds a raw JSON string with a schemaVersion higher than the current one. */
	private fun buildFutureSchemaJson(runId: String): String {
		val snap = buildSnapshot(runId = runId)
		// encodeDefaults = true is required: schemaVersion equals its default value (1), and
		// kotlinx.serialization omits default-valued fields unless told otherwise. Without this,
		// the field would never appear in the JSON and the replace() below would be a silent no-op.
		return kotlinx.serialization.json
			.Json {
				prettyPrint = true
				encodeDefaults = true
			}.encodeToString(DispatcherRunSnapshot.serializer(), snap)
			.replace("\"schemaVersion\": 1", "\"schemaVersion\": 9999")
	}
}
