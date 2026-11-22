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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.prop
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.exceptions.MainProcessFailedException
import cz.vutbr.fit.interlockSim.sim.ControlStepListener
import cz.vutbr.fit.interlockSim.sim.LoopProcess
import cz.vutbr.fit.interlockSim.sim.RunAccountingEvent
import cz.vutbr.fit.interlockSim.sim.RunEnded
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.emitRunAccountingEvent
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
 *
 * PR #1419 pins the recording listener around it: a non-main process's death is not a run failure, a
 * failure after the end-time announce revokes the announce, and a [ShuntingLoop] main process reports
 * itself not live by the time `run()` throws.
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

	/** A non-main process that dies of a plain failure at its first control step. */
	private class FailingWorker : LoopProcess() {
		override suspend fun iteration(): Unit = throw IllegalStateException("worker failure injected by the test")
	}

	/**
	 * A main process that starts a [FailingWorker], outlives its death by a couple of quiet steps and then
	 * passivates for good, so the kernel queue drains and the run ends as before: the worker's death must
	 * not end the run with a failure (PR #1419).
	 */
	private class OutlivingMainProcess : LoopProcess() {
		private var holdsLeft = OUTLIVING_HOLDS

		override suspend fun startAction() {
			activate(FailingWorker())
		}

		override suspend fun iteration() {
			// A quiet step, as in the natural-end test above.
		}

		override suspend fun interLoopSleep() {
			if (holdsLeft > 0) {
				holdsLeft--
				hold(1.0)
			} else {
				passivate()
			}
		}
	}

	/**
	 * A main process that announces the end time exactly the way the real loop does -- [emitRunAccountingEvent]
	 * through the real bus, as in [ShuntingLoop.interLoopSleep]'s end-time branch -- and then throws [injected]
	 * in the same window (PR #1419): the listener runs for real, so the failure must revoke the announce.
	 */
	private class AnnounceThenFailingMainProcess(
		private val injected: Exception
	) : LoopProcess() {
		private var steps = 0

		override suspend fun iteration() {
			steps++
		}

		override suspend fun interLoopSleep() {
			if (steps < QUIET_ANNOUNCE_STEPS) {
				hold(1.0)
			} else {
				// Issue #1304's announce, delivered through the real bus so the context marker is set for real.
				emitRunAccountingEvent(RunAccountingEvent.EndTimeReached(time()))
				throw injected
			}
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

	@Test
	fun `a failing non-main process does not end the run with a failure`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		context.setMainProcess(OutlivingMainProcess())
		val ended = mutableListOf<RunEnded>()
		context.onRunEnded { ended += it }

		// Must return: only the main process's death is a run failure (`event.process === main`).
		context.run()

		assertThat(ended.map { it.endTimeReached }, name = "run ends delivered").isEqualTo(listOf(false))
		val summary = context.getMetricsServices().getMetricsCollectionService().runEndSummary()
		assertThat(summary.classification).isEqualTo(RunEndClassification.TERMINATED_EARLY)
	}

	@Test
	fun `a failure after the end-time announce reports the run as terminated early`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val failure = IllegalStateException("main process failure injected by the test")
		context.setMainProcess(AnnounceThenFailingMainProcess(failure))
		val ended = mutableListOf<RunEnded>()
		context.onRunEnded { ended += it }

		val thrown = assertFailure { context.run() }.isInstanceOf(MainProcessFailedException::class)
		thrown.cause().isNotNull().isSameInstanceAs(failure)
		thrown.prop(MainProcessFailedException::time).isEqualTo(FAILURE_TIME)

		// The announce is revoked: the run ended on the failure, not at its end time.
		assertThat(ended.map { it.endTimeReached }, name = "run ends delivered").isEqualTo(listOf(false))
		val summary = context.getMetricsServices().getMetricsCollectionService().runEndSummary()
		assertThat(summary.classification).isEqualTo(RunEndClassification.TERMINATED_EARLY)
	}

	@Test
	fun `a main-process failure clears the sim-active flag before run() throws`() {
		val context = loadVyhybnaContext(warmUpDynamicWrappers = true)
		val shuntingLoop = ShuntingLoop(context, endTime = 1_000_000L)
		val failure = IllegalStateException("main process failure injected by the test")
		shuntingLoop.controlStepListener = ControlStepListener { throw failure }
		context.setMainProcess(shuntingLoop)

		val thrown = assertFailure { context.run() }.isInstanceOf(MainProcessFailedException::class)
		thrown.cause().isNotNull().isSameInstanceAs(failure)
		// Flag first (Issue #1032): by the time run() throws, the async dispatcher driver's next poll
		// already sees the run as not live. End time is far away: this is the pre-end-time window.
		assertThat(shuntingLoop.isSimActive(), name = "sim-active flag").isFalse()
	}

	private companion object {
		const val FAILURE_TIME = 1.0
		const val OUTLIVING_HOLDS = 2
		const val QUIET_ANNOUNCE_STEPS = 2
	}
}
