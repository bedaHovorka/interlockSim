/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.context

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.pathfinding.PathCostFunctions
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject

@DisplayName("RouteFinder")
class RouteFinderTest : KoinTestBase() {
	private var context: EditingContext? = null

	private val editingContextFactory: JvmEditingContextFactory by inject()

	@AfterEach
	fun closeContext() {
		(context as? cz.vutbr.fit.interlockSim.context.DefaultEditingContext)?.close()
		context = null
	}

	private fun linearContext(): EditingContext = TestTopologies.simpleLinearPath().also { context = it }

	private fun semaphoreContext(): EditingContext =
		TestTopologies.linearPathWithSemaphore(semaphoreAllowing = false).also { context = it }

	private fun asymmetricSpeedContext(): EditingContext =
		TestTopologies.linearPathWithAsymmetricSpeeds().also { context = it }

	private fun yJunctionContext(): EditingContext = TestTopologies.yJunctionWithSwitch().also { context = it }

	private fun shuntingLoopContext(): EditingContext {
		val ctx = TestFixtures.loadShuntingEditingContext(editingContextFactory)
		context = ctx
		return ctx
	}

	private fun findInOut(
		ctx: EditingContext,
		name: String
	): InOut = ctx.getInOuts().single { it.getName() == name }

	@Nested
	@DisplayName("findRoutes")
	inner class FindRoutes {
		@Test
		fun `returns single route on linear network`() {
			val ctx = linearContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx)

			assertThat(routes).hasSize(1)
			assertThat(routes[0].segments).hasSize(1)
			assertThat(routes[0].cost).isGreaterThan(0.0)
			assertThat(routes[0].start).isEqualTo(a)
			assertThat(routes[0].target).isEqualTo(b)
		}

		@Test
		fun `returns multiple alternatives on shunting loop sorted by cost`() {
			val ctx = shuntingLoopContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(routes).isNotEmpty()
			assertThat(routes.size).isGreaterThan(1)
			for (i in 0 until routes.size - 1) {
				assertThat(routes[i].cost).isLessThanOrEqualTo(routes[i + 1].cost)
			}
		}

		@Test
		fun `returns empty list for disconnected InOuts`() {
			val ctx =
				TestContextBuilder()
					.withInOut("A", 1, 1, true)
					.withInOut("B", 5, 5, false)
					.buildEditingContext()
					.also { context = it }
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx)

			assertThat(routes).isEmpty()
		}

		@Test
		fun `returns single empty route when start equals target`() {
			val ctx = linearContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")

			val routes = finder.findRoutes(a, a, ctx)

			assertThat(routes).hasSize(1)
			assertThat(routes[0].segments).isEmpty()
			assertThat(routes[0].cost).isEqualTo(0.0)
			assertThat(routes[0].costBreakdown).isEmpty()
		}

		@Test
		fun `cost breakdown sums to total cost`() {
			val ctx = semaphoreContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_ELEMENT_COUNT)

			assertThat(routes).isNotEmpty()
			val route = routes.first()
			assertThat(route.costBreakdown.sumOf { it.cost }).isEqualTo(route.cost)
		}

		@Test
		fun `cost breakdown per-segment values match direction-dependent speed`() {
			// Topology: A -[600m@60]-> Sem -[900m@90]-> B  (speeds measured from the entry end)
			// BY_TRAVEL_TIME from A to B:
			//   segment 0 (A→Sem): 600 / 60  = 10.0 s
			//   segment 1 (Sem→B): 900 / 90  = 10.0 s
			//   total = 20.0 s
			// A wrong separator passed to the cost function would either throw (wrong end) or
			// produce a different value (e.g. 600/120 = 5.0 or 900/180 = 5.0).
			val ctx = asymmetricSpeedContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_TRAVEL_TIME)

			assertThat(routes).isNotEmpty()
			val route = routes.first()
			assertThat(route.costBreakdown).hasSize(2)
			assertThat(route.costBreakdown[0].cost).isEqualTo(10.0)
			assertThat(route.costBreakdown[1].cost).isEqualTo(10.0)
			assertThat(route.cost).isEqualTo(20.0)
			assertThat(route.costBreakdown.sumOf { it.cost }).isEqualTo(route.cost)
		}

		@Test
		fun `respects switch constraints`() {
			val ctx = yJunctionContext()
			val finder = ctx.getRouteFinder()
			val exitMain = findInOut(ctx, "ExitMain")
			val exitBranch = findInOut(ctx, "ExitBranch")

			// Straight-to-branch switch transition is physically impossible.
			assertThat(finder.findRoutes(exitMain, exitBranch, ctx)).isEmpty()
		}
	}

	@Nested
	@DisplayName("isRouteAvailable")
	inner class IsRouteAvailable {
		@Test
		fun `returns true when route exists`() {
			val ctx = linearContext()
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			assertThat(finder.isRouteAvailable(a, b, ctx)).isTrue()
		}

		@Test
		fun `returns false when nodes are disconnected`() {
			val ctx =
				TestContextBuilder()
					.withInOut("A", 1, 1, true)
					.withInOut("B", 5, 5, false)
					.buildEditingContext()
					.also { context = it }
			val finder = ctx.getRouteFinder()
			val a = findInOut(ctx, "A")
			val b = findInOut(ctx, "B")

			assertThat(finder.isRouteAvailable(a, b, ctx)).isFalse()
		}

		@Test
		fun `returns false for physically impossible switch transition`() {
			val ctx = yJunctionContext()
			val finder = ctx.getRouteFinder()
			val exitMain = findInOut(ctx, "ExitMain")
			val exitBranch = findInOut(ctx, "ExitBranch")

			assertThat(finder.isRouteAvailable(exitMain, exitBranch, ctx)).isFalse()
		}
	}

	@Nested
	@DisplayName("context exposure")
	inner class ContextExposure {
		@Test
		fun `editing context exposes non-null route finder`() {
			val ctx = linearContext()
			assertThat(ctx.getRouteFinder()).isNotNull()
		}

		@Test
		fun `simulation context exposes non-null route finder`() {
			val ctx = TestTopologies.simpleLinearPathSimulation()
			try {
				assertThat(ctx.getRouteFinder()).isNotNull()
			} finally {
				ctx.close()
			}
		}
	}

	@Nested
	@DisplayName("simulation compatibility")
	inner class SimulationCompatibility {
		@Test
		fun `works with DynamicInOut inputs from simulation context`() {
			val ctx = TestTopologies.simpleLinearPathSimulation()
			try {
				val finder = ctx.getRouteFinder()
				val inOuts = ctx.getInOuts()
				val dynamicA = inOuts.single { it.name == "A" }
				val dynamicB = inOuts.single { it.name == "B" }

				// RouteFinder public API accepts static InOut references; DynamicInOut
				// wrappers expose the wrapped static InOut via staticRef.
				val routes = finder.findRoutes(dynamicA.staticRef, dynamicB.staticRef, ctx)

				assertThat(routes).hasSize(1)
				assertThat(routes[0].segments).hasSize(1)
			} finally {
				ctx.close()
			}
		}
	}
}
