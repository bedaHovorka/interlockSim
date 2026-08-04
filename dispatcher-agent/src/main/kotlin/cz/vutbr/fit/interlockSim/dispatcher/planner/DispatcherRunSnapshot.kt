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

import cz.vutbr.fit.interlockSim.dispatcher.ApplyFailureCode
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor
import kotlinx.serialization.Serializable

/**
 * Immutable point-in-time snapshot of a single dispatcher run's accumulated metrics.
 *
 * Produced by [DispatcherRunRecorder.snapshot] (live, in-progress) or
 * [DispatcherRunRecorder.finish] (terminal, frozen).
 *
 * ## Schema versioning
 *
 * [schemaVersion] is incremented whenever fields are added or removed so that the SP2c.23
 * aggregator can detect and handle old JSON files without crashing.  Current version: **1**.
 *
 * ## Invariant
 *
 * `ticksByOutcome.values.sum() == totalTicks`
 *
 * ## Map key encoding
 *
 * Enum-keyed maps (e.g. [ticksByOutcome], [rejectionsByCode]) use `Map<String, Long>` with
 * the enum constant name as the key so that kotlinx.serialization can write/read them as plain
 * JSON objects regardless of whether the key enum is `@Serializable`. Helper extension
 * functions on [DispatcherRunSnapshot.Companion] (e.g. [ticksByOutcomeTyped]) restore the
 * typed views for callers that need them.
 *
 * @property schemaVersion Schema version — increment on any breaking field change.
 * @property runId Opaque unique identifier for the run (UUID or timestamp-based string).
 * @property arm Which dispatcher implementation was active.
 * @property params Fixed run parameters captured at run start.
 * @property totalTicks Total number of completed ticks recorded via [DispatcherRunRecorder.onTick].
 * @property ticksByOutcome Per-[TickOutcome] tick counts as string-keyed map; must sum to [totalTicks].
 * @property timeoutNoOpByCause Per-[TimeoutNoOpCause] counts for [TickOutcome.TIMEOUT_NOOP] ticks.
 * @property llmSuccessRate Fraction of ticks counted as LLM successes (0.0–1.0); `0.0` when [totalTicks] = 0.
 * @property noOpRate Fraction of ticks that were [TickOutcome.LLM_NO_OP] (0.0–1.0).
 * @property invalidOutputRate Fraction of ticks that were [TickOutcome.TIMEOUT_NOOP] (0.0–1.0).
 * @property repairSuccessRate Fraction of [TickOutcome.LLM_REPAIRED] ticks among all ticks (0.0–1.0).
 * @property emittedByActionType Count of emitted actions grouped by action kind string.
 * @property rejectionsByCode Pre-queue rejection counts per [RejectionCode] name.
 * @property applyFailuresByCode Apply-time failure counts per [ApplyFailureCode] name.
 * @property validAt1 Fraction of single-action ticks where the action was valid (0.0–1.0).
 * @property correctAt1 Fraction of valid single-action ticks that were also correct (oracle match); `null` when no oracle.
 * @property oracleAgreementAt1 Fraction of ticks where the LLM's top action matched the oracle's; `null` when no oracle.
 * @property latencyP50Ms Median tick latency in milliseconds.
 * @property latencyP95Ms 95th-percentile tick latency in milliseconds.
 * @property latencyMaxMs Maximum tick latency in milliseconds.
 * @property actionsByAuthor Count of actions per [ActionAuthor] name.
 * @property unattributedApplies Count of applied decisions whose correlation entry was missing.
 * @property terminalFallbackEngaged Whether the terminal fallback guard engaged during this run.
 * @property terminalFallbackTickIndex Tick at which the terminal fallback first engaged; `null` when [terminalFallbackEngaged] is false.
 * @property c7Clean Whether no [ActionAuthor.RULE_FALLBACK] or [ActionAuthor.SAFETY_NET] actions were observed.
 * @property completedNaturally Whether the run ended with [RunEndCause.NATURAL_COMPLETION].
 * @property endCause The cause that ended the run; `null` for in-progress snapshots from [DispatcherRunRecorder.snapshot].
 *
 * @see DispatcherRunRecorder
 * @see RunSnapshotStore
 * @since Issue #845 (SP2c.22 — run identity and per-run JSON persistence)
 */
@Serializable
data class DispatcherRunSnapshot(
	val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
	val runId: String,
	val arm: DispatcherArm,
	val params: RunParameters,
	val totalTicks: Long,
	/** Per-[TickOutcome] tick counts; keys are [TickOutcome] names. Must sum to [totalTicks]. */
	val ticksByOutcome: Map<String, Long>,
	/** Per-[TimeoutNoOpCause] counts; keys are [TimeoutNoOpCause] names. */
	val timeoutNoOpByCause: Map<String, Long>,
	val llmSuccessRate: Double,
	val noOpRate: Double,
	val invalidOutputRate: Double,
	val repairSuccessRate: Double,
	val emittedByActionType: Map<String, Long>,
	/** Pre-queue rejection counts; keys are [RejectionCode] names. */
	val rejectionsByCode: Map<String, Long>,
	/** Apply-time failure counts; keys are [ApplyFailureCode] names. */
	val applyFailuresByCode: Map<String, Long>,
	val validAt1: Double,
	val correctAt1: Double?,
	val oracleAgreementAt1: Double?,
	val latencyP50Ms: Long,
	val latencyP95Ms: Long,
	val latencyMaxMs: Long,
	/** Count of actions per [ActionAuthor]; keys are [ActionAuthor] names. */
	val actionsByAuthor: Map<String, Long>,
	val unattributedApplies: Long,
	val terminalFallbackEngaged: Boolean,
	val terminalFallbackTickIndex: Long?,
	val c7Clean: Boolean,
	val completedNaturally: Boolean,
	val endCause: RunEndCause?
) {
	companion object {
		/** Current JSON schema version. Increment on breaking field changes. */
		const val CURRENT_SCHEMA_VERSION: Int = 1
	}

	init {
		val outcomeSum = ticksByOutcome.values.sum()
		require(outcomeSum == totalTicks) {
			"ticksByOutcome.values.sum()=$outcomeSum must equal totalTicks=$totalTicks"
		}
	}
}

// ── Typed convenience views ───────────────────────────────────────────────────

/** Returns [DispatcherRunSnapshot.ticksByOutcome] with enum keys restored. */
fun DispatcherRunSnapshot.ticksByOutcomeTyped(): Map<TickOutcome, Long> =
	ticksByOutcome.mapKeys { (k, _) -> TickOutcome.valueOf(k) }

/** Returns [DispatcherRunSnapshot.timeoutNoOpByCause] with enum keys restored. */
fun DispatcherRunSnapshot.timeoutNoOpByCauseTyped(): Map<TimeoutNoOpCause, Long> =
	timeoutNoOpByCause.mapKeys { (k, _) -> TimeoutNoOpCause.valueOf(k) }

/** Returns [DispatcherRunSnapshot.rejectionsByCode] with enum keys restored. */
fun DispatcherRunSnapshot.rejectionsByCodeTyped(): Map<RejectionCode, Long> =
	rejectionsByCode.mapKeys { (k, _) -> RejectionCode.valueOf(k) }

/** Returns [DispatcherRunSnapshot.applyFailuresByCode] with enum keys restored. */
fun DispatcherRunSnapshot.applyFailuresByCodeTyped(): Map<ApplyFailureCode, Long> =
	applyFailuresByCode.mapKeys { (k, _) -> ApplyFailureCode.valueOf(k) }

/** Returns [DispatcherRunSnapshot.actionsByAuthor] with enum keys restored. */
fun DispatcherRunSnapshot.actionsByAuthorTyped(): Map<ActionAuthor, Long> =
	actionsByAuthor.mapKeys { (k, _) -> ActionAuthor.valueOf(k) }
