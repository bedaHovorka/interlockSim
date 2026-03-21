/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Cache Validation Tests — Error Message Quality
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
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.commonCoreTestModule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Error message quality: includes diagnostic information.
 */
class CacheValidationErrorMessageQualityTest : KoinComponent {

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
	fun `error includes separator class name`() {
		createEmptyContext().use { context ->
			val unmapped = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)

			val exception = assertFailsWith<IllegalStateException> {
				context.toDynamic(unmapped)
			}
			assertThat(exception.message).isNotNull().contains("RailSemaphore")
		}
	}

	@Test
	fun `error includes map size information`() {
		createEmptyContext().use { context ->
			val unmapped = InOut("test-inout", true, Cell.SpatialType.HORIZONTAL)

			val exception = assertFailsWith<IllegalStateException> {
				context.toDynamic(unmapped)
			}
			val message = exception.message!!
			assertThat(message).contains("Map contains")
			assertThat(message).contains("entries")
		}
	}

	@Test
	fun `error includes separator name if available`() {
		createEmptyContext().use { context ->
			val unmapped = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			unmapped.setName("test-semaphore")

			val exception = assertFailsWith<IllegalStateException> {
				context.toDynamic(unmapped)
			}
			assertThat(exception.message).isNotNull().contains("test-semaphore")
		}
	}

	@Test
	fun `error includes initialization prerequisite instructions`() {
		createEmptyContext().use { context ->
			val unmapped = InOut("test-inout", true, Cell.SpatialType.HORIZONTAL)

			val exception = assertFailsWith<IllegalStateException> {
				context.toDynamic(unmapped)
			}
			assertThat(exception.message).isNotNull().contains("initializeDynamicMapping()")
		}
	}
}
