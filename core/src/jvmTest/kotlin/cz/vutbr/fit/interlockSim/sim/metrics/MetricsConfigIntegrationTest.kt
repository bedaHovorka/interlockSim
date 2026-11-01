/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim.metrics

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.sim.wireSynchronousDispatcher
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Util
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * A `MetricsConfig` bound in Koin before `run()` decides what a real run collects.
 *
 * @since Issue #674 (Goal 6 SP4)
 */
@DisplayName("Metric groups (Integration)")
@Tag("integration-test")
class MetricsConfigIntegrationTest : KoinTestBase() {
	private fun runWith(config: MetricsConfig?): MetricsCollectionService {
		if (config != null) {
			getKoin().loadModules(listOf(module { single<MetricsConfig> { config } }), allowOverride = true)
		}
		val context =
			TestFixtures.loadShuntingXml().use { stream ->
				Util.assertInstanceOf<DefaultSimulationContext>(
					getKoin().get<SimulationContextFactory>().createContext(stream)
				)
			}
		context.getInOuts()
		val loop = ShuntingLoop(context, RUN_END_TIME, maxTrains = RUN_TRAINS)
		wireSynchronousDispatcher(context, loop)
		context.setMainProcess(loop)
		val metrics = context.getMetricsServices().getMetricsCollectionService()
		context.run()
		return metrics
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `the default config collects history and journeys in a full run`() {
		val metrics = runWith(null)

		assertThat(metrics.history().size).isGreaterThan(0)
		assertThat(metrics.journeys().size).isEqualTo(RUN_TRAINS)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	fun `a core only config leaves no history and no journey records but keeps the KPIs`() {
		val metrics = runWith(MetricsConfig.coreOnly())

		assertThat(metrics.history().size).isEqualTo(0)
		assertThat(metrics.journeys()).isEmpty()
		assertThat(metrics.latestSnapshot().completedTrains).isGreaterThan(0)
	}

	private companion object {
		const val RUN_END_TIME = 600L
		const val RUN_TRAINS = 4
	}
}
