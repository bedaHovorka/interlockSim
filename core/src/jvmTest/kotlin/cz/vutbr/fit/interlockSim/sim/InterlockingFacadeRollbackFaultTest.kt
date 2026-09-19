/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.Point
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #1051 / PR #1077: fault-injection coverage for the `setUpPath` catch arm of
 * `DefaultInterlockingFacade.registerBlocks` — the only rollback call site where the reserved
 * and the registered sets differ (`registerAtomic` is all-or-nothing, so a mid-`setUpPath`
 * failure leaves ALL route blocks registered but only some reserved).
 *
 * The arm is defence-in-depth: `checkRouteFreedom` guarantees every route block is FREE, so a
 * real [DynamicTrackBlock.setUpPath] cannot throw through [InterlockingFacade.requestRoute]. It
 * is reachable only through an injected fault, so these tests mirror the MockK strategy of
 * `InterlockingFacadeTest.RealNetworkBehavior` (mocked network elements, a REAL
 * [PathReservationRegistry]) and throw from the second block's `setUpPath`.
 *
 * Un-tagged on purpose: a pure unit test, so it runs under the default `test` gate whose
 * coverage feeds Sonar.
 */
@DisplayName("Issue #1051 — a setUpPath failure rolls back only the failed call's blocks")
class InterlockingFacadeRollbackFaultTest {
	/** A FREE, unoccupied block mock — passes `checkRouteFreedom` (see `InterlockingFacadeTest`). */
	private fun block(
		name: String,
		occupantName: String? = null
	): DynamicTrackBlock =
		mockk<DynamicTrackBlock>(relaxed = true).also {
			every { it.name } returns name
			every { it.trainName } returns null
			every { it.getState() } returns TrackFacility.State.FREE
			every { it.occupant } returns
				occupantName?.let { occ ->
					mockk<TrackOccupant>(relaxed = true).also { every { it.name } returns occ }
				}
		}

	/** Stateful mock semaphore: assigning `signal` is observable via the getter afterwards. */
	private fun semaphore(
		name: String,
		signal: Signal = Signal.STOP
	): DynamicRailSemaphore {
		var current = signal
		return mockk<DynamicRailSemaphore>(relaxed = true).also {
			every { it.name } returns name
			every { it.signal } answers { current }
			every { it.signal = any() } answers { current = firstArg() }
		}
	}

	/**
	 * Builds a [SimulationEnvironment] + a REAL [PathReservationRegistry] over the given mocked
	 * blocks and semaphores (same shape as `InterlockingFacadeTest.RealNetworkBehavior.env`).
	 */
	private fun env(
		blocks: List<DynamicTrackBlock>,
		semaphores: List<DynamicRailSemaphore>
	): Pair<SimulationEnvironment, PathReservationRegistry> {
		val cells: List<Cell> = semaphores
		val grid = mockk<RailwayNetGrid<Cell>>(relaxed = true)
		every { grid.cols } returns (cells.size + 1)
		every { grid.rows } returns 1
		cells.forEachIndexed { idx, cell -> every { grid.getCellAt(idx, 0) } returns cell }

		val graph = mockk<ExtendedUnorientedGraph<Point, DynamicTrackBlock, Cell.Segment>>(relaxed = true)
		every { graph.values() } returns blocks

		val registry = PathReservationRegistry(mockk(relaxed = true))
		val routingServices = mockk<RoutingServices>(relaxed = true)

		val e = mockk<SimulationEnvironment>(relaxed = true)
		every { e.getRailWayNetGrid() } returns grid
		every { e.getGraph() } returns graph
		every { e.getRoutingServices() } returns routingServices
		return e to registry
	}

	/** The route under test: `S1 -> S2` over [blockNames], no switches. */
	private fun route(blockNames: List<String>): TrainRoute =
		TrainRoute(
			from = SignalId("S1"),
			to = SignalId("S2"),
			running = emptyList(),
			blocks = blockNames.map { BlockId(it) }
		)

	private fun assertDenied(
		response: InterlockingFacade.RouteResponse,
		reason: String,
		retryable: Boolean
	) {
		assertThat(response).isInstanceOf<InterlockingFacade.RouteResponse.Denied>()
		val denied = response as InterlockingFacade.RouteResponse.Denied
		assertThat(denied.reason)
			.withMessage("the injected fault must surface as the catch arm's denial")
			.isEqualTo(reason)
		assertThat(denied.cause).isInstanceOf<InterlockingFacade.RouteResponse.DenialCause.ConditionFailed>()
		assertThat((denied.cause as InterlockingFacade.RouteResponse.DenialCause.ConditionFailed).retryable)
			.isEqualTo(retryable)
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a setUpPath failure unregisters every registered block, but cancels only the reserved one")
	fun setUpPathFailureRollsBackRegisteredBlocks() {
		val u1 = block("U1")
		val u2 = block("U2")
		every { u2.setUpPath(any(), any()) } throws IllegalStateException("setUpPath boom")
		val s1 = semaphore("S1")
		val (e, registry) = env(blocks = listOf(u1, u2), semaphores = listOf(s1))
		val facade = DefaultInterlockingFacade(e, registry)

		// When: U1 reserves fine, then U2's setUpPath throws mid-route.
		val response = facade.requestRoute("T1", SignalId("S1"), route(listOf("U1", "U2")), Aspect.Volno)

		// Then: denied as transient contention, and the catch arm's asymmetric sets hold —
		// registerAtomic registered BOTH blocks, only U1 was physically reserved.
		assertDenied(response, reason = "Track section cannot be locked", retryable = true)
		assertThat(registry.getOwner(u1)).isNull()
		assertThat(registry.getOwner(u2)).isNull()
		verify { u1.cancelPathSetup(s1) }
		verify(exactly = 0) { u2.cancelPathSetup(any()) }
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a setUpPath failure keeps the train's unrelated held route and PathInfo")
	fun setUpPathFailureKeepsUnrelatedHeldRoute() {
		val u0 = block("U0")
		val u1 = block("U1")
		val u2 = block("U2")
		every { u2.setUpPath(any(), any()) } throws IllegalStateException("setUpPath boom")
		val s1 = semaphore("S1")
		val (e, registry) = env(blocks = listOf(u0, u1, u2), semaphores = listOf(s1))
		val facade = DefaultInterlockingFacade(e, registry)

		// Given: the train already holds U0 from an earlier route granted through the same facade,
		// and the registry carries its PathInfo (the facade locks blocks but never creates one —
		// only the PathReservationService does; the registry stores whatever it is given).
		val held = facade.requestRoute("T1", SignalId("S1"), route(listOf("U0")), Aspect.Volno)
		assertThat(held).isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
		val heldPathInfo = mockk<PathInfo>(relaxed = true)
		registry.registerPathInfo("T1", heldPathInfo)

		// When: a second route's U2 setUpPath throws mid-route.
		val response = facade.requestRoute("T1", SignalId("S1"), route(listOf("U1", "U2")), Aspect.Volno)

		// Then: only the failed attempt is removed; the held route and its PathInfo survive.
		assertDenied(response, reason = "Track section cannot be locked", retryable = true)
		assertThat(registry.getOwner(u0)).isEqualTo("T1")
		assertThat(registry.getPathInfo("T1")).isSameInstanceAs(heldPathInfo)
		assertThat(registry.getOwner(u1)).isNull()
		assertThat(registry.getOwner(u2)).isNull()
		verify(exactly = 0) { u0.cancelPathSetup(any()) }
	}
}
