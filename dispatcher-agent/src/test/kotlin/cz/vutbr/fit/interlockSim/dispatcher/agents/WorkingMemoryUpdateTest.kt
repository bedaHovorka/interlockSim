/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.dispatcher.CommandId
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.ValidationVerdict
import cz.vutbr.fit.interlockSim.dispatcher.agents.WorkingMemory.Companion.PENDING_TTL_TICKS
import cz.vutbr.fit.interlockSim.dispatcher.observation.AppliedOutcome
import cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservation
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Acceptance-criteria tests for [WorkingMemory.update] (SP2c.7, Issue #830).
 *
 * @since Issue #830 (SP2c.7 — Goal 10 ring buffer)
 */
@DisplayName("SP2c.7 WorkingMemory.update — acceptance criteria (#830)")
class WorkingMemoryUpdateTest {

	// ── Helpers ──────────────────────────────────────────────────────────────────────────

	private fun emptyRecord(tick: Long): TickRecord =
		TickRecord(
			tick = tick,
			simTime = tick.toDouble(),
			stateDigest = "0".repeat(64),
			actions = emptyList(),
			verdicts = emptyList(),
			outcomes = emptyList()
		)

	private fun observation(activeCount: Int = 1, capacity: Int = 2): DispatcherObservation =
		DispatcherObservation.EMPTY.copy(
			tick = 1L,
			activeCount = activeCount,
			capacity = capacity
		)

	/** Deterministic per-test [CommandId] built from a tick and a per-tick sequence number. */
	private fun cmdId(tick: Long, seq: Int = 0) = CommandId(tick * 1000 + seq)

	private fun routeAction(trainId: String = "T-1") = DispatchAction.RequestRoute(trainId, "A", "B")

	/** A generic sealed [AppliedOutcome] carrying [id] — the concrete subtype is irrelevant to
	 * [WorkingMemory.update], which only ever reads [AppliedOutcome.id]. */
	private fun outcomeFor(
		trainId: String,
		id: CommandId,
		tickIndex: Long
	): AppliedOutcome = AppliedOutcome.Approved(trainId = trainId, admitted = true, id = id, tickIndex = tickIndex)

	// ── AC: pure function ─────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("Pure function — same inputs yield == results")
	inner class PureFunction {

		@Test
		@DisplayName("same record and observation always return == WorkingMemory")
		fun sameInputsEqualResult() {
			val wm = WorkingMemory.EMPTY
			val record = emptyRecord(1L)
			val obs = observation()
			val result1 = wm.update(record, obs)
			val result2 = wm.update(record, obs)
			assertThat(result1).isEqualTo(result2)
		}

		@Test
		@DisplayName("multiple calls on same wm with same inputs are idempotent")
		fun multipleCallsIdempotent() {
			val wm = WorkingMemory.EMPTY
			val record = TickRecord(
				tick = 5L,
				simTime = 50.0,
				stateDigest = "a".repeat(64),
				actions = listOf(AttributedAction(cmdId(5L), 5L, routeAction())),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			)
			val obs = observation(activeCount = 2, capacity = 3)
			val r1 = wm.update(record, obs)
			val r2 = wm.update(record, obs)
			assertThat(r1).isEqualTo(r2)
		}
	}

	// ── AC: ticksSinceProgress — progress branch ──────────────────────────────────────────

	@Nested
	@DisplayName("ticksSinceProgress — mechanical definition per branch")
	inner class TicksSinceProgress {

		@Test
		@DisplayName("resets to 0 when record.outcomes is non-empty (progress branch)")
		fun resetsWhenOutcomesPresent() {
			val wm = WorkingMemory.EMPTY.copy(ticksSinceProgress = 3)
			val outcome = outcomeFor("T-1", CommandId(1L), 1L)
			val record = TickRecord(
				tick = 1L,
				simTime = 10.0,
				stateDigest = "0".repeat(64),
				actions = emptyList(),
				verdicts = emptyList(),
				outcomes = listOf(outcome)
			)
			val result = wm.update(record, observation())
			assertThat(result.ticksSinceProgress).isEqualTo(0)
		}

		@Test
		@DisplayName("increments when record.outcomes is empty (no-progress branch)")
		fun incrementsWhenNoOutcomes() {
			val wm = WorkingMemory.EMPTY.copy(ticksSinceProgress = 2)
			val record = emptyRecord(1L) // outcomes = emptyList()
			val result = wm.update(record, observation())
			assertThat(result.ticksSinceProgress).isEqualTo(3)
		}

		@Test
		@DisplayName("first tick with no outcomes starts at 1")
		fun firstTickNoProgressStartsAtOne() {
			val wm = WorkingMemory.EMPTY // ticksSinceProgress = 0
			val result = wm.update(emptyRecord(1L), observation())
			assertThat(result.ticksSinceProgress).isEqualTo(1)
		}

		@Test
		@DisplayName("first tick with outcomes starts at 0")
		fun firstTickWithProgressStartsAtZero() {
			val wm = WorkingMemory.EMPTY
			val outcome = outcomeFor("T-1", CommandId(1L), 1L)
			val record = TickRecord(
				tick = 1L,
				simTime = 10.0,
				stateDigest = "0".repeat(64),
				actions = emptyList(),
				verdicts = emptyList(),
				outcomes = listOf(outcome)
			)
			val result = wm.update(record, observation())
			assertThat(result.ticksSinceProgress).isEqualTo(0)
		}
	}

	// ── AC: pendingRequests drains on matching outcome ─────────────────────────────────────

	@Nested
	@DisplayName("pendingRequests — drains when matching AppliedOutcome arrives")
	inner class PendingRequestsDrain {

		@Test
		@DisplayName("action added to pendingRequests on first tick (no matching outcome yet)")
		fun actionAddedToPending() {
			val wm = WorkingMemory.EMPTY
			val corr = cmdId(1L)
			val action = routeAction()
			val record = TickRecord(
				tick = 1L,
				simTime = 10.0,
				stateDigest = "0".repeat(64),
				actions = listOf(AttributedAction(corr, 1L, action)),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			)
			val result = wm.update(record, observation())
			assertThat(result.pendingRequests[corr]?.action).isEqualTo(action)
		}

		@Test
		@DisplayName("pendingRequests drains exactly when matching CommandId outcome arrives")
		fun pendingDrainsOnMatchingOutcome() {
			val corr = cmdId(1L)
			val action = routeAction()
			// After tick 1: action is pending
			val wmAfterTick1 = WorkingMemory.EMPTY.copy(
				pendingRequests = mapOf(corr to AttributedAction(corr, 1L, action))
			)
			// Tick 2: outcome arrives with matching CommandId
			val outcome = outcomeFor("T-1", corr, 2L)
			val record = TickRecord(
				tick = 2L,
				simTime = 20.0,
				stateDigest = "0".repeat(64),
				actions = emptyList(),
				verdicts = emptyList(),
				outcomes = listOf(outcome)
			)
			val result = wmAfterTick1.update(record, observation())
			assertThat(result.pendingRequests).isEmpty()
		}

		@Test
		@DisplayName("only the matching entry is drained, others remain")
		fun onlyMatchingEntryDrained() {
			val corr1 = cmdId(1L, 0)
			val corr2 = cmdId(1L, 1)
			val action1 = DispatchAction.RequestRoute("T-1", "A", "B")
			val action2 = DispatchAction.RequestRoute("T-2", "C", "D")
			val wmWithTwo = WorkingMemory.EMPTY.copy(
				pendingRequests = mapOf(
					corr1 to AttributedAction(corr1, 1L, action1),
					corr2 to AttributedAction(corr2, 1L, action2)
				)
			)
			// Outcome arrives only for corr1
			val outcome = outcomeFor("T-1", corr1, 2L)
			val record = TickRecord(
				tick = 2L,
				simTime = 20.0,
				stateDigest = "0".repeat(64),
				actions = emptyList(),
				verdicts = emptyList(),
				outcomes = listOf(outcome)
			)
			val result = wmWithTwo.update(record, observation())
			assertThat(result.pendingRequests.size).isEqualTo(1)
			assertThat(result.pendingRequests[corr2]?.action).isEqualTo(action2)
		}

		@Test
		@DisplayName("NoOp actions are never added to pendingRequests")
		fun noOpNotAddedToPending() {
			val wm = WorkingMemory.EMPTY
			val corr = cmdId(1L)
			val record = TickRecord(
				tick = 1L,
				simTime = 10.0,
				stateDigest = "0".repeat(64),
				actions = listOf(AttributedAction(corr, 1L, DispatchAction.NoOp)),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			)
			val result = wm.update(record, observation())
			assertThat(result.pendingRequests).isEmpty()
		}
	}

	// ── AC: TTL expiry surfaces in droppedThisTick ────────────────────────────────────────

	@Nested
	@DisplayName("TTL expiry — surfaces in droppedThisTick, never silently dropped")
	inner class TtlExpiry {

		@Test
		@DisplayName("entry added at tick 0 expires at tick 5 (TTL=5 ticks)")
		fun expiresAfterFiveTicks() {
			val addedTick = 0L
			val corr = cmdId(addedTick)
			val action = routeAction()
			val wmWithPending = WorkingMemory.EMPTY.copy(
				pendingRequests = mapOf(corr to AttributedAction(corr, addedTick, action))
			)
			// Tick 5: TTL threshold reached (5 - 0 = 5 >= PENDING_TTL_TICKS)
			val expiryTick = addedTick + PENDING_TTL_TICKS
			val record = emptyRecord(expiryTick)
			val result = wmWithPending.update(record, observation())

			// Must be removed from pending
			assertThat(result.pendingRequests).isEmpty()
			// Must appear in droppedThisTick
			assertThat(result.droppedThisTick.size).isEqualTo(1)
			assertThat(result.droppedThisTick[0].commandId).isEqualTo(corr)
		}

		@Test
		@DisplayName("entry added at tick 0 is still pending at tick 4 (TTL not yet exceeded)")
		fun stillPendingBeforeTtl() {
			val addedTick = 0L
			val corr = cmdId(addedTick)
			val action = routeAction()
			val wmWithPending = WorkingMemory.EMPTY.copy(
				pendingRequests = mapOf(corr to AttributedAction(corr, addedTick, action))
			)
			// Tick 4: not yet expired (4 - 0 = 4 < PENDING_TTL_TICKS = 5)
			val record = emptyRecord(addedTick + PENDING_TTL_TICKS - 1)
			val result = wmWithPending.update(record, observation())

			assertThat(result.pendingRequests[corr]?.action).isEqualTo(action)
			assertThat(result.droppedThisTick).isEmpty()
		}

		@Test
		@DisplayName("droppedThisTick is reset to empty on the next update call")
		fun droppedThisTickClearedOnNextUpdate() {
			val corr = cmdId(0L)
			val action = routeAction()
			val wmWithPending = WorkingMemory.EMPTY.copy(
				pendingRequests = mapOf(corr to AttributedAction(corr, 0L, action))
			)
			// Force expiry at tick 5
			val wmAfterExpiry = wmWithPending.update(emptyRecord(PENDING_TTL_TICKS), observation())
			assertThat(wmAfterExpiry.droppedThisTick.size).isEqualTo(1)

			// Next update with no new events: droppedThisTick resets
			val wmNextTick = wmAfterExpiry.update(emptyRecord(PENDING_TTL_TICKS + 1), observation())
			assertThat(wmNextTick.droppedThisTick).isEmpty()
		}

		@Test
		@DisplayName("expired entries surface with the TTL-expiry tick")
		fun droppedOutcomeCarriesExpiryTick() {
			val corr = cmdId(0L)
			val action = routeAction()
			val wmWithPending = WorkingMemory.EMPTY.copy(
				pendingRequests = mapOf(corr to AttributedAction(corr, 0L, action))
			)
			val expiryTick = PENDING_TTL_TICKS
			val result = wmWithPending.update(emptyRecord(expiryTick), observation())

			val dropped = result.droppedThisTick[0]
			assertThat(dropped.tick).isEqualTo(expiryTick)
		}

		@Test
		@DisplayName("multiple entries can expire in the same tick")
		fun multipleEntriesExpireSameTick() {
			val corr1 = cmdId(0L, 0)
			val corr2 = cmdId(0L, 1)
			val wmWithTwo = WorkingMemory.EMPTY.copy(
				pendingRequests = mapOf(
					corr1 to AttributedAction(corr1, 0L, DispatchAction.RequestRoute("T-1", "A", "B")),
					corr2 to AttributedAction(corr2, 0L, DispatchAction.RequestRoute("T-2", "C", "D"))
				)
			)
			val result = wmWithTwo.update(emptyRecord(PENDING_TTL_TICKS), observation())
			assertThat(result.pendingRequests).isEmpty()
			assertThat(result.droppedThisTick.size).isEqualTo(2)
		}
	}

	// ── AC: lastActionPerTrain ────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("lastActionPerTrain — updated from record actions per train")
	inner class LastActionPerTrain {

		@Test
		@DisplayName("action for a train is recorded in lastActionPerTrain")
		fun actionRecordedForTrain() {
			val wm = WorkingMemory.EMPTY
			val corr = cmdId(5L)
			val record = TickRecord(
				tick = 5L,
				simTime = 50.0,
				stateDigest = "0".repeat(64),
				actions = listOf(AttributedAction(corr, 5L, routeAction("T-3"))),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			)
			val result = wm.update(record, observation())
			val lastAction = result.lastActionPerTrain["T-3"]
			assertThat(lastAction).isEqualTo(Pair("request_route", 5L))
		}

		@Test
		@DisplayName("NoOp does not create a lastActionPerTrain entry")
		fun noOpNotRecordedInLastActions() {
			val wm = WorkingMemory.EMPTY
			val corr = cmdId(5L)
			val record = TickRecord(
				tick = 5L,
				simTime = 50.0,
				stateDigest = "0".repeat(64),
				actions = listOf(AttributedAction(corr, 5L, DispatchAction.NoOp)),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			)
			val result = wm.update(record, observation())
			assertThat(result.lastActionPerTrain).isEmpty()
		}

		@Test
		@DisplayName("later action for same train overwrites the earlier entry")
		fun laterActionOverwritesEarlier() {
			val wm = WorkingMemory.EMPTY.copy(
				lastActionPerTrain = mapOf("T-1" to Pair("approve_train", 3L))
			)
			val corr = cmdId(7L)
			val record = TickRecord(
				tick = 7L,
				simTime = 70.0,
				stateDigest = "0".repeat(64),
				actions = listOf(AttributedAction(corr, 7L, routeAction("T-1"))),
				verdicts = listOf(ValidationVerdict.Valid),
				outcomes = emptyList()
			)
			val result = wm.update(record, observation())
			assertThat(result.lastActionPerTrain["T-1"]).isEqualTo(Pair("request_route", 7L))
		}

		@Test
		@DisplayName("actions for different trains are all recorded")
		fun differentTrainsAllRecorded() {
			val wm = WorkingMemory.EMPTY
			val corrA = cmdId(1L, 0)
			val corrB = cmdId(1L, 1)
			val record = TickRecord(
				tick = 1L,
				simTime = 10.0,
				stateDigest = "0".repeat(64),
				actions = listOf(
					AttributedAction(corrA, 1L, DispatchAction.ApproveTrain("T-1")),
					AttributedAction(corrB, 1L, routeAction("T-2"))
				),
				verdicts = listOf(ValidationVerdict.Valid, ValidationVerdict.Valid),
				outcomes = emptyList()
			)
			val result = wm.update(record, observation())
			assertThat(result.lastActionPerTrain.keys.containsAll(listOf("T-1", "T-2"))).isTrue()
		}
	}

	// ── AC: activeCount and capacity reflect the observation ─────────────────────────────

	@Nested
	@DisplayName("activeCount and capacity — taken from observation")
	inner class ActiveCountAndCapacity {

		@Test
		@DisplayName("activeCount reflects observation.activeCount")
		fun activeCountFromObservation() {
			val result = WorkingMemory.EMPTY.update(emptyRecord(1L), observation(activeCount = 3, capacity = 5))
			assertThat(result.activeCount).isEqualTo(3)
		}

		@Test
		@DisplayName("capacity reflects observation.capacity")
		fun capacityFromObservation() {
			val result = WorkingMemory.EMPTY.update(emptyRecord(1L), observation(activeCount = 1, capacity = 7))
			assertThat(result.capacity).isEqualTo(7)
		}
	}
}
