/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP7 (Issue #1154): event-driven retry grants entry in the polling order.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import kotlin.test.Test

/**
 * Pins that the event-driven retry grants contended entries in the **same order** as the polling
 * retry it replaced.
 *
 * The scenario: four trains on the single-route linear track all want `A -> B` and the route
 * serializes them, so three of them are blocked at first and must be admitted one after the other
 * as the previous train's blocks are released. The order of the successful `reservePath` calls
 * (the entry grants) was recorded on the polling implementation (head `ef61e908`, before Issue
 * #1154) and is the constant [POLLING_BASELINE_GRANT_ORDER]. Train names come from the `Train`
 * counter, so they are compared by the order in which the trains were generated, not by name.
 */
class RetryOrderEqualsPollingBaselineTest : CommonKoinTestBase() {
	private companion object {
		const val END_TIME: Long = 600L
		const val TRAIN_LENGTH: Double = 20.0
		const val TRAIN_COUNT: Int = 4

		/** Generation indices (0-based, by `inTime`) in the order the polling loop granted entry. */
		val POLLING_BASELINE_GRANT_ORDER: List<Int> = listOf(0, 1, 2, 3)

		/** A reservation held by a train that does not exist, so nothing ever enters or refreshes it. */
		const val PHANTOM: String = "phantom"
	}

	/** Delegates to the real service and records every successful `reservePath` in order. */
	private class RecordingReservationService(
		delegate: PathReservationService
	) : PathReservationService by delegate {
		private val real = delegate
		val grantedTrainIds: MutableList<String> = mutableListOf()

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): PathReservationService.ReservationResult {
			val result = real.reservePath(trainId, start, target, maxDepth)
			if (result is PathReservationService.ReservationResult.Success) {
				grantedTrainIds.add(trainId)
			}
			return result
		}
	}

	/** Pins the one admission policy at [contestedTick]: the older waiter is attempted first. */
	private fun assertOlderWaiterAttemptedFirst(
		attempts: AttemptRecordingService,
		contestedTick: Double,
		excludeTrainIds: Set<String> = emptySet()
	) {
		val waiterId = attempts.attempts.first { it.time == 2.0 }.trainId
		val contested = attempts.attempts.filter { it.time == contestedTick }
		val freshId = attempts.attempts.map { it.trainId }.toSet() - waiterId - excludeTrainIds
		val fresh = freshId.single()
		assertThat(contested.map { it.trainId }, name = "the fresh train attempts at the contested tick").contains(fresh)
		assertThat(contested.first().trainId, name = "the older waiter is attempted before the fresh train")
			.isEqualTo(waiterId)
	}

	@Test
	fun aReleaseQueuedBeforeTheTickEventDoesNotBypassOlderWaiters() {
		// The waiter is approved and refused on the first tick (t=2). The fresh train is generated at
		// t=8 (the generator runs every second, the main loop approves on its even ticks), so its
		// first attempt shares the t=10 tick with a release whose event was queued before that
		// iteration event. The older waiter must still be attempted first at that tick.
		val releaseTick = 10.0
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		ctx.getInOuts()
		val attempts = AttemptRecordingService(ctx.getRoutingServices().getPathReservationService(), refuse = true)
		val loop =
			ReleasingMultiTrainLoop(
				ctx,
				endTime = 60L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec("A", "B", inTime = 8.0, length = TRAIN_LENGTH)
					),
				pathReservationService = attempts,
				maxEntryAttempts = 10,
				releaseTimes = listOf(releaseTick)
			)
		ctx.setMainProcess(loop)

		ctx.run()

		assertOlderWaiterAttemptedFirst(attempts, releaseTick)
		assertThat(loop.getEntryFailures(), name = "no entry failure").isEmpty()
	}

	@Test
	fun aMidAttemptStaleExpiryDoesNotBypassOlderWaiters() {
		// A phantom holds the whole route from t=0 and never refreshes it. Its holding is released
		// by the loop's own expiry tick at t=62 (the Ttl horizon reached). The fresh train has
		// inTime 61.0, so its first attempt shares that t=62 tick with the release -- and an older
		// waiter that waited since t=2 must still win the freed route first.
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		ctx.getInOuts()
		val attempts = AttemptRecordingService(ctx.getRoutingServices().getPathReservationService(), refuse = false)
		val granted =
			attempts.reservePath(PHANTOM, ctx.getInOuts().first { it.name == "A" }, ctx.getInOuts().first { it.name == "B" })
		assertThat(granted is PathReservationService.ReservationResult.Success, name = "phantom route granted").isTrue()
		val staleTick = 62.0
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = 600L,
				trainSpecs =
					listOf(
						MultiTrainLoop.TrainSpec("A", "B", inTime = 0.0, length = TRAIN_LENGTH),
						MultiTrainLoop.TrainSpec("A", "B", inTime = 61.0, length = TRAIN_LENGTH)
					),
				pathReservationService = attempts
			)
		ctx.setMainProcess(loop)

		ctx.run()

		assertOlderWaiterAttemptedFirst(attempts, staleTick, excludeTrainIds = setOf(PHANTOM))
		assertThat(loop.getEntryFailures(), name = "no entry failure").isEmpty()
	}

	@Test
	fun eventDrivenRetryGrantsEntryInThePollingOrder() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = false).tracked()
		ctx.getInOuts()
		val recording = RecordingReservationService(ctx.getRoutingServices().getPathReservationService())
		val specs =
			(0 until TRAIN_COUNT).map {
				MultiTrainLoop.TrainSpec("A", "B", inTime = it.toDouble(), length = TRAIN_LENGTH)
			}
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs = specs,
				maxConcurrentTrains = TRAIN_COUNT,
				pathReservationService = recording
			)
		ctx.setMainProcess(loop)

		ctx.run()

		// Names are "<prefix><counter>"; the counter grows with generation order.
		val generationOrder = recording.grantedTrainIds.sortedBy { it.filter(Char::isDigit).toInt() }
		val grantOrder = recording.grantedTrainIds.map { generationOrder.indexOf(it) }
		assertThat(loop.getEntryFailures(), name = "entry failures").isEmpty()
		assertThat(loop.getTrainsExited(), name = "trains exited").isEqualTo(TRAIN_COUNT)
		assertThat(grantOrder, name = "entry grant order").containsExactly(*POLLING_BASELINE_GRANT_ORDER.toTypedArray())
	}
}
