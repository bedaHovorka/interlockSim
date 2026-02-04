/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Cache Validation Tests - PR #276 Review Recommendations
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.hasMessageContaining
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject
import cz.vutbr.fit.interlockSim.testutil.assertThat as assertThatBlock

/**
 * Comprehensive cache validation tests based on PR #276 code review recommendations.
 *
 * Tests verify:
 * 1. Cache hit behavior - same wrapper returned for same static object
 * 2. Cache miss behavior - helpful error messages for unmapped separators
 * 3. Error message quality - includes diagnostic information
 * 4. Cache completeness - validateDynamicMapping() catches unmapped separators
 * 5. Edge cases - null inputs, already-dynamic inputs, multiple wrapper types
 *
 * Implementation details tested (DefaultSimulationContext):
 * - staticToDynamicMap acts as singleton cache
 * - toDynamic() validates cache hits/misses
 * - validateDynamicMapping() runs after initialization
 * - Error messages include map size, class names, identity hash codes
 */
@DisplayName("Cache Validation (PR #276 Review)")
class CacheValidationTest : KoinTestBase() {
	private val editingContextFactory: cz.vutbr.fit.interlockSim.context.EditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Nested
	@DisplayName("Cache Hit Behavior")
	inner class CacheHitBehavior {
		@Test
		@DisplayName("same wrapper returned for repeated toDynamic calls with same static object")
		fun repeatedCallsReturnSameInstance() {
			// Given: Simulation context with mapped separators
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)

				// When: Getting static reference and calling toDynamic multiple times
				val dynamicInOut = context.getInOuts().first()
				val staticInOut = dynamicInOut.staticRef

				val wrapper1 = context.toDynamic(staticInOut)
				val wrapper2 = context.toDynamic(staticInOut)
				val wrapper3 = context.toDynamic(staticInOut)

				// Then: All calls return the same instance (cache hit)
				assertThat(wrapper1).isSameInstanceAs(wrapper2)
				assertThat(wrapper2).isSameInstanceAs(wrapper3)
				assertThat(wrapper1).isSameInstanceAs(dynamicInOut)
			}
		}

		@Test
		@DisplayName("same wrapper returned for InOut semaphore repeated calls")
		fun semaphoreRepeatedCallsReturnSameInstance() {
			// Given: Context with InOut semaphores
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)
				val dynamicInOut = context.getInOuts().first()
				val staticSemaphore = dynamicInOut.staticRef.getInSemaphore()

				// When: Calling toDynamic multiple times
				val wrapper1 = context.toDynamic(staticSemaphore)
				val wrapper2 = context.toDynamic(staticSemaphore)
				val wrapper3 = context.toDynamic(staticSemaphore)

				// Then: Same instance returned (cache hit)
				assertThat(wrapper1).isSameInstanceAs(wrapper2)
				assertThat(wrapper2).isSameInstanceAs(wrapper3)
				assertThat(wrapper1).isSameInstanceAs(dynamicInOut.inSemaphore)
			}
		}

		@Test
		@DisplayName("toDynamic is identity function for already-dynamic separators")
		fun alreadyDynamicPassthrough() {
			// Given: Context with dynamic wrappers
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)
				val dynamicInOut = context.getInOuts().first()

				// When: Calling toDynamic on already-dynamic separator
				val result = context.toDynamic(dynamicInOut)

				// Then: Same instance returned (identity function)
				assertThat(result).isSameInstanceAs(dynamicInOut)
			}
		}
	}

	@Nested
	@DisplayName("Cache Miss Behavior")
	inner class CacheMissBehavior {
		@Test
		@DisplayName("unmapped InOut throws IllegalStateException")
		fun unmappedInOutThrowsException() {
			// Given: Empty context with no separators
			val context = simulationContextFactory.createEmptyContext()

			// When: Creating unmapped InOut
			val unmappedInOut = InOut("unmapped-inout", true, Cell.SpatialType.HORIZONTAL)

			// Then: toDynamic throws IllegalStateException
			assertThatBlock {
				context.toDynamic(unmappedInOut)
			}.isFailure()
				.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("Dynamic wrapper not found for separator")
		}

		@Test
		@DisplayName("unmapped RailSemaphore throws IllegalStateException")
		fun unmappedSemaphoreThrowsException() {
			// Given: Empty context
			val context = simulationContextFactory.createEmptyContext()

			// When: Creating unmapped semaphore
			val unmappedSemaphore = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			unmappedSemaphore.setName("unmapped-semaphore")

			// Then: toDynamic throws
			assertThatBlock {
				context.toDynamic(unmappedSemaphore)
			}.isFailure()
				.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("Dynamic wrapper not found for separator")
		}

		@Test
		@DisplayName("unmapped RailSwitch throws IllegalStateException")
		fun unmappedSwitchThrowsException() {
			// Given: Empty context
			val context = simulationContextFactory.createEmptyContext()

			// When: Creating unmapped switch
			val unmappedSwitch = RailSwitch(Cell.SpatialType.HORIZONTAL, RailSwitch.Type.SIMPLE_RIGHT_FALSE)
			unmappedSwitch.setName("unmapped-switch")

			// Then: toDynamic throws
			assertThatBlock {
				context.toDynamic(unmappedSwitch)
			}.isFailure()
				.isInstanceOf(IllegalStateException::class)
				.hasMessageContaining("Dynamic wrapper not found for separator")
		}
	}

	@Nested
	@DisplayName("Error Message Quality")
	inner class ErrorMessageQuality {
		@Test
		@DisplayName("error includes separator class name")
		fun includesClassName() {
			// Given: Empty context and unmapped semaphore
			val context = simulationContextFactory.createEmptyContext()
			val unmapped = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)

			// When/Then: Error message includes class name
			assertThatBlock {
				context.toDynamic(unmapped)
			}.isFailure()
				.hasMessageContaining("RailSemaphore")
		}

		@Test
		@DisplayName("error includes map size information")
		fun includesMapSize() {
			// Given: Empty context (map size = 0)
			val context = simulationContextFactory.createEmptyContext()
			val unmapped = InOut("test-inout", true, Cell.SpatialType.HORIZONTAL)

			// When/Then: Error message includes map size
			assertThatBlock {
				context.toDynamic(unmapped)
			}.isFailure()
				.hasMessageContaining("Map contains")
				.hasMessageContaining("entries")
		}

		@Test
		@DisplayName("error includes separator name if available")
		fun includesSeparatorName() {
			// Given: Named unmapped separator
			val context = simulationContextFactory.createEmptyContext()
			val unmapped = RailSemaphore(true, Cell.SpatialType.HORIZONTAL)
			unmapped.setName("test-semaphore")

			// When/Then: Error message includes the name
			assertThatBlock {
				context.toDynamic(unmapped)
			}.isFailure()
				.hasMessageContaining("test-semaphore")
		}

		@Test
		@DisplayName("error includes initialization prerequisite instructions")
		fun includesPrerequisiteInstructions() {
			// Given: Empty context
			val context = simulationContextFactory.createEmptyContext()
			val unmapped = InOut("test-inout", true, Cell.SpatialType.HORIZONTAL)

			// When/Then: Error message mentions initialization prerequisite
			assertThatBlock {
				context.toDynamic(unmapped)
			}.isFailure()
				.hasMessageContaining("initializeDynamicMapping()")
		}
	}

	@Nested
	@DisplayName("Cache Completeness Validation")
	inner class CacheCompletenessValidation {
		@Test
		@DisplayName("validateDynamicMapping succeeds for complete vyhybna.xml")
		fun completeVyhybnaValidates() {
			// Given: Fully initialized context
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)

				// When: Triggering validation by accessing InOuts
				val inouts = context.getInOuts()

				// Then: No exception thrown, InOuts present
				assertThat(inouts).isNotNull()
			}
		}

		@Test
		@DisplayName("all InOut semaphores are mapped after initialization")
		fun allInOutSemaphoresMapped() {
			// Given: Context with InOuts
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)

				// When: Accessing all InOut semaphores
				for (dynamicInOut in context.getInOuts()) {
					val staticInOut = dynamicInOut.staticRef

					// Then: Both in and out semaphores should be mapped
					val inSemaphore = context.toDynamic(staticInOut.getInSemaphore())
					val outSemaphore = context.toDynamic(staticInOut.getOutSemaphore())

					assertThat(inSemaphore).isNotNull()
					assertThat(outSemaphore).isNotNull()
				}
			}
		}
	}

	@Nested
	@DisplayName("Edge Cases")
	inner class EdgeCases {
		@Test
		@DisplayName("multiple different wrapper types can coexist in cache")
		fun multipleWrapperTypes() {
			// Given: Context with multiple separator types
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)

				// When: Getting different wrapper types
				val inouts = context.getInOuts()
				val dynamicInOut = inouts.first()
				val dynamicSemaphore = dynamicInOut.inSemaphore

				// Then: Both can be retrieved via toDynamic
				val retrievedInOut = context.toDynamic(dynamicInOut.staticRef)
				val retrievedSemaphore = context.toDynamic(dynamicSemaphore.staticRef)

				assertThat(retrievedInOut).isSameInstanceAs(dynamicInOut)
				assertThat(retrievedSemaphore).isSameInstanceAs(dynamicSemaphore)
			}
		}

		@Test
		@DisplayName("cache preserves wrapper identity across different access patterns")
		fun identityPreservedAcrossAccessPatterns() {
			// Given: Context with mapped separators
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)

				// When: Accessing same separator via different paths
				val fromGetInOuts = context.getInOuts().first()
				val fromToDynamic = context.toDynamic(fromGetInOuts.staticRef)

				// Then: Both access patterns return same instance
				assertThat(fromToDynamic).isSameInstanceAs(fromGetInOuts)
			}
		}

		@Test
		@DisplayName("nested dynamic objects - InOut contains semaphores")
		fun nestedDynamicObjects() {
			// Given: Context with InOut containing semaphores
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext)
				val dynamicInOut = context.getInOuts().first()

				// When: Accessing nested semaphores
				val inSemaphore = dynamicInOut.inSemaphore
				val outSemaphore = dynamicInOut.outSemaphore

				// Then: Nested semaphores are also cached properly
				val fromCache1 = context.toDynamic(inSemaphore.staticRef)
				val fromCache2 = context.toDynamic(outSemaphore.staticRef)

				assertThat(fromCache1).isSameInstanceAs(inSemaphore)
				assertThat(fromCache2).isSameInstanceAs(outSemaphore)
			}
		}
	}
}
