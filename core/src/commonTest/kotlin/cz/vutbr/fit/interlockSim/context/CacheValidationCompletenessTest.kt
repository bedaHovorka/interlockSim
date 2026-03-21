/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Cache Validation Tests — Cache Completeness Validation
 * Extracted from CacheValidationTest (PR #276 Review Recommendations)
 *
 * Migrated to commonTest: 2026-03 (KMP Task 6)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Cache completeness: validateDynamicMapping() catches unmapped separators.
 */
class CacheValidationCompletenessTest : KoinComponent {

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
			CommonTestFixtures.VYHYBNA_XML,
			get<SimulationProcessFactory>()
		)

	@Test
	fun `validateDynamicMapping succeeds for complete vyhybna`() {
		loadVyhybnaSimulationContext().use { context ->
			val inouts = context.getInOuts()
			assertThat(inouts).isNotNull()
		}
	}

	@Test
	fun `all InOut semaphores are mapped after initialization`() {
		loadVyhybnaSimulationContext().use { context ->
			for (dynamicInOut in context.getInOuts()) {
				val staticInOut = dynamicInOut.staticRef

				val inSemaphore = context.toDynamic(staticInOut.getInSemaphore())
				val outSemaphore = context.toDynamic(staticInOut.getOutSemaphore())

				assertThat(inSemaphore).isNotNull()
				assertThat(outSemaphore).isNotNull()
			}
		}
	}
}
