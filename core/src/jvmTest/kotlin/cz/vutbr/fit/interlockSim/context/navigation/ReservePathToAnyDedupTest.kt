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
import assertk.assertions.each
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.RouteFinder
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.core.PathSeparator
import cz.vutbr.fit.interlockSim.objects.paths.PathInfoBuilder
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import cz.vutbr.fit.interlockSim.util.Point
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.test.inject
import java.util.concurrent.TimeUnit

/**
 * Pins the work [DefaultPathReservationService.reservePathToAny] no longer repeats:
 * the semaphore grid scan (Issue #965) and the second topological path enumeration per InOut
 * target (Issue #966).
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

	/**
	 * The grid scan the service used to run on every call: column by column, top to bottom.
	 * Deliberately hand-rolled instead of [cz.vutbr.fit.interlockSim.util.cellsOfType] (which
	 * the production cache now uses): a scan bug must fail this oracle, not hide inside it.
	 */
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

			val zA = simulationContext.separatorAt(14, 8) as DynamicRailSemaphore
			service.reservePathToAny("train1", zA)
			service.releasePath("train1")
			service.reservePathToAny("train1", zA)

			assertThat(service.allSemaphores()).isSameInstanceAs(first)
		}
	}

	/** Counts [findAllTopologicalPaths] calls per (start, target) pair. */
	private class CountingNavigator(
		private val real: TopologyNavigator
	) : TopologyNavigator by real {
		val calls = mutableMapOf<Pair<PathSeparator, PathSeparator>, Int>()

		override fun findAllTopologicalPaths(
			start: PathSeparator,
			target: PathSeparator,
			maxDepth: Int
		): List<List<TrackSection>> {
			calls.merge(start to target, 1, Int::plus)
			return real.findAllTopologicalPaths(start, target, maxDepth)
		}
	}

	@Nested
	@DisplayName("path reuse (Issue #966)")
	inner class PathReuse {
		private fun semaphoreZA(): DynamicRailSemaphore = simulationContext.separatorAt(14, 8) as DynamicRailSemaphore

		@Test
		fun `the precomputed-path overload gives the same result as the public reservePath`() {
			val zA = semaphoreZA()
			// Order from XML: [0]=B at X=30, [1]=A at X=11; zA faces towards B
			val inOutB = simulationContext.toDynamic(simulationContext.getInOuts().toList()[0])
			val counting = CountingNavigator(navigator)
			val service = newService(counting)

			val viaPublic = service.reservePath("train1", zA, inOutB)
			val publicBlocks = (viaPublic as PathReservationService.ReservationResult.Success).reservedBlocks
			service.releasePath("train1")

			val precomputed = navigator.findAllTopologicalPaths(zA, inOutB)
			counting.calls.clear()
			val viaOverload = service.reservePath("train1", zA, inOutB, 100, lazyOf(precomputed))

			assertThat(viaOverload).isInstanceOf<PathReservationService.ReservationResult.Success>()
			val overloadBlocks = (viaOverload as PathReservationService.ReservationResult.Success).reservedBlocks
			assertThat(overloadBlocks).isEqualTo(publicBlocks)
			assertThat(counting.calls.toMap(), name = "enumerations inside the overload").isEqualTo(emptyMap())
		}

		@Test
		fun `reservePathToAny enumerates each InOut target at most once on vyhybna`() {
			val counting = CountingNavigator(navigator)
			val service = newService(counting)

			service.reservePathToAny("train1", semaphoreZA())

			assertThat(counting.calls.values.toList()).each { it.isLessThanOrEqualTo(1) }
		}

		/**
		 * On rudyUjezd.xml the semaphore at (11,31) has two InOuts in one orientation partition, so
		 * the sort key is evaluated for both of them. Before Issue #966 every InOut the loop then
		 * tried was enumerated a second time inside reservePath; now each pair is enumerated once.
		 */
		@Test
		@Timeout(10, unit = TimeUnit.SECONDS)
		fun `reservePathToAny enumerates every sorted InOut target exactly once`() {
			val rudyUjezd =
				TestFixtures
					.loadRudyUjezdXml()
					.use { simulationContextFactory.createContext(it) }
					.let { it as DefaultSimulationContext }
					.tracked()
			val counting = CountingNavigator(rudyUjezd.scope.get())
			val service =
				DefaultPathReservationService(
					counting,
					rudyUjezd,
					rudyUjezd.scope.get(),
					rudyUjezd.scope.get(),
					rudyUjezd.scope.get()
				)
			val start = rudyUjezd.getRailWayNetGrid()[Point(11, 31)] as DynamicRailSemaphore
			val inOuts = rudyUjezd.getInOuts().map { rudyUjezd.toDynamic(it) as DynamicInOut }
			val oppositeSide = inOuts.filter { it.getOrientation() != start.getOrientation() }
			assertThat(oppositeSide.size, name = "InOuts sorted together").isGreaterThan(1)

			service.reservePathToAny("train1", start)

			val sortedPairs = oppositeSide.map { (start as PathSeparator) to (it as PathSeparator) }
			assertThat(sortedPairs.map { counting.calls[it] }, name = "enumerations per sorted InOut")
				.each { it.isEqualTo(1) }
			assertThat(counting.calls.values.toList(), name = "enumerations per pair").each { it.isEqualTo(1) }
		}
	}
}
