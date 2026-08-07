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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Exercises the real [ForkedJvmSweepProcessRunner] (SP2c.24 review follow-up, Issue #847).
 *
 * The [SweepProcessRunner] *seam* was covered from the start, but the only implementation that
 * ever runs in an actual sweep was not: command construction, `-D` forwarding, `java.class.path`
 * reuse, stdout redirection and the timeout kill all lived untested. Those are exactly the parts
 * that fail on a real machine rather than against a stub, and a sweep is unattended by design —
 * a runner defect surfaces as hours of silently empty results.
 *
 * These are integration tests: each forks a genuine JVM (into [SweepSmokeMain], not the simulator),
 * so they cost seconds rather than milliseconds and need no Ollama.
 *
 * @since Issue #847 (SP2c.24 — code-review follow-up)
 */
@Tag("integration-test")
@DisplayName("SP2c.24 — ForkedJvmSweepProcessRunner forks a real child JVM (#847)")
class ForkedJvmSweepProcessRunnerTest {
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a child that exits normally reports its exit code, gets its -D properties and is logged")
	fun launchesChildAndCapturesOutput(
		@TempDir tempDir: Path
	) {
		val sentinel = tempDir.resolve("sentinel.txt")
		val logFile = tempDir.resolve("run.log")

		val result =
			ForkedJvmSweepProcessRunner().run(
				SweepProcessRequest(
					mainClass = SweepSmokeMain::class.java.name,
					exampleName = "smokeExample",
					endTimeSeconds = 7L,
					systemProperties = mapOf(SweepSmokeMain.SENTINEL_PROPERTY to sentinel.toString()),
					timeoutSeconds = 90L,
					logFile = logFile
				)
			)

		assertThat(result.timedOut).isFalse()
		assertThat(result.exitCode).isEqualTo(0)

		// The sentinel proves the -D property reached the child AND that the child ran at all —
		// a runner that silently failed to launch would leave it absent.
		assertThat(sentinel.exists()).isTrue()
		// The production argument shape: `example <name> <endTime>`. Asserted here so a change to
		// the command the runner builds cannot pass unnoticed.
		assertThat(sentinel.readText()).isEqualTo("example smokeExample 7")

		// stdout redirection is what makes an unattended sweep diagnosable after the fact.
		assertThat(logFile.exists()).isTrue()
		assertThat(logFile.readText()).contains("SweepSmokeMain args=example smokeExample 7")
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("a child that overruns its budget is killed and reported as timedOut with no exit code")
	fun killsChildThatExceedsItsTimeout(
		@TempDir tempDir: Path
	) {
		// The behaviour the whole sweep depends on: one hung run must not stall the grid. A child
		// that ignores its budget is destroyed, and the absent exit code is what makes
		// AiSweepDriver record TIMEOUT_ABORT rather than an absent (silently improving) run.
		val result =
			ForkedJvmSweepProcessRunner().run(
				SweepProcessRequest(
					mainClass = SweepSmokeMain::class.java.name,
					exampleName = "smokeExample",
					endTimeSeconds = 7L,
					systemProperties = mapOf(SweepSmokeMain.SLEEP_PROPERTY to "true"),
					timeoutSeconds = 2L,
					logFile = tempDir.resolve("timeout.log")
				)
			)

		assertThat(result.timedOut).isTrue()
		assertThat(result.exitCode).isNull()
	}
}
