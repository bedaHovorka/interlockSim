/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Integration tests for navigationModule Koin DI configuration
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.di

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TrainNavigationService
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.module.Module

/**
 * Integration tests for navigationModule Koin DI configuration with scope-per-context pattern.
 *
 * ## Test Coverage
 *
 * - ✅ Scoped registry shared within one context
 * - ✅ Different contexts have isolated registries
 * - ✅ PathReservationService and TrainNavigationService share registry
 * - ✅ Scope cleanup releases resources
 * - ✅ No state leakage between contexts
 *
 * ## Design Verification
 *
 * These tests verify the scope-per-context architectural pattern:
 * - Each DefaultSimulationContext creates its own Koin scope
 * - One PathReservationRegistry per scope (shared by all services in that context)
 * - Different contexts have isolated registries (no state bleeding)
 * - Closing context cleans up scoped resources
 *
 * @since Issue #294 (Phase 2 DI Integration)
 * @since Issue #296 (Phase 4 Scope-per-Context Pattern)
 */
@Tag("integration-test")
class NavigationModuleKoinTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	@Test
	fun `services within one context share the same registry`() {
		buildTestContext().use { context ->
			// Arrange - create one simulation context
			// Act - get both navigation services from the context
			val pathReservationService = context.getPathReservationService()
			val trainNavigationService = context.getTrainNavigationService()

			// Reserve path using PathReservationService
			val grid = context.getRailWayNetGrid()
			val inOutA = context.toDynamic(grid.getCellAt(1, 1) as PathSeparator)
			val inOutB = context.toDynamic(grid.getCellAt(5, 5) as PathSeparator)
			pathReservationService.reservePath("train1", inOutA, inOutB)

			// Assert - Both services see the same reservation (shared registry)
			val blocks = pathReservationService.getReservedBlocks("train1")
			assertThat(blocks.size).isEqualTo(1)

			// Verify train1 can navigate through reserved blocks (shared registry)
			val path = trainNavigationService.findReservedPathForTrain("train1", inOutA)

			// Extract blocks from path returned by TrainNavigationService
			val blocksFromPath = path!!.filterIsInstance<TrackSection>()
				.map { it.getTrackBlock() }
				.filterIsInstance<DynamicTrackBlock>()
				.toSet()

			// Compare with blocks from PathReservationService (should be identical)
			val blocksFromReservation = blocks.toSet()
			assertThat(blocksFromPath).isEqualTo(blocksFromReservation)

			// Verify train2 cannot get path (ownership isolation)
			val pathForTrain2 = trainNavigationService.findReservedPathForTrain("train2", inOutA)
			assertThat(pathForTrain2).isNull()
		}
	}

	@Test
	fun `different contexts have isolated registries`() {
		buildTestContext().use { context1 ->
			buildTestContext().use { context2 ->
				// Arrange - create two simulation contexts
				// Act - reserve path in context1
				val service1 = context1.getPathReservationService()
				val grid1 = context1.getRailWayNetGrid()
				val inOutA1 = context1.toDynamic(grid1.getCellAt(1, 1) as PathSeparator)
				val inOutB1 = context1.toDynamic(grid1.getCellAt(5, 5) as PathSeparator)
				service1.reservePath("train1", inOutA1, inOutB1)

				// Assert - context2 should not see train1's reservation (different scope)
				val service2 = context2.getPathReservationService()
				assertThat(service2.getReservedBlocks("train1")).isEmpty()
			}
		}
	}

	@Test
	fun `PathReservationService is functional within scoped context`() {
		buildTestContext().use { context ->
			// Arrange - create context
			// Act - get service from context and use it
			val service = context.getPathReservationService()
			val grid = context.getRailWayNetGrid()
			val inOutA = context.toDynamic(grid.getCellAt(1, 1) as PathSeparator)
			val inOutB = context.toDynamic(grid.getCellAt(5, 5) as PathSeparator)

			val result = service.reservePath("test-train", inOutA, inOutB)

			// Assert - service works correctly
			assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			assertThat(service.getReservedBlocks("test-train").size).isEqualTo(1)
		}
	}

	@Test
	fun `TrainNavigationService is functional within scoped context`() {
		buildTestContext().use { context ->
			// Arrange - create context with train
			// Act - reserve path and get train navigation service
			val pathService = context.getPathReservationService()
			val trainService = context.getTrainNavigationService()

			val grid = context.getRailWayNetGrid()
			val inOutA = context.toDynamic(grid.getCellAt(1, 1) as PathSeparator)
			val inOutB = context.toDynamic(grid.getCellAt(5, 5) as PathSeparator)

			pathService.reservePath("train1", inOutA, inOutB)

			// Assert - TrainNavigationService is instantiated and functional
			assertThat(trainService).isInstanceOf(TrainNavigationService::class)

			// Verify the service can perform ownership checks
			// Note: InOut elements ARE OrientedPathSeparators (via OrientedNodeCell)
			// and contain semaphores, so the path IS valid and returns true
			val isReserved = trainService.isPathReservedForTrain("train1", inOutA)
			// Assert - This correctly returns true because the path is reserved for train1
			// and InOut elements are valid oriented path separators with semaphores
			assertThat(isReserved).isEqualTo(true)
		}
	}

	@Test
	fun `closing context cleans up scoped resources`() {
		buildTestContext().use { context ->
			// Arrange - create context and use services
			val service = context.getPathReservationService()

			val grid = context.getRailWayNetGrid()
			val inOutA = context.toDynamic(grid.getCellAt(1, 1) as PathSeparator)
			val inOutB = context.toDynamic(grid.getCellAt(5, 5) as PathSeparator)
			service.reservePath("train1", inOutA, inOutB)

			// Verify reservation exists
			assertThat(service.getReservedBlocks("train1").size).isEqualTo(1)
		} // Act - context is automatically closed here

		// Assert - scope is closed (accessing services after close would fail)
		// We verify that creating a new context gives us a clean slate
		buildTestContext().use { newContext ->
			val newService = newContext.getPathReservationService()
			assertThat(newService.getReservedBlocks("train1")).isEmpty()
		}
	}

	@Test
	fun `scope isolation prevents state bleeding between sequential contexts`() {
		// This test verifies that scoped pattern + close()
		// prevent state from leaking between simulation runs

		buildTestContext().use { context1 ->
			// Arrange - create first context and register trains
			val service1 = context1.getPathReservationService()
			val grid1 = context1.getRailWayNetGrid()
			val inOutA1 = context1.toDynamic(grid1.getCellAt(1, 1) as PathSeparator)
			val inOutB1 = context1.toDynamic(grid1.getCellAt(5, 5) as PathSeparator)
			service1.reservePath("train1", inOutA1, inOutB1)

			// Verify registration
			assertThat(service1.getReservedBlocks("train1").size).isEqualTo(1)
		} // Act - first context automatically closed here

		// Create second context and verify clean state
		buildTestContext().use { context2 ->
			val service2 = context2.getPathReservationService()

			// Assert - new context has clean registry (no leakage)
			assertThat(service2.getReservedBlocks("train1")).isEmpty()
		}
	}

	// ========== Helper Methods ==========

	/**
	 * Build a simple test context with InOut A -> InOut B.
	 * Each call creates a NEW TestContextBuilder instance to avoid reusing frozen EditingContext.
	 * Each context gets its own Koin scope.
	 */
	private fun buildTestContext(): cz.vutbr.fit.interlockSim.context.DefaultSimulationContext {
		// Get a fresh TestContextBuilder for each call (avoids frozen EditingContext reuse)
		val builder: TestContextBuilder = getKoin().get()
		return builder
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildSimulationContext()
	}
}
