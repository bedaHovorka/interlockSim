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
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.serialization.json.Json
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
 * - Backward compatibility: a schema-version-1 file (written before SP2c.11 added
 *   `railwayOutcome`) is still read, with its railway figures absent rather than zero
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

	/**
	 * Schema-version-1 compatibility (Issue #834, SP2c.11).
	 *
	 * v1 predates the `railwayOutcome` field. Because that field carries a default
	 * ([RailwayOutcome.UNMEASURED]), kotlinx.serialization fills it in and a v1 file stays
	 * readable — which is the whole compatibility decision, so it is pinned here against a
	 * literal v1 document rather than against anything the current code can produce.
	 *
	 * The v1 run's railway figures come back **absent**, not zero: nothing measured them, and a
	 * zero would let an old run be ranked alongside a genuinely-idle new one.
	 *
	 * The `params` object in [SCHEMA_V1_JSON] also predates [RunParameters.inferenceTimeoutSeconds]
	 * and [RunParameters.promptVariant] (added in this same #834/SP2c.11 wave) — this is the
	 * pinning test for the hazard documented on [RunParameters] itself: because both fields carry
	 * defaults, this literal pre-#834 document still decodes instead of being silently dropped by
	 * [DefaultRunSnapshotStore.readAll]'s WARN-and-skip catch-all.
	 */
	@Test
	fun `readAll still reads a schema-version-1 file and reports its railway figures as absent`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		val armDir = tmpDir.resolve("rule_based")
		Files.createDirectories(armDir)
		Files.writeString(armDir.resolve("legacy-v1.json"), SCHEMA_V1_JSON)

		val results = store.readAll(tmpDir)

		assertThat(results).hasSize(1)
		val legacy = results.first()
		assertThat(legacy.runId).isEqualTo("legacy-v1-001")
		// The file's own version is preserved, not rewritten to the current one.
		assertThat(legacy.schemaVersion).isEqualTo(1)
		assertThat(legacy.totalTicks).isEqualTo(3L)
		assertThat(legacy.railwayOutcome).isEqualTo(RailwayOutcome.UNMEASURED)
		assertThat(legacy.railwayOutcome.trainsEntered).isNull()
		assertThat(legacy.railwayOutcome.blockTransitions).isNull()
		// #834/SP2c.11: params.inferenceTimeoutSeconds/promptVariant are absent from the literal
		// JSON above and must decode to their defaults, not fail the whole file.
		assertThat(legacy.params.inferenceTimeoutSeconds).isEqualTo(KoogAgentPlanAdapter.DEFAULT_TIMEOUT_SECONDS)
		assertThat(legacy.params.promptVariant).isEqualTo(RunParameters.DEFAULT_PROMPT_VARIANT)
		// Review finding #3 (Issue #834): the latency fields widened from Long to Long?; a v1 file
		// that stored concrete values must still decode them (not collapse to null), and a stored 0
		// would decode to Long?(0) — absent is not zero now works both directions.
		assertThat(legacy.latencyP50Ms).isEqualTo(100L)
		assertThat(legacy.latencyP95Ms).isEqualTo(200L)
		assertThat(legacy.latencyMaxMs).isEqualTo(300L)
	}

	/**
	 * Schema-version-3 backward compatibility for the `endCause` enum (Issue #909, SP2c).
	 *
	 * v4 added [RunEndCause.TERMINATED_EARLY] but kept [RunEndCause.TIMEOUT_ABORT]; existing persisted
	 * v3 run JSONs that stored `"endCause": "TIMEOUT_ABORT"` (every headless run killed by the sweep
	 * driver's wall-clock watchdog, before #909 split that case off from a genuine deadlock) must
	 * still decode. That guarantee is documented on [DispatcherRunSnapshot] but was not pinned by a
	 * test, so this pins it against a **literal** v3 document — not a round-trip, which would silently
	 * track any future enum change and stop testing backward compatibility at all.
	 *
	 * The decoded run comes back with `endCause = TIMEOUT_ABORT`, `completedNaturally = false`, and
	 * its own `schemaVersion = 3` (preserved, not rewritten to the current one). The aggregator's
	 * [RunReportAggregator.runPassed] predicate must treat it as non-passing: a wall-clock kill is
	 * not a clean completion, and a single passing-rated TIMEOUT_ABORT would let a hung arm sail
	 * through the A4 gate.
	 *
	 * This is a literal-JSON test, not a round-trip: were `TIMEOUT_ABORT` removed from [RunEndCause],
	 * the direct `decodeFromString` below would throw `SerializationException` and the test would fail
	 * loudly, instead of the store's `readAll` silently WARN-and-skipping the file and masking the
	 * regression as an empty result.
	 */
	@Test
	fun `readAll still decodes a literal schema-version-3 TIMEOUT_ABORT run and the aggregator counts it non-passing`(
		@TempDir tmpDir: Path
	) {
		val store = DefaultRunSnapshotStore(root = tmpDir)
		val armDir = tmpDir.resolve("rule_based")
		Files.createDirectories(armDir)
		Files.writeString(armDir.resolve("legacy-v3-timeout-abort.json"), SCHEMA_V3_TIMEOUT_ABORT_JSON)

		// 1. The store's WARN-and-skip readAll path must still read it (not skip it).
		val results = store.readAll(tmpDir)
		assertThat(results).hasSize(1)
		val legacy = results.first()
		assertThat(legacy.runId).isEqualTo("legacy-v3-timeout-abort-001")
		// The file's own version is preserved, not rewritten to the current one.
		assertThat(legacy.schemaVersion).isEqualTo(3)
		assertThat(legacy.endCause).isEqualTo(RunEndCause.TIMEOUT_ABORT)
		assertThat(legacy.completedNaturally).isEqualTo(false)
		// v3-era `fatalExceptionCount` is stored in the literal JSON, but after the v5 field
		// rename it is decoded under the new name `loggedFatalSimExceptionCount`. Because the JSON
		// key no longer matches, kotlinx.serialization supplies the default (null). Old files
		// written with `fatalExceptionCount` are therefore treated as "not scanned" — the honest
		// value for a run whose log was measured under a now-superseded field name.
		assertThat(legacy.loggedFatalSimExceptionCount).isNull()
		assertThat(legacy.loggedFatalSimExceptionFirstMessage).isNull()

		// 2. Direct decode of the literal JSON so the failure is a thrown exception, not a silent
		//    skip, if TIMEOUT_ABORT is ever removed from the enum.
		val direct = SCHEMA_V3_JSON.decodeFromString(DispatcherRunSnapshot.serializer(), SCHEMA_V3_TIMEOUT_ABORT_JSON)
		assertThat(direct.endCause).isEqualTo(RunEndCause.TIMEOUT_ABORT)
		assertThat(direct.completedNaturally).isEqualTo(false)
		assertThat(direct.schemaVersion).isEqualTo(3)

		// 3. The aggregator's per-run gate predicate must treat this run as non-passing.
		val aggregator = RunReportAggregator(store)
		assertThat(aggregator.runPassed(legacy)).isFalse()
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
		// encodeDefaults = true is required: schemaVersion equals its default value, and
		// kotlinx.serialization omits default-valued fields unless told otherwise. Without this,
		// the field would never appear in the JSON and the replace() below would be a silent no-op.
		//
		// The searched-for text is built from CURRENT_SCHEMA_VERSION rather than hardcoded, so a
		// schema bump cannot turn this replacement into a silent no-op that makes the test assert
		// nothing (Issue #834, SP2c.11: the bump from 1 to 2 did exactly that to the literal form).
		return FUTURE_SCHEMA_JSON_CODEC
			.encodeToString(DispatcherRunSnapshot.serializer(), snap)
			.replace(
				"\"schemaVersion\": ${DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION}",
				"\"schemaVersion\": 9999"
			)
	}

	private companion object {
		/** Codec used to build a synthetic future-schema-version JSON document for tests. */
		private val FUTURE_SCHEMA_JSON_CODEC =
			Json {
				prettyPrint = true
				encodeDefaults = true
			}

		/**
		 * A literal schema-version-1 run document, exactly as `DefaultRunSnapshotStore` wrote it
		 * before SP2c.11 added `railwayOutcome`. Written out in full rather than derived from the
		 * current serializer, because a derived fixture would silently track every future schema
		 * change and stop testing backward compatibility at all.
		 */
		private val SCHEMA_V1_JSON =
			"""
			{
				"schemaVersion": 1,
				"runId": "legacy-v1-001",
				"arm": "RULE_BASED",
				"params": {
					"tickPeriodMs": 500,
					"historyN": 10,
					"temperature": 0.0,
					"maxActionsPerTick": 3,
					"model": "",
					"seed": null
				},
				"totalTicks": 3,
				"ticksByOutcome": { "LLM_ACTIONS": 3 },
				"timeoutNoOpByCause": { "DEADLINE_MISS": 0 },
				"llmSuccessRate": 1.0,
				"noOpRate": 0.0,
				"invalidOutputRate": 0.0,
				"repairSuccessRate": 0.0,
				"emittedByActionType": {},
				"rejectionsByCode": {},
				"applyFailuresByCode": {},
				"validAt1": 0.0,
				"correctAt1": null,
				"oracleAgreementAt1": null,
				"latencyP50Ms": 100,
				"latencyP95Ms": 200,
				"latencyMaxMs": 300,
				"actionsByAuthor": {},
				"unattributedApplies": 0,
				"terminalFallbackEngaged": false,
				"terminalFallbackTickIndex": null,
				"c7Clean": true,
				"completedNaturally": true,
				"endCause": "NATURAL_COMPLETION"
			}
			""".trimIndent()

		/**
		 * A literal schema-version-3 run document, exactly as `DefaultRunSnapshotStore` wrote it
		 * before Issue #909 added [RunEndCause.TERMINATED_EARLY] (schema version 4). A v3-era store
		 * wrote every headless watchdog kill as `"endCause": "TIMEOUT_ABORT"`; v4 keeps that enum
		 * value so these files stay readable, and this fixture pins that. Written out in full
		 * rather than derived from the current serializer, for the same reason as [SCHEMA_V1_JSON]:
		 * a derived fixture would silently track every future schema/enum change and stop testing
		 * backward compatibility at all. Includes the v3-added `fatalExceptionCount` /
		 * `fatalExceptionFirstMessage` fields and the v2-added `railwayOutcome` /
		 * `inferenceTimeoutSeconds` / `promptVariant` fields exactly as a v3-era store wrote them
		 * with `encodeDefaults = true`.
		 */
		private val SCHEMA_V3_TIMEOUT_ABORT_JSON =
			"""
			{
				"schemaVersion": 3,
				"runId": "legacy-v3-timeout-abort-001",
				"arm": "RULE_BASED",
				"params": {
					"tickPeriodMs": 500,
					"historyN": 10,
					"temperature": 0.0,
					"maxActionsPerTick": 3,
					"model": "",
					"seed": null,
					"inferenceTimeoutSeconds": 30,
					"promptVariant": "unspecified"
				},
				"totalTicks": 3,
				"ticksByOutcome": { "LLM_ACTIONS": 3 },
				"timeoutNoOpByCause": { "DEADLINE_MISS": 0 },
				"llmSuccessRate": 1.0,
				"noOpRate": 0.0,
				"invalidOutputRate": 0.0,
				"repairSuccessRate": 0.0,
				"emittedByActionType": {},
				"rejectionsByCode": {},
				"applyFailuresByCode": {},
				"validAt1": 0.0,
				"correctAt1": null,
				"oracleAgreementAt1": null,
				"latencyP50Ms": 100,
				"latencyP95Ms": 200,
				"latencyMaxMs": 300,
				"actionsByAuthor": {},
				"unattributedApplies": 0,
				"terminalFallbackEngaged": false,
				"terminalFallbackTickIndex": null,
				"c7Clean": true,
				"completedNaturally": false,
				"endCause": "TIMEOUT_ABORT",
				"railwayOutcome": {
					"journeysCompleted": null,
					"trainsEntered": null,
					"trainsExited": null,
					"maxConcurrentTrains": null,
					"blockTransitions": null,
					"conflicts": null,
					"failedReservations": null
				},
				"fatalExceptionCount": 0,
				"fatalExceptionFirstMessage": null
			}
			""".trimIndent()

		/** Decoder matching the store's own configuration, used for direct literal-JSON decodes. */
		private val SCHEMA_V3_JSON =
			Json {
				prettyPrint = true
				encodeDefaults = true
				ignoreUnknownKeys = true
			}
	}
}
