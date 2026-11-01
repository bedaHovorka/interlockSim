/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.sim.metrics.MetricGroup
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsConfig
import cz.vutbr.fit.interlockSim.sim.metrics.RunRecord
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.koin.dsl.module
import kotlin.test.Test

/**
 * The performance row of a real `MultiTrainLoop` run (Issue #1306, Goal 6 SP8): present with the
 * PERFORMANCE metric group on, absent with it off. Asserts presence, units and the counted figures;
 * never a wall-time number.
 *
 * @since Issue #1306 (Goal 6 SP8)
 */
class MultiTrainLoopPerformanceRowTest : CommonKoinTestBase() {
	private companion object {
		const val END_TIME: Long = 300L
		const val TRAIN_LENGTH: Double = 20.0
	}

	private fun spec(inTime: Double) =
		MultiTrainLoop.TrainSpec(inName = "A", outName = "B", inTime = inTime, length = TRAIN_LENGTH)

	private fun runRecord(): RunRecord {
		val ctx: DefaultSimulationContext =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked().also { it.getInOuts() }
		ctx.setMainProcess(
			MultiTrainLoop(ctx, endTime = END_TIME, trainSpecs = listOf(spec(inTime = 0.0), spec(inTime = 1.0)))
		)
		ctx.run()
		return checkNotNull(ctx.getMetricsServices().getRunRecordSource().runRecord()) { "run record" }
	}

	@Test
	fun theRecordOfARunCarriesItsPerformanceRow() {
		val record = runRecord()

		val performance = record.performance
		assertThat(performance, name = "performance row").isNotNull()
		checkNotNull(performance)
		assertThat(performance.simulationSeconds, name = "simulated seconds = end time").isEqualTo(record.endTime)
		assertThat(performance.wallSeconds, name = "wall seconds").isGreaterThanOrEqualTo(0.0)
		if (performance.wallSeconds > 0.0) {
			assertThat(performance.realTimeRatio, name = "ratio = simulated / wall")
				.isEqualTo(performance.simulationSeconds / performance.wallSeconds)
		}
		assertThat(performance.routeSearchBudgetExceeded, name = "budget stops").isZero()
		// MultiTrainLoop is the only reservePath caller of this run and announces every answer.
		assertThat(performance.reservePathCalls, name = "reservePath calls").isGreaterThan(0)
		assertThat(performance.reservePathCalls, name = "reservePath calls = granted + refused")
			.isEqualTo(record.blockTransitions + record.failedReservations)
	}

	@Test
	fun withThePerformanceGroupOffTheRecordHasNoPerformanceRow() {
		val withoutPerformance = MetricsConfig(MetricGroup.entries.toSet() - MetricGroup.PERFORMANCE)
		getKoin().loadModules(listOf(module { single<MetricsConfig> { withoutPerformance } }), allowOverride = true)

		assertThat(runRecord().performance, name = "performance row").isNull()
	}
}
