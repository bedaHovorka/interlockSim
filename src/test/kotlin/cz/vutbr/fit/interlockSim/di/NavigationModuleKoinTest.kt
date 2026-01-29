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
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
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

			// Get the first section from the block to test TrainNavigationService
			val block = blocks.first()
			val section = block.getNextTrackSection(inOutA, null)
			assertThat(section).isNotNull()

			// TrainNavigationService should recognize train1's ownership via shared registry
			val path = trainNavigationService.findReservedPathForTrain("train1", inOutA, section!!)
			assertThat(path).isNotNull()  // Path found because train1 owns the blocks

			// Different train should not get a path (not owner)
			val pathOther = trainNavigationService.findReservedPathForTrain("train2", inOutA, section)
			assertThat(pathOther).isNull()  // null because train2 doesn't own the blocks
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
			// Act - reserve path and use train navigation service
			val pathService = context.getPathReservationService()
			val trainService = context.getTrainNavigationService()

			val grid = context.getRailWayNetGrid()
			val inOutA = context.toDynamic(grid.getCellAt(1, 1) as PathSeparator)
			val inOutB = context.toDynamic(grid.getCellAt(5, 5) as PathSeparator)

			pathService.reservePath("train1", inOutA, inOutB)

			// Assert - train service can find path through reserved blocks
			val blocks = pathService.getReservedBlocks("train1")
			assertThat(blocks.size).isEqualTo(1)

			val section = blocks.first().getNextTrackSection(inOutA, null)
			assertThat(section).isNotNull()

			val path = trainService.findReservedPathForTrain("train1", inOutA, section!!)
			assertThat(path).isNotNull()  // Train service finds path because train1 owns the blocks
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
