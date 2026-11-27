/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.testutil

import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.exceptions.Severity
import cz.vutbr.fit.interlockSim.exceptions.SimulationException

/**
 * A kDisco process that fails on purpose (Issue #1451): it holds until simulated time [failAt] and
 * then throws [throwable]. Activate it from a main process (`activate(FaultInjectionProcessFixture(...))`)
 * to get a run in which one process dies while the others go on.
 *
 * Today's engine reports a process that threw like one that ended: the throw reaches the thread's
 * `UncaughtExceptionHandler` (see `UncaughtSimulationExceptions`, JVM) and the run continues. The run
 * outcome of such a failure is Issue #1112's job; this fixture only supplies the fault.
 *
 * **Kotlin/Native:** there is no thread `UncaughtExceptionHandler` there, and an uncaught process
 * exception kills the whole test process. A test that runs on linuxX64 must therefore configure the
 * fixture to throw a `CancellationException` (kDisco absorbs it as process control), as `:core`'s
 * `FaultInjectionProcessFixtureThrowTimeTest` does; it then pins the throw time through
 * [thrownAtTime]. The default fault is for JVM tests that record the escape.
 *
 * The default FATAL fault is built when it is thrown, at time [failAt], so its model time
 * (`SimulationException.time`) equals [failAt].
 *
 * @param failAt simulated time of the throw, at least 0
 * @param throwable what is thrown; `null` means a FATAL [SimulationException] naming [name]
 * @param name the process name a run record shows; also the text of the default throwable
 */
class FaultInjectionProcessFixture(
	val failAt: Double,
	throwable: Throwable? = null,
	val name: String = DEFAULT_NAME
) : Process() {
	private val configured: Throwable? = throwable

	/**
	 * What [actions] throws: the configured throwable, else the default fault naming [name]. The
	 * default is built at the first read, which [actions] does at time [failAt], so its model time is
	 * the time of the fault.
	 */
	val throwable: Throwable by lazy { configured ?: defaultFault(name) }

	init {
		require(failAt >= 0.0) { "failAt must not be negative, got $failAt" }
	}

	/** Simulated time at which the throw happened, `null` until it did. */
	var thrownAtTime: Double? = null
		private set

	override suspend fun actions() {
		hold(failAt)
		thrownAtTime = time()
		throw throwable
	}

	override fun toString(): String = "FaultInjectionProcessFixture($name, failAt=$failAt)"

	companion object {
		const val DEFAULT_NAME = "fault-injection"

		/** The default fault: a [SimulationException] with FATAL severity whose message names [name]. */
		fun defaultFault(name: String): SimulationException =
			SimulationException(Severity.FATAL, "Fault injected into process $name", null, null)
	}
}
