package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
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
 * Regression tests for wrapper identity preservation after PR #95.
 *
 * These tests verify that dynamic wrappers maintain singleton identity:
 * - getInOuts() returns same instances as staticToDynamicMap
 * - Grid cells match staticToDynamicMap entries
 * - toDynamic() returns same wrapper for same static object
 *
 * Regression: ShuntingLoop trains getting stuck due to duplicate DynamicInOut
 * wrapper creation in getInOuts() that overwrote GridTransformer's mappings.
 *
 * Migrated to commonTest: 2026-03 (KMP Task 6)
 */
class DynamicWrapperIdentityTest : KoinComponent {

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

	private fun loadVyhybnaSimulationContext(): DefaultSimulationContext {
		val editingCtx = CommonTestFixtures.parseEditingContext(NetworkResources.VYHYBNA_XML)
		val processFactory = get<SimulationProcessFactory>()
		return DefaultSimulationContext.fromEditingContext(editingCtx, processFactory)
	}

	/**
	 * Test that getInOuts() returns the same wrapper instances that are in staticToDynamicMap.
	 *
	 * CRITICAL: getInOuts() must NOT create new wrappers. It should retrieve existing
	 * wrappers from staticToDynamicMap that were created by GridTransformer during
	 * context initialization.
	 */
	@Test
	fun `getInOuts returns same instances as staticToDynamicMap`() {
		loadVyhybnaSimulationContext().use { context ->
			val inoutsFromGetter = context.getInOuts()

			for (dynamicInOut in inoutsFromGetter) {
				val fromMap = context.toDynamic(dynamicInOut.staticRef)
				assertThat(fromMap).isSameInstanceAs(dynamicInOut)
			}
		}
	}

	/**
	 * Test that grid cells match staticToDynamicMap entries.
	 *
	 * CRITICAL: GridTransformer creates dynamic wrappers and places them in both
	 * the grid and staticToDynamicMap. These must be the same instances.
	 */
	@Test
	fun `grid cells match staticToDynamicMap entries`() {
		loadVyhybnaSimulationContext().use { context ->
			val grid = context.getRailWayNetGrid()

			for (x in 0 until grid.cols) {
				for (y in 0 until grid.rows) {
					val cell = grid.getCellAt(x, y)
					val staticRef = staticRefOf(cell) ?: continue
					val fromMap = context.toDynamic(staticRef)
					assertThat(fromMap).isSameInstanceAs(cell)
				}
			}
		}
	}

	/**
	 * Test that toDynamic() returns same wrapper instance for repeated calls.
	 */
	@Test
	fun `toDynamic returns same instance for repeated calls`() {
		loadVyhybnaSimulationContext().use { context ->
			val firstInOut = context.getInOuts().first()
			val staticRef = firstInOut.staticRef

			val wrapper1 = context.toDynamic(staticRef)
			val wrapper2 = context.toDynamic(staticRef)
			val wrapper3 = context.toDynamic(staticRef)

			assertThat(wrapper1).isSameInstanceAs(firstInOut)
			assertThat(wrapper2).isSameInstanceAs(firstInOut)
			assertThat(wrapper3).isSameInstanceAs(firstInOut)
		}
	}

	/**
	 * Test that InOut semaphores are properly mapped to dynamic wrappers.
	 */
	@Test
	fun `InOut semaphores are properly mapped to dynamic wrappers`() {
		loadVyhybnaSimulationContext().use { context ->
			for (dynamicInOut in context.getInOuts()) {
				val staticInOut = dynamicInOut.staticRef
				val inSemaphore = staticInOut.getInSemaphore()
				val outSemaphore = staticInOut.getOutSemaphore()

				val dynamicInSemaphore = context.toDynamic(inSemaphore)
				val dynamicOutSemaphore = context.toDynamic(outSemaphore)

				assertThat(dynamicInSemaphore).isSameInstanceAs(dynamicInOut.inSemaphore)
				assertThat(dynamicOutSemaphore).isSameInstanceAs(dynamicInOut.outSemaphore)
			}
		}
	}

	/** Extract the static reference from a dynamic wrapper, or null for non-wrapper cells. */
	private fun staticRefOf(cell: Cell?): PathSeparator? = when (cell) {
		is DynamicInOut -> cell.staticRef
		is DynamicRailSwitch -> cell.staticRef
		is DynamicRailSemaphore -> cell.staticRef
		else -> null
	}
}
