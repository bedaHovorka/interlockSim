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

/** The flag name without its value; given alone (`--runs-root <dir>`) it is an argument error. */
private const val CMD_RUNS_ROOT = "--runs-root"

/** `strftime` pattern of the run JSON file name, `yyyyMMdd-HHmmss` as `DefaultRunSnapshotStore` writes it. */
private const val TIMESTAMP_PATTERN = "%Y%m%d-%H%M%S"

/** Bytes for one [TIMESTAMP_PATTERN] result and its terminating NUL, with room to spare. */
private const val TIMESTAMP_BUFFER_SIZE = 32

/**
 * The directory of the `--runs-root=<dir>` flag in [args], or `null` when the flag is absent.
 *
 * @throws IllegalArgumentException if the flag names no directory, is given as two tokens
 *   (`--runs-root <dir>`), which would leave the directory among the positional arguments, or is
 *   repeated -- the last one silently winning would hide which run went to which root.
 */
internal fun parseRunsRoot(args: Array<String>): String? {
	require(
		CMD_RUNS_ROOT !in args
	) { "$CMD_RUNS_ROOT takes its directory in the same token: ${CMD_RUNS_ROOT_PREFIX}<dir>" }
	val flags = args.filter { it.startsWith(CMD_RUNS_ROOT_PREFIX) }
	require(flags.size <= 1) { "$CMD_RUNS_ROOT_PREFIX given ${flags.size} times" }
	val flag = flags.lastOrNull() ?: return null
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
 * - **example**: the JVM example the native one equals ([NativeExampleRegistry.jvmTwinOf]), so the
 *   report files the run with its JVM twin and not with the dispatcher-agent runs of the plain name;
 * - **SIGINT**: an interrupted run is not persisted ([PersistOutcome.Interrupted]): it is not a measured
 *   run, and the JVM has no such path.
 *
 * @property runsRoot The directory of the `--runs-root` flag.
 * @property example The example name the run was started with.
 * @property endTime The requested horizon in simulated seconds.
 * @property interrupted Whether the process received SIGINT; [isInterrupted] by default.
 */
internal class RunRecordPersistence(
	private val runsRoot: String,
	private val example: String,
	private val endTime: Long,
	private val interrupted: () -> Boolean = ::isInterrupted
) {
	/**
	 * Writes the run JSON of [env]'s ended run.
	 *
	 * @return what was done: the path written, or why nothing was.
	 * @throws IllegalStateException if a directory or the file cannot be written.
	 */
	fun persist(
		env: SimulationEnvironment,
		mainProcessFailed: Boolean,
		runId: String = RunRecordJsonWriter.newRunId(),
		timestamp: String = localTimestamp()
	): PersistOutcome {
		val record = env.getMetricsServices().getRunRecordSource().runRecord()
		return when {
			interrupted() -> PersistOutcome.Interrupted
			record == null -> PersistOutcome.NoRecord
			else -> {
				val twin = NativeExampleRegistry.jvmTwinOf(example)
				val run = RecordedRun(record, runId, twin, endTime.toDouble(), mainProcessFailed)
				PersistOutcome.Written(RunRecordJsonWriter.write(runsRoot, run, timestamp))
			}
		}
	}
}

/** What [RunRecordPersistence.persist] did. */
internal sealed interface PersistOutcome {
	/** The run JSON was written to [path]. */
	data class Written(
		val path: String
	) : PersistOutcome

	/** Nothing was written: the run has no record, it has not ended. */
	data object NoRecord : PersistOutcome

	/** Nothing was written: the run was interrupted (SIGINT), so it is not a measured run. */
	data object Interrupted : PersistOutcome
}
