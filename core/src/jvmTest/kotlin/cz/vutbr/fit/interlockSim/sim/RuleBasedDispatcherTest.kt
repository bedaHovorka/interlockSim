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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Direct unit tests for [RuleBasedDispatcher] — the branch-level coverage that the
 * `RuleBasedDispatcherDeterminismTest` integration gate cannot provide on its own
 * (determinism verifies cross-run consistency, not branch correctness).
 *
 * [decide] is now a pure function over [DispatchObservation]/[BlockEndObservation]
 * value classes (Issue #729 / SP0.7), so these tests build observations directly —
 * no mocks or fake contexts required.
 *
 * @since Issue #540 (SP0.1 — Goal 10); rewritten for the pure seam in Issue #729
 *   (SP0.7 — Goal 10)
 */
@DisplayName("RuleBasedDispatcher — branch-level unit coverage")
class RuleBasedDispatcherTest {
	// ── Test data builders ──────────────────────────────────────────────────

	private val emptySnapshot =
		SimulationSnapshot(
			simTime = 0.0,
			semaphores = emptyList(),
			blocks = emptyList(),
			trainPositions = emptyList(),
			timetables = emptyList()
		)

	private fun observation(
		approvedTrainCount: Int = 0,
		unapprovedTrains: List<QueuedTrainObservation> = emptyList(),
		innerBlockEnds: List<BlockEndObservation> = emptyList(),
		outerBlockEnds: List<BlockEndObservation> = emptyList()
	): DispatchObservation =
		DispatchObservation(
			snapshot = emptySnapshot.copy(trainPositions = List(approvedTrainCount) { fakeTrainPosition() }),
			unapprovedTrains = unapprovedTrains,
			innerBlockEnds = innerBlockEnds,
			outerBlockEnds = outerBlockEnds
		)

	private fun fakeTrainPosition() =
		cz.vutbr.fit.interlockSim.ports.TrainPositionReading(
			trainId = "placeholder",
			velocity = 0.0,
			acceleration = 0.0,
			totalDistance = 0.0,
			frontSectionName = null
		)

	private fun queued(trainId: String): QueuedTrainObservation = QueuedTrainObservation(trainId, "outA")

	private fun end(
		state: TrackFacility.State,
		towardSemaphoreName: String = "sem",
		ownerTrainId: String? = null,
		isApproachingThisEnd: Boolean = false,
		pathSetUpTowardThisEnd: Boolean = false,
		pathAlreadyExtendedBeyond: Boolean = false
	): BlockEndObservation =
		BlockEndObservation(
			blockId = "block",
			towardSemaphoreName = towardSemaphoreName,
			state = state,
			ownerTrainId = ownerTrainId,
			isApproachingThisEnd = isApproachingThisEnd,
			pathSetUpTowardThisEnd = pathSetUpTowardThisEnd,
			pathAlreadyExtendedBeyond = pathAlreadyExtendedBeyond
		)

	// ── Admission ────────────────────────────────────────────────────────────

	@Test
	@DisplayName("decide() admits queued trains in FIFO order up to maxConcurrentTrains")
	fun admitsUpToCap() {
		val trains = (1..5).map { queued("T$it") }
		val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = 2)

		val decisions = dispatcher.decide(observation(unapprovedTrains = trains))

		assertThat(decisions).containsExactly(
			DispatchDecision.ApproveTrain("T1"),
			DispatchDecision.ApproveTrain("T2")
		)
	}

	@Test
	@DisplayName("decide() respects a higher maxConcurrentTrains")
	fun respectsHigherCap() {
		val trains = (1..5).map { queued("T$it") }
		val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = 3)

		val decisions = dispatcher.decide(observation(unapprovedTrains = trains))

		assertThat(decisions).containsExactly(
			DispatchDecision.ApproveTrain("T1"),
			DispatchDecision.ApproveTrain("T2"),
			DispatchDecision.ApproveTrain("T3")
		)
	}

	@Test
	@DisplayName("decide() partially admits when already-approved trains occupy some slots")
	fun admitsRemainingSlotsOnly() {
		val trains = (1..3).map { queued("T$it") }
		val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = 2)

		val decisions = dispatcher.decide(observation(approvedTrainCount = 1, unapprovedTrains = trains))

		assertThat(decisions).containsExactly(DispatchDecision.ApproveTrain("T1"))
	}

	@Test
	@DisplayName("decide() admits all trains when queue size is below the cap")
	fun admitsAllWhenBelowCap() {
		val dispatcher = RuleBasedDispatcher(maxConcurrentTrains = 2)

		val decisions = dispatcher.decide(observation(unapprovedTrains = listOf(queued("only"))))

		assertThat(decisions).containsExactly(DispatchDecision.ApproveTrain("only"))
	}

	@Test
	@DisplayName("decide() with an empty queue and no block ends returns NoAction")
	fun emptyQueueAndFreeReturnsNoAction() {
		val dispatcher = RuleBasedDispatcher()

		val decisions = dispatcher.decide(observation())

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	// ── Path advancement — OCCUPIED branch ──────────────────────────────────

	@Test
	@DisplayName("FREE block end: no reservation, NoAction")
	fun freeEndMakesNoReservation() {
		val dispatcher = RuleBasedDispatcher()

		val decisions = dispatcher.decide(observation(outerBlockEnds = listOf(end(TrackFacility.State.FREE))))

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("OCCUPIED end, train not approaching: no reservation")
	fun occupiedNotApproachingMakesNoReservation() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockEnds =
					listOf(end(TrackFacility.State.OCCUPIED, ownerTrainId = "T1", isApproachingThisEnd = false))
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("OCCUPIED end, approaching, already extended: idempotent skip")
	fun occupiedApproachingAlreadyExtendedSkips() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockEnds =
					listOf(
						end(
							TrackFacility.State.OCCUPIED,
							ownerTrainId = "T1",
							isApproachingThisEnd = true,
							pathAlreadyExtendedBeyond = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("OCCUPIED end, approaching, not extended: reserves with occupant's train id")
	fun occupiedApproachingReserves() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockEnds =
					listOf(
						end(
							TrackFacility.State.OCCUPIED,
							towardSemaphoreName = "za",
							ownerTrainId = "T1",
							isApproachingThisEnd = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.ReservePath("T1", "za"))
	}

	// ── Path advancement — RESERVED branch ──────────────────────────────────

	@Test
	@DisplayName("RESERVED end, path not set up: no reservation")
	fun reservedNotSetUpMakesNoReservation() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockEnds =
					listOf(end(TrackFacility.State.RESERVED, ownerTrainId = "T2", pathSetUpTowardThisEnd = false))
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("RESERVED end, set up, already extended: idempotent skip")
	fun reservedSetUpAlreadyExtendedSkips() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockEnds =
					listOf(
						end(
							TrackFacility.State.RESERVED,
							ownerTrainId = "T2",
							pathSetUpTowardThisEnd = true,
							pathAlreadyExtendedBeyond = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("RESERVED end, set up, not extended: reserves with block's train name")
	fun reservedSetUpReserves() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockEnds =
					listOf(
						end(
							TrackFacility.State.RESERVED,
							towardSemaphoreName = "zb",
							ownerTrainId = "T2",
							pathSetUpTowardThisEnd = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.ReservePath("T2", "zb"))
	}

	// ── Both ends of an inner block evaluated independently ─────────────────

	@Test
	@DisplayName("of a block's two ends, only the genuinely eligible one yields a decision")
	fun onlyEligibleEndYieldsDecision() {
		// Real domain data: a block's occupant can only be approaching one end
		// (TrackOccupant.nextSemaphore() is single-valued), so at most one of the
		// two BlockEndObservations built by ShuntingLoop is ever eligible. This
		// replaces the pre-#729 short-circuit test (checkBothEndsShortCircuitsOnFirstEnd):
		// under a pure decide(), there is no live actuation result to short-circuit
		// on, so both ends are evaluated independently — the assertion here is that
		// this still yields exactly one decision per block, not two.
		val dispatcher = RuleBasedDispatcher()
		val eligibleEnd =
			end(TrackFacility.State.OCCUPIED, towardSemaphoreName = "semA", ownerTrainId = "TA", isApproachingThisEnd = true)
		val ineligibleEnd =
			end(TrackFacility.State.OCCUPIED, towardSemaphoreName = "semB", ownerTrainId = "TA", isApproachingThisEnd = false)

		val decisions = dispatcher.decide(observation(innerBlockEnds = listOf(eligibleEnd, ineligibleEnd)))

		assertThat(decisions).containsExactly(DispatchDecision.ReservePath("TA", "semA"))
	}

	// ── Constructor guard ───────────────────────────────────────────────────

	@Test
	@DisplayName("maxConcurrentTrains <= 0 is rejected")
	fun nonPositiveCapRejected() {
		assertFailure { RuleBasedDispatcher(maxConcurrentTrains = 0) }
			.isInstanceOf<IllegalArgumentException>()
		assertFailure { RuleBasedDispatcher(maxConcurrentTrains = -1) }
			.isInstanceOf<IllegalArgumentException>()
	}

	@Test
	@DisplayName("default cap is the vyhybna physical capacity (2)")
	fun defaultCapIsTwo() {
		assertThat(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS).isEqualTo(2)
	}
}
