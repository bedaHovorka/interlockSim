/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import cz.ksimulantenbande.kdisco.Process
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for [LoopProcess] lifecycle and throttling behaviour (PR #394).
 *
 * Each test runs a concrete [LoopProcess] subclass inside a real kDisco simulation
 * using the vyhybna.xml context.  The main scenario uses a minimal [ShuntingLoop]-style
 * wrapper that activates the loop-under-test and terminates the simulation after a fixed
 * duration — matching the same pattern used by [TrainReporterIntegrationTest].
 *
 * Tests cover:
 * - Iteration cadence (~1 Hz from `hold(1.0)`)
 * - Cooperative termination via [LoopProcess.terminate]
 * - Idempotency of multiple [LoopProcess.terminate] calls
 * - `startAction()` called exactly once per lifecycle
 * - `byTerminateAction()` called exactly once per lifecycle
 */
@DisplayName("LoopProcess Lifecycle Integration Tests")
@Tag("integration-test")
class LoopProcessTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingSimulationContext(simulationContextFactory, warmUpDynamicWrappers = true)

	// ---------------------------------------------------------------------------
	// Helpers – concrete LoopProcess subclasses used only inside this test file
	// ---------------------------------------------------------------------------

	/**
	 * A [LoopProcess] that counts iterations and sleeps 1 simulated second between them.
	 * Optional hooks let individual tests override [startAction] and [byTerminateAction].
	 */
	private open class CountingLoopProcess(
		val iterationCount: AtomicInteger = AtomicInteger(0),
		val startCount: AtomicInteger = AtomicInteger(0),
		val terminateCount: AtomicInteger = AtomicInteger(0)
	) : LoopProcess() {
		override suspend fun startAction() {
			startCount.incrementAndGet()
		}

		override suspend fun iteration() {
			iterationCount.incrementAndGet()
		}

		override suspend fun interLoopSleep() {
			hold(1.0)
		}

		override suspend fun byTerminateAction() {
			terminateCount.incrementAndGet()
		}
	}

	/**
	 * Wraps a target [LoopProcess] as an [Interlocking]-style main process:
	 * - [startAction] activates the target
	 * - [iteration] polls time and terminates target + stops simulation when [endTime] is reached
	 * - [interLoopSleep] holds 1 simulated second (same as [ShuntingLoop]'s `interLoopSleep`; note
	 *   that ShuntingLoop's `iteration()` holds another 1.0, making its control step 2.0 s, while
	 *   this driver's `iteration()` does not hold at all)
	 */
	private inner class DriverProcess(
		env: cz.vutbr.fit.interlockSim.context.SimulationEnvironment,
		private val target: CountingLoopProcess,
		private val endTime: Double,
		private val terminateExtra: Int = 0
	) : Interlocking(env) {
		private var terminating = false

		override suspend fun startAction() {
			env.addReportTypes(ReportType.TRAIN_CONTINUOUS)
			Process.activate(target)
		}

		override suspend fun iteration() {
			// Two-phase shutdown: once endTime is reached we signal the target to stop,
			// then wait one more hold() so kDisco can schedule byTerminateAction() before
			// we call env.stop().
			if (terminating && target.terminated()) {
				env.stop()
				return
			}
		}

		override suspend fun interLoopSleep() {
			if (time() >= endTime && !terminating) {
				// Terminate the target process the requested number of additional times
				repeat(terminateExtra) { target.terminate() }
				target.terminate()
				terminating = true
			}
			if (terminating) {
				// Give the target one tick to execute byTerminateAction() before we stop
				hold(1.0)
				return
			}
			hold(1.0)
		}
	}

	// ---------------------------------------------------------------------------
	// Tests
	// ---------------------------------------------------------------------------

	@Test
	@DisplayName("Iteration count matches simulated time at ~1 Hz (hold(1.0))")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun loopProcessIterationCountMatchesSimTime() {
		val lp = CountingLoopProcess()
		loadVyhybnaContext().use { ctx ->
			ctx.setMainProcess(DriverProcess(ctx, lp, endTime = 10.0))
			ctx.run()
		}

		// At 1 Hz over 10 simulated seconds we expect roughly 10 iterations.
		// Allow generous ±4 slack to tolerate discrete-event boundary effects.
		assertThat(lp.iterationCount.get(), name = "iteration count lower bound").isGreaterThanOrEqualTo(8)
		assertThat(lp.iterationCount.get(), name = "iteration count upper bound").isLessThanOrEqualTo(12)
	}

	@Test
	@DisplayName("Loop process terminates cleanly and iteration count > 0")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun loopProcessTerminatesCleanly() {
		val lp = CountingLoopProcess()
		loadVyhybnaContext().use { ctx ->
			ctx.setMainProcess(DriverProcess(ctx, lp, endTime = 5.0))
			ctx.run() // must not hang
		}

		assertThat(lp.iterationCount.get(), name = "iterations after termination").isGreaterThan(0)
	}

	@Test
	@DisplayName("Calling terminate() multiple times is idempotent — no exception, simulation completes")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun loopProcessTerminateIdempotent() {
		val lp = CountingLoopProcess()
		// terminateExtra = 2 → driver calls terminate() 3 times total (2 extra + 1 real)
		loadVyhybnaContext().use { ctx ->
			ctx.setMainProcess(DriverProcess(ctx, lp, endTime = 5.0, terminateExtra = 2))
			ctx.run() // must complete without exception or hang
		}

		// byTerminateAction must fire exactly once regardless of extra terminate() calls
		assertThat(lp.terminateCount.get(), name = "byTerminateAction call count").isLessThanOrEqualTo(1)
		assertThat(lp.terminateCount.get(), name = "byTerminateAction called at least once").isGreaterThan(0)
	}

	@Test
	@DisplayName("startAction() is called exactly once per process lifetime")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun loopProcessStartActionCalledOnce() {
		val lp = CountingLoopProcess()
		loadVyhybnaContext().use { ctx ->
			ctx.setMainProcess(DriverProcess(ctx, lp, endTime = 5.0))
			ctx.run()
		}

		assertThat(lp.startCount.get(), name = "startAction call count").isLessThanOrEqualTo(1)
		assertThat(lp.startCount.get(), name = "startAction called at least once").isGreaterThan(0)
	}

	@Test
	@DisplayName("byTerminateAction() is called exactly once after termination")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun loopProcessByTerminateActionCalledOnce() {
		val lp = CountingLoopProcess()
		loadVyhybnaContext().use { ctx ->
			ctx.setMainProcess(DriverProcess(ctx, lp, endTime = 5.0))
			ctx.run()
		}

		assertThat(lp.terminateCount.get(), name = "byTerminateAction call count").isLessThanOrEqualTo(1)
		assertThat(lp.terminateCount.get(), name = "byTerminateAction called at least once").isGreaterThan(0)
	}
}
