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

import cz.vutbr.fit.interlockSim.dispatcher.agents.FailureReason
import cz.vutbr.fit.interlockSim.dispatcher.agents.RunOutcome
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Decorator that wraps a [KoogAgentPlanAdapter] and measures its decision-cycle reliability.
 *
 * ## Motivation (Issue #817 — Goal 10 dispatcher metrics)
 *
 * The LLM-backed [KoogAgentPlanAdapter] silently falls back to the rule-based dispatcher
 * whenever the Ollama model times out, throws, or produces an empty cycle.  Without counters
 * it is impossible to know:
 * - how often the rule-based fallback is actually used in practice, and
 * - what the Ollama decision success rate is over a full simulation run.
 *
 * [MeasuringPlanAdapter] registers itself as a [PlannerTickListener] on the wrapped adapter and:
 * 1. **Counts** every completed cycle by [TickOutcome], crediting the LLM for the outcomes the
 *    partition on [PlannerMetricsSnapshot] marks as successes.
 * 2. **Logs** a structured INFO-level entry on every non-success tick with the outcome,
 *    simulation time, and running success rate — so failures are immediately visible in the log
 *    without a GUI.
 * 3. **Exposes** a [getMetricsSnapshot] query that returns an immutable
 *    [PlannerMetricsSnapshot] mirroring the Goal 6
 *    [cz.vutbr.fit.interlockSim.sim.metrics.MetricsCollectionService] pattern.
 * 4. **Reports** a periodic INFO-level summary every [REPORT_EVERY_N_CYCLES] cycles so that
 *    long-running simulations remain observable in stdout without a dashboard.
 * 5. **Guarantees** a final summary via [logFinalSummary] whenever a caller detects the
 *    simulation has stopped, even if it ended between periodic checkpoints.
 *
 * ## Usage
 *
 * ```kotlin
 * val koogAdapter = KoogAgentPlanAdapter(agentFactory, context, fallback, commandQueue = queue, sinkHolder = sink)
 * val planner: DispatcherPlanner = MeasuringPlanAdapter(koogAdapter)
 * // Use planner as the DispatcherPlanner — capabilities, isAsynchronous, etc. are forwarded.
 * val snapshot: PlannerMetricsSnapshot = (planner as MeasuringPlanAdapter).getMetricsSnapshot()
 * ```
 *
 * ## Thread safety
 *
 * All counters use [AtomicLong]; [getMetricsSnapshot] produces an immutable copy derived from a
 * single pass over the counter map, so its stored success count can never disagree with its
 * stored breakdown (see [PlannerMetricsSnapshot]'s `init` invariant).
 * [onTick] is invoked from the coroutine running [KoogAgentPlanAdapter.plan]
 * (the `dispatcher-agent-driver` daemon thread in production).
 *
 * @param inner [KoogAgentPlanAdapter] to wrap.  This constructor registers `this` through
 *   [KoogAgentPlanAdapter.addTickListener], which fans out to every registered listener — so
 *   other observers (e.g. [cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver]'s attribution
 *   listener) keep working alongside it, in either registration order.
 *
 * @since Issue #817 (Goal 10 dispatcher metrics); moved onto [PlannerTickListener] and
 *   [TickOutcome] in Issue #713 Task 10
 */
class MeasuringPlanAdapter(
	private val inner: KoogAgentPlanAdapter
) : DispatcherPlanner by inner,
	PlannerTickListener {
	companion object {
		private val logger = KotlinLogging.logger {}

		/**
		 * Number of completed cycles between periodic summary log entries at INFO level.
		 *
		 * A value of 10 means: log a summary after cycles 10, 20, 30 … regardless of
		 * how many were successes vs fallbacks.
		 */
		const val REPORT_EVERY_N_CYCLES: Long = 10L
	}

	// ── Live atomic counters ──────────────────────────────────────────────────

	private val outcomeCounters: ConcurrentHashMap<TickOutcome, AtomicLong> =
		concurrentEnumCounters(TickOutcome.entries)

	/**
	 * Plain cycle counter, kept only to gate the periodic-summary modulo check without building
	 * a [PlannerMetricsSnapshot] first.
	 *
	 * Deliberately redundant with `outcomeCounters.values.sum()`: reading that sum on every tick
	 * to answer "is this the tenth cycle?" allocated a whole snapshot nine times out of ten
	 * (Issue #713 Task 10). It is never published: [getMetricsSnapshot] derives every figure —
	 * total cycles included — from the counter map alone, so a tick landing between the two
	 * increments cannot produce an inconsistent snapshot.
	 */
	private val cycleCount = AtomicLong(0L)

	// ── Wire listener into inner adapter ─────────────────────────────────────

	init {
		inner.addTickListener(this)
	}

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Records one completed dispatch cycle, and logs it when it is a non-success outcome or a
	 * periodic checkpoint.
	 *
	 * At most one [PlannerMetricsSnapshot] is built per tick, and none at all on a plain success
	 * tick that is not a checkpoint.
	 *
	 * @param record What happened this tick and when.
	 */
	override fun onTick(record: TickRecord) {
		// MUST NOT THROW. [CompositeTickListener] deliberately does not swallow delegate
		// exceptions, and this adapter is registered first (ExampleRegistry builds it before
		// AgentLoopDriver's init registers its own listener), so a throw here would abort the
		// fan-out and silently starve the driver's attribution listener and the run recorder —
		// re-opening the Issue #843 class of defect where every per-run JSON reported
		// totalTicks = 0. Every statement below is total: the counter map is pre-populated for
		// all TickOutcome entries so getValue cannot miss, the snapshot's require() is satisfied
		// by construction (see getMetricsSnapshot), and the logging lambdas are lazy. Pinned by
		// MeasuringPlanAdapterTest."onTick never throws ...".
		outcomeCounters.getValue(record.outcome).incrementAndGet()
		val cycles = cycleCount.incrementAndGet()
		val isFallback = !record.outcome.countsAsLlmSuccess
		val isCheckpoint = cycles % REPORT_EVERY_N_CYCLES == 0L
		if (!isFallback && !isCheckpoint) {
			return
		}
		val snapshot = getMetricsSnapshot()
		if (isFallback) {
			logger.info {
				"[MeasuringPlanAdapter] fallback: outcome=${record.outcome.name} " +
					"simTime=${record.simTime}s " +
					"fallbackTotal=${snapshot.fallbackCount} " +
					"ollamaSuccessRate=${formatRate(snapshot.ollamaSuccessRate)}"
			}
		}
		if (isCheckpoint) {
			logger.info { formatSummaryLine("summary at simTime=${record.simTime}s", snapshot) }
		}
	}

	/**
	 * Returns an immutable [PlannerMetricsSnapshot] reflecting the current accumulated counters.
	 *
	 * May be called at any point during or after a simulation run.  The returned snapshot is
	 * independent of subsequent counter updates.
	 *
	 * @return Current planner reliability KPI snapshot.
	 */
	fun getMetricsSnapshot(): PlannerMetricsSnapshot {
		val counts: Map<TickOutcome, Long> = outcomeCounters.mapValues { it.value.get() }
		// Derived from the same point-in-time copy the snapshot stores, never from a separate
		// counter: a tick landing mid-read would otherwise make the two disagree and trip
		// PlannerMetricsSnapshot's init invariant (the hazard DefaultDispatcherRunRecorder
		// documents for its own totalTicks).
		val successCount = counts.entries.sumOf { (outcome, count) -> if (outcome.countsAsLlmSuccess) count else 0L }
		return PlannerMetricsSnapshot(ollamaSuccessCount = successCount, outcomeCounts = counts)
	}

	/**
	 * Logs an unconditional final summary of the current [PlannerMetricsSnapshot].
	 *
	 * Unlike the periodic summary in [onTick] (which only fires every [REPORT_EVERY_N_CYCLES]
	 * cycles), this always logs exactly once per call — intended for callers that detect the
	 * simulation has stopped (for any reason: natural completion or manual stop) and want
	 * a guaranteed final data point, even if the run ended between periodic checkpoints.
	 *
	 * When [runOutcome] is [RunOutcome.Failed] (e.g. [FailureReason.LLM_ABANDONED]), a
	 * prominent WARN-level line is emitted **before** the INFO summary so that the failure
	 * is unmissable in the log — a run that silently degrades to rule-based and reports
	 * success is the exact failure mode the terminal fallback guard was introduced to prevent
	 * (SP2c.8, Issue #831).
	 *
	 * Safe to call with zero cycles recorded — [PlannerMetricsSnapshot.ollamaSuccessRate]
	 * is `0.0` in that case. Read-only: does not mutate any counters, so it is safe to
	 * call more than once (e.g. defensively from multiple call sites).
	 *
	 * A cycle still in flight on the `dispatcher-agent-driver` thread at the moment of the
	 * call (e.g. immediately after a manual Stop, mid-LLM-inference) is not yet counted —
	 * this reports state at the moment of the call, not a strict post-mortem of every cycle
	 * ever started.
	 *
	 * The counters span this adapter's entire lifetime (i.e. the owning context's lifetime)
	 * and are never reset — if a context were ever reused for more than one run, this would
	 * report the combined total, not just the most recent run.
	 *
	 * @param runOutcome The terminal outcome of the run. Defaults to [RunOutcome.Running]
	 *   (i.e. normal completion or run stopped without an LLM failure). When
	 *   [RunOutcome.Failed] with reason [FailureReason.LLM_ABANDONED], a WARN-level failure
	 *   banner is logged before the INFO metrics summary.
	 * @param failedAtTick The tick number at which the terminal fallback guard engaged.
	 *   Only used (and should be non-null) when [runOutcome] is [RunOutcome.Failed].
	 *
	 * @since 2026-07-29 (final metrics log on simulation stop); extended SP2c.8 Issue #831
	 */
	fun logFinalSummary(
		runOutcome: RunOutcome = RunOutcome.Running,
		failedAtTick: Long? = null
	) {
		val outcome = runOutcome
		if (outcome is RunOutcome.Failed) {
			val tickStr = if (failedAtTick != null) "at tick $failedAtTick" else "(tick unknown)"
			logger.warn {
				"*** [MeasuringPlanAdapter] FAILED (${outcome.reason.name}) $tickStr ***"
			}
		}
		logger.info { formatSummaryLine("final summary", getMetricsSnapshot()) }
		// Issue #834 review finding #6, extended by Issue #713 Task 10: successRate here is not
		// comparable to a pre-#834 run's — #834 reclassified idle ticks (former RULE_FALLBACK) to
		// LLM_NO_OP, and REVISED's cap-full no_op converts former fallback ticks into LLM
		// successes. #713 re-keyed the counters from FallbackReason onto TickOutcome; that
		// migration reclassified no outcome — LLM_SILENT_NONACTIONABLE deliberately stayed on the
		// fallback side. It can still move the rate very slightly, in one unreachable case: the
		// old PlannerCycleListener path counted TWO fallbacks for a silent cycle whose
		// fallbackDispatcher.decide() threw (EMPTY_NO_TOOLS from onFallback, then EXCEPTION from
		// plan()'s catch), where reportTick — and now this adapter — counts one. Removing that
		// double count can only raise the rate. Read it as a within-#834 figure.
		logger.info {
			"[MeasuringPlanAdapter] note: successRate is reclassified in #834 and not comparable " +
				"to pre-#834 runs (re-keyed onto TickOutcome in #713 without reclassifying any outcome)"
		}
	}

	// ── Internal helpers ──────────────────────────────────────────────────────

	/**
	 * Builds the shared `[MeasuringPlanAdapter] <label> — totalCycles=... byOutcome=[...]
	 * successRate=...` log line.
	 */
	private fun formatSummaryLine(
		label: String,
		snapshot: PlannerMetricsSnapshot
	): String {
		val byOutcomeStr =
			TickOutcome.entries.joinToString(", ") { outcome ->
				"${outcome.name}=${snapshot.outcomeCounts[outcome] ?: 0}"
			}
		// byOutcome is its own field, not a parenthetical on fallback=: it covers ALL outcomes
		// and so sums to totalCycles, not to fallbackCount. Attached to fallback= (as the
		// pre-#713 FallbackReason breakdown legitimately was) it would read as a breakdown of a
		// number it does not add up to.
		return "[MeasuringPlanAdapter] $label — " +
			"totalCycles=${snapshot.totalCycles} " +
			"ollamaSuccess=${snapshot.ollamaSuccessCount} " +
			"fallback=${snapshot.fallbackCount} " +
			"byOutcome=[$byOutcomeStr] " +
			"successRate=${formatRate(snapshot.ollamaSuccessRate)}"
	}

	private fun formatRate(rate: Double): String {
		val pct = (rate * 100.0).toLong()
		return "$pct%"
	}
}
