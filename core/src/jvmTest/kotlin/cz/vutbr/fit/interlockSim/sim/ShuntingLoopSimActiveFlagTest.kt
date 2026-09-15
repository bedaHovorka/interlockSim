package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Tests the [ShuntingLoop] liveness flag behind [ShuntingLoop.isSimActive] (Issue #1032).
 *
 * The flag is what the dispatcher-agent driver polls: between cycles (`AgentDriverLoop`'s
 * `isActive`) and inside a cycle, right after `plan()` returns (`AgentLoopDriver`'s
 * post-plan guard). [ShuntingLoop.signalStopped] exists for the GUI manual-stop path —
 * the runner's interrupt never reaches [ShuntingLoop.interLoopSleep]'s end-time branch,
 * so without the explicit clear the flag stays `true` after a manual stop and the driver
 * outlives the run (stale decision posted; daemon loop spinning on bounded signal
 * timeouts until JVM exit).
 *
 * These tests drive a real `context.run()` to prove: the flag is live mid-run, the
 * cross-thread clear works while the simulation thread is still inside an iteration,
 * the clear is idempotent, and the natural-end branch still clears the flag on its own.
 *
 * @since Issue #1032 (PR #1071 review follow-up)
 */
@DisplayName("ShuntingLoop simActive liveness flag (#1032)")
@Tag("integration-test")
class ShuntingLoopSimActiveFlagTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `signalStopped clears the flag mid-run from another thread and the run still finishes`() {
		val context = loadVyhybnaContext()
		// Initialize dynamic wrapper map before creating ShuntingLoop.
		context.getInOuts()

		// Park the sim thread inside the first control step so the test can act while the
		// run is in flight — the same mid-run window a GUI manual stop acts in. Production
		// listeners must not block; this test listener deliberately does (test-only).
		val firstTickReached = CountDownLatch(1)
		val releaseSimThread = Semaphore(0)
		val shuntingLoop = ShuntingLoop(context, endTime = 0L)
		shuntingLoop.controlStepListener =
			ControlStepListener {
				firstTickReached.countDown()
				try {
					releaseSimThread.acquire()
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
				}
			}
		context.setMainProcess(shuntingLoop)

		val simThread = Thread({ context.run() }, "SimActiveFlagTest-sim")
		simThread.isDaemon = true
		simThread.start()

		assertThat(firstTickReached.await(10, TimeUnit.SECONDS)).isTrue()
		// startAction has run before the first iteration, so the run is live.
		assertThat(shuntingLoop.isSimActive()).isTrue()

		// The production cross-thread clear (EDT in the GUI, test thread here).
		shuntingLoop.signalStopped()
		assertThat(shuntingLoop.isSimActive()).isFalse()
		// Idempotent: a second call (e.g. a double stop) changes nothing.
		shuntingLoop.signalStopped()
		assertThat(shuntingLoop.isSimActive()).isFalse()

		// Let the run finish: the natural-end branch must not resurrect the flag.
		releaseSimThread.release()
		simThread.join(TimeUnit.SECONDS.toMillis(30))
		assertThat(simThread.isAlive).isFalse()
		assertThat(shuntingLoop.isSimActive()).isFalse()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun `natural completion clears the flag without any signalStopped call`() {
		val context = loadVyhybnaContext()
		context.getInOuts()

		val shuntingLoop = ShuntingLoop(context, endTime = 0L)
		context.setMainProcess(shuntingLoop)
		context.run()

		assertThat(shuntingLoop.isSimActive()).isFalse()
	}
}
