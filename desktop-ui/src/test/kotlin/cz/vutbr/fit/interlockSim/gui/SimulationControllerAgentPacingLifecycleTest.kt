/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Agent-pacing delegate lifecycle test (SP4.2 / Issue #564): with a real
	DefaultSimulationContext (real Koin scope binding DelegatingSimulationController),
	SimulationController.start must attach the live SimulationRunner as the delegate, and
	stop / natural completion must reset it to NoOpSimulationController — without leaking
	the previous runner across a stop+start cycle.
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.IntegrationKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.longRunningShuntingLoop
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.get
import java.util.concurrent.TimeUnit
import cz.vutbr.fit.interlockSim.context.SimulationController as CoreSimulationController

@Tag("integration-test")
@DisplayName("SimulationController — agent-pacing delegate lifecycle (SP4.2, #564)")
@Timeout(30, unit = TimeUnit.SECONDS)
class SimulationControllerAgentPacingLifecycleTest : IntegrationKoinTestBase() {
	/**
	 * Builds a real [DefaultSimulationContext] with a long-running [ShuntingLoop] main process,
	 * mirroring [SimulationControllerBridgeIntegrationTest]. The long end time + real-time sync
	 * keep the simulation thread alive until the test explicitly stops it.
	 */
	private fun buildContext(): DefaultSimulationContext = longRunningShuntingLoop(get<SimulationContextFactory>())

	/** The scoped [DelegatingSimulationController] bound in this context's Koin scope. */
	private fun pacingDelegateOf(context: DefaultSimulationContext): CoreSimulationController =
		context.scope.get<DelegatingSimulationController>().delegate

	/** Polls until the monitor thread's finally block has nulled [SimulationController.runner]. */
	private fun awaitMonitorDetach(
		controller: SimulationController,
		timeoutMs: Long = 5000L
	) {
		val deadline = System.currentTimeMillis() + timeoutMs
		while (controller.runner != null && System.currentTimeMillis() < deadline) {
			Thread.sleep(20L)
		}
		assertThat(controller.runner).isNull()
	}

	@Test
	@DisplayName("start() attaches the live SimulationRunner as the pacing delegate")
	fun startAttachesLiveRunnerAsDelegate() {
		val context = buildContext()
		context.use {
			val controller = SimulationController()
			try {
				controller.start(it)

				assertThat(pacingDelegateOf(it)).isSameInstanceAs(controller.runner!!)
			} finally {
				controller.stop()
			}
		}
	}

	@Test
	@DisplayName("stop() resets the pacing delegate to NoOpSimulationController")
	fun stopResetsDelegateToNoOp() {
		val context = buildContext()
		context.use {
			val controller = SimulationController()
			try {
				controller.start(it)
				assertThat(pacingDelegateOf(it)).isSameInstanceAs(controller.runner!!)

				controller.stop()

				assertThat(pacingDelegateOf(it)).isSameInstanceAs(NoOpSimulationController)
			} finally {
				controller.stop()
			}
		}
	}

	@Test
	@DisplayName("natural completion resets the pacing delegate to NoOpSimulationController")
	fun naturalCompletionResetsDelegateToNoOp() {
		val context = buildContext()
		context.use {
			val controller = SimulationController()
			try {
				controller.start(it)
				assertThat(pacingDelegateOf(it)).isSameInstanceAs(controller.runner!!)

				// Stop the runner directly (NOT controller.stop()) so `runner` stays non-null
				// and the monitor's `runner === newRunner` guard passes — exercising the
				// monitor-thread finally path that detaches the pacing delegate.
				controller.runner!!.stop()

				awaitMonitorDetach(controller)

				assertThat(pacingDelegateOf(it)).isSameInstanceAs(NoOpSimulationController)
			} finally {
				controller.stop()
			}
		}
	}

	@Test
	@DisplayName("stop() + start() does not leak the previous runner as the pacing delegate")
	fun restartDoesNotLeakPreviousRunner() {
		val ctxA = buildContext()
		val ctxB = buildContext()
		val controller = SimulationController()
		try {
			controller.start(ctxA)
			val runnerA = controller.runner!!
			assertThat(pacingDelegateOf(ctxA)).isSameInstanceAs(runnerA)

			controller.stop()
			assertThat(pacingDelegateOf(ctxA)).isSameInstanceAs(NoOpSimulationController)

			// Re-start on a fresh context: a new runner is created and attached to ctxB's
			// scoped DelegatingSimulationController; ctxA's delegate stays NoOp (no leak).
			controller.start(ctxB)
			val runnerB = controller.runner!!
			assertThat(runnerA).isNotSameInstanceAs(runnerB)
			assertThat(pacingDelegateOf(ctxB)).isSameInstanceAs(runnerB)
			assertThat(pacingDelegateOf(ctxA)).isSameInstanceAs(NoOpSimulationController)
		} finally {
			controller.stop()
			ctxA.close()
			ctxB.close()
		}
	}
}
