/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.di

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.sim.metrics.DefaultMetricsCollectionService
import cz.vutbr.fit.interlockSim.sim.metrics.MetricsConfig
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Pins the shipped wiring of [MetricsConfig] (Issue #674, Goal 6 SP4): the real [coreModule]
 * binds the default config, and the scoped metrics service built by [navigationModule] takes
 * the config from Koin. The test-fixture modules re-provide these bindings, so only the
 * shipped modules are loaded here.
 */
class CoreModuleMetricsConfigWiringTest {
	@AfterTest
	fun tearDownKoin() {
		stopKoin()
	}

	private fun collectionService(context: DefaultSimulationContext): DefaultMetricsCollectionService {
		val service = context.getMetricsServices().getMetricsCollectionService()
		assertThat(service).isInstanceOf(DefaultMetricsCollectionService::class)
		return service as DefaultMetricsCollectionService
	}

	private fun historySizeAfterOneBlockEvent(): Int {
		val context =
			CommonTestFixtures.parseSimulationContext(
				NetworkResources.LINEAR_TRACK_XML,
				DefaultSimulationProcessFactory()
			)
		try {
			val block = context.getGraph().values().first()
			val service = collectionService(context)
			service.handleBlockEvent(BlockEvent.BlockReserved(block, "T1", time = 1.0))
			return service.history().size
		} finally {
			context.close()
		}
	}

	@Test
	fun theShippedDefaultCollectsHistory() {
		startKoin { modules(coreModule) }

		assertThat(historySizeAfterOneBlockEvent()).isEqualTo(1)
	}

	@Test
	fun aConfigOverrideReachesTheScopedService() {
		startKoin { modules(coreModule, module { single<MetricsConfig> { MetricsConfig.coreOnly() } }) }

		assertThat(historySizeAfterOneBlockEvent()).isZero()
	}
}
