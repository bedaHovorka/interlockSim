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
 * handle old JSON files without crashing.  Current version: **6** (version 2 added
 * [railwayOutcome], Issue #834/SP2c.11; version 3 added [loggedFatalSimExceptionCount] and
 * [loggedFatalSimExceptionFirstMessage] under the now-superseded names; version 5 renamed those
 * fields to their current names, Issue #913; version 6 added [actionableTickRate], Issue #927).
 *
 * ### Compatibility with version 1 and 2 files — decided, not discovered
 *
 * **Older run JSONs stay readable.** [railwayOutcome], [loggedFatalSimExceptionCount] and
 * [loggedFatalSimExceptionFirstMessage] all carry defaults ([RailwayOutcome.UNMEASURED] and `null`
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
 *   Denominator is [totalTicks] — every tick, including [TickOutcome.LLM_SILENT_NONACTIONABLE]
 *   ones (Issue #927 does not change this rate's own contract; it introduces
 *   [actionableTickRate] alongside it).
 * @property actionableTickRate Fraction of *actionable* ticks (denominator: ticks where
 *   [TickOutcome.countsTowardActionableRate] is `true`, i.e. [totalTicks] minus
 *   [TickOutcome.LLM_SILENT_NONACTIONABLE] ticks) that counted as an LLM success (numerator:
 *   same [TickOutcome.countsAsLlmSuccess] ticks as [llmSuccessRate]'s numerator). `0.0` when the
 *   actionable-rate denominator is `0`. Issue #927: unlike [llmSuccessRate], a silent-but-correct
 *   tick is excluded from both numerator and denominator here, so a run with many genuinely
 *   non-actionable ticks does not get penalized (or padded) by them. Defaults to [llmSuccessRate]
 *   for a decoded pre-#927 (schema version < 6) snapshot: those files predate
 *   [TickOutcome.LLM_SILENT_NONACTIONABLE] entirely, so their `ticksByOutcome` never contains it
 *   and the two rates are, in fact, identical for that data — see [DefaultDispatcherRunRecorder]'s
 *   `logFinalSummary` for the caveat this default carries forward.
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
 * @property loggedFatalSimExceptionCount Number of `[FATAL]: … at time` occurrences — matching
 *   any `SimulationException` subclass, not only the base class — found in this run's log by
 *   [cz.vutbr.fit.interlockSim.dispatcher.sweep.FatalExceptionScanner]. The match covers both
 *   caught exceptions whose handler called `logger.warn(e)` (Logback appends the exception's
 *   `toString()` to the log line) and uncaught exceptions printed by the JVM's
 *   `Thread.uncaughtExceptionHandler`. The scanner cannot distinguish the two cases from the log
 *   text alone; a nonzero value warrants manual inspection of the log. `null` means the log scan
 *   itself could not run (log missing or unreadable) — *not measured*, never *measured as none*;
 *   `0` is the positive finding that the log was read in full and contained no FATAL marker.
 *   `null` for any run recorded before this field existed (including runs that stored
 *   `fatalExceptionCount` under the pre-rename field name).
 * @property loggedFatalSimExceptionFirstMessage The first matching log line (trimmed), verbatim;
 *   `null` whenever [loggedFatalSimExceptionCount] is `null` or `0`.
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
	/**
	 * Defaults to [llmSuccessRate] so a decoded pre-#927 (schema version < 6) snapshot — whose
	 * `ticksByOutcome` cannot contain [TickOutcome.LLM_SILENT_NONACTIONABLE] because that outcome
	 * did not exist yet — decodes to the exact value it would have computed had the field always
	 * existed: with zero such ticks, [actionableTickRate]'s numerator and denominator collapse to
	 * [llmSuccessRate]'s own. See this property's own KDoc above for the full contract.
	 */
	val actionableTickRate: Double = llmSuccessRate,
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
	val loggedFatalSimExceptionCount: Long? = null,
	val loggedFatalSimExceptionFirstMessage: String? = null
) {
	companion object {
		/**
		 * Current JSON schema version. Increment on breaking field changes.
		 *
		 * - **1** — SP2c.22 (#845), the original run-identity schema.
		 * - **2** — SP2c.11 (#834), added [railwayOutcome].
		 * - **3** — measurement-integrity fix for #834's C2 condition, added
		 *   [loggedFatalSimExceptionCount] and [loggedFatalSimExceptionFirstMessage] (under the
		 *   now-superseded names `fatalExceptionCount` / `fatalExceptionFirstMessage`).
		 * - **4** — Issue #909, added [RunEndCause.TERMINATED_EARLY] to distinguish a
		 *   simulation event-queue drain (deadlock) from a wall-clock TIMEOUT_ABORT.
		 *   Old run JSONs that stored `"endCause": "TIMEOUT_ABORT"` for a
		 *   [RunOutcome.TERMINATED_EARLY] headless run remain decodable: the enum value
		 *   still exists in [RunEndCause]. They will continue to report `completedNaturally
		 *   = false`, which is the correct gate behaviour.
		 * - **5** — Issue #913, renamed `fatalExceptionCount` →
		 *   [loggedFatalSimExceptionCount] and `fatalExceptionFirstMessage` →
		 *   [loggedFatalSimExceptionFirstMessage] to accurately describe what the scanner
		 *   detects (any FATAL SimulationException occurrence in the log — caught-and-logged
		 *   or uncaught-and-printed — not specifically absorbed/uncaught ones). Runs written
		 *   under versions 3/4 that stored `fatalExceptionCount` will decode with
		 *   [loggedFatalSimExceptionCount] = `null` (not measured), which is the honest
		 *   value: the old field name is not read by the new code.
		 * - **6** — Issue #927, added [actionableTickRate] alongside [llmSuccessRate]. A
		 *   genuinely non-actionable silent tick ([TickOutcome.LLM_SILENT_NONACTIONABLE]) is
		 *   excluded from the new [actionableTickRate]'s denominator instead of counting as a
		 *   dispatch failure; [llmSuccessRate]'s own denominator is unchanged (it still counts
		 *   every tick). Runs written under versions 1-5 predate that outcome entirely, so their
		 *   `ticksByOutcome` never contains it and [actionableTickRate] defaults to
		 *   [llmSuccessRate] on decode — the value it would have computed anyway, since that
		 *   outcome's count is structurally zero in that data.
		 */
		const val CURRENT_SCHEMA_VERSION: Int = 6

		/**
		 * Schema version that introduced [actionableTickRate] (Issue #927). Snapshots decoded
		 * from older files (schema < this) predate [TickOutcome.LLM_SILENT_NONACTIONABLE], so
		 * their [actionableTickRate] is defaulted to [llmSuccessRate] rather than genuinely
		 * measured — see [DefaultDispatcherRunRecorder]'s comparability note. Pinned to `6`
		 * (not [CURRENT_SCHEMA_VERSION]) so a future unrelated schema bump does not re-flag
		 * version-6 files, which DO carry a genuine [actionableTickRate].
		 */
		const val SCHEMA_VERSION_INTRODUCING_ACTIONABLE_TICK_RATE: Int = 6
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
