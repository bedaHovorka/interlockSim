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
import assertk.assertions.isNotSameInstanceAs
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.Context
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.TopologyNavigator
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.integrationTestModule
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.test.inject

/**
 * Integration tests for navigationModule Koin DI configuration.
 *
 * ## Test Coverage
 *
 * - ✅ TopologyNavigator factory creates fresh instances
 * - ✅ PathReservationRegistry factory creates isolated instances
 * - ✅ PathReservationService receives correct dependencies
 * - ✅ Parameter passing works correctly
 * - ✅ No state leakage between injections
 *
 * ## Design Verification
 *
 * These tests verify the architectural decisions from the DI Integration Plan:
 * - Services use factory scope (NOT singleton)
 * - Fresh instances prevent state bleeding
 * - Parameter passing pattern works as expected
 * - Registry isolation is maintained
 *
 * @since Issue #294 (Phase 2 DI Integration)
 */
@Tag("integration-test")
class NavigationModuleKoinTest : KoinTestBase() {
	override fun getTestModule(): Module = integrationTestModule

	private val contextBuilder: TestContextBuilder by inject()

	@Test
	fun `TopologyNavigator factory creates fresh instances`() {
		// Arrange
		val context = buildTestContext()

		// Act - get two navigator instances
		val nav1: TopologyNavigator = getKoin().get { parametersOf(context) }
		val nav2: TopologyNavigator = getKoin().get { parametersOf(context) }

		// Assert - factory creates new instances each time
		assertThat(nav1).isNotSameInstanceAs(nav2)
		assertThat(nav1).isNotNull()
		assertThat(nav2).isNotNull()
	}

	@Test
	fun `PathReservationRegistry factory creates isolated instances`() {
		// Act - get two registry instances
		val reg1: PathReservationRegistry = getKoin().get()
		val reg2: PathReservationRegistry = getKoin().get()

		// Assert - factory creates new instances
		assertThat(reg1).isNotSameInstanceAs(reg2)

		// Verify isolation - register in reg1, reg2 should not see it
		val context = buildTestContext() as SimulationContext
		val blocks = getTestBlocks(context)
		reg1.register("train1", blocks)

		// reg2 should not know about train1's blocks
		assertThat(reg2.getOwner(blocks.first())).isNull()
		assertThat(reg2.getBlocks("train1")).isEmpty()
	}

	@Test
	fun `PathReservationService receives correct dependencies`() {
		// Arrange - create context and navigator
		val simulationContext = contextBuilder
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildSimulationContext()

		val navigator: TopologyNavigator = getKoin().get { parametersOf(simulationContext) }

		// Act - get service with dependencies
		val service: PathReservationService = getKoin().get {
			parametersOf(navigator, simulationContext as SimulationEnvironment)
		}

		// Assert - service is properly constructed and functional
		assertThat(service).isNotNull()

		// Verify service works by attempting a reservation
		val grid = simulationContext.getRailWayNetGrid()
		val inOutA = grid.getCellAt(1, 1) as PathSeparator
		val inOutB = grid.getCellAt(5, 5) as PathSeparator

		val result = service.reservePath("test-train", inOutA, inOutB)
		assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
	}

	@Test
	fun `PathReservationService uses injected registry`() {
		// Arrange - create service
		val simulationContext = contextBuilder
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildSimulationContext()

		val navigator: TopologyNavigator = getKoin().get { parametersOf(simulationContext) }
		val service: PathReservationService = getKoin().get {
			parametersOf(navigator, simulationContext as SimulationEnvironment)
		}

		// Act - reserve path
		val grid = simulationContext.getRailWayNetGrid()
		val inOutA = grid.getCellAt(1, 1) as PathSeparator
		val inOutB = grid.getCellAt(5, 5) as PathSeparator
		service.reservePath("train1", inOutA, inOutB)

		// Assert - service's registry tracks the reservation
		val reservedBlocks = service.getReservedBlocks("train1")
		assertThat(reservedBlocks).isNotNull()
		assertThat(reservedBlocks.size).isEqualTo(1)  // One block in path
	}

	@Test
	fun `multiple services have independent registries`() {
		// Arrange - create one context but two services with different registries
		val context = buildTestContext()

		val navigator1: TopologyNavigator = getKoin().get { parametersOf(context) }
		val navigator2: TopologyNavigator = getKoin().get { parametersOf(context) }

		// Each service gets a fresh registry from Koin factory
		val service1: PathReservationService = getKoin().get {
			parametersOf(navigator1, context as SimulationEnvironment)
		}
		val service2: PathReservationService = getKoin().get {
			parametersOf(navigator2, context as SimulationEnvironment)
		}

		// Act - reserve path in service1
		val grid = context.getRailWayNetGrid()
		val inOutA = grid.getCellAt(1, 1) as PathSeparator
		val inOutB = grid.getCellAt(5, 5) as PathSeparator
		service1.reservePath("train1", inOutA, inOutB)

		// Assert - service2 should not see train1's reservation (different registry)
		assertThat(service2.getReservedBlocks("train1")).isEmpty()
	}

	@Test
	fun `parameter passing pattern works correctly`() {
		// Arrange
		val context: Context<Cell, out TrackBlock> = buildTestContext()

		// Act - use parameter passing to inject context
		val navigator: TopologyNavigator = getKoin().get { parametersOf(context) }

		// Assert - navigator works with the provided context
		assertThat(navigator).isNotNull()

		// Verify navigator uses the context by navigating
		val grid = context.getRailWayNetGrid()
		val inOutA = grid.getCellAt(1, 1) as PathSeparator
		val nextSection = navigator.getNextTrackSection(inOutA, null)
		assertThat(nextSection).isNotNull()
	}

	@Test
	fun `factory pattern prevents state leakage across tests`() {
		// This test verifies that KoinTestBase teardown + factory scope
		// prevent state from leaking between test runs

		// Arrange - create and use a registry
		val registry1: PathReservationRegistry = getKoin().get()
		val context = buildTestContext() as SimulationContext
		val blocks = getTestBlocks(context)
		registry1.register("train1", blocks)

		// Verify registry has the registration
		assertThat(registry1.getBlocks("train1").size).isEqualTo(1)

		// Act - get another registry instance (simulating next test)
		val registry2: PathReservationRegistry = getKoin().get()

		// Assert - new registry is clean (no leakage)
		assertThat(registry2.getBlocks("train1")).isEmpty()
		assertThat(registry2.trainCount()).isEqualTo(0)
		assertThat(registry2.blockCount()).isEqualTo(0)
	}

	// ========== Helper Methods ==========

	/**
	 * Build a simple test context with InOut A -> InOut B
	 */
	private fun buildTestContext() =
		contextBuilder
			.withInOut("A", 1, 1, true)
			.withInOut("B", 5, 5, false)
			.withConnection(1, 1, 5, 5, 100.0, 80.0)
			.buildSimulationContext()

	/**
	 * Get test blocks from a context for registry testing
	 */
	private fun getTestBlocks(context: SimulationContext): List<DynamicTrackBlock> {
		val block = context.getGraph()
			.assignedEdges(Point(1, 1))
			.values()
			.first()
		return listOf(block as DynamicTrackBlock)
	}
}
