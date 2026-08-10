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
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.exceptions.EditorException
import cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException
import cz.vutbr.fit.interlockSim.exceptions.Severity
import cz.vutbr.fit.interlockSim.exceptions.SimulationException
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
 * Unit tests for [FatalExceptionScanner]: the measurement-integrity fix that gives the sweep a
 * way to see when a `FATAL` `SimulationException` was thrown and swallowed by kDisco's
 * `SupervisorJob` (see [FatalExceptionScanner]'s own KDoc for why this can only be observed by
 * scanning the run's log, not by changing `:core`).
 *
 * ## Fixtures are derived from real exceptions, not hand-typed literals
 *
 * An earlier version of [FatalExceptionScanner] matched on the literal prefix
 * `"SimulationException[FATAL]"`, and every test here hand-typed that same literal into its log
 * fixtures — so the suite proved the scanner matched what the fixtures assumed, not what the
 * codebase's real producers render. `SimulationException.toString()` builds its text from
 * `this::class.simpleName`, and the actually-reachable FATAL producers
 * ([PathSeparatorChangeException], thrown from switch path setup during ordinary route
 * reservation, and [TrackOperationException]) both render under their own subclass name, never
 * under `"SimulationException"`. The tests below construct real instances of both subclasses (and
 * the base class) and scan their actual `toString()` output, so the fixtures are pinned to what
 * the codebase produces, not to an assumption about it.
 *
 * The property under test throughout is absent-vs-zero: a log that could not be read must report
 * `count = null` ("not measured"), and a log that was read in full but is clean must report
 * `count = 0` ("measured, and found none") — never the same value for both.
 */
class FatalExceptionScannerTest {
	@Test
	@DisplayName("a real PathSeparatorChangeException[FATAL] line — the reachable switch-setup producer — is detected")
	fun detectsARealPathSeparatorChangeExceptionLine(
		@TempDir tempDir: Path
	) {
		val realLine =
			PathSeparatorChangeException(
				"switch doesn't join this segments",
				mockk<PathSeparator>(relaxed = true)
			).toString()
		// Sanity check on the fixture itself: this is exactly the case that defeated a scanner
		// keyed on the "SimulationException" class-name prefix.
		assertThat(realLine).contains("PathSeparatorChangeException[FATAL]:")
		assertThat(realLine).doesNotContain("SimulationException[FATAL]")

		val log = tempDir.resolve("run.log")
		Files.writeString(log, realLine)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(1L)
		assertThat(result.firstMessage).isEqualTo(realLine)
	}

	@Test
	@DisplayName("a real TrackOperationException[FATAL] line — the reachable track-operation producer — is detected")
	fun detectsARealTrackOperationExceptionLine(
		@TempDir tempDir: Path
	) {
		val realLine =
			TrackOperationException(
				"track operation failed",
				mockk<StaticTrack>(relaxed = true)
			).toString()
		assertThat(realLine).contains("TrackOperationException[FATAL]:")
		assertThat(realLine).doesNotContain("SimulationException[FATAL]")

		val log = tempDir.resolve("run.log")
		Files.writeString(log, realLine)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(1L)
		assertThat(result.firstMessage).isEqualTo(realLine)
	}

	@Test
	@DisplayName("a real base SimulationException[FATAL] line is still detected")
	fun detectsARealBaseSimulationExceptionLine(
		@TempDir tempDir: Path
	) {
		val realLine = SimulationException("pathToSemaphore null").toString()
		assertThat(realLine).contains("SimulationException[FATAL]:")

		val log = tempDir.resolve("run.log")
		Files.writeString(log, realLine)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(1L)
		assertThat(result.firstMessage).isEqualTo(realLine)
	}

	@Test
	@DisplayName("a log mixing real lines from three different SimulationException subclasses counts all three")
	fun countsRealLinesFromDifferentSubclassesTogether(
		@TempDir tempDir: Path
	) {
		val first = SimulationException("first failure").toString()
		val second =
			PathSeparatorChangeException("switch doesn't join this segments", mockk<PathSeparator>(relaxed = true))
				.toString()
		val third = TrackOperationException("track operation failed", mockk<StaticTrack>(relaxed = true)).toString()

		val log = tempDir.resolve("run.log")
		Files.writeString(log, listOf(first, second, third).joinToString("\n"))

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(3L)
		// Only the first line is kept as the sample, regardless of which subclass produced it.
		assertThat(result.firstMessage).isEqualTo(first)
	}

	@Test
	@DisplayName("a clean log reports count = 0, not absent — it was read in full and found nothing")
	fun cleanLogReportsZeroNotAbsent(
		@TempDir tempDir: Path
	) {
		val log = tempDir.resolve("run.log")
		Files.writeString(
			log,
			"""
			2026-08-09 12:00:00 INFO  Simulation starting
			2026-08-09 12:00:05 INFO  Simulation completed normally
			""".trimIndent()
		)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(0L)
		assertThat(result.firstMessage).isNull()
	}

	@Test
	@DisplayName("a non-FATAL severity on a real SimulationException does not trip the FATAL marker")
	fun nonFatalSeverityIsNotCountedAsFatal(
		@TempDir tempDir: Path
	) {
		val realLine = SimulationException(Severity.WARN, "something recoverable happened", null, null).toString()
		assertThat(realLine).contains("[WARN]:")

		val log = tempDir.resolve("run.log")
		Files.writeString(log, realLine)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(0L)
		assertThat(result.firstMessage).isNull()
	}

	@Test
	@DisplayName(
		"a real EditorException[FATAL] line — same class-name/severity/message shape, no time component — " +
			"is not mistaken for a SimulationException FATAL"
	)
	fun editorExceptionFatalIsNotCountedAsSimulationExceptionFatal(
		@TempDir tempDir: Path
	) {
		val realLine = EditorException("operator cancelled the edit").toString()
		// Sanity check: EditorException really does share the ClassName[SEVERITY]: message shape
		// that makes a bracket-only marker risky.
		assertThat(realLine).contains("EditorException[FATAL]:")

		val log = tempDir.resolve("run.log")
		Files.writeString(log, realLine)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(0L)
		assertThat(result.firstMessage).isNull()
	}

	@Test
	@DisplayName("a missing log file reports count = null — absent, never zero")
	fun missingLogFileReportsAbsentNotZero(
		@TempDir tempDir: Path
	) {
		val result = FatalExceptionScanner.scan(tempDir.resolve("does-not-exist.log"))

		assertThat(result.count).isNull()
		assertThat(result.firstMessage).isNull()
	}

	@Test
	@DisplayName("a directory passed instead of a file reports count = null rather than throwing")
	fun directoryInsteadOfFileReportsAbsentNotThrowing(
		@TempDir tempDir: Path
	) {
		val result = FatalExceptionScanner.scan(tempDir)

		assertThat(result.count).isNull()
		assertThat(result.firstMessage).isNull()
	}
}
