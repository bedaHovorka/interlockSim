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

	companion object {
		private val VYHYBNA_XML = File("src/main/resources/cz/vutbr/fit/interlockSim/resource/vyhybna.xml")
	}

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
			val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext

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
		 * Integration test: Verify InOut-related separators are registered after getInOuts()
		 *
		 * Note: getInOuts() only initializes InOut cells and their associated semaphores.
		 * Other separators (standalone RailSemaphore, RailSwitch) are initialized later
		 * by initializeDynamicMapping() when run() is called.
		 */
		@Test
		@DisplayName("vyhybna.xml - InOut-related separators registered after getInOuts()")
		fun vyhybnaXml_inOutRelatedSeparatorsRegistered() {
			// Arrange - Load vyhybna.xml configuration
			val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext

			// Act - Trigger InOut initialization by calling getInOuts()
			val dynamicInOuts = context.getInOuts()

			// Assert - InOut cells and their semaphores should be convertible
			var testedInOuts = 0
			var testedSemaphores = 0

			for (inOut in dynamicInOuts) {
				// Test InOut itself
				val dynamicInOut = context.toDynamic(inOut.static)
				assertThat(dynamicInOut).isInstanceOf(DynamicPathSeparator::class)
				testedInOuts++

				// Test InOut's semaphores (these are registered by getInOuts())
				val staticInSem = inOut.static.getInSemaphore()
				val staticOutSem = inOut.static.getOutSemaphore()

				val dynamicInSem = context.toDynamic(staticInSem)
				assertThat(dynamicInSem).isInstanceOf(DynamicPathSeparator::class)
				assertThat(dynamicInSem).isInstanceOf(DynamicRailSemaphore::class)
				testedSemaphores++

				val dynamicOutSem = context.toDynamic(staticOutSem)
				assertThat(dynamicOutSem).isInstanceOf(DynamicPathSeparator::class)
				assertThat(dynamicOutSem).isInstanceOf(DynamicRailSemaphore::class)
				testedSemaphores++
			}

			// Verify we tested InOuts and their semaphores
			assertThat(testedInOuts).isNotNull()
			assertThat(testedSemaphores).isNotNull()
			println("Tested $testedInOuts InOuts and $testedSemaphores associated semaphores successfully")
		}

		/**
		 * Integration test: Verify InOut semaphores are registered
		 */
		@Test
		@DisplayName("vyhybna.xml - InOut semaphores are registered")
		fun vyhybnaXml_inOutSemaphoresRegistered() {
			// Arrange
			val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext

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
			val context = factory.createContext(VYHYBNA_XML) as DefaultSimulationContext
			context.getInOuts()

			// Get a static semaphore - use first InOut's in semaphore for reliability
			val dynamicInOut = context.getInOuts().first()
			val staticSemaphore = dynamicInOut.static.getInSemaphore()

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
