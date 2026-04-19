/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Cache Validation Tests — Cache Miss Behavior
 * Extracted from CacheValidationTest (PR #276 Review Recommendations)
 *
 * Migrated to commonTest: 2026-03 (KMP Task 6)
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Cache miss behavior: helpful error messages for unmapped separators.
 */
class CacheValidationCacheMissBehaviorTest : KoinComponent {
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

	private fun createEmptyContext(): DefaultSimulationContext =
		CommonTestFixtures.createEmptySimulationContext(get<SimulationProcessFactory>())

	@Test
	fun `unmapped InOut throws IllegalStateException`() {
		createEmptyContext().use { context ->
			val unmappedInOut = InOut("unmapped-inout", true, Cell.SpatialType.HORIZONTAL)

			val exception =
				assertFailsWith<IllegalStateException> {
					context.toDynamic(unmappedInOut)
				}
			assertThat(exception.message).isNotNull().contains("Dynamic wrapper not found for separator")
		}
	}

	@Test
	fun `unmapped RailSemaphore throws IllegalStateException`() {
		createEmptyContext().use { context ->
			val unmappedSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			unmappedSemaphore.setName("unmapped-semaphore")

			val exception =
				assertFailsWith<IllegalStateException> {
					context.toDynamic(unmappedSemaphore)
				}
			assertThat(exception.message).isNotNull().contains("Dynamic wrapper not found for separator")
		}
	}

	@Test
	fun `unmapped RailSwitch throws IllegalStateException`() {
		createEmptyContext().use { context ->
			val unmappedSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			unmappedSwitch.setName("unmapped-switch")

			val exception =
				assertFailsWith<IllegalStateException> {
					context.toDynamic(unmappedSwitch)
				}
			assertThat(exception.message).isNotNull().contains("Dynamic wrapper not found for separator")
		}
	}
}
