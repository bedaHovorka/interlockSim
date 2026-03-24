/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Cross-platform parity test: launches JVM JAR and native binary as separate
 * processes and compares structural invariants of their text output (Issue #439).
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ProcessBuilder-based cross-platform parity test that launches both the JVM shadow JAR
 * and the native linuxX64 binary as external processes, captures their stdout/stderr,
 * and compares structural invariants side-by-side.
 *
 * This test complements the in-process parity tests:
 * - [JvmParityReferenceTest] (in `:core` jvmTest) — JVM in-process invariant checks
 * - `NativeJvmParityTest` (in `:fast-sim` linuxX64Test) — Native in-process invariant checks
 *
 * Unlike those tests, this one verifies that the **CLI entry points** of both platforms
 * produce structurally equivalent text output when invoked as real processes.
 *
 * **Prerequisites** (both artifacts must be built before running):
 * ```
 * ./gradlew :desktop-ui:shadowJar :fast-sim:linkReleaseExecutableLinuxX64
 * ```
 *
 * If artifacts are missing, the test is skipped via [assumeTrue] (no failure).
 *
 * **Structural invariants compared:**
 * 1. Both processes exit with code 0
 * 2. Both produce non-empty event lines (format: `t=<time> [TYPE] ...`)
 * 3. Both produce a summary line (format: `--- Simulation complete: N trains, ...`)
 * 4. Timestamps are chronologically ordered in both outputs
 * 5. Both summaries mention trains
 * 6. Event counts are within the same order of magnitude (ratio 0.5x–2.0x)
 *
 * Exact event counts and timestamps are **not** compared because kDisco.Random
 * may produce different sequences on JVM vs native.
 *
 * @since Issue #439 (ProcessBuilder-based cross-platform parity)
 * @see JvmParityReferenceTest
 */
@Tag("integration-test")
@DisplayName("Cross-Platform Parity Tests (Issue #439)")
class CrossPlatformParityTest {

	companion object {
		private const val TIMEOUT_SECONDS = 120L

		/**
		 * Paths relative to the Gradle test working directory (`desktop-ui/`).
		 * - JAR: `desktop-ui/build/libs/interlockSim.jar` → `build/libs/interlockSim.jar`
		 * - Native: `fast-sim/build/bin/...` → `../fast-sim/build/bin/...`
		 */
		private val JAR_PATH = File("build/libs/interlockSim.jar")
		private val NATIVE_PATH = File("../fast-sim/build/bin/linuxX64/releaseExecutable/fast-sim.kexe")

		private val timestampRegex = Regex("""t=([\d.]+)\s+""")
	}

	data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

	/**
	 * Launches a process with the given command, captures stdout and stderr in
	 * separate threads (to avoid ProcessBuilder deadlock), and enforces a timeout.
	 *
	 * @throws AssertionError if the process does not complete within [TIMEOUT_SECONDS]
	 */
	private fun runProcess(command: List<String>): ProcessResult {
		val process = ProcessBuilder(command)
			.redirectErrorStream(false)
			.start()

		var stdout = ""
		var stderr = ""
		val stdoutReader = Thread { stdout = process.inputStream.bufferedReader().readText() }.apply { isDaemon = true }
		val stderrReader = Thread { stderr = process.errorStream.bufferedReader().readText() }.apply { isDaemon = true }
		stdoutReader.start()
		stderrReader.start()

		val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
		if (!completed) {
			process.destroyForcibly()
			stdoutReader.join(5000)
			stderrReader.join(5000)
			throw AssertionError(
				"Process timed out after ${TIMEOUT_SECONDS}s: ${command.joinToString(" ")}\nstderr: $stderr"
			)
		}
		stdoutReader.join()
		stderrReader.join()
		return ProcessResult(process.exitValue(), stdout, stderr)
	}

	/** Extracts event lines (those containing `t=<number>`) from process stdout. */
	private fun parseEvents(output: String): List<String> =
		output.lines().filter { timestampRegex.containsMatchIn(it) }

	/** Extracts the last summary line (starting with `---`) from process stdout. */
	private fun parseSummary(output: String): String? =
		output.lines().lastOrNull { it.startsWith("---") }

	/** Extracts numeric timestamp values from event lines. */
	private fun parseTimestamps(events: List<String>): List<Double> =
		events.mapNotNull { timestampRegex.find(it)?.groupValues?.get(1)?.toDouble() }

	/** Builds a side-by-side diagnostic string for assertion failure messages. */
	private fun diagnostics(jvmResult: ProcessResult, nativeResult: ProcessResult): String = buildString {
		appendLine("=== JVM (exit=${jvmResult.exitCode}) ===")
		appendLine("STDOUT:\n${jvmResult.stdout}")
		if (jvmResult.stderr.isNotBlank()) appendLine("STDERR:\n${jvmResult.stderr}")
		appendLine("=== Native (exit=${nativeResult.exitCode}) ===")
		appendLine("STDOUT:\n${nativeResult.stdout}")
		if (nativeResult.stderr.isNotBlank()) appendLine("STDERR:\n${nativeResult.stderr}")
	}

	@Test
	fun `JVM and native produce structurally equivalent output`() {
		assumeTrue(JAR_PATH.exists()) {
			"JVM JAR not found at ${JAR_PATH.absolutePath} — run ./gradlew :desktop-ui:shadowJar first"
		}
		assumeTrue(NATIVE_PATH.exists()) {
			"Native binary not found at ${NATIVE_PATH.absolutePath} — " +
				"run ./gradlew :fast-sim:linkReleaseExecutableLinuxX64 first"
		}

		val jvmResult = runProcess(
			listOf("java", "-jar", JAR_PATH.absolutePath, "example", "shuntingLoop", "60")
		)
		val nativeResult = runProcess(
			listOf(NATIVE_PATH.absolutePath, "example", "shuntingLoop", "60")
		)

		val diag = diagnostics(jvmResult, nativeResult)

		// Invariant 1: Both should exit 0
		assertThat(jvmResult.exitCode, name = "JVM exit code\n$diag").isEqualTo(0)
		assertThat(nativeResult.exitCode, name = "Native exit code\n$diag").isEqualTo(0)

		val jvmEvents = parseEvents(jvmResult.stdout)
		val nativeEvents = parseEvents(nativeResult.stdout)
		val jvmSummary = parseSummary(jvmResult.stdout)
		val nativeSummary = parseSummary(nativeResult.stdout)

		// Invariant 2: Both have events
		assertThat(jvmEvents, name = "JVM events\n$diag").isNotEmpty()
		assertThat(nativeEvents, name = "Native events\n$diag").isNotEmpty()

		// Invariant 3: Both have summaries
		assertThat(jvmSummary, name = "JVM summary\n$diag").isNotNull()
		assertThat(nativeSummary, name = "Native summary\n$diag").isNotNull()

		// Invariant 4: Timestamps are chronological in both
		val jvmTs = parseTimestamps(jvmEvents)
		val nativeTs = parseTimestamps(nativeEvents)
		assertThat(jvmTs, name = "JVM timestamps\n$diag").isNotEmpty()
		assertThat(nativeTs, name = "Native timestamps\n$diag").isNotEmpty()
		for (i in 1 until jvmTs.size) {
			assertThat(jvmTs[i] >= jvmTs[i - 1], name = "JVM chronological at $i: ${jvmTs[i]} >= ${jvmTs[i - 1]}").isTrue()
		}
		for (i in 1 until nativeTs.size) {
			assertThat(nativeTs[i] >= nativeTs[i - 1], name = "Native chronological at $i: ${nativeTs[i]} >= ${nativeTs[i - 1]}").isTrue()
		}

		// Invariant 5: Both mention trains in summary
		assertThat(jvmSummary!!.contains("trains"), name = "JVM summary mentions trains").isTrue()
		assertThat(nativeSummary!!.contains("trains"), name = "Native summary mentions trains").isTrue()

		// Invariant 6: Event counts within same order of magnitude
		// (not exact — kDisco.Random may produce different sequences on JVM vs native)
		val ratio = jvmEvents.size.toDouble() / nativeEvents.size.toDouble()
		assertThat(ratio, name = "Event count ratio (JVM=${jvmEvents.size}, Native=${nativeEvents.size})\n$diag")
			.isBetween(0.5, 2.0)
	}
}
