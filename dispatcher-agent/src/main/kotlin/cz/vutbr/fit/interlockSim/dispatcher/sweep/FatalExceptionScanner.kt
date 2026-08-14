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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * What one run's log revealed about `SimulationException[FATAL]` log occurrences.
 *
 * ## What is counted
 *
 * [count] counts every line in the run's log that matches [FatalExceptionScanner.FATAL_MARKER_PATTERN]
 * — i.e. every `SimulationException` (or subclass) that logged `[FATAL]: … at time` anywhere in
 * its output. This covers:
 *
 * - caught exceptions whose handler called `logger.warn(e)` with the throwable attached (Logback
 *   appends the exception's `toString()` to the log line), and
 * - genuinely uncaught exceptions that the JVM's `Thread.uncaughtExceptionHandler` printed via
 *   `Throwable.printStackTrace()` to `System.err` (which the sweep driver redirects into the same
 *   log file).
 *
 * The scanner cannot distinguish these two cases from the log text alone. See
 * [FatalExceptionScanner] for the full rationale.
 *
 * ## Absent is not zero
 *
 * [count] is `null` when the log could not be scanned at all (missing file, unreadable file) —
 * "not measured", never "measured as none". This follows the same convention already established
 * for [cz.vutbr.fit.interlockSim.dispatcher.planner.RailwayOutcome]: collapsing the two would let
 * a run whose log the sweep never got to read be reported as clean, silently improving the arm it
 * belongs to. `count = 0` is the honest positive finding that the log was read in full and
 * contained no FATAL marker.
 *
 * @property count Number of [FatalExceptionScanner.FATAL_MARKER_PATTERN] occurrences found in the
 *   run's log; `null` if the scan itself could not run.
 * @property firstMessage The first matching log line, verbatim (trimmed); `null` when [count] is
 *   `null` or `0`.
 */
data class FatalExceptionScanResult(
	val count: Long? = null,
	val firstMessage: String? = null
)

/**
 * Scans a sweep run's per-run log file for `FATAL`-severity `SimulationException` occurrences.
 *
 * ## What this scanner detects
 *
 * [scan] counts every log line that matches [FATAL_MARKER_PATTERN] — a `[FATAL]: … at time`
 * fragment produced by `SimulationException.toString()` (or any subclass that does not override
 * `toString()`). A match can originate from two sources:
 *
 * - A **caught-and-logged** exception whose catch handler called `logger.warn(e)` with the
 *   throwable attached — Logback appends the exception's `toString()` to the log line.
 * - A **genuinely uncaught** exception absorbed by kDisco's `SupervisorJob` (no
 *   `CoroutineExceptionHandler` installed — `Simulation.kt:101`, `catch` at `:163-175` covers
 *   only `ProcessTerminatedException`) — in that case kotlinx.coroutines falls through to
 *   `Thread.uncaughtExceptionHandler`, which prints via `Throwable.printStackTrace()` to
 *   `System.err`. [ForkedJvmSweepProcessRunner] redirects `System.err` into the per-run log
 *   (`redirectErrorStream(true)` + `redirectOutput(logFile)`), so the text lands where this
 *   scanner reads it.
 *
 * The scanner **cannot distinguish these two cases** from the log text alone. A nonzero
 * [FatalExceptionScanResult.count] means the log contained at least one FATAL marker of
 * *either* kind — see [DispatcherRunSnapshot.loggedFatalSimExceptionCount]'s KDoc for how that
 * finding should be interpreted.
 *
 * ## Why the pattern is subclass-agnostic
 *
 * `SimulationException.toString()` builds its text from the **runtime** class's simple name, not
 * the literal text `"SimulationException"` (`SimulationException.kt:82-85`: `this::class.simpleName`
 * followed by the severity in square brackets, the message, and the model time). Two
 * subclasses exist, neither overrides `toString()`, and both default to `Severity.FATAL`:
 * [cz.vutbr.fit.interlockSim.exceptions.PathSeparatorChangeException] (thrown from
 * `DynamicRailSwitch.getPathConfWithException`/`setUpPath`/`cancelPathSetup` — i.e. switch path
 * setup during ordinary route reservation, plus `DynamicInOut`/`DynamicRailSemaphore`) and
 * [cz.vutbr.fit.interlockSim.exceptions.TrackOperationException] (`AbstractPath`, `DynamicTrack`,
 * `DynamicTrackBlock`). A real occurrence therefore does **not** necessarily start with the text
 * `SimulationException` — e.g. `PathSeparatorChangeException[FATAL]: switch doesn't join this
 * segments at time 12.5` is exactly as real a FATAL as `SimulationException[FATAL]: ...` is, and a
 * scanner keyed on the literal `SimulationException[FATAL]` prefix would silently miss it,
 * reporting `count = 0` — a *positive clean finding* — for a run that was not clean. [scan] must
 * therefore match on the severity bracket, not the class-name prefix, and stay correct for any
 * future `SimulationException` subclass without needing to enumerate it by name: see
 * [FATAL_MARKER_PATTERN].
 *
 * ## Why not capture it in-process instead
 *
 * A `CoroutineExceptionHandler` installed on the simulation's scope would be strictly more
 * precise (no string matching, no dependency on default JVM uncaught-exception printing). It was
 * rejected here because the scope in question is created inside `Simulation.kt`, which lives in
 * kDisco — a separate repository this project does not modify (see the root `CLAUDE.md`, "kDisco
 * Library: Do not modify"). Wiring a hook into it from this project's side would still require
 * touching `:core` (where [cz.vutbr.fit.interlockSim.exceptions.SimulationException] and the
 * simulation-scope wiring live), and that is the one thing this fix must not do: it exists to
 * measure the sweep without moving the rule-based control arm's code on the eve of the
 * measurement run, and a control arm measured on different code is not a control arm. Log
 * scanning is therefore not a compromise chosen for convenience — every option that stays out of
 * `core/` has to observe the run from the outside, and the log is the only outside artifact this
 * driver already keeps per run.
 */
object FatalExceptionScanner {
	/**
	 * Matches the severity bracket [SimulationException.toString] produces for a FATAL, regardless
	 * of which subclass threw it.
	 *
	 * Deliberately **not** anchored on `SimulationException` (or any other class-name prefix): the
	 * class-name portion of `toString()` is `this::class.simpleName`, which varies by subclass, so
	 * a future `SimulationException` subclass must not be able to silently defeat this scanner the
	 * way a name-prefix match already did once (see this object's own KDoc). The trailing
	 * `at time` fragment is included so this pattern cannot match a
	 * `cz.vutbr.fit.interlockSim.exceptions.EditorException` occurrence: `EditorException` shares
	 * the identical class-name/severity/message `toString()` shape but has no time component, so
	 * the two hierarchies are otherwise textually indistinguishable.
	 *
	 * This pattern matches any line that contains the FATAL marker, whether the exception was
	 * caught and logged (e.g. via `logger.warn(e)`) or uncaught and printed by the JVM's
	 * `Thread.uncaughtExceptionHandler`. The scanner cannot distinguish the two cases from the log
	 * text alone; see [FatalExceptionScanner]'s KDoc and
	 * [DispatcherRunSnapshot.loggedFatalSimExceptionCount] for how that is documented.
	 */
	internal val FATAL_MARKER_PATTERN: Regex = Regex("""\[FATAL]:.*\bat time\b""")

	/**
	 * Scans [logFile] line by line for [FATAL_MARKER_PATTERN].
	 *
	 * Never throws: a missing or unreadable file yields a [FatalExceptionScanResult] with
	 * `count = null` rather than propagating, so callers can record "not measured" without
	 * special-casing I/O failure — the same must-not-fail-the-run requirement every other
	 * best-effort step in the sweep driver already follows (see [AiSweepDriver.writeAbortSnapshot]).
	 */
	fun scan(logFile: Path): FatalExceptionScanResult {
		if (!Files.isRegularFile(logFile)) {
			logger.debug { "[aiSweep] no log file at $logFile to scan for FATAL SimulationException occurrences" }
			return FatalExceptionScanResult()
		}
		return try {
			var count = 0L
			var firstMessage: String? = null
			Files.newBufferedReader(logFile).use { reader ->
				reader.lineSequence().forEach { line ->
					if (FATAL_MARKER_PATTERN.containsMatchIn(line)) {
						count++
						if (firstMessage == null) {
							firstMessage = line.trim()
						}
					}
				}
			}
			FatalExceptionScanResult(count = count, firstMessage = firstMessage)
		} catch (e: IOException) {
			logger.warn(e) { "[aiSweep] could not scan $logFile for FATAL SimulationException occurrences" }
			FatalExceptionScanResult()
		}
	}
}
