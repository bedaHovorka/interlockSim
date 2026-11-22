/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.cause
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.prop
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.exceptions.MainProcessFailedException
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import cz.vutbr.fit.interlockSim.sim.onRunEnded
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * A run whose main process dies ends with a failure instead of spinning (Issue #1361, step (e)).
 *
 * The main process is the only one that ends a run: `ShuntingLoop` and `MultiTrainLoop` stop the kernel
 * at their end time, and the kernel runs to `Double.MAX_VALUE`. kDisco runs each process under a
 * supervisor job and reports a process that threw like one that ended normally, so before this fix a run
 * whose main process threw went on with the processes left: the train generator kept making trains until
 * the heap ran out. Here the processes left are a [Spinner] that holds forever, so without the fix
 * [DefaultSimulationContext.run] never returns; the thread-mode timeout only turns that hang into a red
 * failure, the assertions are on the outcome. On red the spinning thread outlives the timeout (JUnit cannot
 * stop it); that is harmless, it holds no growing state and ends with the test JVM.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MainProcessFailureRunEndTest : KoinTestBase() {
	/** Stands for the train generator: a process that never ends on its own. */
	private class Spinner : Process() {
		override suspend fun actions() {
			while (true) {
				hold(1.0)
			}
		}
	}

	/** A main process that starts a [Spinner] and throws [injected] on its second control step, at [FAILURE_TIME]. */
	private class FailingMainProcess(
		private val injected: Exception
	) : LoopProcess() {
		private var steps = 0

		override suspend fun startAction() {
			activate(Spinner())
		}

		override suspend fun iteration() {
			steps++
			if (steps == 2) throw injected
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}
	}

	@Test
	fun `a run whose main process throws ends with that failure`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val failure = IllegalStateException("main process failure injected by the test")
		context.setMainProcess(FailingMainProcess(failure))
		val ended = mutableListOf<RunEnded>()
		context.onRunEnded { ended += it }

		val thrown = assertFailure { context.run() }.isInstanceOf(MainProcessFailedException::class)
		thrown.cause().isNotNull().isSameInstanceAs(failure)
		// The model time of the failure: the second control step, one hold of 1.0 after the start.
		thrown.prop(MainProcessFailedException::time).isEqualTo(FAILURE_TIME)

		// The run end is still delivered and the run record built: the run ended early, not at its end time.
		assertThat(ended.map { it.endTimeReached }, name = "run ends delivered").isEqualTo(listOf(false))
		val summary = context.getMetricsServices().getMetricsCollectionService().runEndSummary()
		assertThat(summary.classification).isEqualTo(RunEndClassification.TERMINATED_EARLY)
	}

	@Test
	fun `an interrupt ending the main process is not a failure of the run`() {
		// A GUI stop can interrupt the simulation thread; that is the end of the run asked for, not a failure.
		// No spinner here: nothing else keeps the kernel busy, so the run ends when the main process is gone.
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		context.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration(): Unit = throw InterruptedException("interrupt injected by the test")
			}
		)
		val ended = mutableListOf<RunEnded>()
		context.onRunEnded { ended += it }

		context.run()

		assertThat(ended.size, name = "run ends delivered").isEqualTo(1)
	}

	@Test
	fun `a run whose main process ends normally does not fail`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		// The main process passivates for good after one step and nothing else is scheduled, so the kernel's
		// queue drains: the run ends as before, with no failure.
		context.setMainProcess(
			object : LoopProcess() {
				override suspend fun iteration() {
					// One quiet step; the default interLoopSleep passivates the process.
				}
			}
		)
		val ended = mutableListOf<RunEnded>()
		context.onRunEnded { ended += it }

		context.run()

		assertThat(ended.size, name = "run ends delivered").isEqualTo(1)
	}

	private companion object {
		const val FAILURE_TIME = 1.0
	}
}
