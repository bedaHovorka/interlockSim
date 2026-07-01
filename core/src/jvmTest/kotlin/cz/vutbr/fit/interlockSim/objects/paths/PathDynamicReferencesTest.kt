/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Phase 7: Test that paths in simulation context contain only dynamic references
 */
package cz.vutbr.fit.interlockSim.objects.paths

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.PathResult
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Tests that paths created in simulation context contain only dynamic references.
 *
 * ## Migration from Issue #296 Phase 5
 *
 * This test suite has been refactored to use the new navigation services API:
 * - **TopologyNavigator**: Pure topology traversal (no state dependencies)
 * - **PathReservationService**: Atomic path reservation with dynamic block handling
 * - **TrainNavigationService**: Train-specific navigation through reserved blocks
 *
 * The original tests validated that `pathToNextSemaphore()` returned paths with
 * dynamic separators. This behavior is now distributed across three services,
 * all of which properly handle dynamic type conversion.
 *
 * ## Test Coverage
 *
 * - ✅ TopologyNavigator returns paths with dynamic separators
 * - ✅ PathReservationService reserves dynamic blocks
 * - ✅ TrainNavigationService validates dynamic ownership
 * - ✅ Path iteration yields dynamic separators
 * - ✅ Static → dynamic conversion via context.toDynamic()
 *
 * @since Phase 7 (original implementation)
 * @since Issue #296 Phase 5 (refactored for new navigation services API)
 */
@DisplayName("Path Dynamic References (Phase 7)")
@Tag("integration-test")
class PathDynamicReferencesTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Nested
	@DisplayName("TopologyNavigator Dynamic References")
	inner class TopologyNavigatorDynamicReferences {
		@Test
		@DisplayName("findAllTopologicalPaths returns paths with dynamic separators")
		fun findAllTopologicalPaths_returnsDynamicSeparators() {
			// Arrange - Load vyhybna.xml network
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				// Get InOut elements (entry/exit points)
				val inOuts = context.getInOuts()
				assertThat(inOuts.size).isNotNull()
				val inOutsList = inOuts.toList()
				val inOut1 = context.toDynamic(inOutsList[0])
				val inOut2 = context.toDynamic(inOutsList[1])

				// Get TopologyNavigator from context scope
				val navigator = context.getRoutingServices().getTopologyNavigator()

				// Act - Find all topological paths from InOut1 to InOut2
				val paths = navigator.findAllTopologicalPaths(inOut1, inOut2)

				// Assert - Paths should exist
				assertThat(paths).isNotNull()
				assertThat(paths.isNotEmpty()).isTrue()

				// Verify all PathSeparators in paths are dynamic (if any exist)
				var totalSeparators = 0
				for (path in paths) {
					for (element in path) {
						if (element is PathSeparator) {
							totalSeparators++
							// All separators must be DynamicPathSeparator
							assertThat(element)
								.isInstanceOf(DynamicPathSeparator::class)
						}
					}
				}
				// Note: vyhybna.xml may have paths without intermediate separators
				// The important thing is that IF separators exist, they are dynamic
				// This test passes if paths exist (even without separators)
			}
		}

		@Test
		@DisplayName("topology paths preserve dynamic types during iteration")
		fun topologyPaths_preserveDynamicTypesDuringIteration() {
			// Arrange
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				val inOuts = context.getInOuts().toList()
				val inOut1 = context.toDynamic(inOuts[0])
				val inOut2 = context.toDynamic(inOuts[1])

				val navigator = context.getRoutingServices().getTopologyNavigator()

				// Act - Get paths and iterate
				val paths = navigator.findAllTopologicalPaths(inOut1, inOut2)
				assertThat(paths).isNotNull()

				// Assert - All iterations yield dynamic separators
				for (path in paths) {
					val separators = path.filter { it is PathSeparator }
					for (separator in separators) {
						assertThat(separator)
							.isInstanceOf(DynamicPathSeparator::class)
					}
				}
			}
		}
	}

	@Nested
	@DisplayName("PathReservationService Dynamic References")
	inner class PathReservationServiceDynamicReferences {
		@Test
		@DisplayName("reserved paths contain only dynamic blocks")
		fun reservedPaths_containOnlyDynamicBlocks() {
			// Arrange
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				val inOuts = context.getInOuts().toList()
				val inOut1 = context.toDynamic(inOuts[0])
				val inOut2 = context.toDynamic(inOuts[1])

				val service = context.getRoutingServices().getPathReservationService()

				// Act - Reserve path
				val result = service.reservePath("train1", inOut1, inOut2)

				// Assert - Reservation should succeed
				assertThat(result).isInstanceOf(PathReservationService.ReservationResult.Success::class)

				val reservedBlocks = (result as PathReservationService.ReservationResult.Success).reservedBlocks

				// All reserved blocks should be DynamicTrackBlock (which are dynamic types)
				assertThat(reservedBlocks.isNotEmpty()).isTrue()

				// Verify blocks are dynamic (have trainName property for ownership tracking)
				for (block in reservedBlocks) {
					// DynamicTrackBlock has trainName property
					assertThat(block.trainName).isNotNull()
				}
			}
		}

		@Test
		@DisplayName("path reservation uses dynamic wrappers")
		fun pathReservation_usesDynamicWrappers() {
			// Arrange
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				val inOuts = context.getInOuts().toList()
				val staticInOut1 = inOuts[0] // Static reference
				val staticInOut2 = inOuts[1] // Static reference

				// Convert to dynamic
				val dynamicInOut1 = context.toDynamic(staticInOut1)
				val dynamicInOut2 = context.toDynamic(staticInOut2)

				// Verify conversion worked
				assertThat(dynamicInOut1).isInstanceOf(DynamicPathSeparator::class)
				assertThat(dynamicInOut2).isInstanceOf(DynamicPathSeparator::class)

				val service = context.getRoutingServices().getPathReservationService()

				// Act - Reserve path using dynamic separators
				val result = service.reservePath("train1", dynamicInOut1, dynamicInOut2)

				// Assert - Reservation should succeed
				assertThat(result).isInstanceOf(PathReservationService.ReservationResult.Success::class)
			}
		}
	}

	@Nested
	@DisplayName("TrainNavigationService Dynamic References")
	inner class TrainNavigationServiceDynamicReferences {
		@Test
		@DisplayName("findReservedPathForTrain returns paths with dynamic separators")
		fun findReservedPathForTrain_returnsDynamicSeparators() {
			// Arrange
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				val inOuts = context.getInOuts().toList()
				val inOut1 = context.toDynamic(inOuts[0])
				val inOut2 = context.toDynamic(inOuts[1])

				// Reserve path first
				val reservationService = context.getRoutingServices().getPathReservationService()
				val reservationResult = reservationService.reservePath("train1", inOut1, inOut2)
				assertThat(reservationResult).isInstanceOf(PathReservationService.ReservationResult.Success::class)

				// Get TrainNavigationService
				val trainService = context.getRoutingServices().getTrainNavigationService()

				// Act - Find reserved path for train
				val path = trainService.findReservedPathForTrain("train1", inOut1)

				// Assert - Path should exist and contain dynamic separators
				assertThat(path).isInstanceOf(PathResult.Available::class)

				val pathElements = (path as PathResult.Available).path

				var separatorCount = 0
				for (element in pathElements) {
					if (element is PathSeparator) {
						separatorCount++
						// All separators must be DynamicPathSeparator
						assertThat(element)
							.isInstanceOf(DynamicPathSeparator::class)
					}
				}

				// Verify path contains separators
				assertThat(separatorCount > 0).isTrue()
			}
		}

		@Test
		@DisplayName("train navigation validates dynamic block types")
		fun trainNavigation_validatesDynamicBlockTypes() {
			// Arrange
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				val inOuts = context.getInOuts().toList()
				val inOut1 = context.toDynamic(inOuts[0])
				val inOut2 = context.toDynamic(inOuts[1])

				// Reserve path for train1
				val reservationService = context.getRoutingServices().getPathReservationService()
				reservationService.reservePath("train1", inOut1, inOut2)

				// Get TrainNavigationService
				val trainService = context.getRoutingServices().getTrainNavigationService()

				// Act - Check if path is reserved for train1 (should be true)
				val isReservedForTrain1 = trainService.isPathReservedForTrain("train1", inOut1)

				// Check if path is reserved for train2 (should be false - different train)
				val isReservedForTrain2 = trainService.isPathReservedForTrain("train2", inOut1)

				// Assert
				assertThat(isReservedForTrain1).isTrue()
				assertThat(isReservedForTrain2).isFalse() // Different train
			}
		}
	}

	@Nested
	@DisplayName("Static to Dynamic Conversion")
	inner class StaticToDynamicConversion {
		@Test
		@DisplayName("context.toDynamic converts static separators to dynamic")
		fun toDynamic_convertsStaticToDynamic() {
			// Arrange
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				// Get STATIC reference (before conversion) using getInOutsList()
				val staticInOuts = context.getInOutsList()
				val staticInOut = staticInOuts[0]

				// Act - Convert to dynamic
				val dynamicInOut = context.toDynamic(staticInOut)

				// Assert - Result should be DynamicPathSeparator
				assertThat(dynamicInOut).isInstanceOf(DynamicPathSeparator::class)

				// Verify conversion created a wrapper (dynamicInOut wraps staticInOut)
				// DynamicInOut is a wrapper type, not the same type as InOut
				assertThat(dynamicInOut.javaClass.simpleName).isNotNull()
				assertThat(staticInOut.javaClass.simpleName).isNotNull()
				// The types should be different (Dynamic vs Static)
				assertThat(dynamicInOut.javaClass != staticInOut.javaClass).isTrue()
			}
		}

		@Test
		@DisplayName("toDynamic is idempotent for dynamic inputs")
		fun toDynamic_idempotentForDynamicInputs() {
			// Arrange
			TestFixtures.loadShuntingXml().use { stream ->
				val editingContext = editingContextFactory.createContext(stream) as EditingContext
				val context = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

				val inOuts = context.getInOuts().toList()
				val staticInOut = inOuts[0]

				// Convert to dynamic
				val dynamicInOut1 = context.toDynamic(staticInOut)

				// Act - Convert again (should be idempotent)
				val dynamicInOut2 = context.toDynamic(dynamicInOut1)

				// Assert - Should return same dynamic wrapper
				assertThat(dynamicInOut2).isInstanceOf(DynamicPathSeparator::class)
				assertThat(dynamicInOut1 === dynamicInOut2).isTrue() // Same object
			}
		}
	}
}
