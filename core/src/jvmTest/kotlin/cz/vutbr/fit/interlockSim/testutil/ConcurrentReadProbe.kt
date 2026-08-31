/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Test utility: run a simulation on its own thread while reader threads hammer a read (Issue #994).
 */
package cz.vutbr.fit.interlockSim.testutil

import assertk.assertThat
import assertk.assertions.isTrue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Runs [runSimulation] on its own daemon thread while [readerCount] daemon reader threads spin
 * [readOnce] until the simulation finishes, and asserts that both the simulation and every
 * reader thread terminate.
 *
 * Shared harness of the Issue #994 race regression tests. It owns the barrier start, the
 * "one representative per exception type" failure capture, the thread-local read counter, the
 * timed joins and the termination assertions — so a harness fix lands once, not once per test.
 *
 * ```kotlin
 * val result = probeConcurrentReads(
 *     readerCount = 1,
 *     joinTimeoutMillis = JOIN_TIMEOUT_MILLIS,
 *     threadNamePrefix = "issue-994",
 *     readOnce = { loop.getApprovedTrains().forEach { it.name } },
 *     runSimulation = { ctx.run() },
 * )
 * assertThat(result.failures).isEmpty()
 * ```
 *
 * @param readerCount how many reader threads to spin. Keep it small so the readers cannot starve
 *   the simulation thread on a two-core CI runner.
 * @param joinTimeoutMillis per-thread join timeout, generous — this is a hang detector, not a
 *   performance budget.
 * @param threadNamePrefix daemon threads are named `<prefix>-sim` and `<prefix>-reader-N`.
 * @param readOnce the read under test; spun by every reader until the simulation ends. The probe
 *   catches everything it throws.
 * @param runSimulation typically `ctx.run()`, executed on the probe's simulation thread.
 * @return the read count and the sorted failure representatives; the caller asserts on both.
 * @since Issue #994
 */
fun probeConcurrentReads(
	readerCount: Int,
	joinTimeoutMillis: Long,
	threadNamePrefix: String,
	readOnce: () -> Unit,
	runSimulation: () -> Unit
): ConcurrentReadProbeResult {
	val simRunning = AtomicBoolean(true)
	// One representative per exception type: the JIT strips repeat stack traces, and a broken
	// read fails thousands of times per second, so a flat list would drown the signal.
	val readerFailures = ConcurrentHashMap<String, String>()
	val readCount = AtomicLong(0)
	val startBarrier = CyclicBarrier(readerCount + 1)

	val simThread =
		thread(name = "$threadNamePrefix-sim", isDaemon = true) {
			startBarrier.await()
			try {
				runSimulation()
			} finally {
				simRunning.set(false)
			}
		}

	val readerThreads =
		List(readerCount) { index ->
			thread(name = "$threadNamePrefix-reader-$index", isDaemon = true) {
				startBarrier.await()
				// The read counter is thread-local and published once at the end: a shared atomic
				// in this loop costs more than the reads under test and would shrink the race
				// window the probe exists to hit.
				var localReads = 0L
				while (simRunning.get()) {
					try {
						readOnce()
						localReads++
					} catch (failure: Throwable) {
						readerFailures.putIfAbsent(
							failure::class.simpleName.orEmpty(),
							"${failure::class.simpleName} thrown at ${failure.stackTrace.firstOrNull()}"
						)
					}
				}
				readCount.addAndGet(localReads)
			}
		}

	// Assert termination, not just wait for it: a timed-out join returns silently (Thread.join
	// is void on the JVM), and a hung simulation thread would otherwise leak into the next test
	// in this fork. `isAlive` tells the two cases apart after the wait.
	simThread.join(joinTimeoutMillis)
	simRunning.set(false)
	readerThreads.forEach { it.join(joinTimeoutMillis) }
	assertThat(!simThread.isAlive, name = "simulation thread terminated").isTrue()
	readerThreads.forEachIndexed { index, reader ->
		assertThat(!reader.isAlive, name = "reader thread $index terminated").isTrue()
	}

	return ConcurrentReadProbeResult(readCount.get(), readerFailures.values.sorted())
}
