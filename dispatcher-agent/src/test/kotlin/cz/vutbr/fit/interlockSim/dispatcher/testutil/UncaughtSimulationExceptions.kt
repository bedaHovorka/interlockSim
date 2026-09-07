/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.testutil

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the exceptions a kDisco process lets escape during a simulation run (Issue #1025).
 *
 * A `SimulationException[FATAL]` thrown inside a train process does **not** propagate out of
 * `DefaultSimulationContext.run()`: kotlinx-coroutines hands an uncaught process exception to the
 * current thread's `UncaughtExceptionHandler`, which prints `Exception in thread ...`, and the
 * simulation goes on without that process. A test that only checks that `run()` returned has not
 * checked that no train died. This helper installs a recording handler on the calling thread for
 * the duration of [block] and returns what escaped.
 */
object UncaughtSimulationExceptions {
	/** The value [block] returned and every exception a process let escape while it ran. */
	class Result<T>(
		val value: T,
		val uncaught: List<Throwable>
	)

	fun <T> record(block: () -> T): Result<T> {
		val thread = Thread.currentThread()
		val previous: Thread.UncaughtExceptionHandler? = thread.uncaughtExceptionHandler
		val uncaught = CopyOnWriteArrayList<Throwable>()
		thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
		try {
			return Result(block(), uncaught.toList())
		} finally {
			thread.uncaughtExceptionHandler = previous
		}
	}
}
