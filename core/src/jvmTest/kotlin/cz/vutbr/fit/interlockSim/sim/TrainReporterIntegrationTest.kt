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
import assertk.assertions.isLessThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for TrainReporter 1 Hz throttling (PR #394).
 *
 * TrainReporter is a private inner class of Train — tested only via full simulation.
 * ShuntingLoop.ENABLED_REPORT_TYPES already includes TRAIN_CONTINUOUS, so a run
 * exercises: actions() while-loop, hold(1.0), TRAIN_CONTINUOUS branch (true path),
 * and terminate() when the train exits.
 *
 * `ShuntingLoop(ctx, 30L)` runs the shunting-loop scenario until simulation time 30 (`endTime`),
 * with at most 2 concurrent trains active at once.
 */
@DisplayName("TrainReporter 1 Hz Throttling Integration Tests")
@Tag("integration-test")
class TrainReporterIntegrationTest : KoinTestBase() {
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext {
		val stream = javaClass.getResourceAsStream("/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			?: error("Resource not found: vyhybna.xml")
		return stream.use { s ->
			Util.assertInstanceOf<DefaultSimulationContext>(simulationContextFactory.createContext(s))
		}.also { it.getInOuts() /* Initialize dynamic wrapper map (required side-effect) */ }
	}

	/**
	 * Registers a listener that counts every TRAIN_CONTINUOUS PropertyChangeEvent fired by
	 * [ctx]. Returns an [AtomicInteger] that increments with each such event.
	 *
	 * A count > 0 after simulation proves TrainReporter.actions() actually executed its
	 * reporting loop at least once.
	 */
	private fun countTrainContinuousEvents(ctx: DefaultSimulationContext): AtomicInteger {
		val count = AtomicInteger(0)
		ctx.addPropertyChangeListener(ContextPropertyChangeListener { event ->
			if (event.propertyName == ReportType.TRAIN_CONTINUOUS.name) count.incrementAndGet()
		})
		return count
	}

	@Test
	@DisplayName("TRAIN_CONTINUOUS enabled — reporter actions() fires and train exits cleanly")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun trainReporterEnabledPathCoverage() {
		// ShuntingLoop.ENABLED_REPORT_TYPES includes TRAIN_CONTINUOUS — no extra setup needed
		val ctx = loadVyhybnaContext()
		ctx.setMainProcess(ShuntingLoop(ctx, 30L))

		val reportCount = countTrainContinuousEvents(ctx)

		ctx.run() // covers: while-loop body, hold(1.0), isReporting==true branch, terminate()

		// Verify TrainReporter.actions() actually executed — not just that TRAIN_CONTINUOUS
		// is registered (which is always true after ShuntingLoop.actions() runs).
		assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS report events fired by TrainReporter")
			.isGreaterThan(0)
		// Upper bound: at ~1 Hz and endTime=30, max 2 concurrent trains → at most 60 reports.
		// 300 = 5× slack. Would fail if throttle were hold(0.001) → ~6000 reports.
		assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS throttle upper bound (endTime=30)")
			.isLessThan(300)
	}

	@Test
	@DisplayName("Short simulation run — TrainReporter terminates without hanging")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun trainReporterTerminatesCleanly() {
		// `ShuntingLoop(ctx, 10L)` runs until simulation time 10 (`endTime`),
		// with at most 2 concurrent trains active at once.
		val ctx = loadVyhybnaContext()
		ctx.setMainProcess(ShuntingLoop(ctx, 10L))

		val reportCount = countTrainContinuousEvents(ctx)

		ctx.run() // covers: TrainReporter terminate() path when simulation ends

		// Verify TrainReporter.actions() actually executed — not just that TRAIN_CONTINUOUS
		// is registered (which is always true after ShuntingLoop.actions() runs).
		assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS report events fired by TrainReporter")
			.isGreaterThan(0)
		// Upper bound: at ~1 Hz and endTime=10, max 2 concurrent trains → at most 20 reports.
		// 100 = 5× slack. Would fail if throttle were hold(0.001) → ~2000 reports.
		assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS throttle upper bound (endTime=10)")
			.isLessThan(100)
	}
}
