/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Cache Validation Tests — Edge Cases
 * Extracted from CacheValidationTest (PR #276 Review Recommendations)
 *
 * Migrated to commonTest: 2026-03 (KMP Task 6)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Edge cases: null inputs, already-dynamic inputs, multiple wrapper types.
 */
class CacheValidationEdgeCasesTest : KoinComponent {

	@BeforeTest
	fun setUp() {
		startKoin {
			modules(commonCoreTestModule)
		}
	}

	@AfterTest
	fun tearDown() {
		stopKoin()
	}

	private fun loadVyhybnaSimulationContext(): DefaultSimulationContext =
		CommonTestFixtures.parseSimulationContext(
			NetworkResources.VYHYBNA_XML,
			get<SimulationProcessFactory>()
		)

	@Test
	fun `multiple different wrapper types can coexist in cache`() {
		loadVyhybnaSimulationContext().use { context ->
			val inouts = context.getInOuts()
			val dynamicInOut = inouts.first()
			val dynamicSemaphore = dynamicInOut.inSemaphore

			val retrievedInOut = context.toDynamic(dynamicInOut.staticRef)
			val retrievedSemaphore = context.toDynamic(dynamicSemaphore.staticRef)

			assertThat(retrievedInOut).isSameInstanceAs(dynamicInOut)
			assertThat(retrievedSemaphore).isSameInstanceAs(dynamicSemaphore)
		}
	}

	@Test
	fun `cache preserves wrapper identity across different access patterns`() {
		loadVyhybnaSimulationContext().use { context ->
			val fromGetInOuts = context.getInOuts().first()
			val fromToDynamic = context.toDynamic(fromGetInOuts.staticRef)

			assertThat(fromToDynamic).isSameInstanceAs(fromGetInOuts)
		}
	}

	@Test
	fun `nested dynamic objects - InOut contains semaphores`() {
		loadVyhybnaSimulationContext().use { context ->
			val dynamicInOut = context.getInOuts().first()

			val inSemaphore = dynamicInOut.inSemaphore
			val outSemaphore = dynamicInOut.outSemaphore

			val fromCache1 = context.toDynamic(inSemaphore.staticRef)
			val fromCache2 = context.toDynamic(outSemaphore.staticRef)

			assertThat(fromCache1).isSameInstanceAs(inSemaphore)
			assertThat(fromCache2).isSameInstanceAs(outSemaphore)
		}
	}
}
