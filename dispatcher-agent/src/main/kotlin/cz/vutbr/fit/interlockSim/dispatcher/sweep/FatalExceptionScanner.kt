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
 * What one run's log revealed about `SimulationException[FATAL]` occurrences.
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
 * Scans a sweep run's per-run log file for evidence that a `FATAL` `SimulationException` was
 * thrown — and swallowed — during the run.
 *
 * ## Why this can only be detected from the log, not from the process
 *
 * kDisco launches every simulation process on a dedicated `SupervisorJob` with no
 * `CoroutineExceptionHandler` — `Simulation.kt:101`, commented "so one process failure doesn't
 * cancel others" — and the surrounding `catch` only covers `ProcessTerminatedException`
 * (`Simulation.kt:163-175`). A `FATAL` [cz.vutbr.fit.interlockSim.exceptions.SimulationException]
 * thrown from inside a `Train` process coroutine (`process.actions()`, launched at
 * `Simulation.kt:163`) is therefore never caught anywhere inside the simulation: it becomes an
 * *uncaught* coroutine exception, and with no handler installed, kotlinx.coroutines' JVM default
 * falls through to `Thread.uncaughtExceptionHandler`, which prints it via
 * `Throwable.printStackTrace()` to `System.err`.
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
 * [ForkedJvmSweepProcessRunner] redirects the child's stderr into its per-run log file
 * (`redirectErrorStream(true)` + `redirectOutput(logFile)`), so that evidence lands exactly where
 * this scanner reads it.
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
 *
 * The switch/track sites above are ordinary *reachable* failure paths — unlike Issue #905 (a
 * FATAL from `Front.separatorAction`, `Train.kt:644`, on an origin-abandon path), which the
 * traffic-simulation-expert ruled "not reachable on the current wiring" for the examples this
 * sweep runs. This scanner does not depend on any single defect being reachable — only on the
 * `SupervisorJob` absorption mechanism being real for *any* FATAL `SimulationException`, and #905
 * remains useful context for what an absorbed FATAL looks like end to end.
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
