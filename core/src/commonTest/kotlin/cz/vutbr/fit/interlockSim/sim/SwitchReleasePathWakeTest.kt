/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1271): switch releases and the event-driven retry.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockReleaseSteps
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationRegistry
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.cells.DynamicRailSwitch
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Issue #1271: a switch claim that keeps a waiting train out must wake the [MultiTrainLoop] retry
 * when it goes -- or be one the waiter's own attempt reclaims, so that no wake is needed.
 *
 * The scenario, on the y-junction `Entry -[trackEntry]- Junction -[trackMain]- ExitMain` with the
 * branch `Junction -[trackBranch]- ExitBranch`: a holder (a reservation without a train process)
 * holds `Entry -> ExitMain`, so `Junction` is locked in the main position. The waiter wants
 * `Entry -> ExitBranch`; its first attempt (t=2) is refused. Scripted steps then drive one path on
 * the holder; the test checks the retry activations and the instant of the waiter's grant.
 *
 * The path table is in `docs/goal9b-demands/SP7-retry-and-watchdog.md` ("Release-path audit"); the
 * block paths are in [BlockReleasePathWakeTest].
 */
class SwitchReleasePathWakeTest : CommonKoinTestBase() {
	private companion object {
		const val HOLDER: String = "holder"
		const val END_TIME: Long = 120L
		const val TRAIN_LENGTH: Double = 20.0
		const val FIRST_ATTEMPT: Double = 2.0

		/** Before the waiter's first attempt: the queue is empty, so a release here wakes nothing. */
		const val SETUP_AT: Double = 1.5
		const val RELEASE_AT: Double = 5.5
		const val SECOND_RELEASE_AT: Double = 9.5
	}

	/** The y-junction with the holder's `Entry -> ExitMain` route reserved before the run. */
	private inner class Network {
		val ctx: DefaultSimulationContext = TestTopologies.yJunctionWithSwitchSimulation().tracked()
		val service: PathReservationService = ctx.getRoutingServices().getPathReservationService()
		val registry: PathReservationRegistry = ctx.scope.get<PathReservationRegistry>()
		private val inOuts: List<DynamicInOut> = ctx.getInOuts().toList()
		val entry: DynamicInOut = inOuts.first { it.name == "Entry" }
		val exitMain: DynamicInOut = inOuts.first { it.name == "ExitMain" }
		val holderBody = StandingOccupant(HOLDER)

		/** The one switch, `Junction`; set by [holdMainRoute], which leaves it in the main position. */
		lateinit var junction: DynamicRailSwitch

		/** Reserves `Entry -> ExitMain` for [trainId]; returns its blocks, trackEntry first. */
		fun holdMainRoute(trainId: String = HOLDER): List<DynamicTrackBlock> {
			val result = service.reservePath(trainId, entry, exitMain)
			check(result is PathReservationService.ReservationResult.Success) { "main route refused: $result" }
			junction = registry.getSwitches(trainId).single()
			return result.reservedBlocks
		}
	}

	private class Outcome(
		val loop: MultiTrainLoop,
		val attempts: AttemptRecordingService
	) {
		val activations: List<Double> get() = loop.getRetryActivationTimes()

		/** The first train generated: the waiter. */
		val waiterAttempts: List<AttemptRecordingService.Attempt>
			get() = attempts.attempts.filter { it.trainId == attempts.attempts.first().trainId }
		val grantedAt: Double? get() = waiterAttempts.firstOrNull { it.granted }?.time
	}

	private fun run(
		network: Network,
		steps: List<Pair<Double, () -> Unit>>,
		trainSpecs: List<MultiTrainLoop.TrainSpec> = listOf(branchTrain(inTime = 0.0))
	): Outcome {
		val attempts = AttemptRecordingService(network.service)
		val loop =
			ScriptedMultiTrainLoop(
				network.ctx,
				endTime = END_TIME,
				trainSpecs = trainSpecs,
				pathReservationService = attempts,
				steps = steps
			)
		network.ctx.setMainProcess(loop)
		network.ctx.run()
		return Outcome(loop, attempts)
	}

	private fun branchTrain(inTime: Double) = MultiTrainLoop.TrainSpec("Entry", "ExitBranch", inTime, TRAIN_LENGTH)

	private fun assertWaiterWasBlocked(outcome: Outcome) {
		val first = outcome.waiterAttempts.first()
		assertThat(first.time, name = "first attempt").isEqualTo(FIRST_ATTEMPT)
		assertThat(first.granted, name = "first attempt granted").isEqualTo(false)
	}

	@Test
	fun switchReleaseRidesOnTheEventOfItsLastAdjacentBlock() {
		// Tail clearance of trackEntry frees the shared block, but the holder still holds trackMain,
		// so Junction stays locked in the main position and the woken waiter is refused. The
		// clearance of trackMain makes the claim stale: unregisterBlock reclaims it before its
		// BlockReleased (and the woken waiter's own Step 2e.5 would reclaim it too), so the wake of
		// that block event is the one the waiter needs.
		val network = Network()
		val (trackEntry, trackMain) = network.holdMainRoute()

		val outcome =
			run(
				network,
				listOf(
					RELEASE_AT to { network.ctx.tailClears(network.holderBody, trackEntry) },
					SECOND_RELEASE_AT to { network.ctx.tailClears(network.holderBody, trackMain) }
				)
			)

		assertWaiterWasBlocked(outcome)
		assertThat(outcome.activations, name = "retry activations").containsExactly(RELEASE_AT, SECOND_RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(SECOND_RELEASE_AT)
	}

	@Test
	fun flankClaimReleasedAtTrainExitWakesTheWaiter() {
		// A flank claim (DefaultInterlockingFacade.lockRouteAtomic) never goes stale, so it outlives
		// the holder's last block; RouteReleaser.unregister at the holder's exit is what releases it.
		// The holder holds no block any more then, so no BlockReleased can carry the wake.
		val network = Network()
		val blocks = network.holdMainRoute()
		val junction = network.junction

		val outcome =
			run(
				network,
				listOf(
					SETUP_AT to {
						network.registry.registerFlankSwitches(HOLDER, listOf(junction))
						blocks.forEach { (network.service as BlockReleaseSteps).releaseBlock(HOLDER, it) }
						check(junction.locked) { "the flank claim keeps Junction locked" }
					},
					RELEASE_AT to { network.ctx.releaseTrainReservations(HOLDER) }
				)
			)

		assertWaiterWasBlocked(outcome)
		assertThat(outcome.activations, name = "retry activations").containsExactly(RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(RELEASE_AT)
	}

	@Test
	fun staleForeignSwitchClaimNeedsNoWake() {
		// A plain claim whose owner holds no block next to the switch is stale. The waiter's own
		// attempt reclaims it (CandidateCommitter Step 2e.5, reclaimStaleForeignSwitchOwnership)
		// before its switch check, so such a claim never refuses an entry and needs no event.
		val network = Network()
		network.holdMainRoute(trainId = "setter")
		network.service.releasePath("setter")
		network.registry.registerSwitches(HOLDER, listOf(network.junction))
		assertThat(network.junction.locked, name = "stale claim locks Junction").isTrue()

		val outcome = run(network, emptyList())

		assertThat(outcome.grantedAt, name = "grant on the first attempt").isEqualTo(FIRST_ATTEMPT)
		assertThat(outcome.activations, name = "retry activations").isEmpty()
	}

	@Test
	fun orphanSwitchLockNeedsNoWake() {
		// A lock with no registered owner (a stray setUpPath) is released by the waiter's own attempt
		// (Step 2e.5, releaseOrphanSwitchLocks) before its switch check: no event is needed.
		val network = Network()
		network.holdMainRoute(trainId = "setter")
		network.service.releasePath("setter")
		network.junction.lock()

		val outcome = run(network, emptyList())

		assertThat(outcome.grantedAt, name = "grant on the first attempt").isEqualTo(FIRST_ATTEMPT)
		assertThat(outcome.activations, name = "retry activations").isEmpty()
	}

	@Test
	fun candidateRollbackOfAnotherAttemptIsNotAWake() {
		// The holder keeps only trackMain, so Junction stays locked in the main position while the
		// branch route's blocks are free. Each attempt for the branch reserves trackEntry and
		// trackBranch, fails on the locked switch and rolls both back inside reservePath. A second,
		// fresh train makes such an attempt at t=4: its rollback frees nothing that was not free
		// before the call, so it must not wake the first waiter (were it to announce a release, the
		// own-train filter of onReleaseSignal would drop it); the clearance of trackMain must.
		val network = Network()
		val (trackEntry, trackMain) = network.holdMainRoute()
		val clearedAt = 20.5

		val outcome =
			run(
				network,
				listOf(
					SETUP_AT to { network.ctx.tailClears(network.holderBody, trackEntry) },
					clearedAt to { network.ctx.tailClears(network.holderBody, trackMain) }
				),
				trainSpecs = listOf(branchTrain(inTime = 0.0), branchTrain(inTime = 3.0))
			)

		assertWaiterWasBlocked(outcome)
		val freshAttempts = outcome.attempts.attempts.filter { it !in outcome.waiterAttempts }
		assertThat(freshAttempts.first().time, name = "the fresh train's refused attempt").isEqualTo(4.0)
		assertThat(outcome.activations.first(), name = "first retry activation").isEqualTo(clearedAt)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(clearedAt)
	}
}
