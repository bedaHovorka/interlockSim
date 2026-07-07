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
import assertk.assertions.isTrue
import assertk.assertions.prop
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.InOut
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
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
		@DisplayName("unknown fromInOutName throws IllegalArgumentException")
		fun unknownFromThrows() {
			val b = inOut("B")
			val p = port(inOuts = listOf(b))
			assertFailsWith<IllegalArgumentException> {
				p.requestRoute("T1", "NOPE", "B")
			}
		}

		@Test
		@DisplayName("unknown toInOutName throws IllegalArgumentException")
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
		@DisplayName("Conflict result maps to RouteRequestResult.AllPathsBlocked(0)")
		fun conflictMapsToAllBlocked() {
			val a = inOut("A")
			val b = inOut("B")
			val conflictBlock = mockk<DynamicTrackBlock>(relaxed = true)
			val svc = mockk<PathReservationService>(relaxed = true)
			every { svc.reservePath("T1", a, b) } returns
				PathReservationService.ReservationResult.Conflict(conflictBlock, "T2")

			val p = port(inOuts = listOf(a, b), reservationService = svc)
			val result = p.requestRoute("T1", "A", "B")

			assertThat(result).isInstanceOf<RouteRequestResult.AllPathsBlocked>()
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
		@DisplayName("returns true and sets signal on a known semaphore with STOP signal")
		fun knownSemaphoreSetAndReturnsTrue() {
			val sem = semaphore("zA", Signal.STOP)
			val p = port(cells = mapOf((0 to 0) to sem))

			val result = p.setSignalAspect("zA", Signal.FREE)

			assertThat(result).isTrue()
		}

		@Test
		@DisplayName("returns false when semaphore is locked by active route (signal is allowing)")
		fun lockedByRouteReturnsFalse() {
			val sem = semaphore("zA", Signal.FREE) // locked: interlocking set it to allowing
			val p = port(cells = mapOf((0 to 0) to sem))

			assertThat(p.setSignalAspect("zA", Signal.STOP)).isFalse()
		}

		@Test
		@DisplayName("returns false for any allowing signal aspect when semaphore is locked")
		fun allAllowingSignalsAreLocked() {
			for (allowingSignal in listOf(Signal.S30, Signal.S60, Signal.S80, Signal.S100, Signal.FREE)) {
				val sem = semaphore("sA", allowingSignal)
				val p = port(cells = mapOf((0 to 0) to sem))
				assertThat(p.setSignalAspect("sA", Signal.STOP))
					.isFalse()
			}
		}
	}
}
