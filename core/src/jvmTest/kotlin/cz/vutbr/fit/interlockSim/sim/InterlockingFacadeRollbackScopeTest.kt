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
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
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
 * `lockRouteAtomic` registers the route's blocks, then locks its switches. When a switch turns out
 * to be locked by another train it rolls the blocks back. The rollback used
 * `PathReservationRegistry.unregister(trainId)`, which drops EVERY registry entry of the train,
 * including the blocks it held from an earlier route (and its `PathInfo`). Those blocks stay
 * physically RESERVED while the registry no longer names an owner, the same registry/physical
 * divergence as Issue #1025 (see `ReservePathConflictRollbackScopeTest`).
 *
 * ## The shape
 *
 * The train holds route 1. A second route request for the same train (different blocks) is denied
 * because a switch on it is locked by another train. Route 1 must be exactly as it was.
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
	@DisplayName("blocks held from an earlier route survive a route denied by a switch locked by another train")
	fun heldBlocksSurviveSwitchLockDenial() {
		val train = "Train #1051"
		val other = "Train #other"

		// Given: the train holds A -> doB1.
		val first = service.reservePath(train, inA, doB1)
		assertThat(first).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val held = (first as PathReservationService.ReservationResult.Success).reservedBlocks
		assertThat(held.isNotEmpty()).withMessage("the first route must reserve at least one block").isTrue()
		val heldPathInfo = registry.getPathInfo(train)
		assertThat(heldPathInfo).isNotNull()

		// And: a second, disjoint route for the same train whose running switch another train has locked.
		val next = firstForwardBlockOf(inA, inB, excluding = held)
		next.name = "route2-block"
		val switch = context.getRailWayNetGrid().cellsOfType<DynamicRailSwitch>().first { it.name == "vB" }
		registry.registerSwitches(other, listOf(switch))
		assertThat(switch.locked).withMessage("the switch must be locked by the other train").isTrue()
		val facade = DefaultInterlockingFacade(context, registry)
		val route =
			TrainRoute(
				from = SignalId("zA"),
				to = SignalId("zB"),
				running = listOf(SwitchSetting(SwitchId("vB"), SwitchPosition.PLUS)),
				blocks = listOf(BlockId("route2-block"))
			)

		// When: the train requests the second route.
		val response = facade.requestRoute(train, SignalId("zA"), route, Aspect.Volno)

		// Then: the request is denied by the switch lock (the arm under test, not an earlier check).
		assertThat(response).isInstanceOf<InterlockingFacade.RouteResponse.Denied>()
		assertThat((response as InterlockingFacade.RouteResponse.Denied).reason)
			.withMessage("the arrangement must hit the switch-lock denial, or the test is vacuous")
			.isEqualTo("Switch vB is locked")

		// And: the second route's own block was rolled back, physically and in the registry.
		assertThat(next.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(next.trainName).isNull()
		assertThat(registry.getOwner(next)).isNull()

		// And: the blocks the train already held keep their physical state and registry owner.
		held.forEach { block ->
			assertThat(block.getState())
				.withMessage("held block $block must stay RESERVED after the facade rollback")
				.isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).isEqualTo(train)
			assertThat(registry.getOwner(block))
				.withMessage("held block $block must keep its registry owner")
				.isEqualTo(train)
		}
		assertThat(registry.getPathInfo(train))
			.withMessage("the held route's PathInfo must survive")
			.isSameInstanceAs(heldPathInfo)
	}

	/** Every block of the [start] -> [target] route's first topological path, in path order. */
	private fun routeBlocksOf(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator
	): List<DynamicTrackBlock> =
		context
			.getRoutingServices()
			.getTopologyNavigator()
			.findAllTopologicalPaths(start, target)
			.first()
			.map { it.getTrackBlock() }
			.filterIsInstance<DynamicTrackBlock>()
			.distinct()

	/** The first block of the [start] -> [target] route that is not in [excluding], in path order. */
	private fun firstForwardBlockOf(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		excluding: List<DynamicTrackBlock>
	): DynamicTrackBlock = routeBlocksOf(start, target).first { it !in excluding }
}
