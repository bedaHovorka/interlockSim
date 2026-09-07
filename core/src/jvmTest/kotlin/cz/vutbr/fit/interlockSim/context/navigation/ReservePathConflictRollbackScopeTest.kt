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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.objects.tracks.TrackSection
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.testutil.separatorAt
import cz.vutbr.fit.interlockSim.testutil.withMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Issue #1025: a registry conflict inside [PathReservationService.reservePath] must roll back only
 * the blocks THAT call physically reserved, never the blocks the train already held.
 *
 * ## The mechanism this pins
 *
 * `reservePath` splits a candidate path into `blocks` (every block on the path) and
 * `forwardBlocks` (the blocks not yet owned by the train). Only `forwardBlocks` are physically
 * reserved and handed to `PathReservationRegistry.registerAtomic`. When the registry answers
 * `Conflict`, the rollback must undo exactly that physical reservation.
 *
 * Rolling back the full `blocks` list instead cancels every block whose `reservedFrom` is this
 * call's start separator — including the blocks the train already held from that separator on its
 * previous route — and leaves the registry untouched. The registry and the train's `PathInfo` then
 * still say "mine" while the block is FREE. `DefaultTrainNavigationService` trusts the registry
 * only, so the train is routed into the FREE block and `DynamicTrackBlock.enter` throws
 * `Wrong state: FREE , expected : RESERVED` — the #1025 FATAL.
 *
 * ## The shape
 *
 * A stateless per-cycle dispatcher (the Goal 10 LLM) re-issues `request_route` from the same
 * start with a longer target while the first route is still held. The conflict comes from a block
 * further along that the registry still attributes to another train although it is physically
 * FREE (`registerAtomic` alone produces that state; so does any release path that frees a block
 * without unregistering it).
 */
@Tag("integration-test")
@DisplayName("Issue #1025 — a registry conflict rolls back only this call's forward blocks")
class ReservePathConflictRollbackScopeTest : KoinTestBase() {
	private lateinit var context: DefaultSimulationContext
	private lateinit var service: PathReservationService
	private lateinit var registry: PathReservationRegistry
	private lateinit var inA: DynamicPathSeparator
	private lateinit var doB1: DynamicPathSeparator
	private lateinit var zB: DynamicPathSeparator

	@BeforeEach
	fun setUp() {
		context = TestFixtures.newShuntingSimulationContext().tracked()
		service = context.getRoutingServices().getPathReservationService()
		registry = context.scope.get<PathReservationRegistry>()
		inA = context.separatorAt(ShuntingLoop.COORD_IN_A_X, ShuntingLoop.COORD_IN_A_Y)
		doB1 = context.separatorAt(ShuntingLoop.COORD_SEM_DOB1_X, ShuntingLoop.COORD_SEM_DOB1_Y)
		zB = context.separatorAt(ShuntingLoop.COORD_SEM_ZB_X, ShuntingLoop.COORD_SEM_ZB_Y)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("blocks already held from the same start survive a registry conflict on the extension")
	fun alreadyHeldBlocksSurviveRegistryConflict() {
		val train = "Train #1025"
		val other = "Train #other"

		// Given: the train holds A → doB1 (the grant seen in the #1025 log), reserved from A.
		val first = service.reservePath(train, inA, doB1)
		assertThat(first).isInstanceOf<PathReservationService.ReservationResult.Success>()
		val held = (first as PathReservationService.ReservationResult.Success).reservedBlocks
		assertThat(held.isNotEmpty()).withMessage("the first route must reserve at least one block").isTrue()
		held.forEach { block ->
			assertThat(block.getState()).isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.reservedFrom).isSameInstanceAs(inA)
		}
		assertThat(context.getRoutingServices().getTrainNavigationService().findReservedPathForTrain(train, inA))
			.withMessage("the first route must be one navigation accepts, or the final check is vacuous")
			.isInstanceOf<PathResult.Available>()

		// And: the next block of the extension A → zB is physically FREE but the registry still
		// attributes it to another train — the registry-only divergence that makes
		// registerAtomic answer Conflict after the free-check passed.
		val contested = firstForwardBlockOf(inA, zB, excluding = held)
		assertThat(contested.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(registry.registerAtomic(other, listOf(contested)))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()

		// When: the same train re-requests from the same start with the longer target.
		val second = service.reservePath(train, inA, zB)
		assertThat(second)
			.withMessage("the arrangement must hit the registry-conflict arm, or the test is vacuous")
			.isInstanceOf<PathReservationService.ReservationResult.Conflict>()

		// Then: the blocks the train already held are untouched — physically and in the registry.
		held.forEach { block ->
			assertThat(block.getState())
				.withMessage("already-held block $block must stay RESERVED after the conflict rollback")
				.isEqualTo(TrackFacility.State.RESERVED)
			assertThat(block.trainName).withMessage("already-held block $block keeps its train").isEqualTo(train)
			assertThat(block.reservedFrom).withMessage("already-held block $block keeps its separator").isSameInstanceAs(inA)
			assertThat(registry.getOwner(block)).isEqualTo(train)
		}
		assertThat(registry.getPathInfo(train)).isNotNull()
		assertThat(registry.getPathInfo(train)!!.target).isSameInstanceAs(doB1)

		// And: the navigation answer and the physical state agree — the precondition
		// DynamicTrackBlock.enter relies on.
		val navigation = context.getRoutingServices().getTrainNavigationService().findReservedPathForTrain(train, inA)
		assertThat(navigation).isInstanceOf<PathResult.Available>()
		(navigation as PathResult.Available)
			.path
			.filterIsInstance<TrackSection>()
			.map { it.getTrackBlock() }
			.filterIsInstance<DynamicTrackBlock>()
			.forEach { block ->
				assertThat(block.getState())
					.withMessage("navigation routes $train over $block, so it must not be FREE")
					.isEqualTo(TrackFacility.State.RESERVED)
			}

		// And: the conflicting block itself was not touched either way.
		assertThat(contested.getState()).isEqualTo(TrackFacility.State.FREE)
		assertThat(contested.trainName).isNull()
		assertThat(registry.getOwner(contested)).isEqualTo(other)
	}

	/**
	 * The conflict rollback's Issue #1025 diagnostic: `logRollbackOfBlock` WARNs when it cancels a
	 * block the registry still attributes to the cancelling train — the registry/physical
	 * divergence — because a block freed while the registry still names its train is what routed
	 * the #1025 train into FREE track at `enter`.
	 *
	 * ## The arrangement
	 *
	 * `forwardBlocks` are filtered on the physical `trainName`, so a block the registry attributes
	 * to THIS train while it is physically FREE still enters the candidate, passes the free-check,
	 * and is physically reserved by `tryAtomicReservation`. `registerAtomic` then passes it
	 * idempotently (same owner) and conflicts on a second pre-registered block owned by another
	 * train. The rollback cancels the first block — RESERVED → FREE, `trainName` cleared — while
	 * the registry still attributes it to the train: the WARN branch, and the divergence it names.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a conflict rollback that cancels a block still registered to the train names that divergence")
	fun conflictRollbackNamesBlockStillRegisteredToTheCancellingTrain() {
		val train = "Train #1025"
		val other = "Train #other"

		// Given: two blocks of the A → zB route, both physically FREE, pre-registered in the
		// registry — the first to THIS train (the ghost own), the second to another train.
		val route = routeBlocksOf(inA, zB)
		assertThat(route.size, "the arrangement needs two blocks on the A → zB route")
			.isGreaterThanOrEqualTo(2)
		val ghost = route.first()
		val contested = route[1]
		assertThat(ghost.getState(), "the ghost block starts FREE").isEqualTo(TrackFacility.State.FREE)
		assertThat(contested.getState(), "the contested block starts FREE").isEqualTo(TrackFacility.State.FREE)
		assertThat(registry.registerAtomic(train, listOf(ghost)))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()
		assertThat(registry.registerAtomic(other, listOf(contested)))
			.isInstanceOf<PathReservationRegistry.RegistrationResult.Success>()

		// When: the train requests the route; the registry conflicts on the other train's block.
		val result = service.reservePath(train, inA, zB)
		assertThat(result)
			.withMessage("the arrangement must hit the registry-conflict arm, or the test is vacuous")
			.isInstanceOf<PathReservationService.ReservationResult.Conflict>()

		// Then: the rollback cancelled the ghost block's physical reservation...
		assertThat(ghost.getState(), "state of the block the registry still attributes to the train")
			.isEqualTo(TrackFacility.State.FREE)
		assertThat(ghost.trainName, "trainName of the cancelled ghost block").isEqualTo(null)
		// ...while the registry still attributes it to the train — the divergence the WARN names.
		assertThat(registry.getOwner(ghost), "registry owner of the cancelled ghost block").isEqualTo(train)

		// And: the other train's registration is untouched either way.
		assertThat(contested.getState(), "state of the conflicting block").isEqualTo(TrackFacility.State.FREE)
		assertThat(contested.trainName, "trainName of the conflicting block").isEqualTo(null)
		assertThat(registry.getOwner(contested), "registry owner of the conflicting block").isEqualTo(other)
	}

	/** Every block of the [start] → [target] route's first topological path, in path order. */
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

	/** The first block of the [start] → [target] route that is not in [excluding], in path order. */
	private fun firstForwardBlockOf(
		start: DynamicPathSeparator,
		target: DynamicPathSeparator,
		excluding: List<DynamicTrackBlock>
	): DynamicTrackBlock = routeBlocksOf(start, target).first { it !in excluding }
}
