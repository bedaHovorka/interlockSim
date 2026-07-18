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
import cz.vutbr.fit.interlockSim.sim.InterlockingFacade
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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

		@Test
		@DisplayName("Denied response maps to RouteRequestResult.AllPathsBlocked(0)")
		fun deniedMapsToAllPathsBlocked() {
			val a = inOut("A")
			val b = inOut("B")
			val facade = mockk<InterlockingFacade>()
			every { facade.requestRouteByEndpoints("T1", "A", "B") } returns
				InterlockingFacade.RouteResponse.Denied("Trasa neexistuje")

			val result =
				portWithFacade(inOuts = listOf(a, b), facade = facade)
					.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.AllPathsBlocked>()
			assertThat((result as RouteRequestResult.AllPathsBlocked).attemptedPaths).isEqualTo(0)
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
		@DisplayName("returns false when no blocks were released")
		fun falseWhenNothingReleased() {
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.releasePath("T99") } returns emptyList()

			val p = port(reservationService = svc)

			assertThat(p.releaseRoute("T99")).isFalse()
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
	}
}
