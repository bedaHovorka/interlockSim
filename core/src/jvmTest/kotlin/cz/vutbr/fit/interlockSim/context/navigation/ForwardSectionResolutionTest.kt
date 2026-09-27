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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.OrientedPathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Covers every branch of `DefaultPathReservationService.resolveForwardSection` (Issue #957)
 * through both of its callers: the oriented [PathReservationService.reservePathToAnyNextSemaphore]
 * overload and [PathReservationService.findNextReservationTarget].
 *
 * The two failure branches (separator without a grid location, separator without a forward
 * track section) are reached by wrapping the real `vyhybna.xml` context in a delegating
 * environment that hides the location or the graph edges.
 */
@DisplayName("resolveForwardSection (Issue #957)")
class ForwardSectionResolutionTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	private lateinit var simulationContext: DefaultSimulationContext
	private lateinit var navigator: TopologyNavigator
	private lateinit var registry: PathReservationRegistry
	private lateinit var pathInfoBuilder: PathInfoBuilder
	private lateinit var routeFinder: RouteFinder

	private lateinit var inOutA: OrientedPathSeparator
	private lateinit var semaphoreZA: OrientedPathSeparator // zA at (14,8), faces vA

	@BeforeEach
	fun setUp() {
		simulationContext =
			TestFixtures.loadShuntingSimulationContext(simulationContextFactory, editingContextFactory).tracked()
		navigator = simulationContext.scope.get()
		registry = simulationContext.scope.get()
		pathInfoBuilder = simulationContext.scope.get()
		routeFinder = simulationContext.scope.get()

		// Order from XML: [0]=B at X=30, [1]=A at X=11
		inOutA = simulationContext.toDynamic(simulationContext.getInOuts().toList()[1]) as DynamicInOut
		semaphoreZA = simulationContext.separatorAt(14, 8) as OrientedPathSeparator
	}

	private fun serviceOver(environment: SimulationContext): DefaultPathReservationService =
		DefaultPathReservationService(navigator, environment, registry, pathInfoBuilder, routeFinder)

	/** Environment whose grid reports no location for any cell. */
	private class NoLocationEnvironment(
		real: SimulationContext
	) : SimulationContext by real {
		private val grid =
			object : RailwayNetGrid<Cell> by real.getRailWayNetGrid() {
				override fun getLocation(out: Cell): Point? = null
			}

		override fun getRailWayNetGrid(): RailwayNetGrid<Cell> = grid
	}

	/** Environment whose graph assigns no edges to any node. */
	private class NoEdgeEnvironment(
		real: SimulationContext
	) : SimulationContext by real {
		private val graph =
			object : ExtendedUnorientedGraph<Point, DynamicTrackBlock, Cell.Segment> by real.getGraph() {
				override fun assignedEdges(node: Point): Map<Cell.Segment, DynamicTrackBlock> = emptyMap()
			}

		override fun getGraph(): ExtendedUnorientedGraph<Point, DynamicTrackBlock, Cell.Segment> = graph
	}

	@Test
	fun `a separator without a grid location yields NoPathExists and no target`() {
		val service = serviceOver(NoLocationEnvironment(simulationContext))

		for (start in listOf(inOutA, semaphoreZA)) {
			assertThat(service.reservePathToAnyNextSemaphore("train1", start), name = "reserve from $start")
				.isEqualTo(PathReservationService.ReservationResult.NoPathExists)
			assertThat(service.findNextReservationTarget(start), name = "target from $start").isNull()
		}
		assertThat(registry.getBlocks("train1")).isEqualTo(emptyList())
	}

	@Test
	fun `a separator without a forward track section yields NoPathExists and no target`() {
		val service = serviceOver(NoEdgeEnvironment(simulationContext))

		for (start in listOf(inOutA, semaphoreZA)) {
			assertThat(service.reservePathToAnyNextSemaphore("train1", start), name = "reserve from $start")
				.isEqualTo(PathReservationService.ReservationResult.NoPathExists)
			assertThat(service.findNextReservationTarget(start), name = "target from $start").isNull()
		}
		assertThat(registry.getBlocks("train1")).isEqualTo(emptyList())
	}

	@Test
	fun `InOut and semaphore both resolve a forward section and reserve towards the found target`() {
		val service = serviceOver(simulationContext)

		for ((index, start) in listOf(inOutA, semaphoreZA).withIndex()) {
			val trainId = "train$index"
			val target = service.findNextReservationTarget(start)
			assertThat(target, name = "target from $start").isNotNull()

			val result = service.reservePathToAnyNextSemaphore(trainId, start)
			assertThat(result, name = "reserve from $start")
				.isInstanceOf<PathReservationService.ReservationResult.Success>()
			assertThat(registry.getPathInfo(trainId)?.target, name = "reserved target from $start")
				.isEqualTo(target)
			service.releasePath(trainId)
		}
	}
}
