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
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.pathfinding.PathCostFunctions
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Goal 2 SP6 – Shunting-loop and alternative-route verification tests (Issue #598).
 *
 * Verifies that [cz.vutbr.fit.interlockSim.pathfinding.AutomaticPathFindingService]
 * (and its [RouteFinder] facade) return the **correct segment sequences** and
 * **ranked alternatives** for two realistic topologies:
 *
 * 1. **vyhybna.xml shunting loop** – two routes A→B through parallel tracks k1/k2.
 * 2. **parallel-routes.xml** – minimal programmatic diamond network with two routes
 *    of known and distinct BY_LENGTH costs.
 *
 * ## vyhybna.xml network (shunting loop)
 *
 * ```
 *                doA1 (16,8) ─── k1 (100 m) ─── doB1 (25,8)
 *               /                                             \
 * A(11,8)─zA(14,8)─vA(15,8)                               vB(26,8)─zB(27,8)─B(30,8)
 *               \                                             /
 *                doA2 (17,9) ─── k2 (100 m) ─── doB2 (24,9)
 * ```
 *
 * Route k1 (upper/main – **cheaper by length**):
 *   A → zA → vA → doA1 → doB1 → vB → zB → B
 *   Segment lengths: [100, 5, 5, 100, 5, 5, 100] → total 320 m
 *
 * Route k2 (lower/branch – **costlier by length**):
 *   A → zA → vA → doA2 → doB2 → vB → zB → B
 *   Segment lengths: [100, 5, 100, 100, 100, 5, 100] → total 510 m
 *
 * ## parallel-routes.xml network
 *
 * ```
 *                 sem1 (15,10)
 *                /             \
 * A(5,10)─swA(10,10)           swB(20,10)─B(25,10)
 *                \             /
 *                 sem2 (15,13)
 * ```
 *
 * Route k1 (main – **cheaper**):
 *   A → swA → sem1 → swB → B
 *   Lengths: [100, 100, 100, 100] → total 400 m, BY_ELEMENT_COUNT = 4
 *
 * Route k2 (branch – **costlier**):
 *   A → swA → sem2 → swB → B
 *   Lengths: [100, 300, 300, 100] → total 800 m, BY_ELEMENT_COUNT = 4
 */
@DisplayName("RouteSegmentVerification (Goal 2 SP6, Issue #598)")
class RouteSegmentVerificationTest : KoinTestBase() {
	private var ctx: EditingContext? = null

	private val editingContextFactory: JvmEditingContextFactory by inject()

	@AfterEach
	fun closeContext() {
		(ctx as? DefaultEditingContext)?.close()
		ctx = null
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private fun shuntingLoopCtx(): EditingContext =
		(editingContextFactory.createContext(TestFixtures.loadShuntingXml()) as EditingContext)
			.also { ctx = it }

	private fun parallelRoutesCtx(): EditingContext =
		(editingContextFactory.createContext(TestFixtures.loadParallelRoutesXml()) as EditingContext)
			.also { ctx = it }

	private fun EditingContext.inOut(name: String): InOut = getInOuts().single { it.getName() == name }

	// -------------------------------------------------------------------------
	// Section 1 – vyhybna.xml shunting-loop tests
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("vyhybna.xml shunting loop")
	inner class ShuntingLoop {
		/**
		 * The primary (shortest-by-length) route A→B follows track k1.
		 *
		 * Expected segment sequence (7 sections):
		 *   A→zA (100 m) · zA→vA (5 m) · vA→doA1 (5 m) ·
		 *   doA1→doB1 (100 m) · doB1→vB (5 m) · vB→zB (5 m) · zB→B (100 m)
		 *
		 * Total BY_LENGTH cost: 320.0 m
		 */
		@Test
		fun `primary route k1 has 7 segments totalling 320 m`() {
			val ctx = shuntingLoopCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			// Act – find cheapest route (BY_LENGTH selects k1 over k2)
			val result = svc.findShortestPath(a, b, PathCostFunctions.BY_LENGTH)!!

			// Expected segment count:
			//   A→zA, zA→vA, vA→doA1, doA1→doB1, doB1→vB, vB→zB, zB→B = 7
			assertThat(result.sections).hasSize(7)

			// Expected total length:
			//   100 + 5 + 5 + 100 + 5 + 5 + 100 = 320 m
			assertThat(result.totalCost).isEqualTo(320.0)
			assertThat(result.totalLength).isEqualTo(320.0)
		}

		/**
		 * The individual segment lengths along k1 must match the XML definitions.
		 *
		 * Segment lengths in traversal order (A→B, k1):
		 *   100, 5, 5, 100, 5, 5, 100
		 */
		@Test
		fun `primary route k1 segment lengths match XML definitions`() {
			val ctx = shuntingLoopCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val result = svc.findShortestPath(a, b, PathCostFunctions.BY_LENGTH)!!

			// Expected lengths in traversal order for k1:
			//   [0] A  → zA  : 100 m (approach track)
			//   [1] zA → vA  :   5 m (entry stub)
			//   [2] vA → doA1:   5 m (switch-to-k1-signal stub)
			//   [3] doA1→doB1: 100 m (k1 main track)
			//   [4] doB1→ vB :   5 m (k1-signal-to-merge-switch stub)
			//   [5] vB  → zB :   5 m (exit stub)
			//   [6] zB  → B  : 100 m (exit track)
			val expectedLengths = listOf(100.0, 5.0, 5.0, 100.0, 5.0, 5.0, 100.0)
			val actualLengths = result.sections.map { it.length() }
			assertThat(actualLengths).isEqualTo(expectedLengths)
		}

		/**
		 * The shunting-loop network has exactly two routes from A to B –
		 * one via k1 (upper track) and one via k2 (lower track).
		 *
		 * When ranked by BY_LENGTH the ordering must be:
		 *   routes[0].cost = 320.0  (k1 – shorter)
		 *   routes[1].cost = 510.0  (k2 – longer)
		 */
		@Test
		fun `shunting loop returns exactly two routes ranked by length k1 before k2`() {
			val ctx = shuntingLoopCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			// Act
			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)

			// Exactly two parallel routes exist (k1 and k2)
			assertThat(routes).hasSize(2)

			// k1 is cheapest (320 m), k2 is costlier (510 m)
			assertThat(routes[0].totalCost).isEqualTo(320.0)
			assertThat(routes[1].totalCost).isEqualTo(510.0)

			// Ordering invariant: cheapest first
			assertThat(routes[0].totalCost).isLessThan(routes[1].totalCost)
		}

		/**
		 * Route k2 (lower/branch track) has 7 segments totalling 510 m.
		 *
		 * Expected segment sequence:
		 *   A→zA (100 m) · zA→vA (5 m) · vA→doA2 (100 m) ·
		 *   doA2→doB2 (100 m) · doB2→vB (100 m) · vB→zB (5 m) · zB→B (100 m)
		 *
		 * Total BY_LENGTH cost: 510.0 m
		 */
		@Test
		fun `alternative route k2 has 7 segments totalling 510 m`() {
			val ctx = shuntingLoopCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)
			assertThat(routes).hasSize(2)

			// k2 is the second (more expensive) route
			val k2 = routes[1]
			assertThat(k2.sections).hasSize(7)
			assertThat(k2.totalCost).isEqualTo(510.0)

			// Segment lengths for k2:
			//   [0] A   → zA  : 100 m
			//   [1] zA  → vA  :   5 m
			//   [2] vA  → doA2: 100 m  (branch leg to k2 entry semaphore)
			//   [3] doA2→ doB2: 100 m  (k2 main track)
			//   [4] doB2→ vB  : 100 m  (k2 to merge switch)
			//   [5] vB  → zB  :   5 m
			//   [6] zB  → B   : 100 m
			val expectedLengths = listOf(100.0, 5.0, 100.0, 100.0, 100.0, 5.0, 100.0)
			assertThat(k2.sections.map { it.length() }).isEqualTo(expectedLengths)
		}

		/**
		 * BY_ELEMENT_COUNT assigns equal cost (7) to both routes because each traverses
		 * the same number of track sections.  The ordering is therefore arbitrary but
		 * both must still be present.
		 */
		@Test
		fun `both routes have equal element-count cost of 7`() {
			val ctx = shuntingLoopCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_ELEMENT_COUNT)

			assertThat(routes).hasSize(2)
			// Both routes traverse exactly 7 sections → cost 7.0 each
			routes.forEach { assertThat(it.totalCost).isEqualTo(7.0) }
		}

		/**
		 * RouteFinder facade must return the same two routes (and the same cost ordering)
		 * as AutomaticPathFindingService.
		 */
		@Test
		fun `RouteFinder returns the same two routes in cost order`() {
			val ctx = shuntingLoopCtx()
			val finder = ctx.getRouteFinder()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(routes).hasSize(2)
			assertThat(routes[0].cost).isEqualTo(320.0)
			assertThat(routes[1].cost).isEqualTo(510.0)

			// Invariant: ascending cost order
			for (i in 0 until routes.size - 1) {
				assertThat(routes[i].cost).isLessThanOrEqualTo(routes[i + 1].cost)
			}

			// Each route has the correct segment count and start/target pointers
			routes.forEach { route ->
				assertThat(route.segments).hasSize(7)
				assertThat(route.start).isEqualTo(a)
				assertThat(route.target).isEqualTo(b)
			}
		}
	}

	// -------------------------------------------------------------------------
	// Section 2 – parallel-routes.xml two-route network tests
	// -------------------------------------------------------------------------

	@Nested
	@DisplayName("parallel-routes.xml two-route network")
	inner class ParallelRoutes {
		/**
		 * The parallel-routes network has exactly two routes from A to B.
		 *
		 * Both routes share the approach (A→swA, 100 m) and exit (swB→B, 100 m) legs.
		 * They diverge at swA and merge at swB:
		 *   k1 (main):   A→swA→sem1→swB→B  – 4 segments, 400 m total
		 *   k2 (branch): A→swA→sem2→swB→B  – 4 segments, 800 m total
		 */
		@Test
		fun `returns exactly two routes from A to B`() {
			val ctx = parallelRoutesCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)

			// Network has exactly two parallel routes
			assertThat(routes).hasSize(2)
			routes.forEach { assertThat(it.sections).isNotEmpty() }
		}

		/**
		 * Routes are sorted cheapest-first by BY_LENGTH cost.
		 *
		 * Expected:
		 *   k1 cost = 400.0 m (approach 100 + k1-entry 100 + k1-exit 100 + exit 100)
		 *   k2 cost = 800.0 m (approach 100 + k2-entry 300 + k2-exit 300 + exit 100)
		 */
		@Test
		fun `routes are ranked cheapest first by length cost`() {
			val ctx = parallelRoutesCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(routes).hasSize(2)

			// k1 (main) is cheaper
			assertThat(routes[0].totalCost).isEqualTo(400.0)
			// k2 (branch) is costlier
			assertThat(routes[1].totalCost).isEqualTo(800.0)

			// Ordering invariant: strictly ascending for different costs
			assertThat(routes[0].totalCost).isLessThan(routes[1].totalCost)
		}

		/**
		 * The primary route (k1) has exactly 4 segments with the correct lengths.
		 *
		 * Segment sequence (A→B via main track):
		 *   A→swA: 100 m · swA→sem1: 100 m · sem1→swB: 100 m · swB→B: 100 m
		 */
		@Test
		fun `primary route k1 has 4 segments each 100 m`() {
			val ctx = parallelRoutesCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)
			assertThat(routes).hasSize(2)
			val k1 = routes[0]

			assertThat(k1.sections).hasSize(4)

			// Each segment on the main track is 100 m
			// [0] A   → swA : 100 m (approach)
			// [1] swA → sem1: 100 m (k1 entry leg)
			// [2] sem1→ swB : 100 m (k1 exit leg)
			// [3] swB → B   : 100 m (exit)
			k1.sections.forEach { section ->
				assertThat(section.length()).isEqualTo(100.0)
			}
		}

		/**
		 * The alternative route (k2) has exactly 4 segments.
		 * The middle two legs use the longer branch track (300 m each).
		 *
		 * Segment lengths (A→B via branch track):
		 *   A→swA: 100 m · swA→sem2: 300 m · sem2→swB: 300 m · swB→B: 100 m
		 */
		@Test
		fun `alternative route k2 has 4 segments with correct lengths`() {
			val ctx = parallelRoutesCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_LENGTH)
			assertThat(routes).hasSize(2)
			val k2 = routes[1]

			assertThat(k2.sections).hasSize(4)

			// [0] A   → swA : 100 m (shared approach)
			// [1] swA → sem2: 300 m (branch entry leg)
			// [2] sem2→ swB : 300 m (branch exit leg)
			// [3] swB → B   : 100 m (shared exit)
			val expectedLengths = listOf(100.0, 300.0, 300.0, 100.0)
			assertThat(k2.sections.map { it.length() }).isEqualTo(expectedLengths)
		}

		/**
		 * BY_ELEMENT_COUNT assigns equal cost (4) to both routes.  The ordering is
		 * therefore unspecified, but both routes must still be discoverable.
		 */
		@Test
		fun `both routes have equal element-count cost of 4`() {
			val ctx = parallelRoutesCtx()
			val svc = ctx.getAutomaticPathFindingService()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = svc.findAllPaths(a, b, costFunction = PathCostFunctions.BY_ELEMENT_COUNT)

			assertThat(routes).hasSize(2)
			routes.forEach { assertThat(it.totalCost).isEqualTo(4.0) }
		}

		/**
		 * RouteFinder facade wraps AutomaticPathFindingService and must return the same
		 * two routes with the correct cost values and start/target references.
		 */
		@Test
		fun `RouteFinder returns both routes with correct cost and start-target refs`() {
			val ctx = parallelRoutesCtx()
			val finder = ctx.getRouteFinder()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(routes).hasSize(2)

			// Cost ordering must hold
			assertThat(routes[0].cost).isEqualTo(400.0)
			assertThat(routes[1].cost).isEqualTo(800.0)
			assertThat(routes[0].cost).isLessThan(routes[1].cost)

			// Both routes must point to the correct InOut instances
			routes.forEach { route ->
				assertThat(route.start).isEqualTo(a)
				assertThat(route.target).isEqualTo(b)
				assertThat(route.segments).hasSize(4)
				assertThat(route.cost).isGreaterThan(0.0)
			}
		}

		/**
		 * Cost breakdown sums must equal the total route cost for both routes.
		 */
		@Test
		fun `cost breakdown sums match total cost for both routes`() {
			val ctx = parallelRoutesCtx()
			val finder = ctx.getRouteFinder()
			val a = ctx.inOut("A")
			val b = ctx.inOut("B")

			val routes = finder.findRoutes(a, b, ctx, costFunction = PathCostFunctions.BY_LENGTH)

			assertThat(routes).hasSize(2)
			routes.forEach { route ->
				val breakdownSum = route.costBreakdown.sumOf { it.cost }
				assertThat(breakdownSum).isEqualTo(route.cost)
			}
		}
	}
}
