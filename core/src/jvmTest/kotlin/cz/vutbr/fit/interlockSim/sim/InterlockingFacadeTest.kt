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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.RailwayNetGrid
import cz.vutbr.fit.interlockSim.context.SimulationEnvironment
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.RoutingServices
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchPosition
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchSetting
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.objects.core.Cell
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.core.TrackOccupant
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.MockSimulationContext
import cz.vutbr.fit.interlockSim.testutil.createMockSimulationContext
import cz.vutbr.fit.interlockSim.util.ExtendedUnorientedGraph
import cz.vutbr.fit.interlockSim.util.Point
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.koin.core.module.Module
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [InterlockingFacade] — the ESA-11 four-condition interlocking kernel.
 *
 * The top-level tests validate structural/error-handling contracts against an empty network
 * (unknown block/switch/signal names correctly deny, per the fail-closed design — see
 * [DefaultInterlockingFacade]). [RealNetworkBehavior] validates actual four-condition
 * enforcement (occupancy, locking, conflicts, release) against MockK-mocked network elements,
 * following the same pattern as [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPortTest].
 *
 * @since Issue #572 (SP3.4 — Goal 10)
 */
@DisplayName("InterlockingFacade — ESA-11 four-condition kernel")
@Timeout(30, unit = TimeUnit.SECONDS)
class InterlockingFacadeTest : KoinTestBase() {
	override fun getTestModule(): Module =
		module {
			// Import core module with InterlockingFacade binding
			includes(cz.vutbr.fit.interlockSim.di.coreModule)
		}

	private lateinit var facade: InterlockingFacade
	private lateinit var mockContext: MockSimulationContext

	@BeforeEach
	fun setUp() {
		mockContext = createMockSimulationContext()
		val registry: PathReservationRegistry = mockContext.scope.get()
		facade = DefaultInterlockingFacade(mockContext, registry)
	}

	/**
	 * Test: Route request returns either Granted or Denied (sealed interface structure).
	 *
	 * **Coverage:** Type contract validation.
	 * **Expected:** RouteResponse.Granted or RouteResponse.Denied (no other subtypes).
	 * **Note:** With null helper methods, requests typically return Denied due to missing blocks.
	 */
	@Test
	fun `requestRoute returns valid RouteResponse sealed type`() {
		val trainId = "T1"
		val entrySignal = SignalId("S1")
		val route =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = listOf(BlockId("U1")),
				running = emptyList(),
				flank = emptyList()
			)
		val clearedAspect = Aspect.Volno

		val response = facade.requestRoute(trainId, entrySignal, route, clearedAspect)

		// Response must be one of the sealed subtypes
		assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse::class)
	}

	/**
	 * Test: Route request with empty blocks list is denied (C1).
	 *
	 * **Coverage:** A route with no track sections protects nothing — the kernel must never
	 * clear a signal for it.
	 * **Expected:** `Denied` with a Czech reason mentioning the empty route.
	 */
	@Test
	fun `requestRoute with empty blocks is Denied`() {
		val trainId = "T1"
		val entrySignal = SignalId("S1")
		val route =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = emptyList(),
				running = emptyList(),
				flank = emptyList()
			)
		val clearedAspect = Aspect.Volno

		val response = facade.requestRoute(trainId, entrySignal, route, clearedAspect)

		assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
		assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).isNotEmpty()
	}

	/**
	 * Test: Denial response includes Czech human-readable reason.
	 *
	 * **Coverage:** Error message generation for dispatcher/agent feedback. On an empty
	 * network the requested block "U1" is unknown, so the route is denied (fail closed).
	 * **Expected:** `Denied` with a non-empty Czech reason.
	 */
	@Test
	fun `Denied response contains non-empty Czech reason`() {
		val trainId = "T1"
		val entrySignal = SignalId("S1")
		val route =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = listOf(BlockId("U1")),
				running = emptyList(),
				flank = emptyList()
			)
		val clearedAspect = Aspect.Volno

		val response = facade.requestRoute(trainId, entrySignal, route, clearedAspect)

		assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
		assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).isNotEmpty()
	}

	/**
	 * Test: Release route does not throw exception with valid trainId.
	 *
	 * **Coverage:** Basic release operation (no routes actually locked).
	 * **Expected:** No exceptions; idempotent operation succeeds.
	 */
	@Test
	fun `releaseRoute with non-existent trainId succeeds (idempotent)`() {
		val trainId = "T-nonexistent"
		val exitSignal = SignalId("S2")

		// Should not throw; operation is idempotent
		try {
			facade.releaseRoute(trainId, exitSignal)
		} catch (e: Exception) {
			throw AssertionError("releaseRoute should not throw for non-existent train", e)
		}
	}

	/**
	 * Test: Multiple sequential route requests create isolated responses.
	 *
	 * **Coverage:** Stateless facade behavior.
	 * **Expected:** Each request independently evaluated.
	 */
	@Test
	fun `multiple sequential route requests are independent`() {
		val route1 =
			TrainRoute(
				from = SignalId("S1"),
				to = SignalId("S2"),
				blocks = listOf(BlockId("U1")),
				running = emptyList(),
				flank = emptyList()
			)
		val route2 =
			TrainRoute(
				from = SignalId("S2"),
				to = SignalId("S3"),
				blocks = listOf(BlockId("U2")),
				running = emptyList(),
				flank = emptyList()
			)

		val response1 = facade.requestRoute("T1", SignalId("S1"), route1, Aspect.Volno)
		val response2 = facade.requestRoute("T2", SignalId("S2"), route2, Aspect.Volno)

		// Both should be valid responses (type contract)
		assertThat(response1).isInstanceOf(InterlockingFacade.RouteResponse::class)
		assertThat(response2).isInstanceOf(InterlockingFacade.RouteResponse::class)
	}

	/**
	 * Real four-condition enforcement against MockK-mocked network elements (no live kDisco
	 * simulation, no Koin). Mirrors the mocking strategy in
	 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPortTest].
	 */
	@Nested
	@DisplayName("real network behavior")
	inner class RealNetworkBehavior {
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

		/**
		 * Stateful mock switch: `lock()`/`unlock()` flip the value `locked` reports, and `conf`
		 * is stubbed (defaults to [RailSwitch.Conf.MAIN], i.e. `SwitchPosition.PLUS`). C3 reads
		 * `conf` to verify the switch is in the route's required position.
		 */
		private fun switch(
			name: String,
			conf: RailSwitch.Conf = RailSwitch.Conf.MAIN
		): DynamicRailSwitch {
			var locked = false
			return mockk<DynamicRailSwitch>(relaxed = true).also {
				every { it.name } returns name
				every { it.conf } returns conf
				every { it.locked } answers { locked }
				every { it.lock() } answers { locked = true }
				every { it.unlock() } answers { locked = false }
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
		 * Builds a [SimulationEnvironment] + its [PathReservationRegistry] (a real instance,
		 * shared with a stub [PathReservationService] whose `releasePath` mirrors
		 * `DefaultPathReservationService.releasePath`'s registry-cleanup contract).
		 */
		@Suppress("UNCHECKED_CAST")
		private fun env(
			blocks: Collection<DynamicTrackBlock> = emptyList(),
			switches: Collection<DynamicRailSwitch> = emptyList(),
			semaphores: Collection<DynamicRailSemaphore> = emptyList(),
			releasePathThrows: Boolean = false
		): Pair<SimulationEnvironment, PathReservationRegistry> {
			val cells: List<Cell> = switches.toList() + semaphores.toList()
			val grid = mockk<RailwayNetGrid<Cell>>(relaxed = true)
			every { grid.cols } returns (cells.size + 1)
			every { grid.rows } returns 1
			cells.forEachIndexed { idx, cell -> every { grid.getCellAt(idx, 0) } returns cell }

			val graph = mockk<ExtendedUnorientedGraph<Point, DynamicTrackBlock, Cell.Segment>>(relaxed = true)
			every { graph.values() } returns blocks

			val registry = PathReservationRegistry(mockk(relaxed = true))

			val reservationService = mockk<PathReservationService>(relaxed = true)
			if (releasePathThrows) {
				every { reservationService.releasePath(any()) } throws IllegalStateException("releasePath boom")
			} else {
				every { reservationService.releasePath(any()) } answers {
					val trainId = firstArg<String>()
					val released = registry.getBlocks(trainId)
					registry.getSwitches(trainId).forEach { it.unlock() }
					registry.unregister(trainId)
					registry.unregisterSwitches(trainId)
					released
				}
			}

			val routingServices = mockk<RoutingServices>(relaxed = true)
			every { routingServices.getPathReservationService() } returns reservationService

			val e = mockk<SimulationEnvironment>(relaxed = true)
			every { e.getRailWayNetGrid() } returns grid
			every { e.getGraph() } returns graph
			every { e.getRoutingServices() } returns routingServices
			return e to registry
		}

		private val plusSetting = SwitchSetting(SwitchId("V1"), SwitchPosition.PLUS)

		@Test
		@DisplayName("grants, locks block+switch, and clears the entry signal")
		fun grantsAndLocksRoute() {
			val u1 = block("U1")
			val v1 = switch("V1")
			val s1 = semaphore("S1", Signal.STOP)
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(s1))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Granted::class)
			assertThat(registry.getOwner(u1)).isEqualTo("T1")
			assertThat(v1.locked).isTrue()
			assertThat(s1.signal).isEqualTo(Signal.FREE)
		}

		@Test
		@DisplayName("denies when a block is physically occupied by another train")
		fun deniesOccupiedBlock() {
			val u1 = block("U1", occupantName = "T-other")
			val (e, registry) = env(blocks = listOf(u1), semaphores = listOf(semaphore("S1")))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(from = SignalId("S1"), to = SignalId("S2"), blocks = listOf(BlockId("U1")), running = emptyList())

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			val reason = (response as InterlockingFacade.RouteResponse.Denied).reason
			assertThat(reason).isNotEmpty()
			assertThat(registry.getOwner(u1)).isNull()
		}

		@Test
		@DisplayName("denies and rolls back reserved blocks when a switch is locked by another train (I2)")
		fun deniesLockedSwitchAndRollsBackBlocks() {
			val u1 = block("U1")
			val v1 = switch("V1", conf = RailSwitch.Conf.MAIN) // matches plusSetting (PLUS -> MAIN)
			v1.lock() // already locked by another train
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(semaphore("S1")))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			// Blocks were reserved before the switch lock failed; the atomic rollback must free them.
			assertThat(registry.getOwner(u1)).isNull()
		}

		@Test
		@DisplayName("second train requesting an already-locked block is denied (conflict exclusion)")
		fun deniesConflictingBlockRequest() {
			val u1 = block("U1")
			val s1 = semaphore("S1")
			val (e, registry) = env(blocks = listOf(u1), semaphores = listOf(s1))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(from = SignalId("S1"), to = SignalId("S2"), blocks = listOf(BlockId("U1")), running = emptyList())
			val firstGrant = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)
			assertThat(firstGrant).isInstanceOf(InterlockingFacade.RouteResponse.Granted::class)

			val secondResponse = facade.requestRoute("T2", SignalId("S1"), route, Aspect.Volno)

			assertThat(secondResponse).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat(registry.getOwner(u1)).isEqualTo("T1")
		}

		@Test
		@DisplayName("releaseRoute frees blocks and unlocks switches, allowing a second train to be granted")
		fun releaseFreesResourcesForNextTrain() {
			val u1 = block("U1")
			val v1 = switch("V1")
			val s1 = semaphore("S1")
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(s1))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)
			facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			facade.releaseRoute("T1", SignalId("S1"))

			assertThat(registry.getOwner(u1)).isNull()
			assertThat(v1.locked).isFalse()
			assertThat(s1.signal).isEqualTo(Signal.STOP)

			val secondResponse = facade.requestRoute("T2", SignalId("S1"), route, Aspect.Volno)
			assertThat(secondResponse).isInstanceOf(InterlockingFacade.RouteResponse.Granted::class)
			assertThat(registry.getOwner(u1)).isEqualTo("T2")
		}

		@Test
		@DisplayName("unknown block name is denied, not silently treated as free")
		fun deniesUnknownBlockName() {
			val (e, registry) = env(semaphores = listOf(semaphore("S1")))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(from = SignalId("S1"), to = SignalId("S2"), blocks = listOf(BlockId("NOPE")), running = emptyList())

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).isNotEmpty()
		}

		@Test
		@DisplayName("Granted response carries the cleared aspect and the locked route")
		fun grantedResponseIncludesClearedAspectAndLockedRoute() {
			val u1 = block("U1")
			val v1 = switch("V1")
			val s1 = semaphore("S1", Signal.STOP)
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(s1))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Granted::class)
			val granted = response as InterlockingFacade.RouteResponse.Granted
			assertThat(granted.clearedAspect).isEqualTo(Aspect.Volno)
			assertThat(granted.lockedRoute).isEqualTo(route)
		}

		@Test
		@DisplayName("C2: denies (and rolls back locks) when the cleared aspect has no Signal equivalent")
		fun grantedRequiresSignalActuallyCleared() {
			val u1 = block("U1")
			val v1 = switch("V1")
			val s1 = semaphore("S1", Signal.STOP)
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(s1))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)

			// Vystraha.toSignal() == null → signal cannot be cleared → Granted must not be returned.
			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Vystraha)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			// Locks acquired before the signal-clear failure are rolled back.
			assertThat(registry.getOwner(u1)).isNull()
			assertThat(v1.locked).isFalse()
			// Signal was never cleared.
			assertThat(s1.signal).isEqualTo(Signal.STOP)
		}

		@Test
		@DisplayName("C3: denies when a running switch is not in the required position")
		fun deniesWhenRunningSwitchInWrongPosition() {
			val u1 = block("U1")
			val v1 = switch("V1", conf = RailSwitch.Conf.BRANCH) // route requires PLUS -> MAIN; actual is BRANCH
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(semaphore("S1")))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).contains("poloze")
			// Denied at condition 2, before any lock is acquired.
			assertThat(v1.locked).isFalse()
			assertThat(registry.getOwner(u1)).isNull()
		}

		@Test
		@DisplayName("C3: denies when a flank switch is not in the required position")
		fun deniesWhenFlankSwitchInWrongPosition() {
			val u1 = block("U1")
			val v1 = switch("V1", conf = RailSwitch.Conf.BRANCH) // flank requires PLUS -> MAIN; actual is BRANCH
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(semaphore("S1")))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = emptyList(),
					flank = listOf(plusSetting)
				)

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).contains("Odvratná")
		}

		@Test
		@DisplayName("C4: releaseRoute resets the tracked entry signal, ignoring the audit-only exitSignal")
		fun releaseRouteResetsTrackedEntrySignalIgnoringExitSignal() {
			val u1 = block("U1")
			val v1 = switch("V1")
			val s1 = semaphore("S1", Signal.STOP)
			val s2 = semaphore("S2", Signal.STOP)
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(s1, s2))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)
			facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)
			assertThat(s1.signal).isEqualTo(Signal.FREE) // entry cleared

			// Caller passes the EXIT signal S2 as exitSignal; the kernel must still reset S1 (the
			// entry it tracked), not S2.
			facade.releaseRoute("T1", SignalId("S2"))

			assertThat(s1.signal).isEqualTo(Signal.STOP)
			assertThat(s2.signal).isEqualTo(Signal.STOP)
		}

		@Test
		@DisplayName("I1: releaseRoute propagates releasePath failures instead of swallowing them")
		fun releaseRouteDoesNotSwallowExceptions() {
			val u1 = block("U1")
			val v1 = switch("V1")
			val s1 = semaphore("S1", Signal.STOP)
			val (e, registry) =
				env(
					blocks = listOf(u1),
					switches = listOf(v1),
					semaphores = listOf(s1),
					releasePathThrows = true
				)
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)
			// Grant succeeds — releasePath is not called on a successful grant.
			facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			// releaseRoute must let the releasePath failure propagate (no silent swallow).
			assertThrows<IllegalStateException> {
				facade.releaseRoute("T1", SignalId("S1"))
			}
		}

		@Test
		@DisplayName("I3: denies when the entry/route.from signal is unknown in the network")
		fun deniesWhenEntrySignalUnknownInNetwork() {
			val u1 = block("U1")
			val (e, registry) = env(blocks = listOf(u1)) // no semaphores → "S1" unknown
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = emptyList()
				)

			val response = facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			val reason = (response as InterlockingFacade.RouteResponse.Denied).reason
			assertThat(reason).contains("návěstidlo")
		}

		@Test
		@DisplayName("I4: releaseRoute for an unknown train does not reset any signal or other train's locks")
		fun releaseRouteForUnknownTrainDoesNotResetAnySignal() {
			val u1 = block("U1")
			val v1 = switch("V1")
			val s1 = semaphore("S1", Signal.STOP)
			val (e, registry) = env(blocks = listOf(u1), switches = listOf(v1), semaphores = listOf(s1))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = listOf(plusSetting)
				)
			facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)
			assertThat(s1.signal).isEqualTo(Signal.FREE)

			// Wrong trainId — has no tracked cleared signal, so nothing is reset.
			facade.releaseRoute("T-typo", SignalId("S1"))

			assertThat(s1.signal).isEqualTo(Signal.FREE) // T1's cleared signal untouched
			assertThat(registry.getOwner(u1)).isEqualTo("T1") // T1's locks untouched
		}

		@Test
		@DisplayName("I5: denies when entrySignal differs from route.from")
		fun deniesWhenEntrySignalDiffersFromRouteFrom() {
			val u1 = block("U1")
			val (e, registry) = env(blocks = listOf(u1), semaphores = listOf(semaphore("S1"), semaphore("S2")))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1")),
					running = emptyList()
				)

			val response = facade.requestRoute("T1", SignalId("S2"), route, Aspect.Volno) // entry != from

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			val reason = (response as InterlockingFacade.RouteResponse.Denied).reason
			assertThat(reason).contains("neodpovídá počátku cesty")
			assertThat(registry.getOwner(u1)).isNull() // no locks acquired
		}

		@Test
		@DisplayName("I7: releaseRoute releases the entire route atomically (all blocks and switches)")
		fun releaseRouteReleasesEntireRouteAtomic() {
			val u1 = block("U1")
			val u2 = block("U2")
			val v1 = switch("V1", conf = RailSwitch.Conf.MAIN) // PLUS
			val v2 = switch("V2", conf = RailSwitch.Conf.BRANCH) // MINUS
			val s1 = semaphore("S1", Signal.STOP)
			val (e, registry) = env(blocks = listOf(u1, u2), switches = listOf(v1, v2), semaphores = listOf(s1))
			val facade = DefaultInterlockingFacade(e, registry)
			val route =
				TrainRoute(
					from = SignalId("S1"),
					to = SignalId("S2"),
					blocks = listOf(BlockId("U1"), BlockId("U2")),
					running = listOf(plusSetting, SwitchSetting(SwitchId("V2"), SwitchPosition.MINUS))
				)
			facade.requestRoute("T1", SignalId("S1"), route, Aspect.Volno)
			assertThat(registry.getOwner(u1)).isEqualTo("T1")
			assertThat(registry.getOwner(u2)).isEqualTo("T1")
			assertThat(v1.locked).isTrue()
			assertThat(v2.locked).isTrue()
			assertThat(s1.signal).isEqualTo(Signal.FREE)

			facade.releaseRoute("T1", SignalId("S1"))

			// All-or-nothing MVP: every block and switch for T1 is released at once.
			assertThat(registry.getOwner(u1)).isNull()
			assertThat(registry.getOwner(u2)).isNull()
			assertThat(v1.locked).isFalse()
			assertThat(v2.locked).isFalse()
			assertThat(s1.signal).isEqualTo(Signal.STOP)
		}

		// ── requestRouteByEndpoints (SP3.5, Issue #573) ─────────────────────

		@Test
		@DisplayName(
			"SP3.5: requestRouteByEndpoints grants and reports the true reserved block count, " +
				"including unnamed blocks"
		)
		fun requestRouteByEndpointsGrantsWithAccurateBlockCountForUnnamedBlocks() {
			val (e, registry) = env(semaphores = listOf(semaphore("S1"), semaphore("S2")))
			val facade = DefaultInterlockingFacade(e, registry)
			val named = block("U1")
			val unnamed = mockk<DynamicTrackBlock>(relaxed = true).also { every { it.name } returns null }
			val reservationService = e.getRoutingServices().getPathReservationService()
			every {
				reservationService.reservePath(any(), any(), any(), any())
			} returns PathReservationService.ReservationResult.Success(listOf(named, unnamed))

			val response = facade.requestRouteByEndpoints("T1", "S1", "S2")

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Granted::class)
			val granted = response as InterlockingFacade.RouteResponse.Granted
			// Regression test: mapNotNull{it.name} used to silently drop the unnamed block,
			// undercounting the block list (and therefore blocksCount) reported to the caller.
			assertThat(granted.lockedRoute.blocks.size).isEqualTo(2)
		}

		@Test
		@DisplayName("SP3.5: requestRouteByEndpoints denies an unknown fromEndpointName")
		fun requestRouteByEndpointsDeniesUnknownFromEndpoint() {
			val (e, registry) = env(semaphores = listOf(semaphore("S2")))
			val facade = DefaultInterlockingFacade(e, registry)

			val response = facade.requestRouteByEndpoints("T1", "NOPE", "S2")

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).contains("NOPE")
		}

		@Test
		@DisplayName("SP3.5: requestRouteByEndpoints denies when the reservation service reports a conflict")
		fun requestRouteByEndpointsDeniesOnConflict() {
			val (e, registry) = env(semaphores = listOf(semaphore("S1"), semaphore("S2")))
			val facade = DefaultInterlockingFacade(e, registry)
			val u1 = block("U1")
			val reservationService = e.getRoutingServices().getPathReservationService()
			every {
				reservationService.reservePath(any(), any(), any(), any())
			} returns PathReservationService.ReservationResult.Conflict(u1, "T-other")

			val response = facade.requestRouteByEndpoints("T1", "S1", "S2")

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).contains("T-other")
		}

		@Test
		@DisplayName("SP3.5: requestRouteByEndpoints denies an unknown toEndpointName")
		fun requestRouteByEndpointsDeniesUnknownToEndpoint() {
			val (e, registry) = env(semaphores = listOf(semaphore("S1")))
			val facade = DefaultInterlockingFacade(e, registry)

			val response = facade.requestRouteByEndpoints("T1", "S1", "NOPE")

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).contains("NOPE")
		}

		@Test
		@DisplayName("SP3.5: requestRouteByEndpoints denies when no topological path exists between the endpoints")
		fun requestRouteByEndpointsDeniesWhenNoPathExists() {
			val (e, registry) = env(semaphores = listOf(semaphore("S1"), semaphore("S2")))
			val facade = DefaultInterlockingFacade(e, registry)
			val reservationService = e.getRoutingServices().getPathReservationService()
			every {
				reservationService.reservePath(any(), any(), any(), any())
			} returns PathReservationService.ReservationResult.NoPathExists

			val response = facade.requestRouteByEndpoints("T1", "S1", "S2")

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			assertThat((response as InterlockingFacade.RouteResponse.Denied).reason).contains("No path exists")
		}

		@Test
		@DisplayName("SP3.5: requestRouteByEndpoints denies when every candidate path is blocked")
		fun requestRouteByEndpointsDeniesWhenAllPathsBlocked() {
			val (e, registry) = env(semaphores = listOf(semaphore("S1"), semaphore("S2")))
			val facade = DefaultInterlockingFacade(e, registry)
			val reservationService = e.getRoutingServices().getPathReservationService()
			every {
				reservationService.reservePath(any(), any(), any(), any())
			} returns PathReservationService.ReservationResult.AllPathsBlocked(attemptedPaths = 3)

			val response = facade.requestRouteByEndpoints("T1", "S1", "S2")

			assertThat(response).isInstanceOf(InterlockingFacade.RouteResponse.Denied::class)
			val reason = (response as InterlockingFacade.RouteResponse.Denied).reason
			assertThat(reason).contains("All paths blocked")
			assertThat(reason).contains("attempts: 3")
		}
	}
}
