/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Cache Validation Tests — Cache Hit Behavior
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Cache hit behavior: same wrapper returned for same static object.
 */
class CacheValidationCacheHitBehaviorTest : KoinComponent {
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
	fun `same wrapper returned for repeated toDynamic calls with same static object`() {
		loadVyhybnaSimulationContext().use { context ->
			val dynamicInOut = context.getInOuts().first()
			val staticInOut = dynamicInOut.staticRef

			val wrapper1 = context.toDynamic(staticInOut)
			val wrapper2 = context.toDynamic(staticInOut)
			val wrapper3 = context.toDynamic(staticInOut)

			assertThat(wrapper1).isSameInstanceAs(wrapper2)
			assertThat(wrapper2).isSameInstanceAs(wrapper3)
			assertThat(wrapper1).isSameInstanceAs(dynamicInOut)
		}
	}

	@Test
	fun `same wrapper returned for InOut semaphore repeated calls`() {
		loadVyhybnaSimulationContext().use { context ->
			val dynamicInOut = context.getInOuts().first()
			val staticSemaphore = dynamicInOut.staticRef.inSemaphore

			val wrapper1 = context.toDynamic(staticSemaphore)
			val wrapper2 = context.toDynamic(staticSemaphore)
			val wrapper3 = context.toDynamic(staticSemaphore)

			assertThat(wrapper1).isSameInstanceAs(wrapper2)
			assertThat(wrapper2).isSameInstanceAs(wrapper3)
			assertThat(wrapper1).isSameInstanceAs(dynamicInOut.inSemaphore)
		}
	}

	@Test
	fun `toDynamic is identity function for already-dynamic separators`() {
		loadVyhybnaSimulationContext().use { context ->
			val dynamicInOut = context.getInOuts().first()

			val result = context.toDynamic(dynamicInOut)

			assertThat(result).isSameInstanceAs(dynamicInOut)
		}
	}
}
