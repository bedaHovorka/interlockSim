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
import assertk.assertions.isNotSameAs
import assertk.assertions.isNull
import assertk.assertions.isSameAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.NoOpSimulationController
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.DelegatingSimulationController
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit
import cz.vutbr.fit.interlockSim.context.SimulationController as CoreSimulationController

@Tag("integration-test")
@DisplayName("SimulationController — agent-pacing delegate lifecycle (SP4.2, #564)")
@Timeout(30, unit = TimeUnit.SECONDS)
class SimulationControllerAgentPacingLifecycleTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	/**
	 * Builds a real [DefaultSimulationContext] with a long-running [ShuntingLoop] main process,
	 * mirroring [SimulationControllerBridgeIntegrationTest]. The long end time + real-time sync
	 * keep the simulation thread alive until the test explicitly stops it.
	 */
	private fun buildContext(): DefaultSimulationContext {
		val factory = get<SimulationContextFactory>()
		val ctx =
			TestFixtures.loadShuntingXml().use {
				factory.createContext(it) as DefaultSimulationContext
			}
		ctx.getInOuts()
		val loop =
			ShuntingLoop(
				ctx,
				endTime = 600L,
				enableRealTimeSync = true,
				initialSpeedMultiplier = 1.0
			)
		ctx.setMainProcess(loop)
		return ctx
	}

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

				assertThat(pacingDelegateOf(it)).isSameAs(controller.runner!!)
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
				assertThat(pacingDelegateOf(it)).isSameAs(controller.runner!!)

				controller.stop()

				assertThat(pacingDelegateOf(it)).isSameAs(NoOpSimulationController)
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
				assertThat(pacingDelegateOf(it)).isSameAs(controller.runner!!)

				// Stop the runner directly (NOT controller.stop()) so `runner` stays non-null
				// and the monitor's `runner === newRunner` guard passes — exercising the
				// monitor-thread finally path that detaches the pacing delegate.
				controller.runner!!.stop()

				awaitMonitorDetach(controller)

				assertThat(pacingDelegateOf(it)).isSameAs(NoOpSimulationController)
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
			assertThat(pacingDelegateOf(ctxA)).isSameAs(runnerA)

			controller.stop()
			assertThat(pacingDelegateOf(ctxA)).isSameAs(NoOpSimulationController)

			// Re-start on a fresh context: a new runner is created and attached to ctxB's
			// scoped DelegatingSimulationController; ctxA's delegate stays NoOp (no leak).
			controller.start(ctxB)
			val runnerB = controller.runner!!
			assertThat(runnerA).isNotSameAs(runnerB)
			assertThat(pacingDelegateOf(ctxB)).isSameAs(runnerB)
			assertThat(pacingDelegateOf(ctxA)).isSameAs(NoOpSimulationController)
		} finally {
			controller.stop()
			ctxA.close()
			ctxB.close()
		}
	}
}
