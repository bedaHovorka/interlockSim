/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.fastsim

import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.persistence.RecordedRun
import cz.vutbr.fit.interlockSim.persistence.RunRecordJsonWriter
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.localtime_r
import platform.posix.strftime
import platform.posix.time
import platform.posix.time_tVar
import platform.posix.tm

/** The flag that makes `example` mode write its run JSON: `--runs-root=<dir>`, one token (Issue #1327). */
internal const val CMD_RUNS_ROOT_PREFIX = "--runs-root="

/** `strftime` pattern of the run JSON file name, `yyyyMMdd-HHmmss` as `DefaultRunSnapshotStore` writes it. */
private const val TIMESTAMP_PATTERN = "%Y%m%d-%H%M%S"

/** Bytes for one [TIMESTAMP_PATTERN] result and its terminating NUL, with room to spare. */
private const val TIMESTAMP_BUFFER_SIZE = 32

/**
 * The directory of the `--runs-root=<dir>` flag in [args], or `null` when the flag is absent.
 *
 * @throws IllegalArgumentException if the flag names no directory.
 */
internal fun parseRunsRoot(args: Array<String>): String? {
	val flag = args.lastOrNull { it.startsWith(CMD_RUNS_ROOT_PREFIX) } ?: return null
	val directory = flag.removePrefix(CMD_RUNS_ROOT_PREFIX)
	require(directory.isNotEmpty()) {
		"$CMD_RUNS_ROOT_PREFIX needs a directory, for example ${CMD_RUNS_ROOT_PREFIX}build/runs"
	}
	return directory
}

/** The local time now as `yyyyMMdd-HHmmss`, the start of a run JSON file name on the JVM. */
@OptIn(ExperimentalForeignApi::class)
internal fun localTimestamp(): String =
	memScoped {
		val now = alloc<time_tVar>()
		now.value = time(null)
		val local = alloc<tm>()
		localtime_r(now.ptr, local.ptr)
		val buffer = allocArray<ByteVar>(TIMESTAMP_BUFFER_SIZE)
		strftime(buffer, TIMESTAMP_BUFFER_SIZE.convert(), TIMESTAMP_PATTERN, local.ptr)
		buffer.toKString()
	}

/**
 * Persists the run JSON of one native `example` run under [runsRoot] (Issue #1327, Goal 6).
 *
 * The JSON is `:core`'s [RunRecordJsonWriter] output for the run's record. The native side works out the
 * rest as follows:
 * - **run id**: a new random UUID ([RunRecordJsonWriter.newRunId]), the JVM default without a
 *   `-Dinterlocksim.dispatcher.runId`;
 * - **file name**: `<runsRoot>/<arm>/<local yyyyMMdd-HHmmss>-<run id>.json` ([localTimestamp]);
 * - **end cause, completedNaturally, arm, params**: decided by the writer from the record, [endTime] and
 *   whether the main process died, by the JVM's rules;
 * - **example**: the native example name as given on the command line ([example]).
 *
 * @property runsRoot The directory of the `--runs-root` flag.
 * @property example The example name the run was started with.
 * @property endTime The requested horizon in simulated seconds.
 */
internal class RunRecordPersistence(
	private val runsRoot: String,
	private val example: String,
	private val endTime: Long
) {
	/**
	 * Writes the run JSON of [env]'s ended run.
	 *
	 * @return the path written, or `null` when the run has no record (it has not ended).
	 * @throws IllegalStateException if a directory or the file cannot be written.
	 */
	fun persist(
		env: SimulationEnvironment,
		mainProcessFailed: Boolean,
		runId: String = RunRecordJsonWriter.newRunId(),
		timestamp: String = localTimestamp()
	): String? {
		val record = env.getMetricsServices().getRunRecordSource().runRecord() ?: return null
		val run = RecordedRun(record, runId, example, endTime.toDouble(), mainProcessFailed)
		return RunRecordJsonWriter.write(runsRoot, run, timestamp)
	}
}
