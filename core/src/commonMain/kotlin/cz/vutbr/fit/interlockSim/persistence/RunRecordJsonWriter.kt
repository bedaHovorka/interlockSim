/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.persistence

import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import cz.vutbr.fit.interlockSim.util.createDirectories
import cz.vutbr.fit.interlockSim.util.deleteFile
import cz.vutbr.fit.interlockSim.util.renameFile
import cz.vutbr.fit.interlockSim.util.writeTextFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One finished run without an LLM dispatcher, as [RunRecordJsonWriter] needs it (Issue #1327).
 *
 * @property record The run record of the run (`RunRecordSource.runRecord`).
 * @property runId The run's id; the file name ends with it, as on the JVM.
 * @property example The example the run ran, persisted as the snapshot's `example`; `null` when unknown.
 * @property requestedEndTime The horizon the run was started with; `null` or not positive when none.
 * @property mainProcessFailed Whether the run's main process died (`MainProcessFailedException`).
 */
data class RecordedRun(
	val record: RunRecord,
	val runId: String,
	val example: String?,
	val requestedEndTime: Double?,
	val mainProcessFailed: Boolean = false
)

/**
 * Writes the run JSON of a run without an LLM dispatcher from its [RunRecord], on every platform
 * (Issue #1327, Goal 6: "every harness prints and persists the same run record").
 *
 * ## Why it is here
 *
 * The JVM writes the run JSON through `DispatcherRunSummaries.finishAndPersist` and
 * `DefaultRunSnapshotStore`, whose `@Serializable` snapshot types live in `:dispatcher-agent`, a JVM
 * module the native `fast-sim` binary must never depend on. Moving those types would pull LLM types into
 * `:core`, and a writer inside `:fast-sim` could not be tested on the JVM. So this writer builds the same
 * JSON with [buildJsonObject] from what a run without a dispatcher agent has: its run record. No
 * serialization type is part of its public signature: it returns a `String` or writes to a path.
 *
 * ## The snapshot keys are a copy (schema-change checklist)
 *
 * The key names below repeat the properties of `DispatcherRunSnapshot`, `RunParameters` and
 * `RailwayOutcome` as strings, and [SCHEMA_VERSION] repeats `DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION`.
 * A schema change therefore has to change this writer too; `RunRecordJsonReadBackTest` in `:desktop-ui`
 * reads its output with `DefaultRunSnapshotStore.readAll` and fails when it no longer equals the JVM file.
 *
 * ## What it decides, as the JVM path does
 *
 * - **Arm** ([arm]): `RULE_BASED` when the run recorded a capacity shown to a dispatcher (every
 *   dispatcher wiring records one; `wireSynchronousDispatcher` is the only one on the native target),
 *   otherwise `NO_DISPATCHER` — the rule of `DispatcherRunSummaries.withDispatcherArm`.
 * - **End cause** ([endCause]): `TERMINATED_EARLY` when the main process died or the kernel clock stopped
 *   short of the horizon by more than max(1 s, 5 %) (`RunCompletionCheck`); otherwise `STARVED` when no
 *   train left the railway (`starvationAdjusted`), else `NATURAL_COMPLETION`.
 * - **Parameters**: the values a JVM run records with no `-Dinterlocksim.dispatcher.*` property
 *   (`ruleBasedRunParameters` over `DispatcherRunConfig.fromProperties` and the shipped
 *   `dispatcher-defaults.properties`), so the report puts a native run into the same cell as a JVM one.
 * - **Dispatcher figures**: a run without a dispatcher agent has no ticks and no actions. The count maps
 *   are written empty, which every reader treats as zero; the JVM writes the same counts as zero-valued
 *   keys.
 *
 * The file goes to `<runsRoot>/<arm in lower case>/<timestamp>-<run id>.json`, the layout of
 * `DefaultRunSnapshotStore.write`, written to `<name>.json.tmp` first and renamed into place.
 *
 * @since Issue #1327 (Goal 6)
 */
object RunRecordJsonWriter {
	/** The run JSON schema this writer produces; equal to `DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION`. */
	const val SCHEMA_VERSION: Int = 14

	/** The arm of a run that wired the synchronous rule-based dispatcher. */
	const val ARM_RULE_BASED: String = "RULE_BASED"

	/** The arm of a run that wired no dispatcher at all. */
	const val ARM_NO_DISPATCHER: String = "NO_DISPATCHER"

	/** The run reached its horizon and the railway made progress. */
	const val END_NATURAL_COMPLETION: String = "NATURAL_COMPLETION"

	/** The run stopped short of its horizon, or its main process died. */
	const val END_TERMINATED_EARLY: String = "TERMINATED_EARLY"

	/** The run reached its horizon, but no train left the railway. */
	const val END_STARVED: String = "STARVED"

	/** Shortfall tolerated as complete, as a fraction of the horizon (`RunCompletionCheck`). */
	private const val TOLERANCE_FRACTION: Double = 0.05

	/** Smallest shortfall tolerated as complete, in seconds (`RunCompletionCheck`). */
	private const val MIN_TOLERANCE_SECONDS: Double = 1.0

	// The rule-based parameters of a JVM run with no -D property (ruleBasedRunParameters).
	private const val DEFAULT_TICK_PERIOD_MS: Long = 0L
	private const val DEFAULT_HISTORY_N: Int = 0
	private const val DEFAULT_MAX_ACTIONS_PER_TICK: Int = 3
	private const val DEFAULT_INFERENCE_TIMEOUT_SECONDS: Long = 30L
	private const val DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD: Int = 3
	private const val DEFAULT_CIRCUIT_BREAKER_COOLDOWN_SECONDS: Double = 60.0

	/** Suffix of the temporary file [write] renames into place. */
	private const val TEMPORARY_SUFFIX: String = ".tmp"

	private val UNSAFE_RUN_ID_CHARACTERS = Regex("[^A-Za-z0-9._-]")

	private val prettyJson = Json { prettyPrint = true }

	/** The dispatcher arm the run JSON of [record] is filed under: [ARM_RULE_BASED] or [ARM_NO_DISPATCHER]. */
	fun arm(record: RunRecord): String = record.capacityShownToDispatcher?.let { ARM_RULE_BASED } ?: ARM_NO_DISPATCHER

	/** The end cause of [run]: [END_NATURAL_COMPLETION], [END_TERMINATED_EARLY] or [END_STARVED]. */
	fun endCause(run: RecordedRun): String =
		when {
			run.mainProcessFailed -> END_TERMINATED_EARLY
			!reachedHorizon(run.record.endTime, run.requestedEndTime) -> END_TERMINATED_EARLY
			run.record.trainsExited == 0 -> END_STARVED
			else -> END_NATURAL_COMPLETION
		}

	private fun reachedHorizon(
		reached: Double,
		requested: Double?
	): Boolean {
		if (requested == null || requested <= 0.0) return true
		val tolerance = maxOf(MIN_TOLERANCE_SECONDS, requested * TOLERANCE_FRACTION)
		return reached >= requested - tolerance
	}

	/** The run JSON of [run], in the format `DefaultRunSnapshotStore.readAll` reads. */
	fun snapshotJson(run: RecordedRun): String = prettyJson.encodeToString(JsonObject.serializer(), snapshot(run))

	/** The file name of a run JSON: [timestamp], then [runId] with every unsafe character replaced by `_`. */
	fun fileName(
		timestamp: String,
		runId: String
	): String = "$timestamp-${runId.replace(UNSAFE_RUN_ID_CHARACTERS, "_")}.json"

	/**
	 * Writes the run JSON of [run] under its arm directory below [runsRoot], creating the directories.
	 *
	 * @param timestamp The local start of the file name, `yyyyMMdd-HHmmss` as on the JVM.
	 * @return the path written.
	 * @throws IllegalStateException if a directory or the file cannot be written.
	 */
	fun write(
		runsRoot: String,
		run: RecordedRun,
		timestamp: String
	): String {
		val armDirectory = "${runsRoot.trimEnd('/')}/${arm(run.record).lowercase()}"
		createDirectories(armDirectory)
		val path = "$armDirectory/${fileName(timestamp, run.runId)}"
		// Through a temporary file with a fresh per-write name and one rename, as DefaultRunSnapshotStore
		// does with Files.createTempFile: a reader never sees a half-written JSON, and two writers of the
		// same final name -- the same second and run id -- never share their temporary file, so one
		// writer's rename cannot publish the other's in-flight bytes (Issue #1327 round of 2026-11-23).
		// readAll reads only names ending in .json, so it skips the temporary one either way.
		val temporary = temporaryPath(path)
		try {
			writeTextFile(temporary, snapshotJson(run))
			renameFile(temporary, path)
		} catch (e: IllegalStateException) {
			deleteFile(temporary)
			throw e
		}
		return path
	}

	/**
	 * The temporary file [write] renames into place: [path] with a fresh random UUID before `.tmp`
	 * (`Uuid.random`, the same source as [newRunId]). One per write -- the name of
	 * `DefaultRunSnapshotStore.write`'s per-call temporary file is unique the same way -- so two
	 * writers of the same final name cannot race on one temporary file: the POSIX interleaving would
	 * let the first rename publish the second writer's in-flight bytes and fail the second rename.
	 * `internal` for the pin of that guarantee.
	 */
	@OptIn(ExperimentalUuidApi::class)
	internal fun temporaryPath(path: String): String = "$path.${Uuid.random()}$TEMPORARY_SUFFIX"

	/** A new random run id, a UUID like the JVM default (`UUID.randomUUID()`). */
	@OptIn(ExperimentalUuidApi::class)
	fun newRunId(): String = Uuid.random().toString()

	private fun snapshot(run: RecordedRun): JsonObject {
		val endCause = endCause(run)
		return buildJsonObject {
			put("schemaVersion", SCHEMA_VERSION)
			put("runId", run.runId)
			put("arm", arm(run.record))
			put("params", ruleBasedParameters())
			putDispatcherFigures()
			put("completedNaturally", endCause == END_NATURAL_COMPLETION)
			put("endCause", endCause)
			put("railwayOutcome", railwayOutcome(run.record))
			putAbsent("loggedFatalSimExceptionCount", "loggedFatalSimExceptionFirstMessage")
			putAbsent("circuitBreakerState", "circuitBreakerTotalSkips", "circuitBreakerOpenCount")
			put("staleOriginRefusalsAfterSilentCycle", 0L)
			putAbsent(
				"skippedNextSectionOffers",
				"offeredNextSectionHops",
				"skippedNextSectionOffersStandingAtSignal",
				"longestSkippedOfferStreakCycles",
				"longestSkippedOfferStreakSimSeconds"
			)
			put("example", run.example)
			putAbsent("processWallSeconds")
			// Schema 14 (Issue #1342): a run without an LLM has no model profile.
			putAbsent("modelProfile")
		}
	}

	/** What a dispatcher recorder holds after a run in which no dispatcher agent ticked. */
	private fun JsonObjectBuilder.putDispatcherFigures() {
		put("totalTicks", 0L)
		putJsonObject("ticksByOutcome") {}
		putJsonObject("timeoutNoOpByCause") {}
		put("llmSuccessRate", 0.0)
		put("actionableTickRate", 0.0)
		put("noOpRate", 0.0)
		put("invalidOutputRate", 0.0)
		put("repairSuccessRate", 0.0)
		putJsonObject("emittedByActionType") {}
		putJsonObject("rejectionsByCode") {}
		putJsonObject("applyFailuresByCode") {}
		put("validAt1", 0.0)
		putAbsent("correctAt1", "oracleAgreementAt1", "latencyP50Ms", "latencyP95Ms", "latencyMaxMs")
		putJsonObject("actionsByAuthor") {}
		put("unattributedApplies", 0L)
		put("terminalFallbackEngaged", false)
		putAbsent("terminalFallbackTickIndex")
		put("c7Clean", true)
	}

	private fun ruleBasedParameters(): JsonObject =
		buildJsonObject {
			put("tickPeriodMs", DEFAULT_TICK_PERIOD_MS)
			put("historyN", DEFAULT_HISTORY_N)
			put("temperature", 0.0)
			put("maxActionsPerTick", DEFAULT_MAX_ACTIONS_PER_TICK)
			put("model", "")
			put("seed", JsonNull)
			put("inferenceTimeoutSeconds", DEFAULT_INFERENCE_TIMEOUT_SECONDS)
			put("promptVariant", "")
			put("circuitBreakerFailureThreshold", DEFAULT_CIRCUIT_BREAKER_FAILURE_THRESHOLD)
			put("circuitBreakerCooldownSeconds", DEFAULT_CIRCUIT_BREAKER_COOLDOWN_SECONDS)
		}

	/** The `RailwayOutcome` of [record], as `RunRecord.toRailwayOutcome` maps it on the JVM. */
	private fun railwayOutcome(record: RunRecord): JsonObject =
		buildJsonObject {
			put("journeysCompleted", record.journeysCompleted.toLong())
			put("trainsEntered", record.trainsEntered.toLong())
			put("trainsExited", record.trainsExited.toLong())
			put("maxConcurrentTrains", record.maxConcurrentTrains.toLong())
			put("blockTransitions", record.blockTransitions.toLong())
			put("conflicts", record.conflicts.toLong())
			put("failedReservations", record.failedReservations.toLong())
			put("unresolvedAtRunEnd", record.unresolvedAtRunEnd.toLong())
			put("wrongExits", record.wrongExits.toLong())
			put("unfinishedJourneys", record.unfinishedJourneys.toLong())
			put("failedReservationsByKind", countsByName(record.failedReservationsByKind))
			put("entryFailures", record.entryFailures.toLong())
			put("entryFailuresByKind", countsByName(record.entryFailuresByKind))
			put("stall", stall(record))
			put("expiredHoldingCount", record.expiredHoldingCount.toLong())
			put("expiredTailCount", record.expiredTailCount.toLong())
			put("staleReleaseCount", record.staleReleaseCount.toLong())
			put("partialReleaseCount", record.partialReleaseCount.toLong())
			put("capacityShownToDispatcher", record.capacityShownToDispatcher?.toLong())
			put("topologyCapacity", record.topologyCapacity?.toLong())
			put("endTime", record.endTime)
			put("endTimeReached", record.endTimeReached)
			put("endClassification", record.classification.name)
			val performance = record.performance
			put("wallSeconds", performance?.wallSeconds)
			put("simulationSeconds", performance?.simulationSeconds)
			put("realTimeRatio", performance?.realTimeRatio)
			put("routeSearchBudgetExceeded", performance?.routeSearchBudgetExceeded?.toLong())
			put("reservePathCalls", performance?.reservePathCalls?.toLong())
			// Issue #1457 (schema version 14), as RunRecordOutcomes.toRefusedEntryOutcome maps it.
			putJsonArray("refusedEntriesAtRunEnd") {
				record.refusedEntriesAtRunEnd.forEach { entry ->
					add(
						buildJsonObject {
							put("trainId", entry.trainId)
							put("inOut", entry.inOutName)
							put("kind", entry.kind.name)
							put("since", entry.since)
						}
					)
				}
			}
		}

	private fun stall(record: RunRecord): JsonElement {
		val stall = record.stall ?: return JsonNull
		return buildJsonObject {
			putJsonArray("cycle") { stall.cycle.forEach { add(it) } }
			// Sorted, as the JVM mapping (RunRecordOutcomes.toStallOutcome) sorts it.
			putJsonArray("knot") { stall.knot.sorted().forEach { add(it) } }
			put("persistedTicks", stall.persistedTicks.toLong())
			put("simTime", stall.simTime)
		}
	}

	private fun <K : Enum<K>> countsByName(counts: Map<K, Int>): JsonObject =
		buildJsonObject { counts.forEach { (kind, count) -> put(kind.name, count.toLong()) } }

	private fun JsonObjectBuilder.putAbsent(vararg keys: String) {
		keys.forEach { put(it, JsonNull) }
	}
}
