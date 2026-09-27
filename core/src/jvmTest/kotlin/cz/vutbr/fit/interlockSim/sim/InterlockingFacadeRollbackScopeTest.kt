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
		inA = context.separatorAt(ShuntingLoop.COORD_IN_A_X, ShuntingLoop.COORD_IN_A_Y)
		doB1 = context.separatorAt(ShuntingLoop.COORD_SEM_DOB1_X, ShuntingLoop.COORD_SEM_DOB1_Y)
		inB = context.separatorAt(ShuntingLoop.COORD_IN_B_X, ShuntingLoop.COORD_IN_B_Y)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
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
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
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

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
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
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("after the switch lock clears, the denied route is granted on retry")
	fun retryAfterSwitchLockDenialIsGranted() {
		val train = "Train #1051"
		val held = holdRouteViaService(train)

		val (next, route) = secondRoute(excluding = held)
		val switch = lockSwitchByOther("Train #other")
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

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
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
}
