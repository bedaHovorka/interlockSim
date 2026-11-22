/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 (Issue #1154): event-driven retry of a blocked entry.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.navigation.BlockEvent
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.context.navigation.onReservationExpired
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import cz.vutbr.fit.interlockSim.util.currentSimulationTime
import kotlin.test.Test

/**
 * Pins that a blocked entry is retried by **one activation per release, at the simulation time of
 * the release**, and not by a polling loop (Issue #1154).
 *
 * Three trains share the single route of the linear track, so the second and third are blocked
 * while the first one runs. The test records every `reservePath` call with its simulation time and
 * every release (a block released or a reservation expired) with its simulation time, then checks:
 * each retry call sits at a release time, the retry process woke at most once per release time and
 * only at release times, and the blocked trains made far fewer calls than a two-second poll would.
 */
class EventDrivenRetryTest : CommonKoinTestBase() {
	private companion object {
		const val END_TIME: Long = 600L
		const val TRAIN_LENGTH: Double = 20.0
		const val TRAIN_COUNT: Int = 3

		/** A tick polls every 2 simulated seconds; a blocked train waits well over 100 s. */
		const val POLL_PERIOD_SECONDS: Double = 2.0
	}

	private data class Call(
		val trainId: String,
		val time: Double
	)

	private class RecordingReservationService(
		private val real: PathReservationService
	) : PathReservationService by real {
		val calls: MutableList<Call> = mutableListOf()

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): PathReservationService.ReservationResult {
			calls.add(Call(trainId, currentSimulationTime()))
			return real.reservePath(trainId, start, target, maxDepth)
		}
	}

	/** Three contending trains on the linear track, with every release instant recorded. */
	private data class ContendedRun(
		val loop: MultiTrainLoop,
		val recording: RecordingReservationService,
		val releaseTimes: Set<Double>
	)

	/** Assembles and runs the three-train contention scenario; the test only checks the record. */
	private fun runThreeContendingTrains(): ContendedRun {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false).tracked()
		ctx.getInOuts()
		val recording = RecordingReservationService(ctx.getRoutingServices().getPathReservationService())
		val releaseTimes = mutableSetOf<Double>()
		ctx.onBlockEvent { if (it is BlockEvent.BlockReleased) releaseTimes.add(currentSimulationTime()) }
		ctx.onReservationExpired { releaseTimes.add(currentSimulationTime()) }
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs =
					(0 until TRAIN_COUNT).map {
						MultiTrainLoop.TrainSpec("A", "B", inTime = it.toDouble(), length = TRAIN_LENGTH)
					},
				maxConcurrentTrains = TRAIN_COUNT,
				pathReservationService = recording
			)
		ctx.setMainProcess(loop)

		ctx.run()
		return ContendedRun(loop, recording, releaseTimes)
	}

	@Test
	fun blockedTrainsAreRetriedOncePerReleaseAtTheReleaseTime() {
		val run = runThreeContendingTrains()

		assertThat(run.loop.getEntryFailures(), name = "entry failures").isEmpty()
		assertThat(run.loop.getTrainsExited(), name = "trains exited").isEqualTo(TRAIN_COUNT)

		val activations = run.loop.getRetryActivationTimes()
		assertThat(activations, name = "retry activations").isNotEmpty()
		assertThat(activations.filterNot { it in run.releaseTimes }, name = "activations away from a release time")
			.isEmpty()
		assertThat(activations.size, name = "one activation per release time at most")
			.isEqualTo(activations.toSet().size)

		val retryCalls =
			run.recording.calls
				.groupBy { it.trainId }
				.values
				.flatMap { it.drop(1) }
		assertThat(retryCalls, name = "retry calls").isNotEmpty()
		assertThat(
			retryCalls.filterNot { it.time in run.releaseTimes },
			name = "retry calls away from a release time"
		).isEmpty()

		val mostCalls =
			run.recording.calls
				.groupBy { it.trainId }
				.values
				.maxOf { it.size }
		val pollingCalls = END_TIME / POLL_PERIOD_SECONDS
		assertThat(mostCalls.toDouble(), name = "calls of the most-retried train")
			.isLessThanOrEqualTo(run.releaseTimes.size + 1.0)
		assertThat(pollingCalls, name = "a polling loop would call far more often")
			.isGreaterThan(mostCalls.toDouble())
	}

	/** One refused train woken by the scripted releases; the scenario assembled and run. */
	private data class BlockedRun(
		val loop: MultiTrainLoop,
		val service: AttemptRecordingService
	)

	/** Assembles and runs one train whose entry is always refused and gets the scripted releases. */
	private fun runBlockedTrainWithReleases(
		releaseTimes: List<Double>,
		maxEntryAttempts: Int,
		throwOnCallNumber: Int = Int.MAX_VALUE
	): BlockedRun {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		ctx.getInOuts()
		val service =
			AttemptRecordingService(
				ctx.getRoutingServices().getPathReservationService(),
				refuse = true,
				throwOnCallNumber = throwOnCallNumber
			)
		val loop =
			ReleasingMultiTrainLoop(
				ctx,
				endTime = 60L,
				trainSpecs = listOf(MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = TRAIN_LENGTH)),
				pathReservationService = service,
				maxEntryAttempts = maxEntryAttempts,
				releaseTimes = releaseTimes
			)
		ctx.setMainProcess(loop)

		ctx.run()
		return BlockedRun(loop, service)
	}

	@Test
	fun twoReleasesAtOneInstantAreCountedAsOneActivation() {
		val run = runBlockedTrainWithReleases(releaseTimes = listOf(5.0, 5.0), maxEntryAttempts = 10)

		// Copilot round on Issue #1154: with two scripted releases at one instant, the second
		// arrives after the retry parked again, so it re-wakes the retry at the same instant. The
		// retry still runs, but the wake metric counts each instant once.
		assertThat(run.loop.getRetryActivationTimes(), name = "activation instants").isEqualTo(listOf(5.0))
	}

	@Test
	fun aThrownReservePathDoesNotStopFutureRetriesForever() {
		// Copilot round on Issue #1154: an exception inside one drain attempt must not kill the
		// retry process, or every later release goes unanswered while the run keeps going.
		val run =
			runBlockedTrainWithReleases(
				releaseTimes = ReleasingMultiTrainLoop.every(5.0, 60L),
				maxEntryAttempts = 100,
				throwOnCallNumber = 2
			)

		// Releases run every 5 s up to 55 s; a retry must still follow the last one.
		assertThat(
			run.service.attempts
				.last()
				.time,
			name = "the last release still woke a retry"
		).isEqualTo(55.0)
		assertThat(run.loop.getEntryFailures(), name = "no entry failure").isEmpty()
	}
}
