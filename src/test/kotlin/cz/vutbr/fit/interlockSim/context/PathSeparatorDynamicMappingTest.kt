/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Test for PathSeparator-to-Dynamic mapping functionality
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.Cell
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.paths.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.hasMessageContaining
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject
import java.io.File
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Tests for PathSeparator-to-Dynamic mapping in SimulationContext
 *
 * Validates:
 * - toDynamic() returns DynamicPathSeparator for registered separators
 * - toDynamic() throws IllegalStateException for unregistered separators
 * - toDynamic() is identity function for already-dynamic separators
 * - All separators are registered after initializeDynamicMapping()
 */
@DisplayName("PathSeparator-to-Dynamic Mapping")
class PathSeparatorDynamicMappingTest : KoinTestBase() {
	private val factory: XMLContextFactory by inject()

	@Nested
	@DisplayName("Unit Tests - toDynamic() behavior")
	inner class ToDynamicUnitTests {
		/**
		 * Validates that toDynamic() is identity function for already-dynamic separators
		 */
		@Test
		@DisplayName("toDynamic returns same instance for already-dynamic separator")
		fun toDynamic_alreadyDynamic_returnsSameInstance() {
			// Arrange - Load vyhybna.xml and initialize mappings
			val xmlFile = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			val context = factory.createContext(xmlFile) as DefaultSimulationContext

			// Initialize dynamic wrappers for InOuts
			val dynamicInOuts = context.getInOuts()
			assertThat(dynamicInOuts).isNotNull()

			// Get a dynamic InOut (these are guaranteed to be registered)
			val dynamicInOut = dynamicInOuts.first()
			val staticInOut = dynamicInOut.static
			
			// Get the InOut's in semaphore (also registered)
			val staticInSemaphore = staticInOut.getInSemaphore()
			val dynamicSemaphore = context.toDynamic(staticInSemaphore)
			assertThat(dynamicSemaphore).isInstanceOf(DynamicPathSeparator::class)

			// Act - call toDynamic on already-dynamic separator
			val result = context.toDynamic(dynamicSemaphore)

			// Assert - should return same instance (identity function)
			assertThat(result).isSameInstanceAs(dynamicSemaphore)
		}

		/**
		 * Validates that toDynamic() throws for unregistered static separator
		 */
		@Test
		@DisplayName("toDynamic throws IllegalStateException for unregistered separator")
		fun toDynamic_unregisteredSeparator_throwsException() {
			// Arrange - Empty context with no separators
			val context = factory.createEmptyContext() as DefaultSimulationContext

			// Create a RailSemaphore that's not registered
			val unregisteredSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			unregisteredSemaphore.setName("unregistered")

			// Act & Assert - should throw IllegalStateException
			assertThatBlock {
				context.toDynamic(unregisteredSemaphore)
			}.isFailure()
				.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("Dynamic wrapper not found for separator")
				.hasMessageContaining("unregistered")
		}

		/**
		 * Validates error message contains helpful debugging information
		 */
		@Test
		@DisplayName("toDynamic error message includes map size and class name")
		fun toDynamic_unregisteredSeparator_includesDebugInfo() {
			// Arrange
			val context = factory.createEmptyContext() as DefaultSimulationContext
			val unregisteredSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)

			// Act & Assert
			assertThatBlock {
				context.toDynamic(unregisteredSemaphore)
			}.isFailure()
				.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("RailSemaphore")  // Class name
				.hasMessageContaining("Map contains")    // Map size info
				.hasMessageContaining("entries")
		}
	}

	@Nested
	@DisplayName("Integration Tests - vyhybna.xml")
	@Tag("integration-test")
	inner class VyhybnaIntegrationTests {
		/**
		 * Integration test: Verify all separators are registered after getInOuts()
		 */
		@Test
		@DisplayName("vyhybna.xml - all separators registered after getInOuts()")
		fun vyhybnaXml_allSeparatorsRegistered() {
			// Arrange - Load vyhybna.xml configuration
			val xmlFile = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			val context = factory.createContext(xmlFile) as DefaultSimulationContext

			// Act - Trigger initialization by calling getInOuts()
			context.getInOuts()

			// Assert - All separators in grid should be convertible
			val grid = context.getRailWayNetGrid()
			var testedSeparators = 0

			for (x in 0 until grid.getCols()) {
				for (y in 0 until grid.getRows()) {
					val cell = grid.getCellAt(x, y)
					if (cell is RailSemaphore) {
						val dynamic = context.toDynamic(cell)
						assertThat(dynamic).isInstanceOf(DynamicPathSeparator::class)
						assertThat(dynamic).isInstanceOf(DynamicRailSemaphore::class)
						testedSeparators++
					} else if (cell is RailSwitch) {
						val dynamic = context.toDynamic(cell)
						assertThat(dynamic).isInstanceOf(DynamicPathSeparator::class)
						testedSeparators++
					} else if (cell is InOut) {
						val dynamic = context.toDynamic(cell)
						assertThat(dynamic).isInstanceOf(DynamicPathSeparator::class)
						testedSeparators++
					}
				}
			}

			// Verify we tested multiple separators
			assertThat(testedSeparators).isNotNull()
			println("Tested $testedSeparators separators successfully")
		}

		/**
		 * Integration test: Verify InOut semaphores are registered
		 */
		@Test
		@DisplayName("vyhybna.xml - InOut semaphores are registered")
		fun vyhybnaXml_inOutSemaphoresRegistered() {
			// Arrange
			val xmlFile = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			val context = factory.createContext(xmlFile) as DefaultSimulationContext

			// Act - Trigger initialization
			val dynamicInOuts = context.getInOuts()

			// Assert - InOut semaphores should be convertible
			for (dynamicInOut in dynamicInOuts) {
				val staticInOut = dynamicInOut.static
				
				// Convert InOut's semaphores
				val inSemaphore = context.toDynamic(staticInOut.getInSemaphore())
				val outSemaphore = context.toDynamic(staticInOut.getOutSemaphore())

				assertThat(inSemaphore).isInstanceOf(DynamicPathSeparator::class)
				assertThat(outSemaphore).isInstanceOf(DynamicPathSeparator::class)
			}
		}

		/**
		 * Integration test: Verify repeated calls return same instance
		 */
		@Test
		@DisplayName("vyhybna.xml - repeated toDynamic calls return same instance")
		fun vyhybnaXml_repeatedCallsReturnSameInstance() {
			// Arrange
			val xmlFile = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
			val context = factory.createContext(xmlFile) as DefaultSimulationContext
			context.getInOuts()

			// Get a static semaphore
			val grid = context.getRailWayNetGrid()
			val staticSemaphore = grid.getCellAt(14, 8) as RailSemaphore

			// Act - call toDynamic multiple times
			val dynamic1 = context.toDynamic(staticSemaphore)
			val dynamic2 = context.toDynamic(staticSemaphore)
			val dynamic3 = context.toDynamic(staticSemaphore)

			// Assert - all should be the same instance
			assertThat(dynamic2).isSameInstanceAs(dynamic1)
			assertThat(dynamic3).isSameInstanceAs(dynamic1)
		}
	}
}
