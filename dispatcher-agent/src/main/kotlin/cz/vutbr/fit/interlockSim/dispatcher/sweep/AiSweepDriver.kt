/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.sweep

import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunSnapshot
import cz.vutbr.fit.interlockSim.dispatcher.planner.RailwayOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunParameters
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunReportAggregator
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.TickOutcome
import cz.vutbr.fit.interlockSim.dispatcher.planner.TimeoutNoOpCause
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.streams.asSequence

private val logger = KotlinLogging.logger {}

/**
 * Unattended N-run parameter sweep over the headless dispatcher examples.
 *
 * Expands a [SweepGrid], runs every cell [SweepGrid.repeat] times, and renders the cross-run
 * report over the results. A4's "measured success rate over N ≥ 10 runs" is a claim nobody can
 * check without this; the run recorder and the aggregator were both built for a producer that
 * did not exist.
 *
 * ## One forked JVM per run
 *
 * Each run is a child process (`<java> -cp <classpath> <mainClass> example <name> <endTime>`)
 * carrying the cell's `-D` properties. In-process repetition was rejected for three concrete
 * reasons, not on principle:
 *
 * 1. **The timeout has to be real.** A run stalls inside a blocking Ollama HTTP call on the
 *    dispatcher-agent driver thread. No in-process watchdog can reliably unwind that; a child
 *    process can be killed.
 * 2. **State does not leak.** Koin singletons, `DispatcherRunSummaries`' process-wide
 *    written-recorder set and any thread a previous run left behind all die with the child, so
 *    run *k+1* cannot be contaminated by run *k*.
 * 3. **A crash costs one data point.** An exception that would end an in-process sweep ends one
 *    child, and the sweep records it and continues.
 *
 * The parent never runs a simulation itself, so it stays responsive and its own log is the
 * progress record.
 *
 * ## Resumable
 *
 * A ten-run local-LLM sweep takes hours and will be interrupted. Every run has a deterministic id
 * derived from its cell and repeat index ([SweepRun.runId]), and the id appears in the result file
 * name, so on start-up the driver reads the output directory and skips every run already present.
 * Re-invoking after a crash or Ctrl-C continues where it stopped.
 *
 * ## Never in CI
 *
 * This needs a live Ollama and takes hours. It is manual-only, in the same category as
 * `heavyTest`, and is deliberately not reachable from `test`, `integrationTest` or `build`.
 *
 * @param store Where run snapshots are read from and abort snapshots are written to.
 * @param processRunner Seam for launching a run; the default forks a JVM. Tests substitute it so
 *   the driver's skip/timeout/report logic can be exercised without an LLM.
 */
class AiSweepDriver(
	private val store: RunSnapshotStore = DefaultRunSnapshotStore(),
	private val processRunner: SweepProcessRunner = ForkedJvmSweepProcessRunner()
) {
	/**
	 * Runs [grid], writing results under [outputRoot], and renders `report.md` there.
	 *
	 * @param dryRun when `true`, enumerates and logs the runs without launching anything —
	 *   the cheap way to check a grid file before committing hours to it.
	 * @return a summary of what happened.
	 */
	fun run(
		grid: SweepGrid,
		outputRoot: Path,
		mainClass: String,
		dryRun: Boolean = false
	): SweepSummary {
		val runs = grid.expand()
		logger.info {
			"[aiSweep] grid expands to ${runs.size} run(s): ${grid.axes.cells().size} cell(s) x " +
				"${grid.repeat} repeat(s), endTime=${grid.endTimeSeconds}s, " +
				"perRunTimeout=${grid.perRunTimeoutSeconds}s, out=$outputRoot"
		}

		if (dryRun) {
			runs.forEach { logger.info { "[aiSweep] would run ${it.runId} (${it.cell})" } }
			return SweepSummary(planned = runs.size, skipped = 0, completed = 0, failed = 0, aborted = 0)
		}

		Files.createDirectories(outputRoot)
		val alreadyPresent = existingRunIds(outputRoot)
		if (alreadyPresent.isNotEmpty()) {
			logger.info { "[aiSweep] resuming: ${alreadyPresent.size} run(s) already present under $outputRoot" }
		}

		var skipped = 0
		var completed = 0
		var failed = 0
		var aborted = 0

		runs.forEachIndexed { index, run ->
			val position = "${index + 1}/${runs.size}"
			if (run.runId in alreadyPresent) {
				skipped++
				logger.info { "[aiSweep] $position skip ${run.runId} — result already present" }
				return@forEachIndexed
			}

			logger.info { "[aiSweep] $position start ${run.runId} — ${run.cell}" }
			val startedAt = System.nanoTime()
			val outcome = execute(run, outputRoot, mainClass, grid.perRunTimeoutSeconds)
			val wallClockSeconds = (System.nanoTime() - startedAt) / NANOS_PER_SECOND

			when (outcome) {
				SweepRunOutcome.COMPLETED -> completed++
				SweepRunOutcome.FAILED -> failed++
				SweepRunOutcome.ABORTED -> aborted++
			}
			logger.info { "[aiSweep] $position done ${run.runId} — $outcome after ${wallClockSeconds}s" }
		}

		val summary = SweepSummary(runs.size, skipped, completed, failed, aborted)
		logger.info { "[aiSweep] finished: $summary" }
		renderReport(outputRoot)
		return summary
	}

	/**
	 * Launches one run and classifies the result, writing an abort snapshot when the child
	 * produced none.
	 *
	 * A child that times out or dies never reaches `DispatcherRunSummaries.finishAndPersist`, so
	 * without this the failure would simply be absent — and an absent run silently improves the
	 * arm's measured success rate, which is the one thing a measurement task must not do.
	 */
	private fun execute(
		run: SweepRun,
		outputRoot: Path,
		mainClass: String,
		timeoutSeconds: Long
	): SweepRunOutcome {
		val logDir = outputRoot.resolve(RUN_LOG_DIR)
		Files.createDirectories(logDir)
		val logFile = logDir.resolve("${run.runId}.log")
		val request =
			SweepProcessRequest(
				mainClass = mainClass,
				exampleName = run.cell.example,
				endTimeSeconds = run.endTimeSeconds,
				systemProperties = run.cell.systemProperties(run.runId, outputRoot.toString()),
				timeoutSeconds = timeoutSeconds,
				logFile = logFile
			)

		val result =
			try {
				processRunner.run(request)
			} catch (e: IOException) {
				logger.error(e) { "[aiSweep] could not launch ${run.runId}" }
				SweepProcessResult(exitCode = null, timedOut = false)
			}

		// Scanned once the child is done, regardless of how it ended: a timeout or a crash can
		// still carry FATAL evidence in its log, and a run that exits 0 needs it patched into the
		// run JSON the child already wrote. See FatalExceptionScanner's KDoc for why this is a log
		// scan rather than in-process capture.
		val fatalScan = FatalExceptionScanner.scan(logFile)

		if (result.timedOut) {
			logger.error {
				"[aiSweep] ${run.runId} exceeded its ${timeoutSeconds}s budget and was killed — " +
					"recording TIMEOUT_ABORT"
			}
			writeAbortSnapshot(run, RunEndCause.TIMEOUT_ABORT, fatalScan)
			return SweepRunOutcome.ABORTED
		}

		if (result.exitCode != 0) {
			logger.error { "[aiSweep] ${run.runId} exited with ${result.exitCode}" }
			// Exit 1 is Main's TERMINATED_EARLY, which the run itself already persisted as
			// TERMINATED_EARLY (Issue #909); anything else means the child died before it could record anything.
			if (run.runId !in existingRunIds(outputRoot)) {
				writeAbortSnapshot(run, RunEndCause.CRASH, fatalScan)
			}
			return SweepRunOutcome.FAILED
		}

		if (run.runId !in existingRunIds(outputRoot)) {
			// A clean exit that wrote nothing is not a success: the example ran without a
			// dispatcher, or persistence failed. Counting it as completed would inflate the
			// denominator with a run that has no measurement behind it.
			logger.error { "[aiSweep] ${run.runId} exited 0 but wrote no run JSON — recording CRASH" }
			writeAbortSnapshot(run, RunEndCause.CRASH, fatalScan)
			return SweepRunOutcome.FAILED
		}

		patchFatalExceptionInfo(outputRoot, run.runId, fatalScan)
		return SweepRunOutcome.COMPLETED
	}

	/**
	 * Run ids already recorded under [root].
	 *
	 * Derived from file names rather than by deserializing every JSON:
	 * [cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore] writes
	 * `<yyyyMMdd-HHmmss>-<sanitised runId>.json`, and [SweepCell.slug] is already built in that
	 * sanitised alphabet, so stripping the timestamp prefix recovers the id exactly. Re-scanned per
	 * run so a result written moments ago by a child is seen.
	 */
	private fun existingRunIds(root: Path): Set<String> {
		if (!Files.isDirectory(root)) return emptySet()
		return try {
			Files.walk(root).use { stream ->
				stream
					.asSequence()
					.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
					.mapNotNull { RUN_FILE_NAME.matchEntire(it.fileName.toString())?.groupValues?.get(1) }
					.toSet()
			}
		} catch (e: IOException) {
			logger.warn(e) { "[aiSweep] could not scan $root for existing runs; assuming none" }
			emptySet()
		}
	}

	private fun writeAbortSnapshot(
		run: SweepRun,
		cause: RunEndCause,
		fatalScan: FatalExceptionScanResult
	) {
		try {
			store.write(abortedSnapshot(run.runId, run.cell.arm, run.cell.runParameters(), cause, fatalScan))
		} catch (e: IOException) {
			logger.error(e) { "[aiSweep] could not record the aborted run ${run.runId}" }
		}
	}

	/**
	 * Finds the run JSON the child already wrote for [runId] and, when [fatalScan] measured
	 * something, rewrites it in place to carry the finding.
	 *
	 * ## Why "in place" and not [RunSnapshotStore.write]
	 *
	 * [RunSnapshotStore.write] always mints a fresh `<timestamp>-<runId>.json` file name.
	 * Calling it again for a run that already has a file would leave **two** JSON files for the
	 * same [runId] under [outputRoot], and [RunSnapshotStore.readAll] has no way to know they are
	 * the same run — the arm's `runCount` would silently double-count it. Overwriting the exact
	 * file the child wrote, atomically (temp file + [StandardCopyOption.ATOMIC_MOVE], the same
	 * pattern `DefaultRunSnapshotStore.write` uses), keeps the one-file-per-run invariant intact.
	 *
	 * Does nothing when [fatalScan] itself is absent (`count == null`): [DispatcherRunSnapshot]'s
	 * own fields already default to `null`, so there is nothing to add, and leaving the pristine
	 * file alone is strictly simpler — and strictly safer — than writing back a value it already
	 * has.
	 *
	 * Best-effort like every other post-hoc write in this class: a failure here logs a WARN and
	 * leaves the run JSON as the child wrote it (fatal-exception fields absent), it never fails
	 * the run or the sweep.
	 */
	private fun patchFatalExceptionInfo(
		outputRoot: Path,
		runId: String,
		fatalScan: FatalExceptionScanResult
	) {
		if (fatalScan.count == null) return
		try {
			val file = findRunFile(outputRoot, runId)
			if (file == null) {
				logger.warn { "[aiSweep] no run file found for $runId — its FATAL-scan result was not recorded" }
				return
			}
			val original = json.decodeFromString(DispatcherRunSnapshot.serializer(), Files.readString(file))
			val patched =
				original.copy(
					loggedFatalSimExceptionCount = fatalScan.count,
					loggedFatalSimExceptionFirstMessage = fatalScan.firstMessage
				)
			val tmp = Files.createTempFile(file.parent, "run-patch-", ".json.tmp")
			try {
				Files.writeString(tmp, json.encodeToString(DispatcherRunSnapshot.serializer(), patched))
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
			} catch (e: IOException) {
				Files.deleteIfExists(tmp)
				throw e
			}
			if (fatalScan.count > 0) {
				logger.error {
					"[aiSweep] $runId recorded ${fatalScan.count} FATAL SimulationException occurrence(s) — " +
						"first: ${fatalScan.firstMessage}"
				}
			}
		} catch (e: IOException) {
			logger.warn(e) { "[aiSweep] could not patch $runId's run JSON with its FATAL-scan result" }
		} catch (e: SerializationException) {
			logger.warn(e) { "[aiSweep] could not patch $runId's run JSON with its FATAL-scan result — parse error" }
		}
	}

	/** Full path to the run JSON [RunSnapshotStore] wrote for [runId] under [root], or `null` if none. */
	private fun findRunFile(
		root: Path,
		runId: String
	): Path? {
		if (!Files.isDirectory(root)) return null
		return try {
			Files.walk(root).use { stream ->
				stream
					.asSequence()
					.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
					.firstOrNull { RUN_FILE_NAME.matchEntire(it.fileName.toString())?.groupValues?.get(1) == runId }
			}
		} catch (e: IOException) {
			logger.warn(e) { "[aiSweep] could not scan $root while looking for $runId's run file" }
			null
		}
	}

	private fun renderReport(outputRoot: Path) {
		val snapshots = store.readAll(outputRoot)
		if (snapshots.isEmpty()) {
			logger.warn { "[aiSweep] no run snapshots under $outputRoot — no report rendered" }
			return
		}
		val aggregator = RunReportAggregator(store)
		val byArm = snapshots.groupBy { it.arm }
		val reports =
			DispatcherArm.entries.map { arm ->
				aggregator.aggregate(byArm[arm] ?: emptyList()).let {
					// RunReportAggregator.aggregate() hardcodes arm = RULE_BASED for the empty-run-list
					// case (runCount == 0), since it has no snapshot to read the arm from. Patch the
					// correct arm back in here so an empty LLM-arm section doesn't misreport itself as
					// RULE_BASED in the rendered report.
					if (it.runCount == 0) it.copy(arm = arm) else it
				}
			}
		val reportFile = outputRoot.resolve("report.md")
		Files.writeString(reportFile, aggregator.renderMarkdown(reports))
		logger.info { "[aiSweep] report written to $reportFile (${snapshots.size} run(s))" }
	}

	private companion object {
		private const val NANOS_PER_SECOND: Long = 1_000_000_000L

		/** Sub-directory of the output root holding one log file per run. */
		private const val RUN_LOG_DIR: String = "logs"

		/** `<yyyyMMdd-HHmmss>-<runId>.json`, as written by `DefaultRunSnapshotStore`. */
		private val RUN_FILE_NAME = Regex("""\d{8}-\d{6}-(.+)\.json""")

		/** Mirrors `DefaultRunSnapshotStore`'s own [Json] config so a patched file reads identically. */
		private val json =
			Json {
				prettyPrint = true
				encodeDefaults = true
			}
	}
}

/** How one scheduled run ended, from the sweep's point of view. */
enum class SweepRunOutcome {
	/** The child exited 0 and its run JSON is present. */
	COMPLETED,

	/** The child failed, or exited 0 without recording anything. */
	FAILED,

	/** The child exceeded its wall-clock budget and was killed. */
	ABORTED
}

/**
 * What a sweep did.
 *
 * @property planned Runs the grid described.
 * @property skipped Runs whose result was already on disk (resumption).
 * @property completed Runs that finished and recorded a snapshot.
 * @property failed Runs that died or recorded nothing.
 * @property aborted Runs killed for exceeding the per-run timeout.
 */
data class SweepSummary(
	val planned: Int,
	val skipped: Int,
	val completed: Int,
	val failed: Int,
	val aborted: Int
)

/**
 * Everything a runner needs to launch one run.
 *
 * @property logFile Where the child's output is written. Each run gets its own file: the default
 *   logging configuration emits several hundred megabytes per 600 s run, so inheriting twenty of
 *   them into one stream is not viable, and `logback.xml`'s shared append-mode
 *   `logs/interlockSim.log` would merge every run of the sweep into a single unbounded file.
 */
data class SweepProcessRequest(
	val mainClass: String,
	val exampleName: String,
	val endTimeSeconds: Long,
	val systemProperties: Map<String, String>,
	val timeoutSeconds: Long,
	val logFile: Path
)

/**
 * What launching one run produced.
 *
 * @property exitCode The child's exit status, or `null` if it could not be launched or was killed.
 * @property timedOut `true` if the child was killed for exceeding its budget.
 */
data class SweepProcessResult(
	val exitCode: Int?,
	val timedOut: Boolean
)

/** Seam for launching one sweep run; substituted in tests. */
fun interface SweepProcessRunner {
	fun run(request: SweepProcessRequest): SweepProcessResult
}

/**
 * Launches each run as a child JVM reusing this process's own java binary and classpath.
 *
 * Reusing `ProcessHandle.current()`'s command and `java.class.path` rather than locating a shaded
 * jar means the sweep works identically from `java -jar interlockSim.jar` and from a Gradle
 * `JavaExec`, with nothing to keep in sync.
 */
class ForkedJvmSweepProcessRunner : SweepProcessRunner {
	override fun run(request: SweepProcessRequest): SweepProcessResult {
		val command =
			buildList {
				add(javaBinary())
				add("-cp")
				add(System.getProperty("java.class.path"))
				// Quietens the per-train-per-tick INFO logging that would otherwise make an
				// unattended sweep produce gigabytes, and keeps the child off logback.xml's
				// shared append-mode logs/interlockSim.log. See logback-sweep.xml for what is
				// deliberately left at INFO.
				add("-D$LOGBACK_CONFIG_PROPERTY=$SWEEP_LOGBACK_CONFIG")
				request.systemProperties.forEach { (key, value) -> add("-D$key=$value") }
				add(request.mainClass)
				add("example")
				add(request.exampleName)
				add(request.endTimeSeconds.toString())
			}
		logger.debug { "[aiSweep] exec: ${command.joinToString(" ")}" }

		val process =
			ProcessBuilder(command)
				.redirectErrorStream(true)
				.redirectOutput(request.logFile.toFile())
				.start()

		val finished = process.waitFor(request.timeoutSeconds, TimeUnit.SECONDS)
		if (!finished) {
			process.descendants().forEach { it.destroyForcibly() }
			process.destroyForcibly()
			process.waitFor(DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)
			return SweepProcessResult(exitCode = null, timedOut = true)
		}
		return SweepProcessResult(exitCode = process.exitValue(), timedOut = false)
	}

	private fun javaBinary(): String =
		ProcessHandle
			.current()
			.info()
			.command()
			.orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString())

	private companion object {
		private const val DESTROY_GRACE_SECONDS: Long = 10L
		private const val LOGBACK_CONFIG_PROPERTY: String = "logback.configurationFile"

		/** Classpath resource in `:desktop-ui`; see its own header comment for the rationale. */
		private const val SWEEP_LOGBACK_CONFIG: String = "logback-sweep.xml"
	}
}

/**
 * An all-zero snapshot standing in for a run that never got to record its own.
 *
 * Every rate is `0.0` and every counter `0` — not because those are measured values, but because
 * the run produced none. `completedNaturally = false` is what the aggregator's pass predicate
 * reads, so such a run counts against the arm exactly as it should.
 *
 * The railway figures are the exception: they are recorded **absent**, not zero. See the comment
 * at the `railwayOutcome` argument below. [fatalScan] follows the same absent-vs-zero rule.
 *
 * @param fatalScan The run's log scanned for FATAL `SimulationException` occurrences before this
 *   snapshot was built, so an aborted or crashed run can still carry the finding — a timeout or a
 *   crash does not rule out the log having recorded a FATAL earlier in the same run. Defaults to
 *   an absent scan for call sites (tests) that have no log to scan.
 */
internal fun abortedSnapshot(
	runId: String,
	arm: DispatcherArm,
	params: RunParameters,
	cause: RunEndCause,
	fatalScan: FatalExceptionScanResult = FatalExceptionScanResult()
): DispatcherRunSnapshot =
	DispatcherRunSnapshot(
		runId = runId,
		arm = arm,
		params = params,
		totalTicks = 0L,
		ticksByOutcome = zeroed(TickOutcome.entries.map { it.name }),
		timeoutNoOpByCause = zeroed(TimeoutNoOpCause.entries.map { it.name }),
		llmSuccessRate = 0.0,
		noOpRate = 0.0,
		invalidOutputRate = 0.0,
		repairSuccessRate = 0.0,
		emittedByActionType = emptyMap(),
		rejectionsByCode = zeroed(RejectionCode.entries.map { it.name }),
		applyFailuresByCode = zeroed(ApplyFailureCode.entries.map { it.name }),
		validAt1 = 0.0,
		correctAt1 = null,
		oracleAgreementAt1 = null,
		latencyP50Ms = 0L,
		latencyP95Ms = 0L,
		latencyMaxMs = 0L,
		actionsByAuthor = zeroed(ActionAuthor.entries.map { it.name }),
		unattributedApplies = 0L,
		terminalFallbackEngaged = false,
		terminalFallbackTickIndex = null,
		// Factually true — an aborted run attributed no action to RULE_FALLBACK or SAFETY_NET,
		// because it attributed no action at all. Reporting `false` would fail the whole arm's C7
		// gate for what is a liveness failure, conflating two independent verdicts; the run is
		// already counted against the arm through `completedNaturally = false`, which is what
		// RunReportAggregator.runPassed reads.
		c7Clean = true,
		completedNaturally = false,
		endCause = cause,
		// Issue #834 (SP2c.11): the one group of figures that must NOT be zeroed here. Every
		// counter above is zero because the run genuinely produced none; the railway figures are
		// absent because the run never got far enough for anything to read them. Writing 0 would
		// enter an unmeasured run into the sweep as one where no train moved — a measurement.
		railwayOutcome = RailwayOutcome.UNMEASURED,
		loggedFatalSimExceptionCount = fatalScan.count,
		loggedFatalSimExceptionFirstMessage = fatalScan.firstMessage
	)

private fun zeroed(keys: List<String>): Map<String, Long> = keys.associateWith { 0L }
