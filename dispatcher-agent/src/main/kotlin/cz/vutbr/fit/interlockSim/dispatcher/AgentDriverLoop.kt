/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

/**
 * Supervising loop that drives [AgentLoopDriver.runCycle] for the lifetime of a simulation run.
 *
 * ## Why this exists (Issue #847 round 4, finding R4-2)
 *
 * The driver loop used to be written inline where the example is wired:
 *
 * ```kotlin
 * loop.agentDriverAction = {
 *     while (loop.isSimActive()) { driver.runCycle() }
 * }
 * ```
 *
 * and started by `platformStartDaemonThread`, which is `Thread { runBlocking { action() } }` with
 * **no** uncaught-exception handler. There was therefore no `catch` anywhere between a throwing
 * cycle and the end of the thread: any exception escaping `runCycle()` terminated the driver
 * silently. The simulation kernel runs on its own thread and knows nothing about the driver, so it
 * carried on ticking to the requested end time and the run still exited 0 — a run with no dispatcher
 * at all was indistinguishable, from the outside, from a healthy one.
 *
 * Round 3's run 1 shows the signature: the last planner summary was logged at
 * `simTime=216.0s, totalCycles=10`, no later summary ever appeared, and the run nonetheless reached
 * 600.9 s. A measurement sweep (#847) counting such a run as a valid data point would be counting a
 * run in which the thing being measured stopped existing four hundred simulated seconds early.
 *
 * ## Policy
 *
 * - A cycle that throws is logged at ERROR and counted; the loop continues. One bad tick must not
 *   end a 600 s run.
 * - [maxConsecutiveFailures] failures **in a row** stop the loop with a final ERROR. This is the
 *   "the dispatcher is broken, not merely unlucky" case — continuing would spin at full speed
 *   producing nothing. Stopping loudly is strictly better than the previous silent death because
 *   [stoppedByFailures] and [lastFailure] survive for the end-of-run summary.
 * - A successful cycle resets the consecutive counter, so intermittent failures never accumulate
 *   into a false shutdown.
 * - [CancellationException] propagates untouched and is **not** counted as a failure: it is the
 *   coroutine shutdown path, not a defect.
 *
 * Thread confinement: one instance drives one daemon thread. The counters are read from the
 * end-of-run summary after the loop has finished, so they need no synchronisation beyond the
 * `@Volatile` publication that the summary read relies on.
 *
 * @param isActive Polled before each cycle; the loop exits normally when it returns `false`.
 * @param runCycle One sense→decide→act→pace cycle — in production, `AgentLoopDriver::runCycle`.
 * @param maxConsecutiveFailures Consecutive throwing cycles tolerated before the loop gives up.
 *
 * @since Issue #847 round 4 (PR #891 — R4-2 silent driver death)
 */
class AgentDriverLoop(
	private val isActive: () -> Boolean,
	private val runCycle: suspend () -> Boolean,
	private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES
) {
	init {
		require(maxConsecutiveFailures > 0) {
			"maxConsecutiveFailures must be positive, was $maxConsecutiveFailures"
		}
	}

	/** Total cycles attempted, successful or not. */
	@Volatile
	var cycleCount: Long = 0L
		private set

	/** Total cycles that threw. Always `<= cycleCount`. */
	@Volatile
	var failureCount: Int = 0
		private set

	/** The most recent exception thrown by a cycle, or `null` if no cycle has ever thrown. */
	@Volatile
	var lastFailure: Throwable? = null
		private set

	/** `true` if the loop exited because [maxConsecutiveFailures] cycles failed in a row. */
	@Volatile
	var stoppedByFailures: Boolean = false
		private set

	/**
	 * Drives cycles until [isActive] returns `false` or the consecutive-failure bound is reached.
	 *
	 * @throws CancellationException if a cycle is cancelled — deliberately propagated.
	 */
	suspend fun run() {
		var consecutiveFailures = 0
		while (isActive()) {
			cycleCount++
			try {
				runCycle()
				consecutiveFailures = 0
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				failureCount++
				consecutiveFailures++
				lastFailure = e
				logger.error(e) {
					"AgentDriverLoop: dispatcher cycle #$cycleCount failed " +
						"($consecutiveFailures consecutive, $failureCount total)"
				}
				if (consecutiveFailures >= maxConsecutiveFailures) {
					stoppedByFailures = true
					logger.error(e) {
						"AgentDriverLoop: stopping the dispatcher driver after $consecutiveFailures " +
							"consecutive failures at cycle #$cycleCount. The simulation continues " +
							"WITHOUT a dispatcher from this point — treat the remainder of this run " +
							"as invalid measurement data."
					}
					return
				}
			}
		}
	}

	/** One-line end-of-run summary, logged by the caller alongside the other run summaries. */
	fun summaryLine(): String =
		"[AgentDriverLoop] cycles=$cycleCount failures=$failureCount " +
			"stoppedByFailures=$stoppedByFailures" +
			(lastFailure?.let { " lastFailure=${it::class.simpleName}: ${it.message}" } ?: "")

	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Consecutive failures tolerated before the loop gives up.
		 *
		 * Chosen so a transient fault (one Ollama timeout, one dropped connection) is always
		 * survived, while a structural one (agent construction failing every time, a bad tool
		 * registry) is reported within a few seconds rather than after 600 s of spinning.
		 */
		const val DEFAULT_MAX_CONSECUTIVE_FAILURES: Int = 10
	}
}
