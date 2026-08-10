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
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
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
 * The property under test throughout is absent-vs-zero: a log that could not be read must report
 * `count = null` ("not measured"), and a log that was read in full but is clean must report
 * `count = 0` ("measured, and found none") — never the same value for both.
 */
class FatalExceptionScannerTest {
	@Test
	@DisplayName("a log containing the FATAL marker is detected, with the line captured verbatim")
	fun detectsAFatalMarker(
		@TempDir tempDir: Path
	) {
		val log = tempDir.resolve("run.log")
		Files.writeString(
			log,
			"""
			2026-08-09 12:00:00 INFO  Simulation starting
			Exception in thread "main" SimulationException[FATAL]: pathToSemaphore null at time 12.5
				at cz.vutbr.fit.interlockSim.sim.Train${'$'}Front.separatorAction(Train.kt:644)
			2026-08-09 12:00:01 INFO  Simulation continuing
			""".trimIndent()
		)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(1L)
		assertThat(result.firstMessage)
			.isEqualTo("""Exception in thread "main" SimulationException[FATAL]: pathToSemaphore null at time 12.5""")
	}

	@Test
	@DisplayName("multiple FATAL markers are all counted, and only the first line is kept as the sample")
	fun countsEveryOccurrenceButKeepsOnlyTheFirstMessage(
		@TempDir tempDir: Path
	) {
		val log = tempDir.resolve("run.log")
		Files.writeString(
			log,
			"""
			SimulationException[FATAL]: first failure at time 1.0
			SimulationException[FATAL]: second failure at time 2.0
			SimulationException[FATAL]: third failure at time 3.0
			""".trimIndent()
		)

		val result = FatalExceptionScanner.scan(log)

		assertThat(result.count).isEqualTo(3L)
		assertThat(result.firstMessage).isEqualTo("SimulationException[FATAL]: first failure at time 1.0")
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
	@DisplayName("a WARNING or other non-FATAL SimulationException severity does not trip the FATAL marker")
	fun nonFatalSeverityIsNotCountedAsFatal(
		@TempDir tempDir: Path
	) {
		val log = tempDir.resolve("run.log")
		Files.writeString(log, "SimulationException[WARNING]: something recoverable happened at time 4.0")

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
