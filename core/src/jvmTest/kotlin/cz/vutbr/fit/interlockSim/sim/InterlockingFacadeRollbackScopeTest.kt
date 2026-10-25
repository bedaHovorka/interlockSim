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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.lang.vocab.Aspect
import cz.vutbr.fit.interlockSim.lang.vocab.BlockId
import cz.vutbr.fit.interlockSim.lang.vocab.SignalId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchId
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchPosition
import cz.vutbr.fit.interlockSim.lang.vocab.SwitchSetting
import cz.vutbr.fit.interlockSim.lang.vocab.TrainRoute
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSemaphore
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.paths.PathInfo
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEvent
import cz.vutbr.fit.interlockSim.objects.tracks.BlockOccupancyEventType
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.assertReservationSuccess
import cz.vutbr.fit.interlockSim.testutil.routeBlocksOf
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import cz.vutbr.fit.interlockSim.testutil.withMessage
import cz.vutbr.fit.interlockSim.util.cellsOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #1051: a failed route setup in [DefaultInterlockingFacade] must roll back only what the
 * failing call registered, never the blocks the train already holds.
 *
 * ## The mechanism this pins
 *
 * `lockRouteAtomic` registers the route's blocks, then locks its switches. When the route turns out
 * to be unavailable it rolls the blocks back. The rollback used
 * `PathReservationRegistry.unregister(trainId)`, which drops EVERY registry entry of the train,
 * including the blocks it held from an earlier route (and its `PathInfo`). Those blocks stay
 * physically RESERVED while the registry no longer names an owner, the same registry/physical
 * divergence as Issue #1025 (see `ReservePathConflictRollbackScopeTest`). The un-clearable-signal
 * arm had the same defect with `PathReservationService.releasePath(trainId)`.
 *
 * ## The shape
 *
 * The train holds route 1. A second route request for the same train (different blocks) fails.
 * Route 1 must be exactly as it was. Every scenario is assembled from the same parts:
 * [holdRouteViaService] / [namedHeldRouteBlocks] + [heldRoute] hold route 1, [secondRoute]
 * builds the disjoint failing route, and [assertDeniedWithReason] / [assertReleased] / [assertHeld]
 * / [assertPathInfoUnchanged] pin the invariants.
 */
@Tag("integration-test")
@DisplayName("Issue #1051 — a failed facade route setup rolls back only this call's blocks")
class InterlockingFacadeRollbackScopeTest : KoinTestBase() {
	private lateinit var context: DefaultSimulationContext
	private lateinit var registry: PathReservationRegistry
	private lateinit var service: PathReservationService
	private lateinit var inA: DynamicPathSeparator
	private lateinit var doB1: DynamicPathSeparator
	private lateinit var inB: DynamicPathSeparator

	@BeforeEach
	fun setUp() {
		context = TestFixtures.newShuntingSimulationContext().tracked()
		service = context.getRoutingServices().getPathReservationService()
		registry = context.scope.get<PathReservationRegistry>()
		inA = context.separatorAt(11, 8) // vyhybna InOut A
		doB1 = context.separatorAt(25, 8) // vyhybna signal doB1
		inB = context.separatorAt(30, 8) // vyhybna InOut B
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("blocks held from an earlier service route survive a route denied by a locked switch")
	fun heldBlocksSurviveSwitchLockDenial() {
		val train = "Train #1051"
		val held = holdRouteViaService(train)
		val heldPathInfo = registry.getPathInfo(train)
		assertThat(heldPathInfo).isNotNull()

		val (next, route) = secondRoute(excluding = held)
		lockSwitchByOther("Train #other")
		val facade = DefaultInterlockingFacade(context, registry)

		// When: the train requests the second route.
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)

		// Then: denied by the switch lock (the arm under test, not an earlier check).
		assertDeniedWithReason(response, "Switch vB is locked or reserved by another train")
		assertRetryable(response, retryable = true)

		// And: the second route's own block was rolled back, physically and in the registry.
		assertReleased(next)

		// And: the blocks the train already held keep their physical state and registry owner.
		assertHeld(held, train)
		assertPathInfoUnchanged(train, heldPathInfo)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("blocks held from an earlier facade route survive the same denial")
	fun facadeHeldBlocksSurviveSwitchLockDenial() {
		val train = "Train #1051"
		val held = namedHeldRouteBlocks()
		val (next, route) = secondRoute(excluding = held)
		lockSwitchByOther("Train #other")
		val facade = DefaultInterlockingFacade(context, registry)

		// Given: the train holds A -> doB1, granted through the facade itself. A facade grant
		// locks blocks and switches but never creates a PathInfo (only the service does), so the
		// invariants here are the physical state and the registry ownership.
		val grant = facade.requestRoute(train, SignalId("zA"), heldRoute(held), Aspect.Volno)
		assertThat(grant)
			.withMessage("the held route must be granted")
			.isInstanceOf<InterlockingFacade.RouteResponse.Granted>()

		// When: a second, disjoint route for the same train is denied by a switch another train locked.
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)

		assertDeniedWithReason(response, "Switch vB is locked or reserved by another train")
		assertReleased(next)
		assertHeld(held, train)
	}

	/**
	 * Issue #961: the facade locks a route through the registry and `setUpPath`, not through
	 * `PathReservationService.reservePath`, so no reservation event is ever published for its blocks.
	 * Its rollback must therefore publish no release event either: a release without a matching
	 * reserve sends the per-event reservation counters negative (Issue #1081).
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("a facade rollback publishes no release for a block it never announced as reserved")
	fun facadeRollbackPublishesNoEventForAnUnannouncedBlock() {
		val train = "Train #1051"
		val held = holdRouteViaService(train)
		val (next, route) = secondRoute(excluding = held)
		lockSwitchByOther("Train #other")
		val facade = DefaultInterlockingFacade(context, registry)
		val events = mutableListOf<BlockOccupancyEvent>()
		service.addBlockOccupancyListener { event -> events += event }

		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)

		assertDeniedWithReason(response, "Switch vB is locked or reserved by another train")
		assertReleased(next)
		assertThat(events.filter { it.type == BlockOccupancyEventType.BLOCK_RESERVED })
			.withMessage("the facade never announces a block it locks as reserved")
			.isEmpty()
		assertThat(events.filter { it.type == BlockOccupancyEventType.BLOCK_RELEASED })
			.withMessage("so its rollback must not announce one as released")
			.isEmpty()
		assertHeld(held, train)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("a route denied before registration changes nothing")
	fun occupiedRouteBlockDenialLeavesHeldRouteUntouched() {
		val train = "Train #1051"
		val held = holdRouteViaService(train)
		val heldPathInfo = registry.getPathInfo(train)
		assertThat(heldPathInfo).isNotNull()

		val (next, route) = secondRoute(excluding = held)
		next.setUpPath(inB, "Train #blocker") // physically RESERVED: condition 1 denies before registration
		val facade = DefaultInterlockingFacade(context, registry)

		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)

		assertDeniedWithReason(response, "Track section route2-block occupied by train Train #blocker")
		assertRetryable(response, retryable = true)

		// Nothing was registered or released: the blocker keeps the block, the train keeps route 1.
		assertThat(next.getState()).isEqualTo(TrackFacility.State.RESERVED)
		assertThat(next.trainName).isEqualTo("Train #blocker")
		assertThat(registry.getOwner(next)).isNull()
		assertHeld(held, train)
		assertPathInfoUnchanged(train, heldPathInfo)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("after the switch lock clears, the denied route is granted on retry")
	fun retryAfterSwitchLockDenialIsGranted() {
		val train = "Train #1051"
		val held = holdRouteViaService(train)

		val (next, route) = secondRoute(excluding = held)
		val switch = lockSwitchByOther("Train #other")
		// Train #other also holds a block next to vB, so its lock is live: the rollback leaves it alone
		// (a lock whose owner holds no block next to the switch is reclaimed, see below).
		registry.registerAtomic("Train #other", listOf(blockNextTo(switch, excluding = held + next)))
		val facade = DefaultInterlockingFacade(context, registry)

		val first = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)
		assertDeniedWithReason(first, "Switch vB is locked or reserved by another train")
		assertReleased(next)

		// When: the other train releases the switch and the same route is requested again.
		assertThat(registry.unregisterSwitch("Train #other", switch)).isTrue()
		val second = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)

		// Then: the retry is granted and the train holds both routes.
		assertThat(second).withMessage("the retry must be granted").isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
		assertThat(registry.getOwner(next)).isEqualTo(train)
		assertThat(switch.locked).isTrue()
		assertThat(registry.getSwitchOwner(switch)).isEqualTo(train)
		assertHeld(held, train)
		assertThat(registry.getPathInfo(train)).isNotNull()
	}

	/**
	 * Issue #961: the facade rollback now goes through `BlockRollbackStep.rollbackBlock`, which
	 * reclaims a switch lock left stale by the released block, as every block release has done since
	 * Issue #1065. Train #other owns vB but holds no block next to it, so nothing protects its lock.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("a facade rollback reclaims a switch lock whose owner holds no block next to it")
	fun facadeRollbackReclaimsAStaleForeignSwitchLock() {
		val train = "Train #1051"
		val held = holdRouteViaService(train)
		val (next, route) = secondRoute(excluding = held)
		val switch = lockSwitchByOther("Train #other")
		assertThat(switch in next.ends())
			.withMessage("the rolled-back block must border vB, or the reclaim is not exercised")
			.isTrue()
		val facade = DefaultInterlockingFacade(context, registry)

		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)

		assertDeniedWithReason(response, "Switch vB is locked or reserved by another train")
		assertReleased(next)
		assertThat(registry.getSwitchOwner(switch)).withMessage("owner of the stale lock").isNull()
		assertThat(switch.locked).withMessage("the stale lock must be released").isFalse()
		assertHeld(held, train)
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("an un-clearable signal rolls back only the failed call's locks")
	fun unClearableSignalRollsBackOnlyTheFailedCall() {
		val train = "Train #1051"
		val held = holdRouteViaService(train)
		val heldPathInfo = registry.getPathInfo(train)
		assertThat(heldPathInfo).isNotNull()

		val (next, route) = secondRoute(excluding = held)
		val facade = DefaultInterlockingFacade(context, registry)
		// The held route's entry semaphore zA is cleared and in the service's signal ledger.
		// The old code reset it here; the scoped rollback must leave both untouched.
		val entrySignal = context.getRailWayNetGrid().cellsOfType<DynamicRailSemaphore>().first { it.name == "zA" }
		val heldSignal = entrySignal.signal
		assertThat(service.hasClearedSignals(train)).isTrue()

		// When: the second route is requested with an aspect no Signal maps to (PosunDovolen),
		// so clearSignal fails AFTER the route was locked.
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.PosunDovolen)

		// Then: denied (a permanent output defect), with only this call's locks rolled back.
		assertDeniedWithReason(response, "Signal zA cannot be cleared (unknown signal or invalid signal aspect)")
		assertRetryable(response, retryable = false)
		assertReleased(next)
		val switch = context.getRailWayNetGrid().cellsOfType<DynamicRailSwitch>().first { it.name == "vB" }
		assertThat(switch.locked).withMessage("the failed call's switch must be unlocked again").isFalse()
		assertThat(registry.getSwitchOwner(switch)).isNull()
		// And: the held route's signal authorisation survives — the old whole-train release
		// reset zA to STOP and dropped it from the ledger (the other half of the #1051 damage).
		assertThat(entrySignal.signal).withMessage("the held route's entry signal must stay cleared").isEqualTo(heldSignal)
		assertThat(service.hasClearedSignals(train)).isTrue()
		assertHeld(held, train)
		assertPathInfoUnchanged(train, heldPathInfo)
	}

	/**
	 * Issue #1173: `lockSwitches` admits a switch the train already owns, so the failed call's
	 * rollback must release only the switches this call newly locked. It used to release every
	 * switch the failed route listed, which dropped an EARLIER route's flank lock on a switch the
	 * failed route listed again -- the registry keeps no per-route count for switches.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("an un-clearable route that re-lists an earlier route's flank switch keeps that flank lock")
	fun unClearableRouteKeepsAnEarlierRoutesFlankLock() {
		val train = "Train #1173"
		val held = namedHeldRouteBlocks()
		val (next, route) = secondRoute(excluding = held)
		val switch = switchVB()
		val facade = DefaultInterlockingFacade(context, registry)

		// Given: route 1 (A -> doB1) is granted with vB as its flank switch.
		val grant = facade.requestRoute(train, SignalId("zA"), heldRoute(held).copy(flank = listOf(VB_PLUS)), Aspect.Volno)
		assertThat(grant)
			.withMessage("the held route must be granted")
			.isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
		assertThat(registry.isFlankProtected(switch)).withMessage("route 1 flank-locks vB").isTrue()

		// When: route 2 lists vB again (as a running switch) and fails AFTER locking (PosunDovolen).
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.PosunDovolen)

		// Then: only route 2's own block is rolled back; vB stays route 1's flank lock.
		assertDeniedWithReason(response, "Signal zA cannot be cleared (unknown signal or invalid signal aspect)")
		assertReleased(next)
		assertThat(registry.getSwitchOwner(switch)).withMessage("owner of route 1's flank switch").isEqualTo(train)
		assertThat(switch.locked).withMessage("route 1's flank switch must stay locked").isTrue()
		assertThat(registry.isFlankProtected(switch)).withMessage("route 1's flank claim").isTrue()
		assertHeld(held, train)
	}

	/**
	 * Issue #1218 (flips the Issue #1173 open point (c) pin): a plain (running) claim that a failed
	 * route lists again as FLANK is promoted to a flank claim by `registerFlankSwitches`; the
	 * rollback keeps the switch (it is not this call's new lock) and demotes the claim back to a
	 * plain claim, so the failed route leaves route 1's claim exactly as it was. Route 1 still holds
	 * a block next to vB, so the demoted claim is live and the block rollback does not reclaim it.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("a running claim re-listed as flank by an un-clearable route stays held and is demoted to running")
	fun unClearableRouteDemotesAPromotedRunningClaim() {
		val train = "Train #1173"
		// The failing route's block is doB1 -> vB, the first A -> B block after the A -> doB1 route.
		val (next, failingRunningRoute) = secondRoute(excluding = context.routeBlocksOf(inA, doB1))
		val switch = switchVB()
		val heldBlock = blockNextTo(switch, excluding = listOf(next))
		heldBlock.name = "route1-block"
		val facade = DefaultInterlockingFacade(context, registry)

		// Given: route 1 holds a block next to vB and runs over vB: a plain, live claim.
		val heldRoute =
			TrainRoute(
				from = SignalId("zA"),
				to = SignalId("zB"),
				running = listOf(VB_PLUS),
				blocks = listOf(BlockId("route1-block"))
			)
		val grant = facade.requestRoute(train, SignalId("zA"), heldRoute, Aspect.Volno)
		assertThat(grant)
			.withMessage("the held route must be granted")
			.isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
		assertThat(registry.isFlankProtected(switch)).withMessage("route 1's claim is a running claim").isFalse()

		// When: route 2 lists vB as FLANK and fails after locking.
		val route = failingRunningRoute.copy(running = emptyList(), flank = listOf(VB_PLUS))
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.PosunDovolen)

		// Then: vB stays held by the train, as the running claim route 1 made.
		assertDeniedWithReason(response, "Signal zA cannot be cleared (unknown signal or invalid signal aspect)")
		assertReleased(next)
		assertThat(registry.getSwitchOwner(switch)).withMessage("owner of route 1's running switch").isEqualTo(train)
		assertThat(switch.locked).withMessage("route 1's running switch must stay locked").isTrue()
		assertThat(registry.isFlankProtected(switch))
			.withMessage("promoted by the failed route, demoted again by its rollback (#1218)")
			.isFalse()
		assertHeld(listOf(heldBlock), train)
	}

	/**
	 * Issue #1218: the demote runs with the switch rollback, BEFORE the block rollback, so a demoted
	 * claim that protects nothing is then reclaimed by the block rollback like any other stale claim.
	 * Route 1 lists vB as a running switch but holds no block next to it (`TrainRoute` does not
	 * require its blocks to bound its running switches), so its plain claim is stale as soon as
	 * route 2's block next to vB is rolled back. Left promoted, the flank claim would be immune to
	 * that reclaim and vB would stay locked until the train's whole-route release.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("a demoted claim that bounds no held block is reclaimed by the rollback and frees the switch")
	fun demotedStaleClaimIsReclaimedByTheBlockRollback() {
		val train = "Train #1218"
		val held = namedHeldRouteBlocks()
		val (next, failingRunningRoute) = secondRoute(excluding = held)
		val switch = switchVB()
		assertThat(held.none { switch in it.ends() })
			.withMessage("route 1 must hold no block next to vB, or its claim is live and nothing is reclaimed")
			.isTrue()
		assertThat(switch in next.ends())
			.withMessage("the rolled-back block must border vB, or the reclaim is not exercised")
			.isTrue()
		// The other train's block, on vB's far side; named before the facade indexes the blocks.
		val otherBlock = blockNextTo(switch, excluding = held + next)
		otherBlock.name = "other-block"
		val facade = DefaultInterlockingFacade(context, registry)

		// Given: route 1 (A -> doB1) runs over vB without holding a block next to it: a plain claim.
		val grant =
			facade.requestRoute(train, SignalId("zA"), heldRoute(held).copy(running = listOf(VB_PLUS)), Aspect.Volno)
		assertThat(grant)
			.withMessage("the held route must be granted")
			.isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
		assertThat(registry.isFlankProtected(switch)).withMessage("route 1's claim is a running claim").isFalse()

		// When: route 2 lists vB as FLANK and fails after locking.
		val route = failingRunningRoute.copy(running = emptyList(), flank = listOf(VB_PLUS))
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.PosunDovolen)

		// Then: the demoted claim bounds no held block, so the block rollback reclaims it.
		assertDeniedWithReason(response, "Signal zA cannot be cleared (unknown signal or invalid signal aspect)")
		assertReleased(next)
		assertThat(registry.getSwitchOwner(switch)).withMessage("owner of the stale demoted claim").isNull()
		assertThat(switch.locked).withMessage("the stale demoted claim must be released").isFalse()
		// And: only the stale switch claim went; route 1's blocks are untouched.
		assertHeld(held, train)
		assertThat(registry.getBlocks(train).toSet()).isEqualTo(held.toSet())

		// When: another train asks for a route over vB from the B side.
		val otherRoute =
			TrainRoute(
				from = SignalId("zB"),
				to = SignalId("zA"),
				running = listOf(VB_PLUS),
				blocks = listOf(BlockId("other-block"))
			)
		val other = facade.requestRoute("Train #other", SignalId("zB"), otherRoute, Aspect.Volno)

		// Then: it is granted and locks vB; the first train keeps exactly route 1's blocks.
		assertThat((other as? InterlockingFacade.RouteResponse.Denied)?.reason)
			.withMessage("denial reason of the other train's route")
			.isNull()
		assertThat(other)
			.withMessage("the freed switch must be lockable by another train")
			.isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
		assertThat(registry.getSwitchOwner(switch)).isEqualTo("Train #other")
		assertThat(switch.locked).isTrue()
		assertThat(registry.getBlocks("Train #other")).isEqualTo(listOf(otherBlock))
		assertHeld(held, train)
		assertThat(registry.getSwitches(train)).isEmpty()
	}

	/**
	 * Issue #1218 ruling: the only promotion is a plain claim becoming a flank claim. A claim that was
	 * ALREADY a flank claim before the failed route stays a flank claim through any later
	 * registration, so the failed route did not promote it and its rollback must not demote it.
	 */
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("an un-clearable route that re-lists an earlier flank switch as flank leaves it flank")
	fun unClearableRouteDoesNotDemoteAnEarlierFlankClaim() {
		val train = "Train #1218"
		val held = namedHeldRouteBlocks()
		val (next, failingRunningRoute) = secondRoute(excluding = held)
		val switch = switchVB()
		val facade = DefaultInterlockingFacade(context, registry)

		// Given: route 1 (A -> doB1) is granted with vB as its flank switch.
		val grant = facade.requestRoute(train, SignalId("zA"), heldRoute(held).copy(flank = listOf(VB_PLUS)), Aspect.Volno)
		assertThat(grant)
			.withMessage("the held route must be granted")
			.isInstanceOf<InterlockingFacade.RouteResponse.Granted>()
		assertThat(registry.isFlankProtected(switch)).withMessage("route 1 flank-locks vB").isTrue()

		// When: route 2 lists vB as FLANK as well and fails after locking.
		val route = failingRunningRoute.copy(running = emptyList(), flank = listOf(VB_PLUS))
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.PosunDovolen)

		// Then: vB stays route 1's flank lock -- not demoted, and so not reclaimed by the block rollback.
		assertDeniedWithReason(response, "Signal zA cannot be cleared (unknown signal or invalid signal aspect)")
		assertReleased(next)
		assertThat(registry.getSwitchOwner(switch)).withMessage("owner of route 1's flank switch").isEqualTo(train)
		assertThat(switch.locked).withMessage("route 1's flank switch must stay locked").isTrue()
		assertThat(registry.isFlankProtected(switch)).withMessage("route 1's flank claim").isTrue()
		assertHeld(held, train)
	}

	/** The vyhybna switch vB, which every second route in this class runs over. */
	private fun switchVB(): DynamicRailSwitch =
		context.getRailWayNetGrid().cellsOfType<DynamicRailSwitch>().first { it.name == "vB" }

	/** Holds A -> doB1 for [train] through the real [PathReservationService]; returns the reserved blocks. */
	private fun holdRouteViaService(train: String): List<DynamicTrackBlock> {
		val held = assertReservationSuccess(service.reservePath(train, inA, doB1)).reservedBlocks
		assertThat(held.isNotEmpty()).withMessage("the held route must reserve at least one block").isTrue()
		return held
	}

	/** Names every block of the A -> doB1 route "held-N" (in path order) so the facade can look them up. */
	private fun namedHeldRouteBlocks(): List<DynamicTrackBlock> =
		context.routeBlocksOf(inA, doB1).onEachIndexed { index, block -> block.name = "held-$index" }

	/** Route 1 as the dispatcher names it: zA -> doB1 over the given blocks, no switches. */
	private fun heldRoute(blocks: List<DynamicTrackBlock>): TrainRoute =
		TrainRoute(
			from = SignalId("zA"),
			to = SignalId("doB1"),
			running = emptyList(),
			blocks = blocks.map { BlockId(it.name!!) }
		)

	/** The second, disjoint route: its only block is the first A -> B block not in [excluding]. */
	private fun secondRoute(excluding: List<DynamicTrackBlock>): Pair<DynamicTrackBlock, TrainRoute> {
		val next = firstForwardBlockOf(inA, inB, excluding)
		next.name = "route2-block"
		val route =
			TrainRoute(
				from = SignalId("zA"),
				to = SignalId("zB"),
				running = listOf(SwitchSetting(SwitchId("vB"), SwitchPosition.PLUS)),
				blocks = listOf(BlockId("route2-block"))
			)
		return next to route
	}

	/** Locks switch vB to [other] so a route over vB is denied by condition 3/4. */
	private fun lockSwitchByOther(other: String): DynamicRailSwitch {
		val switch = context.getRailWayNetGrid().cellsOfType<DynamicRailSwitch>().first { it.name == "vB" }
		registry.registerSwitches(other, listOf(switch))
		assertThat(switch.locked).withMessage("the switch must be locked by $other").isTrue()
		return switch
	}

	/** A block bordering [switch] that is not in [excluding]. */
	private fun blockNextTo(
		switch: DynamicRailSwitch,
		excluding: List<DynamicTrackBlock>
	): DynamicTrackBlock =
		context
			.getGraph()
			.values()
			.filterIsInstance<DynamicTrackBlock>()
			.first { block -> switch in block.ends() && block !in excluding }

	/** The request must be [Denied][InterlockingFacade.RouteResponse.Denied] with exactly [expected] as its reason. */
	private fun assertDeniedWithReason(
		response: InterlockingFacade.RouteResponse,
		expected: String
	) {
		assertThat(response).isInstanceOf<InterlockingFacade.RouteResponse.Denied>()
		assertThat((response as InterlockingFacade.RouteResponse.Denied).reason)
			.withMessage("the arrangement must hit the expected denial, or the test is vacuous")
			.isEqualTo(expected)
	}

	/** The denial cause must be ConditionFailed with the given retry flag. */
	private fun assertRetryable(
		response: InterlockingFacade.RouteResponse,
		retryable: Boolean
	) {
		val denied = response as InterlockingFacade.RouteResponse.Denied
		assertThat(denied.cause).isInstanceOf<InterlockingFacade.RouteResponse.DenialCause.ConditionFailed>()
		assertThat((denied.cause as InterlockingFacade.RouteResponse.DenialCause.ConditionFailed).retryable)
			.isEqualTo(retryable)
	}

	/** A block the failed call rolled back: FREE, unnamed, and owned by nobody in the registry. */
	private fun assertReleased(block: DynamicTrackBlock) {
		assertThat(block.getState())
			.withMessage("the rolled-back block must be FREE")
			.isEqualTo(TrackFacility.State.FREE)
		assertThat(block.trainName).isNull()
		assertThat(registry.getOwner(block)).isNull()
	}

	/** Blocks the train already holds: physically RESERVED for it and owned in the registry. */
	private fun assertHeld(
		blocks: List<DynamicTrackBlock>,
		train: String
	) {
		blocks.forEach { block ->
			assertThat(block.getState())
				.withMessage("held block $block must stay RESERVED after the facade rollback")
				.isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo(train)
			assertThat(registry.getOwner(block))
				.withMessage("held block $block must keep its registry owner")
				.isEqualTo(train)
		}
	}

	/** The held route's [PathInfo] must survive the failed call untouched (same instance). */
	private fun assertPathInfoUnchanged(
		train: String,
		pathInfo: PathInfo?
	) {
		assertThat(registry.getPathInfo(train))
			.withMessage("the held route's PathInfo must survive")
			.isSameInstanceAs(pathInfo)
	}

	/** The first block of the [start] -> [target] route that is not in [excluding], in path order. */
	private fun firstForwardBlockOf(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		excluding: List<DynamicTrackBlock>
	): DynamicTrackBlock = context.routeBlocksOf(start, target).first { it !in excluding }

	private companion object {
		/** vB in the position every route in this class needs it. */
		val VB_PLUS = SwitchSetting(SwitchId("vB"), SwitchPosition.PLUS)
	}
}
