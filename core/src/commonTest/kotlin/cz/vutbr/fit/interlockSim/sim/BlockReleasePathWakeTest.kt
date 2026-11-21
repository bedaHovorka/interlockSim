/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 follow-up (Issue #1271): every block release path wakes the event-driven retry.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.BlockReleaseSteps
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.ReservationExpired
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.objects.cells.DynamicInOut
import cz.vutbr.fit.interlockSim.objects.tracks.DynamicTrackBlock
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.CommonTestFixtures
import cz.vutbr.fit.interlockSim.testutil.NetworkResources
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import org.koin.core.component.get
import kotlin.test.Test

/**
 * Issue #1271: each production path that frees a block another train waits for must wake the
 * [MultiTrainLoop] retry, at the instant of the release.
 *
 * The scenario, on the linear track `A -[b1]- Sem -[b2]- B`: a holder (a reservation without a
 * train process, so only the scripted steps change it) holds the whole `A -> B` route before the
 * run. One train wants `A -> B`; its first attempt (t=2) is refused, so it waits. A scripted step
 * then drives one release path on the holder. The test checks the retry activations and the
 * instant of the waiter's grant: a path that frees the route without an event leaves the waiter
 * queued to the run end, with no activation at all.
 *
 * The path table these tests belong to is in `docs/goal9b-demands/SP7-retry-and-watchdog.md`
 * ("Release-path audit"). The switch paths are in [SwitchReleasePathWakeTest].
 */
class BlockReleasePathWakeTest : CommonKoinTestBase() {
	private companion object {
		const val HOLDER: String = "holder"
		const val END_TIME: Long = 200L
		const val TRAIN_LENGTH: Double = 20.0

		/** The waiter's first entry attempt: the loop approves and tries it on its t=2 tick. */
		const val FIRST_ATTEMPT: Double = 2.0

		/** Off the loop's tick grid, so the wake can only come from the step's own event. */
		const val RELEASE_AT: Double = 5.5
		const val SECOND_RELEASE_AT: Double = 9.5
	}

	/** The network whose `A -> B` route the holder takes before the run; the linear track by default. */
	private inner class Network(
		val ctx: DefaultSimulationContext =
			TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
	) {
		val service: PathReservationService = ctx.getRoutingServices().getPathReservationService()
		private val inOuts: List<DynamicInOut> = ctx.getInOuts().toList()
		val inA: DynamicInOut = inOuts.first { it.name == "A" }
		val inB: DynamicInOut = inOuts.first { it.name == "B" }
		val holderBody = StandingOccupant(HOLDER)

		/** Reserves the whole `A -> B` route for the holder; returns its blocks, the one at A first. */
		fun holdRoute(): List<DynamicTrackBlock> {
			val result = service.reservePath(HOLDER, inA, inB)
			check(result is PathReservationService.ReservationResult.Success) { "holder route refused: $result" }
			return result.reservedBlocks
		}
	}

	/** The run's record: the loop and every entry attempt of the waiter. */
	private class Outcome(
		val loop: MultiTrainLoop,
		val attempts: AttemptRecordingService
	) {
		val activations: List<Double> get() = loop.getRetryActivationTimes()
		val grantedAt: Double? get() = attempts.attempts.firstOrNull { it.granted }?.time
		val firstAttempt: AttemptRecordingService.Attempt get() = attempts.attempts.first()
	}

	private fun run(
		network: Network,
		steps: List<Pair<Double, () -> Unit>>
	): Outcome {
		val attempts = AttemptRecordingService(network.service)
		val loop =
			ScriptedMultiTrainLoop(
				network.ctx,
				endTime = END_TIME,
				trainSpecs = listOf(MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = TRAIN_LENGTH)),
				pathReservationService = attempts,
				steps = steps
			)
		network.ctx.setMainProcess(loop)
		network.ctx.run()
		return Outcome(loop, attempts)
	}

	/** The waiter was refused at its first attempt, so it could only enter through a wake. */
	private fun assertWaiterWasBlocked(outcome: Outcome) {
		assertThat(outcome.firstAttempt.time, name = "first attempt").isEqualTo(FIRST_ATTEMPT)
		assertThat(outcome.firstAttempt.granted, name = "first attempt granted").isEqualTo(false)
	}

	@Test
	fun trainExitUnregisterWakesTheWaiter() {
		// Train.actions -> SimulationEnvironment.releaseTrainReservations -> RouteReleaser.unregister.
		val network = Network()
		network.holdRoute()

		val outcome = run(network, listOf(RELEASE_AT to { network.ctx.releaseTrainReservations(HOLDER) }))

		assertWaiterWasBlocked(outcome)
		assertThat(outcome.activations, name = "retry activations").containsExactly(RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(RELEASE_AT)
	}

	@Test
	fun tailClearanceOfEachBlockWakesTheWaiter() {
		// Train.Tail.separatorAction -> unregisterBlock -> RouteReleaser.dropFreedBlock, once per block.
		val network = Network()
		val (b1, b2) = network.holdRoute()

		val outcome =
			run(
				network,
				listOf(
					RELEASE_AT to { network.ctx.tailClears(network.holderBody, b1) },
					SECOND_RELEASE_AT to { network.ctx.tailClears(network.holderBody, b2) }
				)
			)

		assertWaiterWasBlocked(outcome)
		// The first clearance frees b1 only: the waiter is retried and refused (b2 still held).
		assertThat(outcome.activations, name = "retry activations").containsExactly(RELEASE_AT, SECOND_RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(SECOND_RELEASE_AT)
	}

	@Test
	fun releaseBlockWakesTheWaiter() {
		// BlockReleaseSteps.releaseBlock (RegistryPartialRouteReleaser) -> RouteReleaser.releaseBlock.
		val network = Network()
		val blocks = network.holdRoute()
		val steps = network.service as BlockReleaseSteps

		val outcome = run(network, listOf(RELEASE_AT to { blocks.forEach { steps.releaseBlock(HOLDER, it) } }))

		assertWaiterWasBlocked(outcome)
		assertThat(outcome.activations, name = "retry activations").containsExactly(RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(RELEASE_AT)
	}

	@Test
	fun unconditionalReleasePathWakesTheWaiter() {
		// PathReservationService.releasePath -> RouteReleaser.releaseAllBlocks (no approach lock).
		val network = Network()
		network.holdRoute()

		val outcome = run(network, listOf(RELEASE_AT to { network.service.releasePath(HOLDER) }))

		assertWaiterWasBlocked(outcome)
		assertThat(outcome.activations, name = "retry activations").containsExactly(RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(RELEASE_AT)
	}

	@Test
	fun cancelRouteReleaseWakesTheWaiterOnTheCallThatFreesTheRoute() {
		// cancel_route and the orphan sweeper: NetworkActuatorPort.releaseRoute -> releasePathDetailed.
		// The holder's entry signal shows proceed for b1, so the first call approach-locks b1; the
		// cut after b1 is not a PathInfo end here, so that call frees nothing (signals to STOP only)
		// and is no release. The call after the booking window frees the route and wakes the waiter.
		val network = Network()
		network.holdRoute()
		var heldAfterFirstCall = -1

		val outcome =
			run(
				network,
				listOf(
					RELEASE_AT to {
						network.service.releasePathDetailed(HOLDER)
						heldAfterFirstCall = network.service.getReservedBlocks(HOLDER).size
					},
					SECOND_RELEASE_AT to { network.service.releasePathDetailed(HOLDER) }
				)
			)

		assertWaiterWasBlocked(outcome)
		assertThat(heldAfterFirstCall, name = "blocks held after the deferring call").isEqualTo(2)
		assertThat(outcome.activations, name = "retry activations").containsExactly(SECOND_RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(SECOND_RELEASE_AT)
	}

	@Test
	fun wholeHoldingExpiryWakesTheWaiter() {
		// The loop tick's expireStaleReservations -> DefaultReservationExpiry, phase 1 (Issue #1174).
		val network = Network()
		network.holdRoute()
		val expiries = mutableListOf<ReservationExpired>()
		network.ctx.onReservationExpired { expiries.add(it) }

		val outcome = run(network, emptyList())

		assertWaiterWasBlocked(outcome)
		assertThat(expiries.map { it.kind }, name = "expiries").containsExactly(ReservationExpired.Kind.WHOLE_HOLDING)
		val expiredAt = expiries.single().time
		assertThat(outcome.activations.last(), name = "last retry activation").isEqualTo(expiredAt)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(expiredAt)
	}

	@Test
	fun tailExpiryOfAStandingTrainWakesTheWaiter() {
		// The loop tick's expireStaleReservations -> DefaultReservationExpiry, phase 2 (Issue #1220),
		// whose releasePathDetailed frees the tail through releaseAllBut. On vyhybna.xml the holder
		// stands on kA (A - zA) and makes no progress, so the un-entered rest of its route expires;
		// kA stays held, so the woken waiter is refused until the Tail clearance of kA lets it in.
		val network = Network(CommonTestFixtures.parseSimulationContext(NetworkResources.VYHYBNA_XML, get()).tracked())
		val kA = network.holdRoute().first()
		val expiries = mutableListOf<ReservationExpired>()
		network.ctx.onReservationExpired { expiries.add(it) }
		val releaseInstants = mutableSetOf<Double>()
		network.ctx.onBlockEvent { if (it is BlockEvent.BlockReleased) releaseInstants.add(it.time) }
		val clearedAt = 150.5

		val outcome =
			run(
				network,
				listOf(
					1.5 to { kA.enter(network.holderBody) },
					clearedAt to {
						kA.leave(network.holderBody)
						network.ctx.unregisterBlock(HOLDER, kA)
					}
				)
			)

		assertWaiterWasBlocked(outcome)
		assertThat(expiries.map { it.kind }, name = "expiries")
			.containsExactly(ReservationExpired.Kind.OCCUPYING_TRAIN_TAIL)
		val expiredAt = expiries.single().time
		assertThat(outcome.activations, name = "retry activations").contains(expiredAt)
		assertThat(outcome.activations.filterNot { it in releaseInstants }, name = "activations away from a release")
			.isEmpty()
		assertThat(outcome.activations.last(), name = "last retry activation").isEqualTo(clearedAt)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(clearedAt)
	}

	@Test
	fun leavingABlockTheRegistryDoesNotAttributeWakesTheWaiter() {
		// The Issue #893 shape: a body stands on b1 although no registry entry holds b1. Its Tail
		// clearance makes b1 FREE, but unregisterBlock has nothing to drop, so no BlockReleased is
		// published; only DynamicTrackBlock.leave's OccupancyCleared announces the free block.
		val network = Network()
		val (b1, _) = network.holdRoute()
		network.service.releasePath(HOLDER)
		val ghost = StandingOccupant("ghost")

		val outcome =
			run(
				network,
				listOf(
					1.5 to {
						b1.setUpPath(network.inA, ghost.name)
						b1.enter(ghost)
					},
					RELEASE_AT to {
						b1.leave(ghost)
						network.ctx.unregisterBlock(ghost.name, b1)
					}
				)
			)

		assertWaiterWasBlocked(outcome)
		assertThat(outcome.activations, name = "retry activations").containsExactly(RELEASE_AT)
		assertThat(outcome.grantedAt, name = "grant").isEqualTo(RELEASE_AT)
	}
}
