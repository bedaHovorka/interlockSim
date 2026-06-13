/*
	Brno University of Technology
	Faculty of Information Technology

	BSc Thesis       2006/2007
	Railway Interlocking Simulator

	Performance benchmarks for SimulationRunner speed control (Phase 4.2, Issue #197)
*/

package cz.vutbr.fit.interlockSim.gui

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import io.github.oshai.kotlinlogging.KotlinLogging
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * Performance benchmarks for [SimulationRunner] speed control (Phase 4.2, Issue #197).
 *
 * Validates:
 * - SimulationRunner overhead vs raw context.run() is < 1% (target; CI-safe bound 5%)
 * - throttle() wall-clock accuracy at 0.1×, 10×, and 100× speed multipliers
 * - Full 300s ShuntingLoop simulation completes quickly at 100× (CPU-bound, no throttle)
 * - EDT invokeLater latency < 500ms (CI-safe) while background sim thread is blocked
 *
 * All tests are tagged @Tag("integration-test") and run via `./gradlew integrationTest`.
 *
 * ## Performance Targets
 *
 * | Speed  | Expected Behaviour                                            |
 * |--------|---------------------------------------------------------------|
 * | 0.1×   | Wall-clock = sim-time × 10 (±10%), tested via throttle(0.1s) |
 * | 1×     | Wall-clock = sim-time (±5%), overhead < 1%                   |
 * | 10×    | ~100ms wall-clock per 1s sim event (CI bound 80–500ms)        |
 * | 100×   | CPU-bound for small sim deltas; 1000 × 0.001s events < 500ms wall-clock |
 *
 * @see SimulationRunner
 * @see <a href="https://github.com/bedaHovorka/interlockSim/issues/197">Issue #197</a>
 */
@DisplayName("SimulationRunner Speed Performance (Phase 4.2)")
@Tag("integration-test")
class SimulationSpeedPerformanceTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	private val logger = KotlinLogging.logger {}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private fun loadShuntingLoop(endTime: Long): DefaultSimulationContext {
		val factory = get<SimulationContextFactory>()
		val ctx =
			TestFixtures.loadShuntingXml().use { factory.createContext(it) }
				as DefaultSimulationContext
		ctx.getInOuts()
		ctx.setMainProcess(ShuntingLoop(ctx, endTime))
		return ctx
	}

	// ── Overhead: SimulationRunner vs raw context.run() ───────────────────────

	/**
	 * Measures the overhead added by [SimulationRunner] relative to a direct
	 * context.run() call.  The dominant cost is thread creation (~1 ms), which
	 * is negligible for any real simulation (target < 1%; CI-safe bound 5%).
	 *
	 * Uses a 3 s baseline so that 5% tolerance = ~150ms, providing sufficient
	 * headroom for OS scheduling jitter on contended CI runners.
	 */
	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("overhead vs raw context.run() is negligible (< 5% CI-safe; target < 1%)")
	fun runnerOverheadIsNegligible() {
		val sleepMs = 3_000L

		// Baseline: direct context.run() call
		val baseCtx = mockk<SimulationContext>(relaxed = true)
		every { baseCtx.run(ofType<SimulationController>()) } answers { Thread.sleep(sleepMs) }
		val baselineNs = System.nanoTime()
		baseCtx.run(cz.vutbr.fit.interlockSim.context.NoOpSimulationController)
		val baselineMs = (System.nanoTime() - baselineNs) / 1_000_000.0

		// With SimulationRunner: thread is created, context.run() called inside it
		val runCtx = mockk<SimulationContext>(relaxed = true)
		val done = CountDownLatch(1)
		every { runCtx.run(ofType<SimulationController>()) } answers {
			Thread.sleep(sleepMs)
			done.countDown()
		}
		val runner = SimulationRunner(runCtx)
		val runnerNs = System.nanoTime()
		runner.start()
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
		val runnerMs = (System.nanoTime() - runnerNs) / 1_000_000.0

		val overheadPct = (runnerMs - baselineMs) / baselineMs * 100.0
		logger.info {
			"Overhead: ${"%.2f".format(overheadPct)}% " +
				"(baseline=${"%.1f".format(baselineMs)}ms, runner=${"%.1f".format(runnerMs)}ms)"
		}
		// Primary target: < 1%. Allow up to 5% for OS scheduling noise in CI.
		assertThat(overheadPct).isLessThan(5.0)
	}

	// ── Throttle accuracy ─────────────────────────────────────────────────────

	/**
	 * At 100× speed with small sim deltas (0.001s per event — typical for kDisco):
	 * sleepMs = round(0.001 / 100 × 1000) = round(0.01) = 0 → no sleep.
	 * 1000 such events should complete in < 500ms (pure CPU overhead, no Thread.sleep).
	 *
	 * Note: throttle(1.0) at 100× still sleeps 10ms (1.0/100×1000=10ms).
	 * "100× = CPU-bound, no throttling" applies to realistic small sim deltas.
	 */
	@Test
	@Timeout(10, unit = TimeUnit.SECONDS)
	@DisplayName("at 100x: 1000 x 0.001s sim events (typical kDisco step) complete in < 500ms")
	fun throttleAt100xWithSmallDeltasIsNearZero() {
		val runner = SimulationRunner(mockk(relaxed = true))
		runner.speedMultiplier = 100.0

		val startNs = System.nanoTime()
		repeat(1000) { runner.throttle(0.001) } // sleepMs = round(0.001/100×1000) = 0ms
		val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

		logger.info { "100×: 1000 × 0.001s sim events took ${elapsedMs}ms wall-clock (target: CPU-bound, no sleep)" }
		assertThat(elapsedMs).isLessThan(500)
	}

	/**
	 * At 10× speed: sleepMs = round(1.0 / 10 × 1000) = 100ms per 1s sim event.
	 * Verified range 80–500ms (lower: JVM sleep precision; upper: CI scheduling).
	 */
	@Test
	@Timeout(10, unit = TimeUnit.SECONDS)
	@DisplayName("at 10x: 1s sim event takes ~100ms wall-clock (80-500ms CI-safe)")
	fun throttleAt10xDelivers100msPerSimSecond() {
		val runner = SimulationRunner(mockk(relaxed = true))
		runner.speedMultiplier = 10.0

		val startNs = System.nanoTime()
		runner.throttle(1.0)
		val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

		logger.info { "10×: 1s sim throttle took ${elapsedMs}ms wall-clock (target: ~100ms)" }
		assertThat(elapsedMs).isGreaterThan(80)
		assertThat(elapsedMs).isLessThan(500)
	}

	/**
	 * At 0.1× speed: sleepMs = round(0.1 / 0.1 × 1000) = 1000ms per 0.1s sim event.
	 * Verifies the simulation is slowed 10× relative to real time (±10%, CI 3s bound).
	 */
	@Test
	@Timeout(10, unit = TimeUnit.SECONDS)
	@DisplayName("at 0.1x: 0.1s sim event takes ~1000ms wall-clock (900-3000ms CI-safe)")
	fun throttleAt01xIs10xSlowerThanRealTime() {
		val runner = SimulationRunner(mockk(relaxed = true))
		runner.speedMultiplier = 0.1

		val startNs = System.nanoTime()
		runner.throttle(0.1)
		val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

		logger.info { "0.1×: 0.1s sim throttle took ${elapsedMs}ms wall-clock (target: ~1000ms)" }
		assertThat(elapsedMs).isGreaterThan(900)
		assertThat(elapsedMs).isLessThan(3000)
	}

	// ── Full simulation at 100× ───────────────────────────────────────────────

	/**
	 * Runs a real 300s ShuntingLoop at 100× speed via [SimulationRunner].
	 * Since [ShuntingLoop] does not call throttle(), the simulation runs CPU-bound
	 * regardless of speed setting — verifying no unexpected wall-clock throttling.
	 *
	 * Completion within the @Timeout budget is the primary assertion; the elapsed
	 * duration is logged for trend monitoring but not asserted, to avoid flakiness
	 * on slower/contended CI runners.
	 */
	@Test
	@Timeout(60, unit = TimeUnit.SECONDS)
	@DisplayName("300s ShuntingLoop at 100x completes within @Timeout budget (logs wall-clock)")
	fun shuntingLoop300sAt100xCompletesQuickly() {
		loadShuntingLoop(300L).use { ctx ->
			val runner = SimulationRunner(ctx)
			runner.speedMultiplier = 100.0

			val done = CountDownLatch(1)
			val monitor =
				Thread {
					while (runner.isRunning()) Thread.sleep(50)
					done.countDown()
				}
			monitor.isDaemon = true

			val startNs = System.nanoTime()
			runner.start()
			monitor.start()
			assertThat(done.await(55, TimeUnit.SECONDS)).isTrue()
			val elapsedMs = (System.nanoTime() - startNs) / 1_000_000

			// Log for trend monitoring; hard assertion omitted to avoid CI flakiness.
			logger.info { "ShuntingLoop 300s at 100×: ${elapsedMs}ms wall-clock" }
		}
	}

	// ── EDT responsiveness ────────────────────────────────────────────────────

	/**
	 * Verifies that [SwingUtilities.invokeLater] dispatch latency remains below 500ms
	 * while a background simulation thread exists (blocked on a latch, not CPU-busy).
	 *
	 * This test validates the baseline EDT availability: the simulation thread must
	 * not hold any lock that could block the EDT.  A separate warmup event ensures
	 * the EDT is already active before the measured dispatch.
	 *
	 * Note: the simulation thread is blocked on a latch, not CPU-busy, so this
	 * test does not exercise EDT responsiveness under actual CPU contention.
	 */
	@Test
	@Timeout(30, unit = TimeUnit.SECONDS)
	@DisplayName("EDT invokeLater latency is < 500ms while background sim thread is blocked")
	fun edtRemainsResponsiveDuringSimulation() {
		// SIM_BLOCK_TIMEOUT_S is chosen to fit within the @Timeout(30s) budget.
		val simBlockTimeoutS = 20L
		val latchAwaitTimeoutS = 5L

		val simStarted = CountDownLatch(1)
		val stopSim = CountDownLatch(1)
		val mockCtx = mockk<SimulationContext>(relaxed = true)
		every { mockCtx.run(ofType<SimulationController>()) } answers {
			simStarted.countDown()
			stopSim.await(simBlockTimeoutS, TimeUnit.SECONDS)
		}

		val runner = SimulationRunner(mockCtx)
		runner.speedMultiplier = 100.0
		runner.start()
		assertThat(simStarted.await(latchAwaitTimeoutS, TimeUnit.SECONDS)).isTrue()

		// Warm up EDT to ensure it is actively processing events before measurement
		val warmup = CountDownLatch(1)
		SwingUtilities.invokeLater { warmup.countDown() }
		assertThat(warmup.await(latchAwaitTimeoutS, TimeUnit.SECONDS)).isTrue()

		// Measure EDT dispatch latency with simulation thread running in background
		val edtDone = CountDownLatch(1)
		val startNs = System.nanoTime()
		SwingUtilities.invokeLater { edtDone.countDown() }
		assertThat(edtDone.await(latchAwaitTimeoutS, TimeUnit.SECONDS)).isTrue()
		val latencyMs = (System.nanoTime() - startNs) / 1_000_000

		logger.info { "EDT invokeLater latency (sim thread blocked): ${latencyMs}ms (target: < 100ms)" }
		// CI-safe bound: 500ms. Real responsiveness target documented in class KDoc: 100ms.
		assertThat(latencyMs).isLessThan(500)

		stopSim.countDown()
		runner.stop()
	}
}
