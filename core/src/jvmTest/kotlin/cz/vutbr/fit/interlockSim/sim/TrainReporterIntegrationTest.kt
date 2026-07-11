/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext.ReportType
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.core.ContextPropertyChangeListener
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
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
		val stream = TestFixtures.loadShuntingXml()
		return stream
			.use { s ->
				Util.assertInstanceOf<DefaultSimulationContext>(simulationContextFactory.createContext(s))
			}.also {
				it.getInOuts() // Initialize dynamic wrapper map (required side-effect)
			}
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
		ctx.addPropertyChangeListener(
			ContextPropertyChangeListener { event ->
				if (event.propertyName == ReportType.TRAIN_CONTINUOUS.name) count.incrementAndGet()
			}
		)
		return count
	}

	@Test
	@DisplayName("TRAIN_CONTINUOUS enabled — reporter actions() fires and train exits cleanly")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun trainReporterEnabledPathCoverage() {
		// ShuntingLoop.ENABLED_REPORT_TYPES includes TRAIN_CONTINUOUS — no extra setup needed
		loadVyhybnaContext().use { ctx ->
			val loop = ShuntingLoop(ctx, 30L)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)

			val reportCount = countTrainContinuousEvents(ctx)

			ctx.run()
			// covers: while-loop body, hold(1.0), isReporting==true branch, terminate()

			// Verify TrainReporter.actions() actually executed — not just that TRAIN_CONTINUOUS
			// is registered (which is always true after ShuntingLoop.actions() runs).
			assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS report events fired by TrainReporter")
				.isGreaterThan(0)
			// Upper bound: at ~1 Hz and endTime=30, max 2 concurrent trains → at most 60 reports.
			// 300 = 5× slack. Would fail if throttle were hold(0.001) → ~6000 reports.
			assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS throttle upper bound (endTime=30)")
				.isLessThan(300)
		}
	}

	@Test
	@DisplayName("Short simulation run — TrainReporter terminates without hanging")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun trainReporterTerminatesCleanly() {
		// `ShuntingLoop(ctx, 10L)` runs until simulation time 10 (`endTime`),
		// with at most 2 concurrent trains active at once.
		loadVyhybnaContext().use { ctx ->
			val loop = ShuntingLoop(ctx, 10L)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)

			val reportCount = countTrainContinuousEvents(ctx)

			ctx.run()
			// covers: TrainReporter terminate() path when simulation ends

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

	@Test
	@DisplayName("TRAIN_CONTINUOUS disabled — TrainReporter fires 0 reporting events")
	@Tag("integration-test")
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun trainReporterDisabledNoEvents() {
		// Use a simple linear path context where SimpleTestProcess acts as main process.
		// SimpleTestProcess does NOT call addReportTypes(TRAIN_CONTINUOUS), so
		// allowedReportTypes stays empty throughout the simulation.
		// TrainReporter.iteration() checks isReporting(TRAIN_CONTINUOUS) — which returns
		// false — so env.report() is never called and no TRAIN_CONTINUOUS events fire.
		(TestTopologies.simpleLinearPathSimulation() as DefaultSimulationContext).use { ctx ->
			val reportCount = AtomicInteger(0)
			ctx.addPropertyChangeListener(
				ContextPropertyChangeListener { event ->
					if (event.propertyName == ReportType.TRAIN_CONTINUOUS.name) reportCount.incrementAndGet()
				}
			)

			val inOuts = ctx.getInOuts().toList()
			require(inOuts.size >= 2) { "Test requires at least 2 InOuts" }
			val startInOut = inOuts[0]
			val targetInOut = inOuts[1]

			// Reserve path so train can actually move (exercises TrainReporter.iteration() repeatedly)
			ctx.getRoutingServices().getPathReservationService().reservePath("Test#1", startInOut, targetInOut)

			val timetable = Timetable(startInOut, targetInOut, Time(0.0), Time(60.0), 100.0)
			val train = Train(ctx, timetable)

			// SimpleTestProcess does not enable TRAIN_CONTINUOUS — the key condition under test
			val testProcess = SimpleTestProcess(train, endTime = 10.0)
			ctx.setMainProcess(testProcess)
			ctx.run()

			// TrainReporter ran (train moved) but isReporting(TRAIN_CONTINUOUS) was always false,
			// so env.report() was never reached and no property change events fired.
			assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS events when reporting disabled")
				.isEqualTo(0)
		}
	}

	@Test
	@DisplayName("TRAIN_CONTINUOUS enabled — reporter fires at ≥ 20 events in 30 s (≈ 1 Hz lower bound)")
	@Tag("integration-test")
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	fun trainReporterRateLowerBound() {
		// Same setup as trainReporterEnabledPathCoverage but with tighter bounds:
		// - Lower bound: >= 25 proves ~1 Hz cadence (not spurious single event)
		// - Upper bound: < 100 (tighter than 300) — would catch hold(0.01) throttle bug
		loadVyhybnaContext().use { ctx ->
			val loop = ShuntingLoop(ctx, 30L)
			wireSynchronousDispatcher(ctx, loop)
			ctx.setMainProcess(loop)

			val reportCount = countTrainContinuousEvents(ctx)

			ctx.run()

			assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS rate lower bound (endTime=30, ~1 Hz)")
				.isGreaterThanOrEqualTo(20)
			assertThat(reportCount.get(), name = "TRAIN_CONTINUOUS rate upper bound (endTime=30)")
				.isLessThan(100)
		}
	}
}
