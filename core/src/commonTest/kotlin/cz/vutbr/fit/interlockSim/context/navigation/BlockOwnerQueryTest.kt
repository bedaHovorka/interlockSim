/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1269): the block-owner query and the blocked-by result.
 */
package cz.vutbr.fit.interlockSim.context.navigation

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.sim.StandingOccupant
import cz.vutbr.fit.interlockSim.sim.tailClears
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Issue #1269 (Goal 9B demand 1): [PathReservationService.getBlockOwner] names the train that keeps
 * a block from other trains, and a refused [PathReservationService.reservePath] names, per busy
 * candidate, what held it ([PathReservationService.ReservationResult.AllPathsBlocked.blockedBy]).
 *
 * The network is the y-junction `Entry -[trackEntry]- Junction -[trackMain]- ExitMain` with the
 * branch `Junction -[trackBranch]- ExitBranch`. The holder reserves `Entry -> ExitMain`, which locks
 * `Junction` in the main position; the waiter asks for `Entry -> ExitBranch`.
 */
class BlockOwnerQueryTest : CommonKoinTestBase() {
	private companion object {
		const val HOLDER: String = "holder"
		const val WAITER: String = "waiter"
		const val STRANGER: String = "stranger"
	}

	private inner class Network {
		val ctx: DefaultSimulationContext = TestTopologies.yJunctionWithSwitchSimulation().tracked()
		val service: PathReservationService = ctx.getRoutingServices().getPathReservationService()
		val registry: PathReservationRegistry = ctx.scope.get<PathReservationRegistry>()
		private val inOuts: List<DynamicInOut> = ctx.getInOuts().toList()
		val entry: DynamicInOut = inOuts.first { it.name == "Entry" }
		val exitMain: DynamicInOut = inOuts.first { it.name == "ExitMain" }
		val exitBranch: DynamicInOut = inOuts.first { it.name == "ExitBranch" }

		/** Reserves `Entry -> ExitMain` for the holder; returns trackEntry and trackMain. */
		fun holdMainRoute(): List<DynamicTrackBlock> {
			val result = service.reservePath(HOLDER, entry, exitMain)
			check(result is PathReservationService.ReservationResult.Success) { "main route refused: $result" }
			return result.reservedBlocks
		}

		/** The block of `Entry -> ExitBranch` that is not trackEntry. */
		fun trackBranch(trackEntry: DynamicTrackBlock): DynamicTrackBlock =
			ctx
				.getRouteFinder()
				.findRoutes(entry.staticRef, exitBranch.staticRef, ctx)
				.flatMap { extractUniqueBlocks(it.segments) }
				.first { it != trackEntry }

		fun requestBranch(): PathReservationService.ReservationResult.AllPathsBlocked {
			val result = service.reservePath(WAITER, entry, exitBranch)
			assertThat(result, name = "branch request").isInstanceOf<PathReservationService.ReservationResult.AllPathsBlocked>()
			return result as PathReservationService.ReservationResult.AllPathsBlocked
		}
	}

	@Test
	fun ownerQueryNamesTheReservingTrainAndNobodyForAFreeBlock() {
		val network = Network()
		val (trackEntry, trackMain) = network.holdMainRoute()

		assertThat(network.service.getBlockOwner(trackEntry), name = "owner of trackEntry").isEqualTo(HOLDER)
		assertThat(network.service.getBlockOwner(trackMain), name = "owner of trackMain").isEqualTo(HOLDER)
		assertThat(network.service.getBlockOwner(network.trackBranch(trackEntry)), name = "owner of trackBranch")
			.isNull()
	}

	@Test
	fun ownerQueryNamesATrainStandingOnABlockTheRegistryDoesNotAttribute() {
		// The Issue #893 shape: a train occupies a block without a registry entry.
		val network = Network()
		val trackEntry = network.holdMainRoute().first()
		val trackBranch = network.trackBranch(trackEntry)
		trackBranch.setUpPath(network.exitBranch, STRANGER)
		trackBranch.enter(StandingOccupant(STRANGER))

		assertThat(network.registry.getOwner(trackBranch), name = "registry owner").isNull()
		assertThat(network.service.getBlockOwner(trackBranch), name = "owner of trackBranch").isEqualTo(STRANGER)
	}

	@Test
	fun refusedRequestNamesTheTrainHoldingItsBlock() {
		val network = Network()
		val trackEntry = network.holdMainRoute().first()

		val refused = network.requestBranch()

		assertThat(refused.blockingTrains, name = "blocking trains").isEqualTo(setOf(HOLDER))
		val candidate = refused.blockedBy.single()
		assertThat(candidate.blockedBlocks, name = "blocked blocks").isEqualTo(mapOf(trackEntry to HOLDER))
	}

	@Test
	fun refusedRequestNamesTheTrainHoldingItsSwitch() {
		// The holder's tail has cleared trackEntry, so every block of the branch route is free; the
		// holder still holds trackMain, which keeps its claim on Junction (main position) live.
		val network = Network()
		val (trackEntry, _) = network.holdMainRoute()
		val junction = network.registry.getSwitches(HOLDER).single()
		network.ctx.tailClears(StandingOccupant(HOLDER), trackEntry)
		assertThat(junction.locked, name = "Junction stays locked for the holder").isTrue()

		val refused = network.requestBranch()

		assertThat(refused.blockingTrains, name = "blocking trains").isEqualTo(setOf(HOLDER))
		val candidate = refused.blockedBy.single()
		assertThat(candidate.blockedBlocks, name = "blocked blocks").isEmpty()
		assertThat(candidate.lockedSwitches, name = "locked switches").isEqualTo(mapOf(junction to HOLDER))
	}
}
