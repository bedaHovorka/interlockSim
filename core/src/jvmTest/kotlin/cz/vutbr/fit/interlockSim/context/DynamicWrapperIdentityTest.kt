package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isSameAs
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.InputStream

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
 * See investigation plan in git history for detailed root cause analysis.
 */
@DisplayName("Dynamic Wrapper Identity Tests (PR #95 Regression)")
class DynamicWrapperIdentityTest : KoinTestBase() {
	private val editingContextFactory: EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private fun loadVyhybnaContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = editingContextFactory.createContext(xmlStream) as EditingContext
			simulationContextFactory.createContext(editingContext) as DefaultSimulationContext
		}

	/**
	 * Test that getInOuts() returns the same wrapper instances that are in staticToDynamicMap.
	 *
	 * CRITICAL: getInOuts() must NOT create new wrappers. It should retrieve existing
	 * wrappers from staticToDynamicMap that were created by GridTransformer during
	 * context initialization.
	 *
	 * Failure mode: If getInOuts() creates duplicate wrappers, train path progression
	 * fails because navigation compares wrapper instances for equality.
	 */
	@Test
	fun `getInOuts returns same instances as staticToDynamicMap`() {
		// Given: Simulation context with InOuts
		TestFixtures.loadShuntingXml().use { xmlStream ->
			(editingContextFactory.createContext(xmlStream) as EditingContext).use { editingContext ->
				(simulationContextFactory.createContext(editingContext) as DefaultSimulationContext).use { context ->
					// When: Retrieving InOuts via getInOuts()
					val inoutsFromGetter = context.getInOuts()

					// Then: Each InOut wrapper must be same instance as in staticToDynamicMap
					for (dynamicInOut in inoutsFromGetter) {
						val fromMap = context.toDynamic(dynamicInOut.staticRef)
						assertThat(fromMap).isSameAs(dynamicInOut)
					}
				}
			}
		}
	}

	/**
	 * Test that grid cells match staticToDynamicMap entries.
	 *
	 * CRITICAL: GridTransformer creates dynamic wrappers and places them in both
	 * the grid and staticToDynamicMap. These must be the same instances.
	 *
	 * Failure mode: If different wrappers exist in grid vs map, path lookups fail
	 * because identity comparison (===) returns false for logically equal wrappers.
	 */
	@Test
	fun `grid cells match staticToDynamicMap entries`() {
		// Given: Simulation context with dynamic grid
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")
		(editingContextFactory.createContext(xmlStream) as EditingContext).use { editingContext ->
			(simulationContextFactory.createContext(editingContext) as DefaultSimulationContext).use { context ->
				val grid = context.getRailWayNetGrid()

				// When/Then: Iterate through all grid cells
				val cols = grid.cols
				val rows = grid.rows

				for (x in 0 until cols) {
					for (y in 0 until rows) {
						val cell = grid.getCellAt(x, y)
						when (cell) {
							is DynamicInOut -> {
								// Grid DynamicInOut must match staticToDynamicMap entry
								val fromMap = context.toDynamic(cell.staticRef)
								assertThat(fromMap).isSameAs(cell)
							}
							is DynamicRailSwitch -> {
								// Grid DynamicRailSwitch must match staticToDynamicMap entry
								val fromMap = context.toDynamic(cell.staticRef)
								assertThat(fromMap).isSameAs(cell)
							}
							is DynamicRailSemaphore -> {
								// Grid DynamicRailSemaphore must match staticToDynamicMap entry
								val fromMap = context.toDynamic(cell.staticRef)
								assertThat(fromMap).isSameAs(cell)
							}
							// TrackBlockPart cells are not wrappers, skip them
							else -> {
								// No validation needed for non-wrapper cells
							}
						}
					}
				}
			}
		}
	}

	/**
	 * Test that toDynamic() returns same wrapper instance for repeated calls.
	 *
	 * This verifies the singleton guarantee: calling toDynamic() multiple times
	 * with the same static object must return the exact same wrapper instance.
	 */
	@Test
	fun `toDynamic returns same instance for repeated calls`() {
		// Given: Simulation context
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")
		(editingContextFactory.createContext(xmlStream) as EditingContext).use { editingContext ->
			(simulationContextFactory.createContext(editingContext) as DefaultSimulationContext).use { context ->
				// When: Retrieving an InOut and calling toDynamic multiple times
				val firstInOut = context.getInOuts().first()
				val staticRef = firstInOut.staticRef

				val wrapper1 = context.toDynamic(staticRef)
				val wrapper2 = context.toDynamic(staticRef)
				val wrapper3 = context.toDynamic(staticRef)

				// Then: All calls must return the same instance
				assertThat(wrapper1).isSameAs(firstInOut)
				assertThat(wrapper2).isSameAs(firstInOut)
				assertThat(wrapper3).isSameAs(firstInOut)
			}
		}
	}

	/**
	 * Test that InOut semaphores are properly mapped.
	 *
	 * Each InOut has embedded in/out semaphores. These semaphores must be mapped
	 * to their corresponding DynamicRailSemaphore wrappers.
	 */
	@Test
	fun `InOut semaphores are properly mapped to dynamic wrappers`() {
		// Given: Simulation context with InOuts
		val xmlStream: InputStream =
			TestFixtures.loadShuntingXml()
				?: throw IllegalStateException("vyhybna.xml not found in resources")
		(editingContextFactory.createContext(xmlStream) as EditingContext).use { editingContext ->
			(simulationContextFactory.createContext(editingContext) as DefaultSimulationContext).use { context ->
				// When/Then: Check each InOut's semaphores
				for (dynamicInOut in context.getInOuts()) {
					// InOut's embedded semaphores must be in staticToDynamicMap
					val staticInOut = dynamicInOut.staticRef
					val inSemaphore = staticInOut.getInSemaphore()
					val outSemaphore = staticInOut.getOutSemaphore()

					// toDynamic() should find the semaphore wrappers
					val dynamicInSemaphore = context.toDynamic(inSemaphore)
					val dynamicOutSemaphore = context.toDynamic(outSemaphore)

					// Verify these are the same instances as in DynamicInOut
					assertThat(dynamicInSemaphore).isSameAs(dynamicInOut.inSemaphore)
					assertThat(dynamicOutSemaphore).isSameAs(dynamicInOut.outSemaphore)
				}
			}
		}
	}
}
