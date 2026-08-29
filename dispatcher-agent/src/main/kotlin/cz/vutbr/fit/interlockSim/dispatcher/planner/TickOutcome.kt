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

import cz.vutbr.fit.interlockSim.dispatcher.agents.ActionAuthor

/**
 * Exhaustive taxonomy of what happened on a single dispatcher tick, replacing the legacy two-way
 * split — a "the LLM succeeded" callback versus a three-value fallback reason — that could not
 * tell "the LLM correctly decided nothing was needed" apart from "the LLM failed to produce
 * anything": both were counted as the single legacy reason `EMPTY_NO_TOOLS`. See the
 * "Historical" section below for that taxonomy and how it maps onto this one.
 *
 * On `vyhybna.xml`, a high [LLM_NO_OP] rate is expected and healthy: most ticks genuinely
 * require no intervention, and constraint C6 makes an explicit `no_op` a first-class,
 * frequently-correct action so the model stops inventing unnecessary work. Distinguishing that
 * from a dead model ([TIMEOUT_NOOP]) is the entire point of this taxonomy.
 *
 * [LLM_NO_OP] is produced on the live path by [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter.plan]
 * in two cases (Issue #834): an idle station (no active or queued trains) with no LLM emissions,
 * and a cycle whose only tool emission(s) were an explicit `no_op`. Before Issue #834 both cases
 * were mis-scored as [RULE_FALLBACK] via the undifferentiated legacy `EMPTY_NO_TOOLS` path,
 * which is why `noOpRate` read `0` in every run measured before that fix.
 *
 * | Outcome | Meaning | [tickClass] | Counts as LLM success | Counts toward actionable-rate denominator |
 * |---|---|---|---|---|
 * | [LLM_ACTIONS] | ≥ 1 valid action emitted and accepted by the action validator | [TickClass.SUCCESS] | yes | yes |
 * | [LLM_NO_OP] | LLM **explicitly** emitted `no_op` | [TickClass.SUCCESS] | yes | yes |
 * | [LLM_REPAIRED] | First output invalid; the single repair attempt produced valid output | [TickClass.SUCCESS] | yes (also `repairSuccessCount`) | yes |
 * | [LLM_SILENT_NONACTIONABLE] | LLM answered silently (no tool emissions, no decisions) and the fallback oracle confirmed there was nothing legal to do | [TickClass.NONACTIONABLE] | no | **no** |
 * | [TIMEOUT_NOOP] | Safe do-nothing applied by the harness; carries a [TimeoutNoOpCause] | [TickClass.DEGRADED] | no | yes |
 * | [LLM_EXCEPTION] | Non-cancellation throwable from the LLM path | [TickClass.DEGRADED] | no | yes |
 * | [LLM_ABANDONED] | Terminal fallback engaged; LLM arm retired for the rest of the run | [TickClass.RUN_FAILURE] | no | yes |
 * | [RULE_FALLBACK] | A deterministic planner originated the dispatching actions this tick | [TickClass.RUN_FAILURE] | no | yes |
 *
 * ## [LLM_SILENT_NONACTIONABLE] (Issue #927 — actionable-rate metric redesign)
 *
 * Before this outcome existed, a silent LLM cycle on a non-idle station always fell back to
 * [RULE_FALLBACK] — even when the fallback dispatcher itself found nothing legal to do
 * (`fallbackDispatcher.decide(observation)` returned an empty list). That mis-scored a
 * genuinely non-actionable tick (a parked-tail regime with approved trains present but nothing
 * legal to change) as a dispatch failure, decoupling `llmSuccessRate` from actual dispatch
 * quality — one measured run scored 84.6% while the railway moved nothing (journeys 0/7).
 *
 * [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter.plan] now still consults the
 * fallback dispatcher on a silent, non-idle cycle (needed either way — to get real decisions, or
 * to discover there are none) but reports [LLM_SILENT_NONACTIONABLE] instead of [RULE_FALLBACK]
 * when that consultation returns zero decisions. [RULE_FALLBACK] is reserved for the case where
 * the fallback dispatcher actually found and dispatched something — a genuine miss the LLM should
 * have caught.
 *
 * **Oracle caveat:** `decide() == 0` is the rule dispatcher's judgment, not domain ground truth —
 * it can itself decline to act while trains wait (e.g. every path legitimately blocked). This is
 * acceptable for metric classification (a strictly more informed classification than the
 * two-way split it replaces) but is not a liveness guarantee: a run can still fail to progress
 * while reporting a clean actionable-rate. The A4 gate is therefore actionable-rate **and**
 * railway outcome (see [RunReportAggregator.runPassed]), never actionable-rate alone.
 *
 * ## Why [TickClass.NONACTIONABLE] and not [TickClass.DEGRADED] (Issue #927)
 *
 * [TickClass.DEGRADED] means the harness had to intervene with a no-dispatching-action outcome
 * because the LLM did not produce a usable result — [TIMEOUT_NOOP] and [LLM_EXCEPTION] are both
 * cases where something went wrong on the LLM path. [LLM_SILENT_NONACTIONABLE] is different in
 * kind: the LLM's silence turned out to be *correct* (the fallback oracle independently confirmed
 * nothing was actionable), so there is nothing degraded about the tick — folding it into
 * [TickClass.DEGRADED] would make a correct-but-silent tick indistinguishable from a genuine
 * harness intervention. [TickClass.NONACTIONABLE] is a fourth, dedicated bucket for exactly this
 * case, keeping [TickClass.DEGRADED]'s meaning ("the harness had to intervene") intact.
 *
 * ## Historical: the legacy fallback-reason taxonomy (removed in Issue #713)
 *
 * Until Issue #713 the planner reported through two now-deleted types: a three-value
 * fallback-reason enum, `FallbackReason` (`EMPTY_NO_TOOLS` / `TIMEOUT` / `EXCEPTION`, Issue #817)
 * and the two-callback cycle listener, `PlannerCycleListener` (`onLlmSuccess` / `onFallback`)
 * that carried it. Issue #842 added
 * this taxonomy alongside them together with a **lossy, one-directional** projection bridge;
 * Issue #713 then moved every producer and consumer onto [PlannerTickListener] and deleted all
 * three. The bridge's mapping is recorded here rather than lost with it, because it is what any
 * figure recorded before the migration has to be read through (e.g.
 * `docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md`):
 *
 * | Legacy reason | Fate | New encoding |
 * |---|---|---|
 * | `EMPTY_NO_TOOLS` | **splits** | [LLM_NO_OP] (success) when the cycle is known to have carried an explicit `no_op` emission, else [TIMEOUT_NOOP] with [TimeoutNoOpCause.EMPTY_UNPARSEABLE] (degraded) |
 * | `TIMEOUT` | renamed, semantics changed | [TIMEOUT_NOOP] with [TimeoutNoOpCause.DEADLINE_MISS] |
 * | `EXCEPTION` | renamed | [LLM_EXCEPTION] |
 *
 * `EMPTY_NO_TOOLS` alone was ambiguous — it could mean either "the LLM correctly decided to do
 * nothing" ([LLM_NO_OP]) or "the LLM produced nothing at all" ([TIMEOUT_NOOP] with
 * [TimeoutNoOpCause.EMPTY_UNPARSEABLE]). Telling those apart requires knowing whether the cycle
 * carried an *explicit* `no_op` emission — information the legacy enum did not itself carry,
 * which is exactly why the bridge had to take that fact as an extra argument instead of being a
 * pure enum-to-enum lookup, and why one legacy value had to become two opposite outcomes here.
 *
 * ### Safety rule: an empty response is never a success
 *
 * The split is only trustworthy once the loop makes `no_op` an explicit, mandatory emission
 * (SP2c.6's "exactly one action per tick, `no_op` included" contract). Until a producer can
 * independently prove that contract was satisfied for a given cycle, it MUST score the cycle on
 * the degraded side — **an empty response can never be distinguished from a dead model, so it
 * must never be scored as a success.** The bridge encoded that by defaulting its "was there an
 * explicit no_op?" argument to `false`.
 *
 * The rule outlives the bridge, because it is still what justifies two live decisions — neither
 * of which is safe to "simplify" without re-deriving it:
 *
 * - **Issue #834's classification.** [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter.plan]
 *   scores an emission-less cycle as [LLM_NO_OP] only where the silence is independently
 *   explained: an explicit `no_op` emission, or an idle station. It is also why
 *   `KoogAgentPlanAdapter.isIdleStation` is deliberately narrow — no approved and no unapproved
 *   trains, never the wider "no action was applicable". A wider predicate would readmit
 *   precisely the ambiguity the split removed, by folding genuine LLM failures on a busy station
 *   back into the success bucket.
 * - **[LLM_SILENT_NONACTIONABLE] is not a success.** The fallback oracle establishes only that
 *   nothing was legal to do this tick, not that the model was alive and chose silence — so the
 *   outcome sits in [TickClass.NONACTIONABLE], outside [countsAsLlmSuccess], and is counted on
 *   the fallback side by [PlannerMetricsSnapshot]. Promoting it to a success would score exactly
 *   the silence this taxonomy was built to stop scoring, and would move every rate already
 *   recorded.
 *
 * @see TickClass
 * @see TimeoutNoOpCause
 * @see TickRecord
 * @see PlannerTickListener
 * @since Issue #842 (Goal 10 SP2c.19 — tick-outcome taxonomy)
 */
enum class TickOutcome {
	/** ≥ 1 valid action emitted and accepted by the action validator. */
	LLM_ACTIONS,

	/** LLM **explicitly** emitted `no_op` — a correct, healthy outcome, not a fallback. */
	LLM_NO_OP,

	/** First output invalid; the single repair attempt produced valid output. */
	LLM_REPAIRED,

	/**
	 * LLM answered silently (no tool emissions, no decisions) on a non-idle station, and
	 * `fallbackDispatcher.decide(observation)` — consulted as an oracle, not to dispatch —
	 * independently confirmed there was nothing legal to do this tick. A correct-but-silent
	 * outcome, not a fallback: see [TickOutcome]'s "LLM_SILENT_NONACTIONABLE" KDoc section
	 * (Issue #927).
	 */
	LLM_SILENT_NONACTIONABLE,

	/**
	 * Safe do-nothing applied by the harness because the LLM did not produce a usable result
	 * this tick. Always carried together with a [TimeoutNoOpCause] on [TickRecord].
	 */
	TIMEOUT_NOOP,

	/**
	 * Non-cancellation throwable from the LLM path (network error, invalid tool call, etc.).
	 *
	 * **Not currently emitted by [cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter]**
	 * — that adapter maps its generic-exception catch block to [RULE_FALLBACK] (the fallback
	 * dispatcher's decisions are actually posted, so they must be attributed as a fallback,
	 * not a no-op). Issue #713 removed the legacy projection bridge that was its only other
	 * source, so no code path produces it today; it is retained for future producers and as the
	 * encoding of the legacy `EXCEPTION` reason when reading pre-#713 data. Its [toActionAuthor]
	 * mapping to
	 * [ActionAuthor.TIMEOUT_NOOP] is correct for attribution because, like [TIMEOUT_NOOP], it is
	 * a no-dispatching-action degraded outcome — but no live producer pairs it with dispatching
	 * actions, so the mapping is never exercised against a real tick (Issue #843 review).
	 */
	LLM_EXCEPTION,

	/** Terminal fallback engaged; the LLM arm is retired for the rest of the run. */
	LLM_ABANDONED,

	/** A deterministic planner originated the dispatching actions this tick. */
	RULE_FALLBACK
}

/**
 * Coarse [TickClass] severity bucket for this [TickOutcome]. See the class-level table on
 * [TickOutcome] for the full mapping.
 */
val TickOutcome.tickClass: TickClass
	get() =
		when (this) {
			TickOutcome.LLM_ACTIONS, TickOutcome.LLM_NO_OP, TickOutcome.LLM_REPAIRED -> TickClass.SUCCESS
			TickOutcome.LLM_SILENT_NONACTIONABLE -> TickClass.NONACTIONABLE
			TickOutcome.TIMEOUT_NOOP, TickOutcome.LLM_EXCEPTION -> TickClass.DEGRADED
			TickOutcome.LLM_ABANDONED, TickOutcome.RULE_FALLBACK -> TickClass.RUN_FAILURE
		}

/**
 * Whether this [TickOutcome] counts as an LLM success for reliability metrics (e.g. the
 * `ollamaSuccessCount` counter in `MeasuringPlanAdapter`). See the class-level table on
 * [TickOutcome] for the full mapping.
 */
val TickOutcome.countsAsLlmSuccess: Boolean
	get() = tickClass == TickClass.SUCCESS

/**
 * Whether this [TickOutcome] counts toward the actionable-rate denominator (Issue #927 — the
 * `actionableTickRate` metric on [DispatcherRunSnapshot]). `true` for every [TickOutcome] except
 * [TickOutcome.LLM_SILENT_NONACTIONABLE]: a tick where the fallback oracle confirmed nothing was
 * legal to do was never actionable in the first place, so excluding it from the denominator (as
 * well as the numerator, via [countsAsLlmSuccess] already being `false` for it) keeps the rate
 * from being diluted by ticks that could not have counted as a dispatch success or failure
 * either way. See the class-level table on [TickOutcome] for the full mapping.
 */
val TickOutcome.countsTowardActionableRate: Boolean
	get() = this != TickOutcome.LLM_SILENT_NONACTIONABLE

/**
 * [ActionAuthor] that should be attributed to every decision posted this tick, given this
 * [TickOutcome].
 *
 * Used by [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver] to correctly attribute a
 * live LLM-planner cycle's `commandQueue.postAll` call instead of the pre-SP2c.20-follow-up
 * default of always tagging it [ActionAuthor.LLM] (SP2c.20 follow-up, Issue #843).
 *
 * - LLM-success outcomes ([TickOutcome.LLM_ACTIONS], [TickOutcome.LLM_NO_OP],
 *   [TickOutcome.LLM_REPAIRED]) → [ActionAuthor.LLM].
 * - [TickOutcome.LLM_SILENT_NONACTIONABLE] → [ActionAuthor.LLM] as well, despite not being a
 *   [TickClass.SUCCESS] outcome: no decisions were posted either way (the tick was silent), so
 *   the author tag only affects attribution bookkeeping, not safety — and the cycle that produced
 *   the silence was still an LLM cycle, not a fallback dispatch (Issue #927).
 * - No-dispatching-action outcomes ([TickOutcome.TIMEOUT_NOOP], [TickOutcome.LLM_EXCEPTION]) →
 *   [ActionAuthor.TIMEOUT_NOOP].
 * - Deterministic-fallback outcomes ([TickOutcome.LLM_ABANDONED], [TickOutcome.RULE_FALLBACK]) →
 *   [ActionAuthor.RULE_FALLBACK] — the fallback dispatcher's decisions were actually posted.
 */
val TickOutcome.toActionAuthor: ActionAuthor
	get() =
		when (this) {
			TickOutcome.LLM_ACTIONS, TickOutcome.LLM_NO_OP, TickOutcome.LLM_REPAIRED,
			TickOutcome.LLM_SILENT_NONACTIONABLE
			-> ActionAuthor.LLM
			TickOutcome.TIMEOUT_NOOP, TickOutcome.LLM_EXCEPTION -> ActionAuthor.TIMEOUT_NOOP
			TickOutcome.LLM_ABANDONED, TickOutcome.RULE_FALLBACK -> ActionAuthor.RULE_FALLBACK
		}
