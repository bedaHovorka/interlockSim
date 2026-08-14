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
 * [schemaVersion] is incremented whenever fields are added or removed, or when an enum
 * vocabulary used by a stored field changes, so that the SP2c.23 aggregator can detect and
 * handle old JSON files without crashing.  Current version: **4** (version 2 added
 * [railwayOutcome], Issue #834/SP2c.11; version 3 added [fatalExceptionCount] and
 * [fatalExceptionFirstMessage], the measurement-integrity fix for #834's C2 condition).
 *
 * ### Compatibility with version 1 and 2 files — decided, not discovered
 *
 * **Older run JSONs stay readable.** [railwayOutcome], [fatalExceptionCount] and
 * [fatalExceptionFirstMessage] all carry defaults ([RailwayOutcome.UNMEASURED] and `null`
 * respectively), so kotlinx.serialization supplies them for any document that lacks the key, and
 * [RunSnapshotStore.readAll] only skips files whose version is *greater* than
 * [CURRENT_SCHEMA_VERSION]. A version 1 or 2 run therefore loads with every railway figure and
 * the fatal-exception figures **absent**, which is the literal truth about it: nothing measured
 * those figures when it ran. The alternative — decoding old runs with zeroed fields — would let a
 * pre-fix run be ranked alongside a genuinely clean one, which is the whole point of keeping
 * absent and zero apart. `DefaultRunSnapshotStoreTest` pins this against a literal version 1
 * document.
 *
 * The guarantee is specific to adding *defaulted* fields. A future field without a default would
 * make version 1 files fail to decode — `readAll` would then skip them with a WARN rather than
 * crash, but they would be silently lost from any aggregate. Give new fields defaults.
 *
 * Type-widening a field from `Long` to `Long?` (as #834 review finding #3 did to the three latency
 * fields) is backward-compatible without a version bump: a stored `0` decodes into `Long?(0)`, and
 * only *new* rule-based / pre-inference-failure runs write `null`. The "absent is not zero"
 * convention is then honest for the latency figures the way it already is for [railwayOutcome].
 *
 * This is not hypothetical for [RunParameters]: its own properties were entirely non-defaulted
 * until Issue #834 (SP2c.11) added [RunParameters.inferenceTimeoutSeconds] and
 * [RunParameters.promptVariant] in this same version-2 wave. Both were given defaults precisely
 * to avoid the hazard above — see [RunParameters]'s own "No defaults on the original six fields"
 * KDoc for the full reasoning and why [schemaVersion] did not need a further bump for them.
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
 * @property latencyP50Ms Median tick latency in milliseconds; `null` when no tick carried a
 *   meaningful latency (e.g. the rule-based arm, or an LLM run whose every cycle failed before
 *   inference started) — *not measured*, never *measured as none* (see
 *   [nearestRankPercentile]'s "absent is not zero" convention).
 * @property latencyP95Ms 95th-percentile tick latency in milliseconds; `null` when unmeasured.
 * @property latencyMaxMs Maximum tick latency in milliseconds; `null` when unmeasured.
 * @property actionsByAuthor Count of actions per [ActionAuthor] name.
 * @property unattributedApplies Count of applied decisions whose correlation entry was missing.
 * @property terminalFallbackEngaged Whether the terminal fallback guard engaged during this run.
 * @property terminalFallbackTickIndex Tick at which the terminal fallback first engaged; `null` when [terminalFallbackEngaged] is false.
 * @property c7Clean Whether no [ActionAuthor.RULE_FALLBACK] or [ActionAuthor.SAFETY_NET] actions were observed.
 * @property completedNaturally Whether the run ended with [RunEndCause.NATURAL_COMPLETION].
 * @property endCause The cause that ended the run; `null` for in-progress snapshots from [DispatcherRunRecorder.snapshot].
 * @property railwayOutcome What the railway achieved — journeys completed, trains admitted and
 *   exited, movement events, conflicts and refused reservations. Every field inside is nullable
 *   and `null` means *not measured*, never *measured as none*; see [RailwayOutcome]. Defaults to
 *   [RailwayOutcome.UNMEASURED], which is both the honest value for a run nobody measured and
 *   what keeps schema-version-1 files decodable (see "Schema versioning" above).
 * @property fatalExceptionCount Number of FATAL `SimulationException` occurrences — including
 *   subclasses such as `PathSeparatorChangeException` and `TrackOperationException`, not only the
 *   base class — found by scanning this run's log; see
 *   `cz.vutbr.fit.interlockSim.dispatcher.sweep.FatalExceptionScanner`. `null` means the scan
 *   itself could not run (log missing or unreadable) — *not measured*, never *measured as none*;
 *   `0` is the positive finding that the log was read in full and contained no FATAL marker. A
 *   nonzero value means kDisco's `SupervisorJob` absorbed a FATAL error during this run and it
 *   still completed and exited 0 — the run should be treated as a discarded data point, not a
 *   passing one, even though the gate predicate itself (deliberately) does not read this field.
 *   `null` for any run recorded before this field existed.
 * @property fatalExceptionFirstMessage The first matching log line (trimmed), verbatim; `null`
 *   whenever [fatalExceptionCount] is `null` or `0`.
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
	val latencyP50Ms: Long? = null,
	val latencyP95Ms: Long? = null,
	val latencyMaxMs: Long? = null,
	/** Count of actions per [ActionAuthor]; keys are [ActionAuthor] names. */
	val actionsByAuthor: Map<String, Long>,
	val unattributedApplies: Long,
	val terminalFallbackEngaged: Boolean,
	val terminalFallbackTickIndex: Long?,
	val c7Clean: Boolean,
	val completedNaturally: Boolean,
	val endCause: RunEndCause?,
	val railwayOutcome: RailwayOutcome = RailwayOutcome.UNMEASURED,
	val fatalExceptionCount: Long? = null,
	val fatalExceptionFirstMessage: String? = null
) {
	companion object {
		/**
		 * Current JSON schema version. Increment on breaking field changes.
		 *
		 * - **1** — SP2c.22 (#845), the original run-identity schema.
		 * - **2** — SP2c.11 (#834), added [railwayOutcome].
		 * - **3** — measurement-integrity fix for #834's C2 condition, added
		 *   [fatalExceptionCount] and [fatalExceptionFirstMessage].
		 * - **4** — Issue #909, added [RunEndCause.TERMINATED_EARLY] to distinguish a
		 *   simulation event-queue drain (deadlock) from a wall-clock TIMEOUT_ABORT.
		 *   Old run JSONs that stored `"endCause": "TIMEOUT_ABORT"` for a
		 *   [RunOutcome.TERMINATED_EARLY] headless run remain decodable: the enum value
		 *   still exists in [RunEndCause]. They will continue to report `completedNaturally
		 *   = false`, which is the correct gate behaviour.
		 */
		const val CURRENT_SCHEMA_VERSION: Int = 4
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
