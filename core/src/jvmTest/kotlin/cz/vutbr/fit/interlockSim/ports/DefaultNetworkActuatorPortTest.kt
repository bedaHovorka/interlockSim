/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.ports

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.cells.createConstantInstance
import cz.vutbr.fit.interlockSim.objects.cells.createDynamicInstance
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.DefaultInterlockingFacade
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.Point
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertFailsWith

/**
 * Unit tests for [DefaultNetworkActuatorPort].
 *
 * All simulation objects are mocked with MockK so tests run without a live kDisco
 * simulation.  The grid is set up as a 3×1 strip; test elements are placed at
 * specific positions.
 *
 * @since Issue #545 (SP0.6 — Goal 10)
 */
@DisplayName("DefaultNetworkActuatorPort — unit coverage")
class DefaultNetworkActuatorPortTest {
	// ── Helpers ────────────────────────────────────────────────────────────

	private fun inOut(name: String): DynamicInOut {
		val staticRef = mockk<InOut>(relaxed = true)
		every { staticRef.getName() } returns name
		return mockk<DynamicInOut>(relaxed = true).also {
			every { it.name } returns name
			every { it.staticRef } returns staticRef
		}
	}

	private fun semaphore(
		name: String,
		signal: Signal = Signal.STOP
	): DynamicRailSemaphore =
		mockk<DynamicRailSemaphore>(relaxed = true).also {
			every { it.name } returns name
			every { it.signal } returns signal
		}

	/**
	 * Real mutable [DynamicRailSemaphore] with a non-blank [name] so the port's
	 * `buildSemaphoreCache` picks it up.  The [signal] is set through the real setter so
	 * `setSignalAspect` post-condition checks reflect actual state.
	 */
	private fun realDynamicSemaphore(
		name: String,
		signal: Signal = Signal.STOP
	): DynamicRailSemaphore =
		createDynamicInstance(RailSemaphore(name, true, Cell.SpatialType.HORIZONTAL)).also {
			it.signal = signal
		}

	/**
	 * Real *constant* [DynamicRailSemaphore] (fixed aspect, writes ignored) — models
	 * predzvěst / narážník / rychlostnik.
	 */
	private fun realConstantSemaphore(
		name: String,
		signal: Signal
	): DynamicRailSemaphore = createConstantInstance(RailSemaphore(name, true, Cell.SpatialType.HORIZONTAL), signal)

	private fun switch(
		name: String,
		conf: RailSwitch.Conf = RailSwitch.Conf.MAIN,
		locked: Boolean = false
	): DynamicRailSwitch =
		mockk<DynamicRailSwitch>(relaxed = true).also {
			every { it.name } returns name
			every { it.conf } returns conf
			every { it.locked } returns locked
		}

	/**
	 * Builds a minimal [SimulationEnvironment] stub for [DefaultNetworkActuatorPort].
	 *
	 * [inOuts] populates `getInOuts()`; [cells] maps (col, row) → [Cell] for the 3×1 grid;
	 * [reservationService] is wired into the routing services.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun env(
		inOuts: Collection<DynamicInOut> = emptyList(),
		cells: Map<Pair<Int, Int>, Cell?> = emptyMap(),
		reservationService: PathReservationService = mockk(relaxed = true)
	): Pair<SimulationEnvironment, PathReservationService> {
		val grid = mockk<RailwayNetGrid<Cell>>(relaxed = true)
		every { grid.cols } returns 3
		every { grid.rows } returns 1
		for (c in 0 until 3) {
			every { grid.getCellAt(c, 0) } returns cells[c to 0]
		}

		val routingServices = mockk<RoutingServices>(relaxed = true)
		every { routingServices.getPathReservationService() } returns reservationService

		val e = mockk<SimulationEnvironment>(relaxed = true)
		every { e.getInOuts() } returns inOuts
		every { e.getRailWayNetGrid() } returns grid
		every { e.getRoutingServices() } returns routingServices
		return e to reservationService
	}

	private fun port(
		inOuts: Collection<DynamicInOut> = emptyList(),
		cells: Map<Pair<Int, Int>, Cell?> = emptyMap(),
		reservationService: PathReservationService = mockk(relaxed = true)
	): DefaultNetworkActuatorPort {
		val (e, svc) = env(inOuts, cells, reservationService)
		return DefaultNetworkActuatorPort(e, svc)
	}

	// ── requestRoute ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("requestRoute()")
	inner class RequestRoute {
		@Test
		@DisplayName("blank trainName throws IllegalArgumentException")
		fun blankTrainNameThrows() {
			val a = inOut("A")
			val b = inOut("B")
			val p = port(inOuts = listOf(a, b))
			assertFailsWith<IllegalArgumentException> {
				p.requestRoute("", "A", "B")
			}
		}

		@Test
		@DisplayName("unknown fromEndpointName throws IllegalArgumentException")
		fun unknownFromThrows() {
			val b = inOut("B")
			val p = port(inOuts = listOf(b))
			assertFailsWith<IllegalArgumentException> {
				p.requestRoute("T1", "NOPE", "B")
			}
		}

		@Test
		@DisplayName("unknown toEndpointName throws IllegalArgumentException")
		fun unknownToThrows() {
			val a = inOut("A")
			val p = port(inOuts = listOf(a))
			assertFailsWith<IllegalArgumentException> {
				p.requestRoute("T1", "A", "NOPE")
			}
		}

		@Test
		@DisplayName("Success result maps to RouteRequestResult.Reserved")
		fun successMapsToReserved() {
			val a = inOut("A")
			val b = inOut("B")
			val blocks = listOf(mockk<DynamicTrackBlock>(relaxed = true))
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, b) } returns
				PathReservationService.ReservationResult.Success(blocks)

			val p = port(inOuts = listOf(a, b), reservationService = svc)
			val result = p.requestRoute("T1", "A", "B")

			assertThat(result)
				.isInstanceOf<RouteRequestResult.Reserved>()
				.prop(RouteRequestResult.Reserved::trainName)
				.isEqualTo("T1")
		}

		@Test
		@DisplayName("Reserved result carries correct blocksCount")
		fun reservedHasBlocksCount() {
			val a = inOut("A")
			val b = inOut("B")
			val twoBlocks =
				listOf(
					mockk<DynamicTrackBlock>(relaxed = true),
					mockk<DynamicTrackBlock>(relaxed = true)
				)
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, b) } returns
				PathReservationService.ReservationResult.Success(twoBlocks)

			val p = port(inOuts = listOf(a, b), reservationService = svc)
			val result = p.requestRoute("T1", "A", "B") as RouteRequestResult.Reserved

			assertThat(result.blocksCount).isEqualTo(2)
		}

		@Test
		@DisplayName("NoPathExists result maps to RouteRequestResult.NoRouteExists")
		fun noPathMapsToNoRoute() {
			val a = inOut("A")
			val b = inOut("B")
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, b) } returns
				PathReservationService.ReservationResult.NoPathExists

			val p = port(inOuts = listOf(a, b), reservationService = svc)
			val result = p.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.NoRouteExists>()
		}

		@Test
		@DisplayName("AllPathsBlocked result maps to RouteRequestResult.AllPathsBlocked")
		fun allBlockedMapsToAllBlocked() {
			val a = inOut("A")
			val b = inOut("B")
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, b) } returns
				PathReservationService.ReservationResult.AllPathsBlocked(3)

			val p = port(inOuts = listOf(a, b), reservationService = svc)
			val result = p.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.AllPathsBlocked>()
		}

		@Test
		@DisplayName("Conflict result maps to RouteRequestResult.Conflict carrying block and owner")
		fun conflictMapsToConflict() {
			val a = inOut("A")
			val b = inOut("B")
			val conflictBlock = mockk<DynamicTrackBlock>(relaxed = true)
			every { conflictBlock.name } returns "blockX"
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, b) } returns
				PathReservationService.ReservationResult.Conflict(conflictBlock, "T2")

			val p = port(inOuts = listOf(a, b), reservationService = svc)
			val result = p.requestRoute("T1", "A", "B") as RouteRequestResult.Conflict

			assertThat(result.blockName).isEqualTo("blockX")
			assertThat(result.existingOwner).isEqualTo("T2")
		}

		@Test
		@DisplayName("Conflict result with unnamed block maps blockName to null")
		fun conflictMapsUnnamedBlockToNull() {
			val a = inOut("A")
			val b = inOut("B")
			val conflictBlock = mockk<DynamicTrackBlock>(relaxed = true)
			every { conflictBlock.name } returns null
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, b) } returns
				PathReservationService.ReservationResult.Conflict(conflictBlock, "T3")

			val p = port(inOuts = listOf(a, b), reservationService = svc)
			val result = p.requestRoute("T1", "A", "B") as RouteRequestResult.Conflict

			assertThat(result.blockName).isNull()
			assertThat(result.existingOwner).isEqualTo("T3")
		}

		@Test
		@DisplayName("partial route InOut → Semaphore is accepted")
		fun partialRouteInOutToSemaphore() {
			val a = inOut("A")
			val s1 = semaphore("S1")
			val blocks = listOf(mockk<DynamicTrackBlock>(relaxed = true))
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, s1) } returns
				PathReservationService.ReservationResult.Success(blocks)

			val p = port(inOuts = listOf(a), cells = mapOf((1 to 0) to s1), reservationService = svc)
			val result = p.requestRoute("T1", "A", "S1")

			assertThat(result).isInstanceOf<RouteRequestResult.Reserved>()
		}

		@Test
		@DisplayName("partial route Semaphore → InOut is accepted")
		fun partialRouteSemaphoreToInOut() {
			val s1 = semaphore("S1")
			val b = inOut("B")
			val blocks = listOf(mockk<DynamicTrackBlock>(relaxed = true))
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", s1, b) } returns
				PathReservationService.ReservationResult.Success(blocks)

			val p = port(inOuts = listOf(b), cells = mapOf((0 to 0) to s1), reservationService = svc)
			val result = p.requestRoute("T1", "S1", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.Reserved>()
		}

		@Test
		@DisplayName("unknown endpoint (neither InOut nor Semaphore) throws IllegalArgumentException")
		fun unknownEndpointThrows() {
			val a = inOut("A")
			val s1 = semaphore("S1")
			val p = port(inOuts = listOf(a), cells = mapOf((0 to 0) to s1))
			assertFailsWith<IllegalArgumentException> {
				p.requestRoute("T1", "A", "UNKNOWN")
			}
		}
	}

	// ── requestRoute via InterlockingFacade (SP3.5) ─────────────────────────

	@Nested
	@DisplayName("requestRoute() via InterlockingFacade (SP3.5 — Issue #573)")
	inner class RequestRouteThroughFacade {
		private fun portWithFacade(
			inOuts: Collection<DynamicInOut> = emptyList(),
			cells: Map<Pair<Int, Int>, Cell?> = emptyMap(),
			facade: InterlockingFacade
		): DefaultNetworkActuatorPort {
			val (e, _) = env(inOuts, cells)
			return DefaultNetworkActuatorPort(env = e, interlockingFacade = facade)
		}

		@Test
		@DisplayName("Granted response maps to RouteRequestResult.Reserved with correct trainName and blocksCount")
		fun grantedMapsToReserved() {
			val a = inOut("A")
			val b = inOut("B")
			val route =
				TrainRoute(
					from = SignalId("A"),
					to = SignalId("B"),
					running = emptyList(),
					blocks = listOf(BlockId("U1"), BlockId("U2"))
				)
			val facade = mockk<InterlockingFacade>()
			every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
				InterlockingFacade.RouteResponse.Granted(Aspect.Volno, route)

			val result =
				portWithFacade(inOuts = listOf(a, b), facade = facade)
					.requestRoute("T1", "A", "B") as RouteRequestResult.Reserved

			assertThat(result.trainName).isEqualTo("T1")
			assertThat(result.blocksCount).isEqualTo(2)
		}

		/**
		 * Test F-g (Issue #834, task alpha-7a) — the rewrite of the former
		 * `deniedMapsToAllPathsBlocked`, which pinned the defect: it asserted that EVERY facade
		 * denial became `AllPathsBlocked(0)`, i.e. that the kernel's reason was discarded and a
		 * count that contradicts [RouteRequestResult.AllPathsBlocked]'s own contract
		 * (`attemptedPaths` = number of candidate paths actually checked) was invented.
		 *
		 * Its replacement pins the **residual** case only:
		 * [InterlockingFacade.RouteResponse.DenialCause.Other] — a denial with no reservation
		 * outcome behind it, so no candidate-path count exists. Per invariant I5 such a denial must
		 * never be reported as contention; it lands in [RouteRequestResult.NoRouteExists], the
		 * permanent-refusal bucket a dispatcher must not blindly retry.
		 */
		@Test
		@DisplayName("Denied with the residual cause (Other) maps to NoRouteExists, never AllPathsBlocked")
		fun deniedWithResidualCauseMapsToNoRouteExists() {
			val a = inOut("A")
			val b = inOut("B")
			val facade = mockk<InterlockingFacade>()
			every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
				InterlockingFacade.RouteResponse.Denied("Unknown route endpoint: A")

			val result =
				portWithFacade(inOuts = listOf(a, b), facade = facade)
					.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.NoRouteExists>()
			result as RouteRequestResult.NoRouteExists
			assertThat(result.fromEndpointName).isEqualTo("A")
			assertThat(result.toEndpointName).isEqualTo("B")
		}

		/**
		 * Test F-a (Issue #834, task alpha-7a): a kernel `NoPathExists` must reach the caller as
		 * [RouteRequestResult.NoRouteExists] on the facade branch, exactly as it already does on
		 * the legacy/no-facade branch. Before this fix it collapsed to `AllPathsBlocked(0)`.
		 */
		@Test
		@DisplayName("Denied with DenialCause.NoPath maps to RouteRequestResult.NoRouteExists")
		fun deniedNoPathMapsToNoRouteExists() {
			val a = inOut("A")
			val b = inOut("B")
			val facade = mockk<InterlockingFacade>()
			every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
				InterlockingFacade.RouteResponse.Denied(
					"No path exists: A → B",
					InterlockingFacade.RouteResponse.DenialCause.NoPath
				)

			val result =
				portWithFacade(inOuts = listOf(a, b), facade = facade)
					.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.NoRouteExists>()
			result as RouteRequestResult.NoRouteExists
			assertThat(result.fromEndpointName).isEqualTo("A")
			assertThat(result.toEndpointName).isEqualTo("B")
		}

		/**
		 * Test F-c (Issue #834, task alpha-7a): the real candidate-path count must survive the
		 * facade branch. `attemptedPaths = 0` was not merely imprecise — it contradicted
		 * [RouteRequestResult.AllPathsBlocked]'s contract and was rendered verbatim to the LLM
		 * dispatcher as "0 path(s) attempted".
		 */
		@Test
		@DisplayName("Denied with DenialCause.AllPathsBlocked(n) preserves n (never collapses to 0)")
		fun deniedAllPathsBlockedPreservesAttemptedPaths() {
			val a = inOut("A")
			val b = inOut("B")
			val facade = mockk<InterlockingFacade>()
			every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
				InterlockingFacade.RouteResponse.Denied(
					"All paths blocked (A → B, attempts: 3)",
					InterlockingFacade.RouteResponse.DenialCause.AllPathsBlocked(3)
				)

			val result =
				portWithFacade(inOuts = listOf(a, b), facade = facade)
					.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.AllPathsBlocked>()
			assertThat((result as RouteRequestResult.AllPathsBlocked).attemptedPaths).isEqualTo(3)
		}

		/**
		 * Test F-b (Issue #834, task alpha-7a): the conflicting block and its owner must survive
		 * the facade branch, so a dispatcher can wait for that specific train instead of retrying
		 * blindly. Both payloads were discarded before this fix.
		 */
		@Test
		@DisplayName("Denied with DenialCause.Conflict preserves block name and existing owner")
		fun deniedConflictPreservesBlockAndOwner() {
			val a = inOut("A")
			val b = inOut("B")
			val facade = mockk<InterlockingFacade>()
			every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
				InterlockingFacade.RouteResponse.Denied(
					"Block U7 occupied by train T2",
					InterlockingFacade.RouteResponse.DenialCause.Conflict("U7", "T2")
				)

			val result =
				portWithFacade(inOuts = listOf(a, b), facade = facade)
					.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.Conflict>()
			result as RouteRequestResult.Conflict
			assertThat(result.blockName).isEqualTo("U7")
			assertThat(result.existingOwner).isEqualTo("T2")
		}

		/**
		 * Task A-R1b (Issue #893): the facade branch must not collapse EVERY denial to
		 * `AllPathsBlocked(0)` -- a denial the kernel specifically flagged as
		 * `originNotContiguous` (mapped from
		 * `PathReservationService.ReservationResult.NonContiguousStart` by
		 * `DefaultInterlockingFacade.requestRouteByEndpoints`) must surface as
		 * `RouteRequestResult.OriginNotContiguous`, reason preserved verbatim, exactly as the
		 * legacy/no-facade branch already does. Before this fix `DefaultNetworkActuatorPort`
		 * ignored the discriminant entirely and this always produced `AllPathsBlocked(0)`.
		 */
		@Test
		@DisplayName(
			"Denied response with originNotContiguous=true maps to RouteRequestResult.OriginNotContiguous, " +
				"reason preserved"
		)
		fun deniedWithNonContiguousOriginMapsToOriginNotContiguous() {
			val a = inOut("A")
			val b = inOut("B")
			val facade = mockk<InterlockingFacade>()
			val reason = "T1 holds no block bounded by 'A'; legal origins: X, Y"
			every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
				InterlockingFacade.RouteResponse.Denied(reason, originNotContiguous = true)

			val result =
				portWithFacade(inOuts = listOf(a, b), facade = facade)
					.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.OriginNotContiguous>()
			result as RouteRequestResult.OriginNotContiguous
			assertThat(result.fromEndpointName).isEqualTo("A")
			assertThat(result.reason).isEqualTo(reason)
		}

		@Test
		@DisplayName("unknown endpoint throws IllegalArgumentException even when facade is wired")
		fun unknownEndpointThrowsWithFacade() {
			val a = inOut("A")
			val facade = mockk<InterlockingFacade>()

			val p = portWithFacade(inOuts = listOf(a), facade = facade)

			assertFailsWith<IllegalArgumentException> {
				p.requestRoute("T1", "A", "UNKNOWN")
			}
		}

		@Test
		@DisplayName("blank trainName throws IllegalArgumentException even when facade is wired")
		fun blankTrainNameThrowsWithFacade() {
			val facade = mockk<InterlockingFacade>()
			val (e, _) = env()
			val p = DefaultNetworkActuatorPort(env = e, interlockingFacade = facade)

			assertFailsWith<IllegalArgumentException> {
				p.requestRoute("", "A", "B")
			}
		}

		@Test
		@DisplayName("facade is not called when trainName is blank (fail-fast before facade)")
		fun facadeNotCalledOnBlankTrainName() {
			val facade = mockk<InterlockingFacade>(relaxed = true)
			val (e, _) = env()
			val p = DefaultNetworkActuatorPort(env = e, interlockingFacade = facade)

			runCatching { p.requestRoute("", "A", "B") }

			// Verify facade was never invoked
			io.mockk.verify(exactly = 0) { facade.requestRouteByEndpoints(any(), any(), any()) }
		}
	}

	// ── facade branch ≡ legacy branch (Issue #834, task alpha-7a) ───────────

	/**
	 * Test F-e (Issue #834, task alpha-7a) — the strongest single regression guard available for
	 * this fix, and the repair of a measurement defect.
	 *
	 * [DefaultNetworkActuatorPort] has two branches producing [RouteRequestResult]: the facade
	 * branch (production LLM dispatcher — `DispatcherAgentModule` always supplies an
	 * [InterlockingFacade]) and the legacy/no-facade branch (`:fast-sim`, the rule-based
	 * `SynchronousDispatcherWiring` baseline, and tests without Koin DI). They classified the same
	 * kernel outcome differently: the legacy branch mapped all four
	 * [PathReservationService.ReservationResult] failures faithfully, while the facade branch
	 * collapsed everything except `NonContiguousStart` into `AllPathsBlocked(0)`. The #847 sweep
	 * therefore compared `ALL_PATHS_BLOCKED` counts produced by two different classifiers.
	 *
	 * This property pins the repair: **identical [PathReservationService.ReservationResult] ⇒
	 * identical [RouteRequestResult]**, on every subtype of the sealed hierarchy — with
	 * [providerCoversEveryReservationResultSubtype] guaranteeing the parameter list stays
	 * exhaustive if a subtype is ever added.
	 */
	@Nested
	@DisplayName("facade branch and legacy branch agree (Issue #834, task alpha-7a)")
	inner class FacadeLegacyEquivalence {
		/**
		 * Runs the identical request down both branches of [DefaultNetworkActuatorPort] against
		 * the same stubbed [PathReservationService], and returns `legacy to viaFacade`.
		 *
		 * The facade is a **real** [DefaultInterlockingFacade] (not a mock): the whole point is
		 * that the kernel's own translation of a [PathReservationService.ReservationResult] into a
		 * [InterlockingFacade.RouteResponse] preserves everything the legacy branch reads directly.
		 */
		private fun bothBranches(
			reservation: PathReservationService.ReservationResult
		): Pair<RouteRequestResult, RouteRequestResult> {
			val a = inOut("A")
			val b = inOut("B")
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath(any(), any(), any(), any()) } returns reservation

			val (e, _) = env(inOuts = listOf(a, b), reservationService = svc)
			val graph = mockk<ExtendedUnorientedGraph<Point, DynamicTrackBlock, Cell.Segment>>(relaxed = true)
			every { graph.values() } returns emptyList()
			every { e.getGraph() } returns graph

			val legacy = DefaultNetworkActuatorPort(e, svc).requestRoute("T1", "A", "B")
			val facade = DefaultInterlockingFacade(e, PathReservationRegistry(mockk(relaxed = true)))
			val viaFacade =
				DefaultNetworkActuatorPort(e, svc, facade).requestRoute("T1", "A", "B")
			return legacy to viaFacade
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPortTest#reservationResults")
		@DisplayName("same ReservationResult yields the same RouteRequestResult on both branches")
		fun branchesAgree(reservation: PathReservationService.ReservationResult) {
			val (legacy, viaFacade) = bothBranches(reservation)

			assertThat(viaFacade).isEqualTo(legacy)
		}

		/**
		 * Guards [reservationResults] against silently going stale: a new
		 * [PathReservationService.ReservationResult] subtype must be added to the provider, or
		 * [branchesAgree] would stop covering it while still passing.
		 */
		@Test
		@DisplayName("the equivalence provider covers every ReservationResult subtype")
		fun providerCoversEveryReservationResultSubtype() {
			val covered = reservationResults().map { it::class }.toSet()

			assertThat(covered).isEqualTo(PathReservationService.ReservationResult::class.sealedSubclasses.toSet())
		}

		/**
		 * Test F-f (Issue #834, task alpha-7a): a denial with **no reservation behind it**.
		 *
		 * [cz.vutbr.fit.interlockSim.sim.DefaultInterlockingFacade.requestRouteByEndpoints] denies
		 * an unresolvable endpoint name before it ever calls `reservePath`, so no candidate-path
		 * count exists. Per invariant I5 that denial must not be reported as contention — it
		 * carries the residual cause and classifies as [RouteRequestResult.NoRouteExists].
		 *
		 * This state is currently unreachable *through the port* (which pre-validates endpoint
		 * names and throws [IllegalArgumentException] first — see `unknownEndpointThrowsWithFacade`
		 * above), but it is reachable for every other facade caller, so the classifier needs a
		 * defined answer rather than an accidental one.
		 */
		@Test
		@DisplayName("unknown-endpoint denial carries the residual cause, never a contention cause")
		fun unknownEndpointDenialIsResidualNotContention() {
			val a = inOut("A")
			val (e, _) = env(inOuts = listOf(a))
			val graph = mockk<ExtendedUnorientedGraph<Point, DynamicTrackBlock, Cell.Segment>>(relaxed = true)
			every { graph.values() } returns emptyList()
			every { e.getGraph() } returns graph
			val facade = DefaultInterlockingFacade(e, PathReservationRegistry(mockk(relaxed = true)))

			val response = facade.requestRouteByEndpoints("T1", "NOPE", "A")

			assertThat(response).isInstanceOf<InterlockingFacade.RouteResponse.Denied>()
			response as InterlockingFacade.RouteResponse.Denied
			assertThat(response.cause).isEqualTo(InterlockingFacade.RouteResponse.DenialCause.Other)
		}
	}

	// ── releaseRoute ────────────────────────────────────────────────────────

	@Nested
	@DisplayName("releaseRoute()")
	inner class ReleaseRoute {
		@Test
		@DisplayName("blank trainName throws IllegalArgumentException")
		fun blankTrainNameThrows() {
			val p = port()
			assertFailsWith<IllegalArgumentException> { p.releaseRoute("") }
		}

		@Test
		@DisplayName("returns true when PathReservationService released at least one block")
		fun trueWhenBlocksReleased() {
			val released = listOf(mockk<DynamicTrackBlock>(relaxed = true))
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.releasePath("T1") } returns released

			val p = port(reservationService = svc)

			assertThat(p.releaseRoute("T1")).isTrue()
		}

		@Test
		@DisplayName("returns false when no blocks were released and no signal was cleared")
		fun falseWhenNothingReleased() {
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.releasePath("T99") } returns emptyList()
			every { svc.hasClearedSignals("T99") } returns false

			val p = port(reservationService = svc)

			assertThat(p.releaseRoute("T99")).isFalse()
		}

		/**
		 * Issue #893 task A7 (G7): `DefaultPathReservationService.releasePath` resets a train's
		 * cleared signals even when it has zero blocks left to give back (reachable after a
		 * partial release reclaimed the train's un-travelled tail, tasks A3/A4). Before this fix,
		 * `releaseRoute` reported `false` here despite the signal genuinely being reset --
		 * `OrphanReservationSweeper` never counted the reclaim and retried the same owner every
		 * sweep. Per the binding traffic-simulation-expert R5 ruling, `releaseRoute` means "the
		 * train's route state is now clear": `true` whenever blocks OR signals were released.
		 */
		@Test
		@DisplayName("returns true when only a signal was cleared, even with zero blocks released")
		fun trueWhenOnlySignalCleared() {
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.releasePath("T50") } returns emptyList()
			every { svc.hasClearedSignals("T50") } returns true

			val p = port(reservationService = svc)

			assertThat(p.releaseRoute("T50")).isTrue()
		}

		/**
		 * `hasClearedSignals` is read BEFORE `releasePath` is called, since `releasePath` purges
		 * that very bookkeeping as part of resetting the signal (see
		 * `PathReservationService.hasClearedSignals` KDoc). A stub that only returns `true` when
		 * queried while the train's registered blocks are still intact catches an implementation
		 * that accidentally reordered the two calls.
		 */
		@Test
		@DisplayName("hasClearedSignals is queried before releasePath purges its bookkeeping")
		fun hasClearedSignalsQueriedBeforeReleasePath() {
			var released = false
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.hasClearedSignals("T51") } answers { !released }
			every { svc.releasePath("T51") } answers {
				released = true
				emptyList()
			}

			val p = port(reservationService = svc)

			assertThat(p.releaseRoute("T51")).isTrue()
		}

		/**
		 * Deliberate scope boundary of task A7 (G7): a train that holds neither a block nor a
		 * cleared signal still reports `false`. This is NOT a "genuine failure" masked as one --
		 * it is the case `DispatchDecisionApplier` surfaces to the LLM dispatcher as a distinct
		 * `NO_RESERVATION` outcome (see `AppliedOutcomeChannelSp2c17Test.releaseRouteNoReservationRendered`
		 * in `:dispatcher-agent`), which collapsing into the same `true` as a genuine release would
		 * erase for no gain: `OrphanReservationSweeper` never calls `releaseRoute` for a train with
		 * zero footprint in the first place (it only visits owners the snapshot already shows
		 * holding a block), so widening `true` to this case fixes no reachable defect and would
		 * only remove a working diagnostic signal.
		 */
		@Test
		@DisplayName("stays false (not forced idempotent-true) when neither blocks nor a signal existed")
		fun falseWhenNeitherBlocksNorSignalExisted() {
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.releasePath("T52") } returns emptyList()
			every { svc.hasClearedSignals("T52") } returns false

			val p = port(reservationService = svc)

			assertThat(p.releaseRoute("T52")).isFalse()
		}
	}

	// ── setSwitchPosition ───────────────────────────────────────────────────

	@Nested
	@DisplayName("setSwitchPosition()")
	inner class SetSwitchPosition {
		@Test
		@DisplayName("returns false for unknown switch name")
		fun unknownSwitchReturnsFalse() {
			val p = port(cells = emptyMap())
			assertThat(p.setSwitchPosition("NO_SUCH_SW", RailSwitch.Conf.MAIN)).isFalse()
		}

		@Test
		@DisplayName("returns false when switch is locked")
		fun lockedSwitchReturnsFalse() {
			val sw = switch("vA", locked = true)
			val p = port(cells = mapOf((0 to 0) to sw))
			assertThat(p.setSwitchPosition("vA", RailSwitch.Conf.BRANCH)).isFalse()
		}

		@Test
		@DisplayName("returns true when switch is already in requested position (no-op)")
		fun alreadyInPositionReturnsTrue() {
			val sw = switch("vA", conf = RailSwitch.Conf.MAIN, locked = false)
			val p = port(cells = mapOf((0 to 0) to sw))
			assertThat(p.setSwitchPosition("vA", RailSwitch.Conf.MAIN)).isTrue()
		}

		@Test
		@DisplayName("returns true when switch is locked but already in requested position (idempotent no-op)")
		fun lockedButAlreadyInPositionReturnsTrue() {
			val sw = switch("vA", conf = RailSwitch.Conf.MAIN, locked = true)
			val p = port(cells = mapOf((0 to 0) to sw))
			assertThat(p.setSwitchPosition("vA", RailSwitch.Conf.MAIN)).isTrue()
		}
	}

	// ── setSignalAspect ─────────────────────────────────────────────────────

	@Nested
	@DisplayName("setSignalAspect()")
	inner class SetSignalAspect {
		@Test
		@DisplayName("returns false for unknown semaphore name")
		fun unknownSemaphoreReturnsFalse() {
			val p = port(cells = emptyMap())
			assertThat(p.setSignalAspect("NO_SEM", Signal.FREE)).isFalse()
		}

		@Test
		@DisplayName("upgrade STOP → FREE on dynamic semaphore returns true and sets the signal")
		fun upgradeOnDynamicSemaphoreReturnsTrue() {
			val sem = realDynamicSemaphore("zA", Signal.STOP)
			val p = port(cells = mapOf((0 to 0) to sem))

			val result = p.setSignalAspect("zA", Signal.FREE)

			assertThat(result).isTrue()
			assertThat(sem.signal).isEqualTo(Signal.FREE)
		}

		@Test
		@DisplayName("downgrade FREE → S30 on dynamic semaphore returns true and sets the signal")
		fun downgradeOnDynamicSemaphoreReturnsTrue() {
			val sem = realDynamicSemaphore("zA", Signal.FREE)
			val p = port(cells = mapOf((0 to 0) to sem))

			val result = p.setSignalAspect("zA", Signal.S30)

			assertThat(result).isTrue()
			assertThat(sem.signal).isEqualTo(Signal.S30)
		}

		@Test
		@DisplayName("setting the current aspect is a no-op that returns true")
		fun sameAspectReturnsTrue() {
			val sem = realDynamicSemaphore("zA", Signal.S60)
			val p = port(cells = mapOf((0 to 0) to sem))

			assertThat(p.setSignalAspect("zA", Signal.S60)).isTrue()
			assertThat(sem.signal).isEqualTo(Signal.S60)
		}

		@Test
		@DisplayName("constant semaphore refuses a change to a different aspect (returns false)")
		fun constantSemaphoreRefusesChange() {
			val sem = realConstantSemaphore("zA", Signal.FREE)
			val p = port(cells = mapOf((0 to 0) to sem))

			assertThat(p.setSignalAspect("zA", Signal.STOP)).isFalse()
			// Constant semaphore keeps its fixed aspect.
			assertThat(sem.signal).isEqualTo(Signal.FREE)
		}

		@Test
		@DisplayName("constant semaphore returns true when requested its own fixed aspect")
		fun constantSemaphoreSameAspectReturnsTrue() {
			val sem = realConstantSemaphore("zA", Signal.S40)
			val p = port(cells = mapOf((0 to 0) to sem))

			assertThat(p.setSignalAspect("zA", Signal.S40)).isTrue()
			assertThat(sem.signal).isEqualTo(Signal.S40)
		}

		/**
		 * G5 attribution slice (Issue #893, task A6): the plain 2-arg [DefaultNetworkActuatorPort.setSignalAspect]
		 * stays untracked (no trainId to attribute the write to) -- only the attributed 3-arg
		 * overload records the write with [PathReservationService.recordExternalClearedSemaphore],
		 * closing the tracking-contract hole a naive write would otherwise leave for any future
		 * caller. [svc] mirrors just enough of `DefaultPathReservationService`'s cleared-signal
		 * ledger to prove the round trip end-to-end: the attributed write must be reset by a
		 * later [DefaultNetworkActuatorPort.releaseRoute] for the same train.
		 */
		@Test
		@DisplayName("attributed overload records the write so releaseRoute(trainName) resets it")
		fun attributedWriteIsResetByReleaseRoute() {
			val sem = realDynamicSemaphore("zA", Signal.STOP)
			val cleared = mutableMapOf<String, MutableSet<DynamicRailSemaphore>>()
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.recordExternalClearedSemaphore(any(), any()) } answers {
				val trainId = firstArg<String>()
				val s = secondArg<DynamicRailSemaphore>()
				if (s.signal.isAllowing()) cleared.getOrPut(trainId) { mutableSetOf() }.add(s)
			}
			every { svc.hasClearedSignals(any()) } answers { cleared[firstArg<String>()]?.isNotEmpty() == true }
			every { svc.releasePath(any()) } answers {
				cleared.remove(firstArg<String>())?.forEach { it.signal = Signal.STOP }
				emptyList()
			}
			val p = port(cells = mapOf((0 to 0) to sem), reservationService = svc)

			val writeResult = p.setSignalAspect("zA", Signal.FREE, trainName = "T1")

			assertThat(writeResult).isTrue()
			assertThat(sem.signal).isEqualTo(Signal.FREE)

			val releaseResult = p.releaseRoute("T1")

			assertThat(releaseResult).isTrue()
			assertThat(sem.signal).isEqualTo(Signal.STOP)
		}

		@Test
		@DisplayName("plain (unattributed) write is not reset by a later releaseRoute for any train")
		fun unattributedWriteIsNotTrackedByReleaseRoute() {
			val sem = realDynamicSemaphore("zA", Signal.STOP)
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.hasClearedSignals(any()) } returns false
			every { svc.releasePath(any()) } returns emptyList()
			val p = port(cells = mapOf((0 to 0) to sem), reservationService = svc)

			assertThat(p.setSignalAspect("zA", Signal.FREE)).isTrue()
			assertThat(sem.signal).isEqualTo(Signal.FREE)

			// No trainId was ever attributed, so no release call can know to reset it.
			p.releaseRoute("T1")

			assertThat(sem.signal).isEqualTo(Signal.FREE)
		}
	}

	companion object {
		/**
		 * One instance of every [PathReservationService.ReservationResult] subtype, feeding the
		 * branch-equivalence property in [FacadeLegacyEquivalence] (Issue #834, task alpha-7a).
		 *
		 * Exhaustiveness is asserted, not assumed — see
		 * [FacadeLegacyEquivalence.providerCoversEveryReservationResultSubtype].
		 */
		@JvmStatic
		fun reservationResults(): List<PathReservationService.ReservationResult> {
			val block =
				mockk<DynamicTrackBlock>(relaxed = true).also {
					every { it.name } returns "U7"
				}
			return listOf(
				PathReservationService.ReservationResult.Success(
					listOf(mockk<DynamicTrackBlock>(relaxed = true), mockk<DynamicTrackBlock>(relaxed = true))
				),
				PathReservationService.ReservationResult.NoPathExists,
				PathReservationService.ReservationResult.AllPathsBlocked(attemptedPaths = 4),
				PathReservationService.ReservationResult.Conflict(block, "T2"),
				PathReservationService.ReservationResult.NonContiguousStart(
					startName = "A",
					reason = "T1 holds no block bounded by 'A'; legal origins: B"
				)
			)
		}
	}
}
