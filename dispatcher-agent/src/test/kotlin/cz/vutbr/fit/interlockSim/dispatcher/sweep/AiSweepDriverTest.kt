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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.planner.DefaultRunSnapshotStore
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunEndCause
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.TrackOperationException
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.core.StaticTrack
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [AiSweepDriver] (SP2c.24, Issue #847).
 *
 * No JVM is forked and no Ollama is contacted: the [SweepProcessRunner] seam is substituted, and
 * a fake "child" writes a snapshot exactly as a real run would. The properties under test —
 * resumption, timeout accounting, and never losing a failed run — are the ones an unattended
 * multi-hour sweep depends on and the ones that are impossible to check by running it once.
 *
 * @since Issue #847 (SP2c.24 — headless N-run sweep driver and parameter grid)
 */
@DisplayName("SP2c.24 — AiSweepDriver: resumption, timeout accounting, progress (#847)")
class AiSweepDriverTest {
	@TempDir
	lateinit var outputRoot: Path

	private val mainClass = "cz.vutbr.fit.interlockSim.MainKt"

	private fun grid(
		repeat: Int = 2,
		temperatures: List<Double> = listOf(0.28)
	): SweepGrid =
		SweepGrid(
			endTimeSeconds = 60,
			repeat = repeat,
			perRunTimeoutSeconds = 30,
			axes = SweepAxes(temperature = temperatures)
		)

	/** Records every launch and writes a snapshot for the run id it was given, like a real child. */
	private inner class RecordingRunner(
		private val behaviour: (SweepProcessRequest) -> SweepProcessResult = {
			SweepProcessResult(exitCode = 0, timedOut = false)
		},
		private val writesSnapshot: Boolean = true
	) : SweepProcessRunner {
		val launched = mutableListOf<SweepProcessRequest>()

		override fun run(request: SweepProcessRequest): SweepProcessResult {
			launched += request
			val result = behaviour(request)
			if (writesSnapshot && result.exitCode == 0 && !result.timedOut) {
				val runId = request.systemProperties.getValue("interlocksim.dispatcher.runId")
				DefaultRunSnapshotStore(outputRoot).write(
					abortedSnapshot(
						runId,
						DispatcherArm.LLM_TOOL_CALLING,
						SweepAxes().cells().single().runParameters(),
						RunEndCause.NATURAL_COMPLETION
					).copy(completedNaturally = true)
				)
			}
			return result
		}
	}

	private fun driverWith(runner: SweepProcessRunner) =
		AiSweepDriver(store = DefaultRunSnapshotStore(outputRoot), processRunner = runner)

	@Test
	fun `a dry run enumerates every run and launches nothing`() {
		val runner = RecordingRunner()
		val summary = driverWith(runner).run(grid(repeat = 3), outputRoot, mainClass, dryRun = true)

		assertThat(summary.planned).isEqualTo(3)
		assertThat(runner.launched).isEmpty()
	}

	@Test
	fun `every planned run is launched once and counted as completed`() {
		val runner = RecordingRunner()
		val summary = driverWith(runner).run(grid(repeat = 2), outputRoot, mainClass)

		assertThat(runner.launched).hasSize(2)
		assertThat(summary.completed).isEqualTo(2)
		assertThat(summary.skipped).isEqualTo(0)
	}

	@Test
	@DisplayName("re-invoking after an interrupt skips the runs already on disk")
	fun resumesInsteadOfRestarting() {
		// The property the whole design turns on: a ten-run local-LLM sweep takes hours and will
		// be interrupted, and restarting from zero each time makes it unfinishable.
		driverWith(RecordingRunner()).run(grid(repeat = 3), outputRoot, mainClass)

		val secondRunner = RecordingRunner()
		val summary = driverWith(secondRunner).run(grid(repeat = 3), outputRoot, mainClass)

		assertThat(secondRunner.launched).isEmpty()
		assertThat(summary.skipped).isEqualTo(3)
		assertThat(summary.completed).isEqualTo(0)
	}

	@Test
	@DisplayName("resumption is per run, not all-or-nothing")
	fun resumesPartially() {
		driverWith(RecordingRunner()).run(grid(repeat = 1), outputRoot, mainClass)

		val secondRunner = RecordingRunner()
		val summary = driverWith(secondRunner).run(grid(repeat = 3), outputRoot, mainClass)

		assertThat(summary.skipped).isEqualTo(1)
		assertThat(secondRunner.launched).hasSize(2)
	}

	@Test
	@DisplayName("a timed-out run is recorded as TIMEOUT_ABORT rather than left absent")
	fun timeoutIsRecorded() {
		// An absent failed run silently improves the arm's measured success rate — the one thing a
		// measurement task must never do.
		val runner =
			RecordingRunner(behaviour = { SweepProcessResult(exitCode = null, timedOut = true) })
		val summary = driverWith(runner).run(grid(repeat = 1), outputRoot, mainClass)

		assertThat(summary.aborted).isEqualTo(1)
		val written = DefaultRunSnapshotStore(outputRoot).readAll(outputRoot)
		assertThat(written).hasSize(1)
		assertThat(written.single().endCause).isEqualTo(RunEndCause.TIMEOUT_ABORT)
		assertThat(written.single().completedNaturally).isEqualTo(false)
	}

	@Test
	@DisplayName("a child that dies without recording anything is written as CRASH")
	fun crashIsRecorded() {
		val runner =
			RecordingRunner(
				behaviour = { SweepProcessResult(exitCode = 3, timedOut = false) },
				writesSnapshot = false
			)
		val summary = driverWith(runner).run(grid(repeat = 1), outputRoot, mainClass)

		assertThat(summary.failed).isEqualTo(1)
		val written = DefaultRunSnapshotStore(outputRoot).readAll(outputRoot)
		assertThat(written.single().endCause).isEqualTo(RunEndCause.CRASH)
	}

	@Test
	@DisplayName("a clean exit that recorded nothing is a failure, not a success")
	fun silentNonRunIsAFailure() {
		// Exit 0 with no run JSON means the example ran without a dispatcher, or persistence
		// failed. Counting it as completed would pad the denominator with an empty measurement.
		val runner = RecordingRunner(writesSnapshot = false)
		val summary = driverWith(runner).run(grid(repeat = 1), outputRoot, mainClass)

		assertThat(summary.failed).isEqualTo(1)
		assertThat(summary.completed).isEqualTo(0)
	}

	@Test
	fun `a timed-out run is not retried on resume, but is not repeated either`() {
		val runner =
			RecordingRunner(behaviour = { SweepProcessResult(exitCode = null, timedOut = true) })
		driverWith(runner).run(grid(repeat = 1), outputRoot, mainClass)

		val secondRunner = RecordingRunner()
		val summary = driverWith(secondRunner).run(grid(repeat = 1), outputRoot, mainClass)

		assertThat(secondRunner.launched).isEmpty()
		assertThat(summary.skipped).isEqualTo(1)
	}

	@Test
	fun `each launch carries the cell parameters and its own deterministic run id`() {
		val runner = RecordingRunner()
		driverWith(runner).run(grid(repeat = 1, temperatures = listOf(0.28, 0.5)), outputRoot, mainClass)

		val temperatures =
			runner.launched.map { it.systemProperties.getValue("interlocksim.dispatcher.temperature") }
		assertThat(temperatures.toSet()).isEqualTo(setOf("0.28", "0.5"))

		val runIds = runner.launched.map { it.systemProperties.getValue("interlocksim.dispatcher.runId") }
		assertThat(runIds.toSet()).hasSize(2)
		assertThat(runner.launched.all { it.systemProperties["interlocksim.dispatcher.runsRoot"] == outputRoot.toString() })
			.isTrue()
	}

	/**
	 * Issue #893 iteration 2: `inferenceTimeoutSeconds` flows from the grid JSON through
	 * [SweepCell.systemProperties] into the child's `-D` flags exactly like `temperature` does above
	 * — this is the sweep-side half of making the LLM's per-cycle deadline grid-drivable.
	 */
	@Test
	@DisplayName("a grid-supplied inferenceTimeoutSeconds reaches the child as a -D property")
	fun inferenceTimeoutSecondsReachesTheChild() {
		val runner = RecordingRunner()
		val grid =
			SweepGrid(
				endTimeSeconds = 60,
				repeat = 1,
				perRunTimeoutSeconds = 30,
				axes = SweepAxes(inferenceTimeoutSeconds = listOf(90L))
			)
		driverWith(runner).run(grid, outputRoot, mainClass)

		val values =
			runner.launched.map { it.systemProperties.getValue("interlocksim.dispatcher.inferenceTimeoutSeconds") }
		assertThat(values).containsExactly("90")
	}

	/** The mirror case: an axis the grid never mentions must not appear in the child's -D flags at all. */
	@Test
	@DisplayName("an omitted inferenceTimeoutSeconds axis passes no -D property, so the child keeps its own default")
	fun omittedInferenceTimeoutSecondsReachesNoChildProperty() {
		val runner = RecordingRunner()
		driverWith(runner).run(grid(repeat = 1), outputRoot, mainClass)

		val hasProperty =
			runner.launched
				.single()
				.systemProperties
				.containsKey("interlocksim.dispatcher.inferenceTimeoutSeconds")
		assertThat(hasProperty).isFalse()
	}

	// ── FATAL-exception log scanning (measurement-integrity fix for #834's C2 condition) ──────

	private fun runnerWritingLogAndSnapshot(logContent: String): SweepProcessRunner =
		SweepProcessRunner { request ->
			Files.writeString(request.logFile, logContent)
			val runId = request.systemProperties.getValue("interlocksim.dispatcher.runId")
			DefaultRunSnapshotStore(outputRoot).write(
				abortedSnapshot(
					runId,
					DispatcherArm.LLM_TOOL_CALLING,
					SweepAxes().cells().single().runParameters(),
					RunEndCause.NATURAL_COMPLETION
				).copy(completedNaturally = true)
			)
			SweepProcessResult(exitCode = 0, timedOut = false)
		}

	@Test
	@DisplayName(
		"a real PathSeparatorChangeException[FATAL] line in the child's log — not a hand-typed " +
			"literal — is patched into the persisted run JSON"
	)
	fun fatalExceptionInLogIsPatchedIntoTheRunJson() {
		// PathSeparatorChangeException, not SimulationException: this is exactly the reachable
		// switch-setup producer that a class-name-prefix-only scanner would have missed, so this
		// test exercises the real end-to-end shape, not an assumed literal.
		val realLine =
			PathSeparatorChangeException(
				"switch doesn't join this segments",
				mockk<PathSeparator>(relaxed = true)
			).toString()
		driverWith(runnerWritingLogAndSnapshot("$realLine\n")).run(grid(repeat = 1), outputRoot, mainClass)

		val written = DefaultRunSnapshotStore(outputRoot).readAll(outputRoot)
		assertThat(written).hasSize(1)
		assertThat(written.single().fatalExceptionCount).isEqualTo(1L)
		assertThat(written.single().fatalExceptionFirstMessage).isEqualTo(realLine)
	}

	@Test
	@DisplayName("a run whose log is clean reports fatalExceptionCount = 0, not absent")
	fun cleanLogReportsZeroFatalExceptionsNotAbsent() {
		val runner = runnerWritingLogAndSnapshot("Simulation completed normally\n")
		driverWith(runner).run(grid(repeat = 1), outputRoot, mainClass)

		val written = DefaultRunSnapshotStore(outputRoot).readAll(outputRoot)
		assertThat(written.single().fatalExceptionCount).isEqualTo(0L)
		assertThat(written.single().fatalExceptionFirstMessage).isNull()
	}

	@Test
	@DisplayName("a run whose log was never written keeps fatalExceptionCount absent, not zero")
	fun missingLogKeepsFatalExceptionCountAbsent() {
		// The default RecordingRunner never touches request.logFile — the same shape as a real
		// ForkedJvmSweepProcessRunner that could not create the log file at all.
		driverWith(RecordingRunner()).run(grid(repeat = 1), outputRoot, mainClass)

		val written = DefaultRunSnapshotStore(outputRoot).readAll(outputRoot)
		assertThat(written.single().fatalExceptionCount).isNull()
	}

	@Test
	@DisplayName("a timed-out run still carries a FATAL finding (a real TrackOperationException line) from its log")
	fun timedOutRunStillCarriesAFatalFindingFromItsLog() {
		val realLine =
			TrackOperationException("track operation failed", mockk<StaticTrack>(relaxed = true)).toString()
		val runner =
			SweepProcessRunner { request ->
				Files.writeString(request.logFile, "$realLine\n")
				SweepProcessResult(exitCode = null, timedOut = true)
			}
		driverWith(runner).run(grid(repeat = 1), outputRoot, mainClass)

		val written = DefaultRunSnapshotStore(outputRoot).readAll(outputRoot)
		assertThat(written.single().endCause).isEqualTo(RunEndCause.TIMEOUT_ABORT)
		assertThat(written.single().fatalExceptionCount).isEqualTo(1L)
	}

	@Test
	fun `a completed sweep renders the report next to its own runs`() {
		driverWith(RecordingRunner()).run(grid(repeat = 2), outputRoot, mainClass)

		val report = outputRoot.resolve("report.md")
		assertThat(Files.exists(report)).isTrue()
		assertThat(Files.readString(report)).contains("## Parameter Sweep")
	}
}
