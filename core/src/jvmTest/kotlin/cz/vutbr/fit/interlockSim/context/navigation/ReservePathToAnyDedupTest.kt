/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isNotEmpty
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Pins the work [DefaultPathReservationService.reservePathToAny] no longer repeats:
 * the semaphore grid scan (Issue #965).
 */
@DisplayName("reservePathToAny de-duplication")
class ReservePathToAnyDedupTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var navigator: TopologyNavigator
	private lateinit var registry: PathReservationRegistry
	private lateinit var pathInfoBuilder: PathInfoBuilder
	private lateinit var routeFinder: RouteFinder

	@BeforeEach
	fun setUp() {
		simulationContext =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		navigator = simulationContext.scope.get()
		registry = simulationContext.scope.get()
		pathInfoBuilder = simulationContext.scope.get()
		routeFinder = simulationContext.scope.get()
	}

	private fun newService(navigator: TopologyNavigator = this.navigator): DefaultPathReservationService =
		DefaultPathReservationService(navigator, simulationContext, registry, pathInfoBuilder, routeFinder)

	/** The grid scan the service used to run on every call: column by column, top to bottom. */
	private fun freshSemaphoreScan(): List<DynamicRailSemaphore> {
		val grid = simulationContext.getRailWayNetGrid()
		val result = mutableListOf<DynamicRailSemaphore>()
		for (x in 0 until grid.cols) {
			for (y in 0 until grid.rows) {
				val cell = grid[Point(x, y)]
				if (cell is DynamicRailSemaphore) result.add(cell)
			}
		}
		return result
	}

	@Nested
	@DisplayName("semaphore cache (Issue #965)")
	inner class SemaphoreCache {
		@Test
		fun `cached semaphores equal a fresh grid scan in the same order`() {
			val expected = freshSemaphoreScan()
			assertThat(expected).isNotEmpty()

			assertThat(newService().allSemaphores()).containsExactly(*expected.toTypedArray())
		}

		@Test
		fun `the semaphore list is computed once and reused across reservePathToAny calls`() {
			val service = newService()
			val first = service.allSemaphores()

			val zA = simulationContext.getRailWayNetGrid()[Point(14, 8)] as DynamicRailSemaphore
			service.reservePathToAny("train1", zA)
			service.releasePath("train1")
			service.reservePathToAny("train1", zA)

			assertThat(service.allSemaphores()).isSameInstanceAs(first)
		}
	}
}
