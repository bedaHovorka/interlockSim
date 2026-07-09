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
 * [decide] is now a pure function over [DispatchObservation]/[BlockInputObservation]
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
		innerBlockInputs: List<BlockInputObservation> = emptyList(),
		outerBlockInputs: List<BlockInputObservation> = emptyList()
	): DispatchObservation =
		DispatchObservation(
			snapshot = emptySnapshot.copy(trainPositions = List(approvedTrainCount) { fakeTrainPosition() }),
			unapprovedTrains = unapprovedTrains,
			innerBlockInputs = innerBlockInputs,
			outerBlockInputs = outerBlockInputs
		)

	private fun fakeTrainPosition() =
		cz.vutbr.fit.interlockSim.ports.TrainPositionReading(
			trainId = "placeholder",
			velocity = 0.0,
			acceleration = 0.0,
			totalDistance = 0.0,
			frontSectionName = null
		)

	private fun queued(
		trainId: String,
		destinationInOutName: String = "outA"
	): QueuedTrainObservation = QueuedTrainObservation(trainId, destinationInOutName)

	private fun input(
		state: TrackFacility.State,
		towardSemaphoreName: String = "sem",
		toSeparatorName: String? = "nextSep",
		ownerTrainId: String? = null,
		isApproachingThisInput: Boolean = false,
		pathSetUpTowardThisInput: Boolean = false,
		pathAlreadyExtendedBeyond: Boolean = false,
		blockId: String = "block"
	): BlockInputObservation =
		BlockInputObservation(
			blockId = blockId,
			towardSemaphoreName = towardSemaphoreName,
			toSeparatorName = toSeparatorName,
			state = state,
			ownerTrainId = ownerTrainId,
			isApproachingThisInput = isApproachingThisInput,
			pathSetUpTowardThisInput = pathSetUpTowardThisInput,
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
	@DisplayName("decide() with an empty queue and no block inputs returns NoAction")
	fun emptyQueueAndFreeReturnsNoAction() {
		val dispatcher = RuleBasedDispatcher()

		val decisions = dispatcher.decide(observation())

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	// ── Path advancement — OCCUPIED branch ──────────────────────────────────

	@Test
	@DisplayName("FREE block input: no reservation, NoAction")
	fun freeInputMakesNoReservation() {
		val dispatcher = RuleBasedDispatcher()

		val decisions = dispatcher.decide(observation(outerBlockInputs = listOf(input(TrackFacility.State.FREE))))

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("OCCUPIED input, train not approaching: no reservation")
	fun occupiedNotApproachingMakesNoReservation() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockInputs =
					listOf(input(TrackFacility.State.OCCUPIED, ownerTrainId = "T1", isApproachingThisInput = false))
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("OCCUPIED input, approaching, already extended: idempotent skip")
	fun occupiedApproachingAlreadyExtendedSkips() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockInputs =
					listOf(
						input(
							TrackFacility.State.OCCUPIED,
							ownerTrainId = "T1",
							isApproachingThisInput = true,
							pathAlreadyExtendedBeyond = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("OCCUPIED input, approaching, not extended: reserves from→to with occupant's train id")
	fun occupiedApproachingReserves() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockInputs =
					listOf(
						input(
							TrackFacility.State.OCCUPIED,
							towardSemaphoreName = "za",
							toSeparatorName = "zb",
							ownerTrainId = "T1",
							isApproachingThisInput = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.ReservePath("T1", "za", "zb"))
	}

	@Test
	@DisplayName("OCCUPIED input, approaching, but no FREE next separator: NoAction (train waits)")
	fun occupiedApproachingNoFreeSeparatorDefers() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockInputs =
					listOf(
						input(
							TrackFacility.State.OCCUPIED,
							towardSemaphoreName = "za",
							toSeparatorName = null,
							ownerTrainId = "T1",
							isApproachingThisInput = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	// ── Path advancement — RESERVED branch ──────────────────────────────────

	@Test
	@DisplayName("RESERVED input, path not set up: no reservation")
	fun reservedNotSetUpMakesNoReservation() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockInputs =
					listOf(input(TrackFacility.State.RESERVED, ownerTrainId = "T2", pathSetUpTowardThisInput = false))
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("RESERVED input, set up, already extended: idempotent skip")
	fun reservedSetUpAlreadyExtendedSkips() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockInputs =
					listOf(
						input(
							TrackFacility.State.RESERVED,
							ownerTrainId = "T2",
							pathSetUpTowardThisInput = true,
							pathAlreadyExtendedBeyond = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.NoAction)
	}

	@Test
	@DisplayName("RESERVED input, set up, not extended: reserves from→to with block's train name")
	fun reservedSetUpReserves() {
		val dispatcher = RuleBasedDispatcher()
		val observed =
			observation(
				outerBlockInputs =
					listOf(
						input(
							TrackFacility.State.RESERVED,
							towardSemaphoreName = "zb",
							toSeparatorName = "outB",
							ownerTrainId = "T2",
							pathSetUpTowardThisInput = true
						)
					)
			)

		val decisions = dispatcher.decide(observed)

		assertThat(decisions).containsExactly(DispatchDecision.ReservePath("T2", "zb", "outB"))
	}

	// ── All inputs evaluated independently ──────────────────────────────────

	@Test
	@DisplayName("of a block's two inputs, only the genuinely eligible one yields a decision")
	fun onlyEligibleInputYieldsDecision() {
		// Real domain data: a block's occupant can only be approaching one input
		// (TrackOccupant.nextSemaphore() is single-valued), so at most one of the
		// two BlockInputObservations built by ShuntingLoop is ever eligible. This
		// replaces the pre-#729 short-circuit test (checkBothEndsShortCircuitsOnFirstEnd):
		// under a pure decide() there is no live actuation result to short-circuit
		// on, so checkAllInputs/checkInput evaluate every input independently — the
		// assertion here is that this still yields exactly one decision per block,
		// not two.
		val dispatcher = RuleBasedDispatcher()
		val eligibleInput =
			input(
				TrackFacility.State.OCCUPIED,
				towardSemaphoreName = "semA",
				toSeparatorName = "nextA",
				ownerTrainId = "TA",
				isApproachingThisInput = true
			)
		val ineligibleInput =
			input(
				TrackFacility.State.OCCUPIED,
				towardSemaphoreName = "semB",
				toSeparatorName = "nextB",
				ownerTrainId = "TA",
				isApproachingThisInput = false
			)

		val decisions = dispatcher.decide(observation(innerBlockInputs = listOf(eligibleInput, ineligibleInput)))

		assertThat(decisions).containsExactly(DispatchDecision.ReservePath("TA", "semA", "nextA"))
	}

	@Test
	@DisplayName("eligible inputs of DIFFERENT blocks each yield a from→to reservation (no cross-block dedup)")
	fun multipleBlocksEachYieldReservation() {
		// Documents the frozen-observation + independent-evaluation contract: a
		// reservation decision for one block does not suppress another block's
		// decision. The shell builds one BlockInputObservation per block input, and
		// checkAllInputs evaluates them all; pure decide() never deduplicates across
		// blocks.
		val dispatcher = RuleBasedDispatcher()
		val inputK1 =
			input(
				TrackFacility.State.OCCUPIED,
				blockId = "k1",
				towardSemaphoreName = "doB1",
				toSeparatorName = "zB",
				ownerTrainId = "T1",
				isApproachingThisInput = true
			)
		val inputK2 =
			input(
				TrackFacility.State.OCCUPIED,
				blockId = "k2",
				towardSemaphoreName = "doB2",
				toSeparatorName = "zB",
				ownerTrainId = "T2",
				isApproachingThisInput = true
			)

		val decisions = dispatcher.decide(observation(innerBlockInputs = listOf(inputK1, inputK2)))

		assertThat(decisions).containsExactly(
			DispatchDecision.ReservePath("T1", "doB1", "zB"),
			DispatchDecision.ReservePath("T2", "doB2", "zB")
		)
	}

	@Test
	@DisplayName("two eligible inputs of the SAME block both yield a decision (unreachable for real data)")
	fun sameBlockTwoEligibleInputsBothYield() {
		// This state is unreachable for real domain data: a block's occupant
		// approaches at most one input (TrackOccupant.nextSemaphore() is
		// single-valued) and a reserved block's path is set up toward at most one
		// input, so the shell never builds two eligible inputs for one block. The
		// test documents that pure decide() does NOT deduplicate by block — if the
		// invariant ever regressed, both inputs would yield a reservation rather
		// than silently collapsing to one.
		val dispatcher = RuleBasedDispatcher()
		val inputA =
			input(
				TrackFacility.State.OCCUPIED,
				blockId = "k1",
				towardSemaphoreName = "semA",
				toSeparatorName = "nextA",
				ownerTrainId = "TA",
				isApproachingThisInput = true
			)
		val inputB =
			input(
				TrackFacility.State.OCCUPIED,
				blockId = "k1",
				towardSemaphoreName = "semB",
				toSeparatorName = "nextB",
				ownerTrainId = "TB",
				isApproachingThisInput = true
			)

		val decisions = dispatcher.decide(observation(innerBlockInputs = listOf(inputA, inputB)))

		assertThat(decisions).containsExactly(
			DispatchDecision.ReservePath("TA", "semA", "nextA"),
			DispatchDecision.ReservePath("TB", "semB", "nextB")
		)
	}

	// ── Observation contract ─────────────────────────────────────────────────

	@Test
	@DisplayName("QueuedTrainObservation.destinationInOutName is preserved into the observation")
	fun queuedDestinationInOutNameIsPreserved() {
		val observed = observation(unapprovedTrains = listOf(queued("T1", "outB")))

		assertThat(observed.unapprovedTrains.first().destinationInOutName).isEqualTo("outB")
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
