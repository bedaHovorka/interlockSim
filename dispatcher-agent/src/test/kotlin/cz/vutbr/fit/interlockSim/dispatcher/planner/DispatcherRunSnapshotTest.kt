/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.planner

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsOnly
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DispatcherRunSnapshot], covering the typed convenience view extension
 * functions, the `ticksByOutcome.values.sum() == totalTicks` constructor invariant, and the
 * SP2c.11 schema-version-2 railway-outcome fields and their JSON round-trip.
 *
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
class DispatcherRunSnapshotTest {
	private fun snapshotWith(
		ticksByOutcome: Map<String, Long> = mapOf(TickOutcome.LLM_ACTIONS.name to 1L),
		totalTicks: Long = 1L,
		timeoutNoOpByCause: Map<String, Long> = mapOf(TimeoutNoOpCause.DEADLINE_MISS.name to 0L),
		rejectionsByCode: Map<String, Long> = mapOf(RejectionCode.UNKNOWN_TRAIN.name to 2L),
		applyFailuresByCode: Map<String, Long> = mapOf(ApplyFailureCode.ALL_PATHS_BLOCKED.name to 3L),
		actionsByAuthor: Map<String, Long> = mapOf(ActionAuthor.LLM.name to 4L),
		railwayOutcome: RailwayOutcome = RailwayOutcome.UNMEASURED,
		fatalExceptionCount: Long? = null,
		fatalExceptionFirstMessage: String? = null
	): DispatcherRunSnapshot =
		DispatcherRunSnapshot(
			runId = "typed-view-001",
			arm = DispatcherArm.RULE_BASED,
			params =
				RunParameters(
					tickPeriodMs = 500L,
					historyN = 10,
					temperature = 0.0,
					maxActionsPerTick = 3,
					model = "",
					seed = null
				),
			totalTicks = totalTicks,
			ticksByOutcome = ticksByOutcome,
			timeoutNoOpByCause = timeoutNoOpByCause,
			llmSuccessRate = 1.0,
			noOpRate = 0.0,
			invalidOutputRate = 0.0,
			repairSuccessRate = 0.0,
			emittedByActionType = emptyMap(),
			rejectionsByCode = rejectionsByCode,
			applyFailuresByCode = applyFailuresByCode,
			validAt1 = 0.0,
			correctAt1 = null,
			oracleAgreementAt1 = null,
			latencyP50Ms = 0L,
			latencyP95Ms = 0L,
			latencyMaxMs = 0L,
			actionsByAuthor = actionsByAuthor,
			unattributedApplies = 0L,
			terminalFallbackEngaged = false,
			terminalFallbackTickIndex = null,
			c7Clean = true,
			completedNaturally = true,
			endCause = RunEndCause.NATURAL_COMPLETION,
			railwayOutcome = railwayOutcome,
			fatalExceptionCount = fatalExceptionCount,
			fatalExceptionFirstMessage = fatalExceptionFirstMessage
		)

	@Test
	fun `ticksByOutcomeTyped restores TickOutcome enum keys`() {
		val snap = snapshotWith(ticksByOutcome = mapOf(TickOutcome.LLM_ACTIONS.name to 1L))
		val typed = snap.ticksByOutcomeTyped()
		assertThat(typed.keys).containsOnly(TickOutcome.LLM_ACTIONS)
		assertThat(typed[TickOutcome.LLM_ACTIONS]).isEqualTo(1L)
	}

	@Test
	fun `timeoutNoOpByCauseTyped restores TimeoutNoOpCause enum keys`() {
		val snap = snapshotWith(timeoutNoOpByCause = mapOf(TimeoutNoOpCause.DEADLINE_MISS.name to 5L))
		val typed = snap.timeoutNoOpByCauseTyped()
		assertThat(typed.keys).containsOnly(TimeoutNoOpCause.DEADLINE_MISS)
		assertThat(typed[TimeoutNoOpCause.DEADLINE_MISS]).isEqualTo(5L)
	}

	@Test
	fun `rejectionsByCodeTyped restores RejectionCode enum keys`() {
		val snap = snapshotWith(rejectionsByCode = mapOf(RejectionCode.UNKNOWN_TRAIN.name to 2L))
		val typed = snap.rejectionsByCodeTyped()
		assertThat(typed.keys).containsOnly(RejectionCode.UNKNOWN_TRAIN)
		assertThat(typed[RejectionCode.UNKNOWN_TRAIN]).isEqualTo(2L)
	}

	@Test
	fun `applyFailuresByCodeTyped restores ApplyFailureCode enum keys`() {
		val snap = snapshotWith(applyFailuresByCode = mapOf(ApplyFailureCode.ALL_PATHS_BLOCKED.name to 3L))
		val typed = snap.applyFailuresByCodeTyped()
		assertThat(typed.keys).containsOnly(ApplyFailureCode.ALL_PATHS_BLOCKED)
		assertThat(typed[ApplyFailureCode.ALL_PATHS_BLOCKED]).isEqualTo(3L)
	}

	@Test
	fun `actionsByAuthorTyped restores ActionAuthor enum keys`() {
		val snap = snapshotWith(actionsByAuthor = mapOf(ActionAuthor.LLM.name to 4L))
		val typed = snap.actionsByAuthorTyped()
		assertThat(typed.keys).containsOnly(ActionAuthor.LLM)
		assertThat(typed[ActionAuthor.LLM]).isEqualTo(4L)
	}

	@Test
	fun `constructor rejects ticksByOutcome that does not sum to totalTicks`() {
		assertFailure {
			snapshotWith(ticksByOutcome = mapOf(TickOutcome.LLM_ACTIONS.name to 2L), totalTicks = 1L)
		}.isInstanceOf(IllegalArgumentException::class)
			.hasMessage("ticksByOutcome.values.sum()=2 must equal totalTicks=1")
	}

	// ── endCause vocabulary (Issue #909, SP2c — TERMINATED_EARLY vs TIMEOUT_ABORT) ────────────

	/**
	 * The new [RunEndCause.TERMINATED_EARLY] value (Issue #909) must survive encode→decode so a run
	 * whose event queue drained early is not silently re-filed under a different cause on reload.
	 * `snapshotWith` defaults to [RunEndCause.NATURAL_COMPLETION], so this pins the new value
	 * specifically rather than re-testing the default.
	 */
	@Test
	fun `serialization round-trips a snapshot whose endCause is TERMINATED_EARLY`() {
		val snap = snapshotWith().copy(endCause = RunEndCause.TERMINATED_EARLY, completedNaturally = false)

		val encoded = json.encodeToString(DispatcherRunSnapshot.serializer(), snap)
		assertThat(encoded).contains("\"endCause\": \"TERMINATED_EARLY\"")

		val decoded = json.decodeFromString(DispatcherRunSnapshot.serializer(), encoded)
		assertThat(decoded.endCause).isEqualTo(RunEndCause.TERMINATED_EARLY)
		assertThat(decoded.completedNaturally).isEqualTo(false)
	}

	// ── Schema version and railway outcomes (Issue #834, SP2c.11) ────────────

	@Test
	fun `current schema version is 4`() {
		assertThat(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION).isEqualTo(SCHEMA_VERSION_WITH_TERMINATED_EARLY_CAUSE)
	}

	@Test
	fun `a snapshot defaults to the current schema version, an unmeasured railway outcome, and no fatal-exception scan`() {
		val snap = snapshotWith()
		assertThat(snap.schemaVersion).isEqualTo(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)
		assertThat(snap.railwayOutcome).isEqualTo(RailwayOutcome.UNMEASURED)
		assertThat(snap.railwayOutcome.journeysCompleted).isNull()
		assertThat(snap.fatalExceptionCount).isNull()
		assertThat(snap.fatalExceptionFirstMessage).isNull()
	}

	// ── Fatal-exception fields (measurement-integrity fix for #834's C2 condition) ────────────

	@Test
	fun `serialization round-trips a snapshot carrying a measured fatal-exception count of zero`() {
		val snap = snapshotWith(fatalExceptionCount = 0L, fatalExceptionFirstMessage = null)

		val encoded = json.encodeToString(DispatcherRunSnapshot.serializer(), snap)
		assertThat(encoded).contains("\"fatalExceptionCount\": 0")

		val decoded = json.decodeFromString(DispatcherRunSnapshot.serializer(), encoded)
		assertThat(decoded.fatalExceptionCount).isEqualTo(0L)
		assertThat(decoded.fatalExceptionFirstMessage).isNull()
	}

	@Test
	fun `serialization round-trips a snapshot carrying a nonzero fatal-exception finding`() {
		val snap =
			snapshotWith(
				fatalExceptionCount = 3L,
				fatalExceptionFirstMessage = "SimulationException[FATAL]: pathToSemaphore null at time 12.5"
			)

		val encoded = json.encodeToString(DispatcherRunSnapshot.serializer(), snap)
		val decoded = json.decodeFromString(DispatcherRunSnapshot.serializer(), encoded)

		assertThat(decoded).isEqualTo(snap)
		assertThat(decoded.fatalExceptionCount).isEqualTo(3L)
		assertThat(decoded.fatalExceptionFirstMessage)
			.isEqualTo("SimulationException[FATAL]: pathToSemaphore null at time 12.5")
	}

	/**
	 * Absent must survive the JSON round-trip as absent, exactly like [RailwayOutcome]'s own
	 * absent-vs-zero guarantee. Were `fatalExceptionCount` encoded as `0` for a run whose log was
	 * never scanned, a sweep would rank that run as measured-clean rather than not-measured.
	 */
	@Test
	fun `serialization round-trips an absent fatal-exception scan as JSON null, never as zero`() {
		val snap = snapshotWith(fatalExceptionCount = null, fatalExceptionFirstMessage = null)

		val encoded = json.encodeToString(DispatcherRunSnapshot.serializer(), snap)
		assertThat(encoded).contains("\"fatalExceptionCount\": null")

		val decoded = json.decodeFromString(DispatcherRunSnapshot.serializer(), encoded)
		assertThat(decoded.fatalExceptionCount).isNull()
	}

	@Test
	fun `serialization round-trips a snapshot carrying measured railway outcomes`() {
		val snap =
			snapshotWith(
				railwayOutcome =
					RailwayOutcome(
						journeysCompleted = 5L,
						trainsEntered = 13L,
						trainsExited = 12L,
						maxConcurrentTrains = 2L,
						blockTransitions = 173L,
						conflicts = 1L,
						failedReservations = 8L
					)
			)

		val encoded = json.encodeToString(DispatcherRunSnapshot.serializer(), snap)
		val decoded = json.decodeFromString(DispatcherRunSnapshot.serializer(), encoded)

		assertThat(decoded).isEqualTo(snap)
		assertThat(decoded.schemaVersion).isEqualTo(DispatcherRunSnapshot.CURRENT_SCHEMA_VERSION)
		assertThat(decoded.railwayOutcome.blockTransitions).isEqualTo(173L)
	}

	/**
	 * Absent must survive the JSON round-trip as absent. Were `railwayOutcome` encoded with `0`
	 * placeholders, a sweep would rank a rule-based arm and an unmeasured arm identically.
	 */
	@Test
	fun `serialization round-trips absent railway outcomes as JSON null, never as zero`() {
		val snap = snapshotWith(railwayOutcome = RailwayOutcome.UNMEASURED)

		val encoded = json.encodeToString(DispatcherRunSnapshot.serializer(), snap)
		assertThat(encoded).contains("\"journeysCompleted\": null")

		val decoded = json.decodeFromString(DispatcherRunSnapshot.serializer(), encoded)
		assertThat(decoded.railwayOutcome.journeysCompleted).isNull()
		assertThat(decoded.railwayOutcome.trainsEntered).isNull()
	}

	private companion object {
		/** Pinned literally so a future bump has to touch this test deliberately. */
		private const val SCHEMA_VERSION_WITH_FATAL_EXCEPTION_FIELDS: Int = 3

		/** Pinned literally so a future bump has to touch this test deliberately. */
		private const val SCHEMA_VERSION_WITH_TERMINATED_EARLY_CAUSE: Int = 4

		private val json =
			Json {
				prettyPrint = true
				encodeDefaults = true
			}
	}
}
