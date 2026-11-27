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
 * [thrownAtTime]. The default fault is for JVM tests that record the escape. That absorption runs on
 * every target: on the JVM too, a configured `CancellationException` stays invisible to
 * `UncaughtSimulationExceptions`.
 *
 * The default FATAL fault is built when it is thrown, at time [failAt], so its model time
 * (`SimulationException.time`) equals [failAt]. A read of [throwable] before that returns a preview
 * built at the read (a pre-run read would sample NaN as its time, a mid-run read that run's time);
 * the preview is not cached and is not the instance thrown.
 *
 * @param failAt simulated time of the throw, at least 0; an activation after it is rejected
 * @param throwable what is thrown; `null` means a FATAL [SimulationException] naming [name]
 * @param name the process name a run record shows; also the text of the default throwable
 */
class FaultInjectionProcessFixture(
	val failAt: Double,
	throwable: Throwable? = null,
	val name: String = DEFAULT_NAME
) : Process() {
	private val configured: Throwable? = throwable

	/** The default fault instance [actions] threw, `null` before that or when [configured] is thrown instead. */
	private var thrown: Throwable? = null

	/**
	 * What [actions] throws: the configured throwable, else the default fault naming [name]. A read
	 * before the throw returns a fresh preview of the default that is not cached, so it is not the
	 * instance thrown; a read after the throw returns that instance.
	 */
	val throwable: Throwable
		get() = configured ?: (thrown ?: defaultFault(name))

	init {
		require(failAt >= 0.0) { "failAt must not be negative, got $failAt" }
	}

	/** Simulated time at which the throw happened, `null` until it did. */
	var thrownAtTime: Double? = null
		private set

	override suspend fun actions() {
		// hold() takes a duration (kDisco), so hold only the remaining stretch; an activation after
		// the target is rejected with context, which kDisco's own bare require does not carry.
		val activationTime = time()
		if (activationTime > failAt) {
			throw IllegalStateException(
				"Fault fixture $name activated at simulated time $activationTime, after its target time $failAt"
			)
		}
		hold(failAt - activationTime)
		thrownAtTime = time()
		throw configured ?: defaultFault(name).also { thrown = it }
	}

	override fun toString(): String = "FaultInjectionProcessFixture($name, failAt=$failAt)"

	companion object {
		const val DEFAULT_NAME = "fault-injection"

		/** The default fault: a [SimulationException] with FATAL severity whose message names [name]. */
		fun defaultFault(name: String): SimulationException =
			SimulationException(Severity.FATAL, "Fault injected into process $name", null, null)
	}
}
