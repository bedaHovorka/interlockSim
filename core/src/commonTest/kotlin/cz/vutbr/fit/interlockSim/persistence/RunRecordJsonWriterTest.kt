/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.persistence

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.matches
import assertk.assertions.startsWith
import cz.vutbr.fit.interlockSim.ExampleNames
import cz.vutbr.fit.interlockSim.sim.FailedReservationKind
import cz.vutbr.fit.interlockSim.sim.MultiTrainLoop
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.metrics.RunPerformance
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import cz.vutbr.fit.interlockSim.sim.metrics.RunStall
import cz.vutbr.fit.interlockSim.util.currentTimeMillisKMP
import cz.vutbr.fit.interlockSim.util.deleteFile
import cz.vutbr.fit.interlockSim.util.directoryExists
import cz.vutbr.fit.interlockSim.util.readTextFile
import cz.vutbr.fit.interlockSim.util.tempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The run JSON writer of `:core` (Issue #1327): the end cause, the arm, the snapshot keys and the file
 * layout of a run without an LLM dispatcher. That the JSON is one `DefaultRunSnapshotStore.readAll`
 * reads, and equal to what the JVM path writes for the same run, is pinned in `:desktop-ui`
 * (`RunRecordJsonReadBackTest`), the one module that sees both sides.
 */
class RunRecordJsonWriterTest {
	private val createdPaths = mutableListOf<String>()

	@AfterTest
	fun cleanUp() {
		// Deepest first: the file, then the arm directory, then the runs root.
		createdPaths.reversed().forEach { deleteFile(it) }
		createdPaths.clear()
	}

	private fun record(
		trainsExited: Int = 3,
		endTime: Double = 600.0,
		capacityShownToDispatcher: Int? = 4
	): RunRecord =
		RunRecord(
			journeysCompleted = trainsExited,
			trainsEntered = 5,
			trainsExited = trainsExited,
			wrongExits = 0,
			maxConcurrentTrains = 2,
			blockTransitions = 41,
			conflicts = 1,
			failedReservations = 2,
			failedReservationsByKind = mapOf(FailedReservationKind.ALL_PATHS_BLOCKED to 2),
			unresolvedAtRunEnd = 0,
			entryFailures = 1,
			entryFailuresByKind = mapOf(MultiTrainLoop.EntryFailureKind.UNKNOWN_IN_OUT to 1),
			stall = RunStall(cycle = listOf("T1", "T2"), knot = setOf("T2", "T1"), persistedTicks = 7, simTime = 512.5),
			expiredHoldingCount = 0,
			expiredTailCount = 0,
			staleReleaseCount = 0,
			partialReleaseCount = 0,
			capacityShownToDispatcher = capacityShownToDispatcher,
			topologyCapacity = 6,
			unfinishedJourneys = 2,
			endTime = endTime,
			endTimeReached = true,
			classification = RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS,
			performance =
				RunPerformance(
					wallSeconds = 1.5,
					simulationSeconds = 600.0,
					realTimeRatio = 400.0,
					routeSearchBudgetExceeded = 0,
					reservePathCalls = 77
				)
		)

	private fun run(
		record: RunRecord = record(),
		requestedEndTime: Double? = 600.0,
		mainProcessFailed: Boolean = false
	): RecordedRun =
		RecordedRun(
			record = record,
			runId = "native-run-1",
			example = ExampleNames.CERVENY_UJEZD,
			requestedEndTime = requestedEndTime,
			mainProcessFailed = mainProcessFailed
		)

	private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

	// --- end cause: the rule of Main.classifyRun + DispatcherRunSummaries.starvationAdjusted ---

	@Test
	fun `a run that reached its horizon and let trains out ended naturally`() {
		assertThat(RunRecordJsonWriter.endCause(run())).isEqualTo(RunRecordJsonWriter.END_NATURAL_COMPLETION)
	}

	@Test
	fun `a run that reached its horizon within the tolerance ended naturally`() {
		// The tolerance is max(1 s, 5 % of the horizon): 30 s at 600 s.
		val run = run(record(endTime = 571.0))

		assertThat(RunRecordJsonWriter.endCause(run)).isEqualTo(RunRecordJsonWriter.END_NATURAL_COMPLETION)
	}

	@Test
	fun `a run that stopped short of its horizon terminated early`() {
		val run = run(record(endTime = 569.0))

		assertThat(RunRecordJsonWriter.endCause(run)).isEqualTo(RunRecordJsonWriter.END_TERMINATED_EARLY)
	}

	@Test
	fun `a run that reached its horizon with no train out starved`() {
		val run = run(record(trainsExited = 0))

		assertThat(RunRecordJsonWriter.endCause(run)).isEqualTo(RunRecordJsonWriter.END_STARVED)
	}

	@Test
	fun `a run whose main process failed terminated early even at its horizon`() {
		val run = run(mainProcessFailed = true)

		assertThat(RunRecordJsonWriter.endCause(run)).isEqualTo(RunRecordJsonWriter.END_TERMINATED_EARLY)
	}

	@Test
	fun `a run without a usable horizon counts as having reached it`() {
		assertThat(RunRecordJsonWriter.endCause(run(record(endTime = 1.0), requestedEndTime = null)))
			.isEqualTo(RunRecordJsonWriter.END_NATURAL_COMPLETION)
		assertThat(RunRecordJsonWriter.endCause(run(record(endTime = 1.0), requestedEndTime = 0.0)))
			.isEqualTo(RunRecordJsonWriter.END_NATURAL_COMPLETION)
	}

	// --- arm: the rule of DispatcherRunSummaries.withDispatcherArm ---

	@Test
	fun `a run that recorded a capacity wired the synchronous dispatcher and is rule-based`() {
		assertThat(
			RunRecordJsonWriter.arm(record(capacityShownToDispatcher = 4))
		).isEqualTo(RunRecordJsonWriter.ARM_RULE_BASED)
	}

	@Test
	fun `a run that recorded no capacity had no dispatcher`() {
		assertThat(RunRecordJsonWriter.arm(record(capacityShownToDispatcher = null)))
			.isEqualTo(RunRecordJsonWriter.ARM_NO_DISPATCHER)
	}

	// --- the snapshot JSON ---

	@Test
	fun `the snapshot carries the schema version and the run identity and the end cause`() {
		val json = parse(RunRecordJsonWriter.snapshotJson(run(record(trainsExited = 0))))

		assertThat(json.getValue("schemaVersion").jsonPrimitive.int).isEqualTo(RunRecordJsonWriter.SCHEMA_VERSION)
		assertThat(json.getValue("runId").jsonPrimitive.content).isEqualTo("native-run-1")
		assertThat(json.getValue("arm").jsonPrimitive.content).isEqualTo(RunRecordJsonWriter.ARM_RULE_BASED)
		assertThat(json.getValue("example").jsonPrimitive.content).isEqualTo(ExampleNames.CERVENY_UJEZD)
		assertThat(json.getValue("endCause").jsonPrimitive.content).isEqualTo(RunRecordJsonWriter.END_STARVED)
		assertThat(json.getValue("completedNaturally").jsonPrimitive.boolean).isEqualTo(false)
		assertThat(json.getValue("totalTicks").jsonPrimitive.long).isEqualTo(0L)
		assertThat(json.getValue("c7Clean").jsonPrimitive.boolean).isEqualTo(true)
	}

	@Test
	fun `a natural completion is recorded as completed naturally`() {
		val json = parse(RunRecordJsonWriter.snapshotJson(run()))

		assertThat(json.getValue("completedNaturally").jsonPrimitive.boolean).isEqualTo(true)
	}

	@Test
	fun `the parameters are the JVM rule-based defaults so the report keeps the run in the JVM cell`() {
		val json = parse(RunRecordJsonWriter.snapshotJson(run()))

		// ruleBasedRunParameters(DispatcherRunConfig.fromProperties()) with no -D property: the shipped
		// dispatcher-defaults.properties values plus the rule-based "no prompt" sentinels.
		val expected =
			buildJsonObject {
				put("tickPeriodMs", 0L)
				put("historyN", 0)
				put("temperature", 0.0)
				put("maxActionsPerTick", 3)
				put("model", "")
				put("seed", JsonNull)
				put("inferenceTimeoutSeconds", 30L)
				put("promptVariant", "")
				put("circuitBreakerFailureThreshold", 3)
				put("circuitBreakerCooldownSeconds", 60.0)
			}
		assertThat(json.getValue("params")).isEqualTo(expected)
	}

	@Test
	fun `the railway outcome carries the run record figures`() {
		val outcome = parse(RunRecordJsonWriter.snapshotJson(run())).getValue("railwayOutcome").jsonObject

		assertThat(outcome.getValue("trainsExited").jsonPrimitive.long).isEqualTo(3L)
		assertThat(outcome.getValue("blockTransitions").jsonPrimitive.long).isEqualTo(41L)
		assertThat(outcome.getValue("capacityShownToDispatcher").jsonPrimitive.long).isEqualTo(4L)
		assertThat(outcome.getValue("endClassification").jsonPrimitive.content)
			.isEqualTo(RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS.name)
		assertThat(outcome.getValue("reservePathCalls").jsonPrimitive.long).isEqualTo(77L)
		assertThat(outcome.getValue("failedReservationsByKind"))
			.isEqualTo(buildJsonObject { put(FailedReservationKind.ALL_PATHS_BLOCKED.name, 2L) })
		val stall = outcome.getValue("stall").jsonObject
		// The JVM mapping sorts the knot (RunRecordOutcomes.toStallOutcome).
		assertThat(stall.getValue("knot").toString()).isEqualTo("""["T1","T2"]""")
		assertThat(stall.getValue("cycle").toString()).isEqualTo("""["T1","T2"]""")
	}

	@Test
	fun `a record without a capacity writes the capacity as absent and not as zero`() {
		val json = parse(RunRecordJsonWriter.snapshotJson(run(record(capacityShownToDispatcher = null))))

		assertThat(json.getValue("arm").jsonPrimitive.content).isEqualTo(RunRecordJsonWriter.ARM_NO_DISPATCHER)
		assertThat(json.getValue("railwayOutcome").jsonObject.getValue("capacityShownToDispatcher"))
			.isEqualTo(JsonNull)
	}

	// --- file name and layout: DefaultRunSnapshotStore.write ---

	@Test
	fun `the file name is the timestamp and the sanitised run id`() {
		assertThat(RunRecordJsonWriter.fileName("20261122-101500", "a b/c:d.e_f-g"))
			.isEqualTo("20261122-101500-a_b_c_d.e_f-g.json")
	}

	@Test
	fun `write puts the snapshot under the arm directory of the runs root`() {
		val root = "${tempDirectory().trimEnd('/')}/interlockSim-run-json-${currentTimeMillisKMP()}"
		val run = run(record(capacityShownToDispatcher = null))
		val armDirectory = "$root/no_dispatcher"
		createdPaths += listOf(root, armDirectory)

		val path = RunRecordJsonWriter.write(root, run, "20261122-101500")
		createdPaths += path

		assertThat(path).isEqualTo("$armDirectory/20261122-101500-native-run-1.json")
		assertThat(directoryExists(armDirectory)).isEqualTo(true)
		assertThat(readTextFile(path)).isEqualTo(RunRecordJsonWriter.snapshotJson(run))
		// The temporary name is per write, so no assertion here can name the file that was renamed away.
		// A leftover temporary file not breaking readers is pinned in RunRecordJsonReadBackTest (it
		// plants a *.tmp beside a native run JSON), and two writers never share one: the pin below.
	}

	@Test
	fun `each write gets its own temporary name so concurrent writers never share one`() {
		val target = "runs/no_dispatcher/20261122-101500-native-run-1.json"

		val first = RunRecordJsonWriter.temporaryPath(target)

		assertThat(first, name = "first temp path beside its target").startsWith(target)
		assertThat(first, name = "a second temp path of the same target")
			.isNotEqualTo(RunRecordJsonWriter.temporaryPath(target))
	}

	@Test
	fun `a new run id is a random UUID like the JVM default`() {
		val first = RunRecordJsonWriter.newRunId()

		assertThat(first).matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
		assertThat(RunRecordJsonWriter.newRunId()).isNotEqualTo(first)
	}

	@Test
	fun `the schema version is a number the store accepts`() {
		assertThat(JsonPrimitive(RunRecordJsonWriter.SCHEMA_VERSION).int).isEqualTo(13)
	}
}
