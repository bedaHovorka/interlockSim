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
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.JvmEditingContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestContextBuilder
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.test.inject

/**
 * Integration tests for RouteFinder wiring into PathReservationService (Issue #597).
 *
 * Verifies that when both endpoints are InOut elements, [PathReservationService]
 * delegates to [cz.vutbr.fit.interlockSim.context.RouteFinder] for cost-ordered
 * route selection rather than raw BFS topology order.
 *
 * Acceptance criteria tested:
 * - PathReservationService calls RouteFinder for automatic InOut-to-InOut routes
 * - The current NetworkState is passed (environment implements NetworkState)
 * - The default reservation uses the lowest-cost (first) candidate
 * - A missing path results in NoPathExists rather than a crash
 * - Existing explicit-route reservations (non-InOut separators) continue to work
 */
class RouteFinderIntegrationTest : KoinTestBase() {
	private val editingContextFactory: JvmEditingContextFactory by inject()
	private val simulationContextFactory: SimulationContextFactory by inject()

	@Nested
	inner class AutomaticRouteSelection {
		/**
		 * When both endpoints are DynamicInOut, PathReservationService should succeed
		 * by using RouteFinder and reserve all blocks on the primary (lowest-cost) route.
		 */
		@Test
		fun `reservePath uses RouteFinder for InOut-to-InOut and succeeds`() {
			val editingContext = TestFixtures.loadShuntingEditingContext(editingContextFactory)
			val simCtx = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

			simCtx.use {
				val service = simCtx.getRoutingServices().getPathReservationService()
				val inOuts = simCtx.getInOuts().toList()
				val start = inOuts[0]
				val target = inOuts[1]

				val result = service.reservePath("train1", start, target)

				assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
				val success = result as PathReservationService.ReservationResult.Success
				assertThat(success.reservedBlocks.size).isGreaterThan(0)
				success.reservedBlocks.forEach { block ->
					assertThat(block.getState()).isInstanceOf(TrackFacility.State::class)
				}
			}
		}

		/**
		 * The reserved path should use the lowest-cost route (RouteFinder sorts by cost ascending).
		 * For a shunting loop network with two parallel paths, the primary route (k1, 320 m)
		 * has fewer total metres than the secondary route (k2, 510 m), even though both traverse
		 * exactly 7 segments.  The assertion verifies both the exact block count and the total
		 * reserved length so that accidentally reserving the costlier k2 route would cause the
		 * test to fail.
		 */
		@Test
		fun `reservePath reserves lowest-cost route first`() {
			val editingContext = TestFixtures.loadShuntingEditingContext(editingContextFactory)
			val simCtx = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

			simCtx.use {
				val service = simCtx.getRoutingServices().getPathReservationService()
				val routeFinder = simCtx.getRouteFinder()
				val inOuts = simCtx.getInOuts().toList()
				val inOut0 = inOuts[0]
				val inOut1 = inOuts[1]

				// Find the cheapest route independently via RouteFinder
				val routes = routeFinder.findRoutes(inOut0.staticRef, inOut1.staticRef, simCtx)
				val cheapestRoute = routes.first()
				assertThat(cheapestRoute.cost).isGreaterThan(0.0)

				// Reserve via service — must select the cheapest route
				val result = service.reservePath("train1", inOut0, inOut1)
				assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
				val success = result as PathReservationService.ReservationResult.Success

				// The reserved block count must equal the cheapest route's segment count exactly.
				// (Both routes in vyhybna.xml have 7 segments, so this also bounds the costlier route.)
				// NOTE: this and the length assertion below assume k1 visits each block exactly
				// once. The service deduplicates sections that map to the same block, so a future
				// fixture where k1 revisits a block would make reservedBlocks.size < segments.size
				// (and the segment-sum length would double-count that block) - failing both
				// assertions on a *correct* reservation. True for vyhybna.xml today; a failure here
				// from a fixture edit is not a reservation-logic regression.
				assertThat(success.reservedBlocks.size).isEqualTo(cheapestRoute.segments.size)

				// The total reserved length must equal the cheapest route's total length.
				// vyhybna.xml: k1 = 320.0 m, k2 = 510.0 m — a wrong route would fail this check.
				val totalReservedLength = success.reservedBlocks.sumOf { it.length() }
				assertThat(totalReservedLength).isEqualTo(cheapestRoute.totalLength)
			}
		}
	}

	@Nested
	inner class GracefulFailure {
		/**
		 * When no route exists between disconnected InOuts, RouteFinder returns empty list
		 * and the service must return NoPathExists (not crash).
		 */
		@Test
		fun `reservePath returns NoPathExists when RouteFinder finds no route`() {
			// Build a context with two disconnected InOuts (no track between them)
			val simCtx =
				TestContextBuilder()
					.withInOut("A", 1, 1, false)
					.withInOut("B", 10, 10, true)
					.buildSimulationContext()

			simCtx.use {
				val service = simCtx.getRoutingServices().getPathReservationService()
				val inOuts = simCtx.getInOuts().toList()
				val start = inOuts[0]
				val target = inOuts[1]

				val result = service.reservePath("train1", start, target)

				assertThat(result).isInstanceOf<PathReservationService.ReservationResult.NoPathExists>()
			}
		}
	}

	@Nested
	inner class ExplicitRouteBackwardsCompatibility {
		/**
		 * Existing reservePathToAnyNextSemaphore (semaphore-to-semaphore / semaphore-to-InOut)
		 * should continue to work unchanged — TopologyNavigator is used for non-InOut endpoints.
		 */
		@Test
		fun `reservePathToAnyNextSemaphore still works after RouteFinder integration`() {
			val editingContext = TestFixtures.loadShuntingEditingContext(editingContextFactory)
			val simCtx = simulationContextFactory.createContext(editingContext) as DefaultSimulationContext

			simCtx.use {
				val service = simCtx.getRoutingServices().getPathReservationService()
				val navigator = simCtx.getRoutingServices().getTopologyNavigator()
				val inOuts = simCtx.getInOuts().toList()
				val inOut = simCtx.toDynamic(inOuts[0]) as DynamicInOut

				// Get first track section from InOut
				val next = navigator.getNextTrackSection(inOut, null)
				requireNotNull(next) { "Expected a track section after InOut" }

				val result = service.reservePathToAnyNextSemaphore("train1", inOut, next)

				assertThat(result).isInstanceOf<PathReservationService.ReservationResult.Success>()
			}
		}
	}
}
