/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Goal 1B SP1 (Issue #1148): bounded entry reservation in MultiTrainLoop.
 */
package cz.vutbr.fit.interlockSim.sim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isZero
import cz.vutbr.fit.interlockSim.context.navigation.PathReservationService
import cz.vutbr.fit.interlockSim.objects.core.DynamicPathSeparator
import cz.vutbr.fit.interlockSim.testutil.CommonKoinTestBase
import cz.vutbr.fit.interlockSim.testutil.TestTopologies
import kotlin.test.Test

/**
 * Pins the Issue #1148 contract of `MultiTrainLoop.reserveEntryPath`: **one**
 * [PathReservationService.reservePath] call per entry attempt, at most `maxEntryAttempts`
 * attempts per train, and a structured [MultiTrainLoop.EntryFailure] once they are used up.
 *
 * Driven with a counting fake that always answers one fixed result, so every attempt fails.
 * Before Issue #1148 the loop called `reservePath` once per topological candidate in every
 * attempt and retried forever. A retryable result
 * ([PathReservationService.ReservationResult.AllPathsBlocked]) uses up the cap; a permanent one
 * ([PathReservationService.ReservationResult.NoPathExists],
 * [PathReservationService.ReservationResult.GeometricallyImpossible]) fails on the first attempt.
 */
class MultiTrainLoopBoundedEntryTest : CommonKoinTestBase() {
	private companion object {
		/** Attempt cap under test. */
		const val MAX_ATTEMPTS: Int = 5

		/**
		 * Long enough for every capped attempt of two trains (one attempt per two-second
		 * dispatcher cycle), and far longer than [MAX_ATTEMPTS] cycles, so an uncapped loop
		 * would make many more calls.
		 */
		const val END_TIME: Long = 60L

		const val TRAIN_LENGTH: Double = 20.0

		val BLOCKED = PathReservationService.ReservationResult.AllPathsBlocked(attemptedPaths = 1)
	}

	/** Delegates everything to the real service, but counts every `reservePath` and answers [refusal]. */
	private class CountingBlockedReservationService(
		delegate: PathReservationService,
		private val refusal: PathReservationService.ReservationResult = BLOCKED
	) : PathReservationService by delegate {
		val reservePathCallsByTrain: MutableMap<String, Int> = mutableMapOf()

		val reservePathCalls: Int
			get() = reservePathCallsByTrain.values.sum()

		override fun reservePath(
			trainId: String,
			start: DynamicPathSeparator,
			target: DynamicPathSeparator,
			maxDepth: Int
		): PathReservationService.ReservationResult {
			reservePathCallsByTrain[trainId] = (reservePathCallsByTrain[trainId] ?: 0) + 1
			return refusal
		}
	}

	private fun spec(
		inTime: Double,
		inName: String = "A"
	): MultiTrainLoop.TrainSpec =
		MultiTrainLoop.TrainSpec(inName = inName, outName = "B", inTime = inTime, length = TRAIN_LENGTH)

	/** Runs one train against a fake that answers [refusal] and returns the loop and the fake. */
	private fun runOneRefusedTrain(
		refusal: PathReservationService.ReservationResult
	): Pair<MultiTrainLoop, CountingBlockedReservationService> {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		ctx.getInOuts()
		val counting = CountingBlockedReservationService(ctx.getRoutingServices().getPathReservationService(), refusal)
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs = listOf(spec(inTime = 0.0)),
				pathReservationService = counting,
				maxEntryAttempts = MAX_ATTEMPTS
			)
		ctx.setMainProcess(loop)
		ctx.run()
		return loop to counting
	}

	@Test
	fun reserveEntryPathBoundedAttempts() {
		val (loop, counting) = runOneRefusedTrain(BLOCKED)

		assertThat(counting.reservePathCalls, name = "reservePath calls (one per attempt, capped)")
			.isEqualTo(MAX_ATTEMPTS)
		val failures = loop.getEntryFailures()
		assertThat(failures.map { it.kind }, name = "failure kinds")
			.containsExactly(MultiTrainLoop.EntryFailureKind.ATTEMPTS_EXHAUSTED)
		val failure = failures.single()
		assertThat(failure.attempts, name = "attempts recorded").isEqualTo(MAX_ATTEMPTS)
		assertThat(failure.lastResult, name = "last reservePath result").isEqualTo(BLOCKED)
		assertThat(failure.inName, name = "entry").isEqualTo("A")
		assertThat(failure.outName, name = "exit").isEqualTo("B")
		assertThat(loop.getApprovedTrains(), name = "refused train retired from the approved list").isEmpty()
		assertThat(loop.getTrainsExited(), name = "trains exited").isZero()
		assertThat(loop.getOccupiedResourceCount(), name = "gate resources left held").isZero()
	}

	@Test
	fun refusedTrainFreesItsConcurrencySlot() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		ctx.getInOuts()
		val counting = CountingBlockedReservationService(ctx.getRoutingServices().getPathReservationService())
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs = listOf(spec(inTime = 0.0), spec(inTime = 1.0)),
				maxConcurrentTrains = 1,
				pathReservationService = counting,
				maxEntryAttempts = MAX_ATTEMPTS
			)
		ctx.setMainProcess(loop)

		ctx.run()

		assertThat(counting.reservePathCallsByTrain.values.toList(), name = "reservePath calls per train")
			.containsExactly(MAX_ATTEMPTS, MAX_ATTEMPTS)
		assertThat(loop.getEntryFailures().map { it.attempts }, name = "attempts per refused train")
			.containsExactly(MAX_ATTEMPTS, MAX_ATTEMPTS)
		assertThat(loop.getApprovedTrains(), name = "both refused trains retired").isEmpty()
	}

	@Test
	fun noPathExistsFailsOnTheFirstAttempt() {
		val (loop, counting) = runOneRefusedTrain(PathReservationService.ReservationResult.NoPathExists)

		assertThat(counting.reservePathCalls, name = "reservePath calls (permanent result, no retry)").isEqualTo(1)
		val failure = loop.getEntryFailures().single()
		assertThat(failure.kind, name = "failure kind").isEqualTo(MultiTrainLoop.EntryFailureKind.NO_ROUTE)
		assertThat(failure.attempts, name = "attempts recorded").isEqualTo(1)
		assertThat(loop.getApprovedTrains(), name = "refused train retired from the approved list").isEmpty()
	}

	@Test
	fun geometricallyImpossibleFailsOnTheFirstAttempt() {
		val impossible = PathReservationService.ReservationResult.GeometricallyImpossible("rear-facing start")
		val (loop, counting) = runOneRefusedTrain(impossible)

		assertThat(counting.reservePathCalls, name = "reservePath calls (permanent result, no retry)").isEqualTo(1)
		val failure = loop.getEntryFailures().single()
		assertThat(failure.kind, name = "failure kind")
			.isEqualTo(MultiTrainLoop.EntryFailureKind.GEOMETRICALLY_IMPOSSIBLE)
		assertThat(failure.lastResult, name = "last reservePath result").isEqualTo(impossible)
	}

	@Test
	fun unknownInOutIsRecordedWithoutCreatingATrain() {
		val ctx = TestTopologies.linearPathWithSemaphoreSimulation(semaphoreAllowing = true).tracked()
		ctx.getInOuts()
		val counting = CountingBlockedReservationService(ctx.getRoutingServices().getPathReservationService())
		val loop =
			MultiTrainLoop(
				ctx,
				endTime = END_TIME,
				trainSpecs = listOf(spec(inTime = 0.0, inName = "NoSuchInOut")),
				pathReservationService = counting,
				maxEntryAttempts = MAX_ATTEMPTS
			)
		ctx.setMainProcess(loop)

		ctx.run()

		val failure = loop.getEntryFailures().single()
		assertThat(failure.kind, name = "failure kind").isEqualTo(MultiTrainLoop.EntryFailureKind.UNKNOWN_IN_OUT)
		assertThat(failure.trainName, name = "train name (no train created)").isNull()
		assertThat(failure.inName, name = "entry").isEqualTo("NoSuchInOut")
		assertThat(failure.attempts, name = "attempts").isZero()
		assertThat(loop.getTrainsEntered(), name = "trains entered").isZero()
		assertThat(counting.reservePathCalls, name = "reservePath calls").isZero()
	}
}
