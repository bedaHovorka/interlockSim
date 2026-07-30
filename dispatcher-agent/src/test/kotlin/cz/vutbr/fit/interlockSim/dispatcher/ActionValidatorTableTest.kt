/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.observation.BlockView
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import cz.vutbr.fit.interlockSim.dispatcher.observation.QueuedTrainView
import cz.vutbr.fit.interlockSim.dispatcher.observation.ReservationView
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainPhase
import cz.vutbr.fit.interlockSim.dispatcher.observation.TrainView
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Table-driven test for [ActionValidator]: one row per [RejectionCode], exhaustiveness enforced
 * via [EnumSource] + a covered-set assertion (SP2c.3, Issue #826).
 *
 * ## Exhaustiveness contract
 *
 * [allCodesAreCovered] asserts that every value of [RejectionCode] has an entry in either
 * [SINGLE_CASES] or [BATCH_CASES]. If a new code is added to the enum without a matching
 * test-table entry, [allCodesAreCovered] fails the build immediately.
 *
 * [eachSingleActionCodeProducesRejection] and [eachBatchCodeProducesRejection] then verify
 * each covered code actually triggers the expected [ValidationVerdict.Rejected] result.
 */
@DisplayName("ActionValidator — table-driven test: one row per RejectionCode (SP2c.3 #826)")
class ActionValidatorTableTest {
	companion object {
		// ── Shared test infrastructure ─────────────────────────────────────────

		/** Endpoint names used across all scenarios. None overlaps with [BLOCK_IDS]. */
		val VALID_ENDPOINTS: Set<String> = setOf("A", "B", "C", "D", "sigA", "sigB")

		/** Block IDs that must never be used as endpoint names. */
		val BLOCK_IDS: Set<String> = setOf("kA", "kB", "kC")

		/** Default station capacity used in scenarios. */
		const val CAPACITY = 2

		/** Standard validator used for single-action scenarios. maxActionsPerTick = 3 (default). */
		val VALIDATOR = ActionValidator(
			validEndpointNames = VALID_ENDPOINTS,
			blockIds = BLOCK_IDS,
		)

		// ── Observation builder helpers ────────────────────────────────────────

		fun obs(
			trains: List<TrainView> = emptyList(),
			blocks: List<BlockView> = emptyList(),
			reservations: List<ReservationView> = emptyList(),
			queued: List<QueuedTrainView> = emptyList(),
			activeCount: Int = 0,
			capacity: Int = CAPACITY,
		): DispatcherObservation =
			DispatcherObservation(
				tick = 1L,
				simTime = 10.0,
				trains = trains,
				blocks = blocks,
				switches = emptyList(),
				signals = emptyList(),
				reservations = reservations,
				queued = queued,
				activeCount = activeCount,
				capacity = capacity,
				appliedOutcomes = emptyList(),
			)

		fun trainView(
			trainId: String,
			phase: TrainPhase = TrainPhase.RUNNING,
			frontSectionName: String? = null,
			destinationInOutName: String = "B",
			signalAheadName: String? = null,
		): TrainView =
			TrainView(
				trainId = trainId,
				phase = phase,
				frontSectionName = frontSectionName,
				velocityMps = 0.0,
				accelerationMps2 = 0.0,
				destinationInOutName = destinationInOutName,
				signalAheadName = signalAheadName,
				signalAheadAspect = null,
				distanceToSignalAheadMetres = 0.0,
				waitingSinceSimTime = null,
				waitSeconds = 0.0,
			)

		fun queuedView(trainId: String, destination: String = "B"): QueuedTrainView =
			QueuedTrainView(trainId = trainId, destinationInOutName = destination, queuedSinceSimTime = 5.0)

		fun reservationView(trainId: String, target: String, blocks: List<String> = listOf("kA", "kB")): ReservationView =
			ReservationView(trainId = trainId, fromEndpointName = "A", targetName = target, blockIds = blocks)

		fun blockView(blockId: String, state: TrackFacility.State, occupant: String? = null): BlockView =
			BlockView(blockId = blockId, state = state, occupantTrainId = occupant)

		// ── Single-action test cases ───────────────────────────────────────────
		//
		// Map: RejectionCode → (action, observation) that triggers it via validate().

		val SINGLE_CASES: Map<RejectionCode, Pair<DispatchAction, DispatcherObservation>> = mapOf(

			// ── Shape / identity ───────────────────────────────────────────────

			RejectionCode.BLANK_ARGUMENT to (
				DispatchAction.ApproveTrain("") to obs()
			),

			RejectionCode.UNKNOWN_TRAIN to (
				DispatchAction.ApproveTrain("Ghost") to obs()
			),

			RejectionCode.UNKNOWN_ENDPOINT to (
				DispatchAction.RequestRoute("T1", "A", "NOWHERE") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING, destinationInOutName = "NOWHERE"))
				)
			),

			RejectionCode.ENDPOINT_IS_BLOCK_ID to (
				DispatchAction.RequestRoute("T1", "kA", "B") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING, destinationInOutName = "B"))
				)
			),

			// ── approve_train ──────────────────────────────────────────────────

			RejectionCode.TRAIN_ALREADY_ACTIVE to (
				DispatchAction.ApproveTrain("T1") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING)),
					activeCount = 1,
				)
			),

			// T1 is in trains with QUEUED phase but NOT in queued list — defensive/inconsistent state
			RejectionCode.TRAIN_NOT_QUEUED to (
				DispatchAction.ApproveTrain("T1") to obs(
					trains = listOf(trainView("T1", TrainPhase.QUEUED)),
					queued = emptyList(), // deliberately absent
				)
			),

			RejectionCode.CAPACITY_FULL to (
				DispatchAction.ApproveTrain("T1") to obs(
					queued = listOf(queuedView("T1")),
					activeCount = CAPACITY, // at capacity
					capacity = CAPACITY,
				)
			),

			RejectionCode.TRAIN_ALREADY_EXITED to (
				DispatchAction.ApproveTrain("T1") to obs(
					trains = listOf(trainView("T1", TrainPhase.EXITED)),
				)
			),

			// ── request_route ──────────────────────────────────────────────────

			// #814 Symptom 3: same-target re-request
			RejectionCode.ROUTE_ALREADY_HELD_TO_SAME_TARGET to (
				DispatchAction.RequestRoute("T1", "A", "B") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING, destinationInOutName = "B")),
					reservations = listOf(reservationView("T1", target = "B")),
					activeCount = 1,
				)
			),

			RejectionCode.ROUTE_HELD_TO_DIFFERENT_TARGET to (
				DispatchAction.RequestRoute("T1", "A", "B") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING, destinationInOutName = "B")),
					reservations = listOf(reservationView("T1", target = "C")), // target C, not B
					activeCount = 1,
				)
			),

			// T1 is only in queued (not yet admitted)
			RejectionCode.TRAIN_NOT_ADMITTED to (
				DispatchAction.RequestRoute("T1", "A", "B") to obs(
					queued = listOf(queuedView("T1", "B")),
				)
			),

			// T1 is active but toEndpoint != destinationInOutName
			RejectionCode.TARGET_NOT_TRAIN_DESTINATION to (
				DispatchAction.RequestRoute("T1", "A", "C") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING, destinationInOutName = "B")), // dest B, not C
					activeCount = 1,
				)
			),

			// T1 is HELD at sigA, but fromEndpointName = sigB (wrong origin)
			RejectionCode.ORIGIN_NOT_AT_TRAIN_POSITION to (
				DispatchAction.RequestRoute("T1", "sigB", "B") to obs(
					trains = listOf(
						trainView(
							"T1",
							TrainPhase.HELD,
							destinationInOutName = "B",  // matches toEndpointName → TARGET check passes
							signalAheadName = "sigA",    // HELD at sigA, not sigB
						)
					),
					activeCount = 1,
				)
			),

			// All blocks owned by other trains — no plausible free path
			RejectionCode.NO_FREE_PATH to (
				DispatchAction.RequestRoute("T1", "A", "B") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING, destinationInOutName = "B")),
					blocks = listOf(
						blockView("kA", TrackFacility.State.RESERVED, "T2"),
						blockView("kB", TrackFacility.State.OCCUPIED, "T2"),
						blockView("kC", TrackFacility.State.RESERVED, "T2"),
					),
					activeCount = 2,
				)
			),

			// ── cancel_route ───────────────────────────────────────────────────

			RejectionCode.NO_ROUTE_HELD to (
				DispatchAction.CancelRoute("T1") to obs(
					trains = listOf(trainView("T1", TrainPhase.RUNNING)),
					reservations = emptyList(), // no reservation
					activeCount = 1,
				)
			),

			// T1 is on block kA (OCCUPIED) which is in its reservation
			RejectionCode.TRAIN_ON_RESERVED_BLOCK to (
				DispatchAction.CancelRoute("T1") to obs(
					trains = listOf(
						trainView("T1", TrainPhase.RUNNING, frontSectionName = "kA")
					),
					blocks = listOf(
						blockView("kA", TrackFacility.State.OCCUPIED, "T1"),
					),
					reservations = listOf(reservationView("T1", target = "B", blocks = listOf("kA", "kB"))),
					activeCount = 1,
				)
			),
		)

		// ── Batch-level test cases ─────────────────────────────────────────────
		//
		// Map: RejectionCode → (list of actions, observation) that triggers the code
		// via validateBatch(). The first occurrence of the code in the batch result is asserted.

		val BATCH_CASES: Map<RejectionCode, Pair<List<DispatchAction>, DispatcherObservation>> = mapOf(

			// Second occurrence of identical action → DUPLICATE
			RejectionCode.DUPLICATE_ACTION_THIS_TICK to (
				listOf(
					DispatchAction.ApproveTrain("Ghost"), // first: UNKNOWN_TRAIN
					DispatchAction.ApproveTrain("Ghost"), // second: DUPLICATE
				) to obs()
			),

			// maxActionsPerTick = 1, two valid actions → second gets ACTION_LIMIT_EXCEEDED
			RejectionCode.ACTION_LIMIT_EXCEEDED to (
				listOf(
					DispatchAction.ApproveTrain("T1"),
					DispatchAction.ApproveTrain("T2"),
				) to obs(
					queued = listOf(queuedView("T1"), queuedView("T2")),
					activeCount = 0,
					capacity = CAPACITY,
				)
			),
		)

		/** All [RejectionCode] values that have at least one test scenario. */
		val ALL_COVERED_CODES: Set<RejectionCode> = SINGLE_CASES.keys + BATCH_CASES.keys
	}

	// ── Exhaustiveness assertion ───────────────────────────────────────────────

	@Test
	@DisplayName("every RejectionCode has a test scenario in either SINGLE_CASES or BATCH_CASES")
	fun allCodesAreCovered() {
		val uncovered = RejectionCode.entries.toSet() - ALL_COVERED_CODES
		assert(uncovered.isEmpty()) {
			"Missing test-table entries for RejectionCode values: $uncovered. " +
				"Add one row per code to SINGLE_CASES or BATCH_CASES."
		}
	}

	// ── Single-action parameterized test ──────────────────────────────────────

	@ParameterizedTest(name = "[{index}] {0} — produces Rejected with that code")
	@EnumSource(RejectionCode::class)
	@DisplayName("each single-action RejectionCode produces Rejected with the expected code")
	fun eachSingleActionCodeProducesRejection(code: RejectionCode) {
		val entry = SINGLE_CASES[code] ?: return // batch-only codes tested separately
		val (action, observation) = entry

		val verdict = VALIDATOR.validate(action, observation)

		assertThat(verdict, name = "verdict for $code")
			.isInstanceOf(ValidationVerdict.Rejected::class)
		assertThat((verdict as ValidationVerdict.Rejected).code, name = "code for $code")
			.isEqualTo(code)
	}

	// ── Batch-level parameterized test ────────────────────────────────────────

	@ParameterizedTest(name = "[{index}] {0} — produced somewhere in validateBatch result")
	@EnumSource(RejectionCode::class)
	@DisplayName("each batch-level RejectionCode appears in validateBatch result")
	fun eachBatchCodeProducesRejection(code: RejectionCode) {
		val entry = BATCH_CASES[code] ?: return // single-action codes tested separately
		val (actions, observation) = entry

		// ACTION_LIMIT_EXCEEDED needs a tighter limit — use maxActionsPerTick = 1 for that case
		val validator =
			if (code == RejectionCode.ACTION_LIMIT_EXCEEDED) {
				ActionValidator(
					validEndpointNames = VALID_ENDPOINTS,
					blockIds = BLOCK_IDS,
					maxActionsPerTick = 1,
				)
			} else {
				VALIDATOR
			}

		val results = validator.validateBatch(actions, observation)
		val rejectedCodes = results.mapNotNull { (_, v) -> (v as? ValidationVerdict.Rejected)?.code }

		assertThat(rejectedCodes, name = "rejected codes in batch for $code").contains(code)
	}
}
