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
 * [MeasuringPlanAdapter] plugs into [KoogAgentPlanAdapter.cycleListener] and:
 * 1. **Counts** every LLM-success cycle and every fallback cycle (by [FallbackReason]).
 * 2. **Logs** a structured INFO-level entry on every fallback with reason code, simulation
 *    time, and running success rate — so failures are immediately visible in the log without
 *    a GUI.
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
 * All counters use [AtomicLong]; [getMetricsSnapshot] produces an immutable copy.
 * The listener callbacks are invoked from the coroutine running [KoogAgentPlanAdapter.plan]
 * (the `dispatcher-agent-driver` daemon thread in production).
 *
 * @param inner [KoogAgentPlanAdapter] to wrap.  This constructor sets [inner.cycleListener]
 *   to `this` — the listener must not be reset externally after construction.
 *
 * @since Issue #817 (Goal 10 dispatcher metrics)
 */
class MeasuringPlanAdapter(
	private val inner: KoogAgentPlanAdapter
) : DispatcherPlanner by inner {
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

	private val ollamaSuccessCount = AtomicLong(0L)
	private val fallbackCountByReason: ConcurrentHashMap<FallbackReason, AtomicLong> =
		ConcurrentHashMap<FallbackReason, AtomicLong>().also { map ->
			FallbackReason.entries.forEach { reason -> map[reason] = AtomicLong(0L) }
		}

	// ── Wire listener into inner adapter ─────────────────────────────────────

	init {
		inner.cycleListener =
			object : PlannerCycleListener {
				override fun onLlmSuccess(simTime: Double) {
					ollamaSuccessCount.incrementAndGet()
					logPeriodicSummary(simTime)
				}

				override fun onFallback(
					reason: FallbackReason,
					simTime: Double
				) {
					fallbackCountByReason.getValue(reason).incrementAndGet()
					val snapshot = getMetricsSnapshot()
					logger.info {
						"[MeasuringPlanAdapter] fallback: reason=${reason.name} " +
							"simTime=${simTime}s " +
							"fallbackTotal=${snapshot.fallbackCount} " +
							"ollamaSuccessRate=${formatRate(snapshot.ollamaSuccessRate)}"
					}
					logPeriodicSummary(simTime)
				}
			}
	}

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Returns an immutable [PlannerMetricsSnapshot] reflecting the current accumulated counters.
	 *
	 * May be called at any point during or after a simulation run.  The returned snapshot is
	 * independent of subsequent counter updates.
	 *
	 * @return Current planner reliability KPI snapshot.
	 */
	fun getMetricsSnapshot(): PlannerMetricsSnapshot {
		val successCount = ollamaSuccessCount.get()
		val byReason: Map<FallbackReason, Long> = fallbackCountByReason.mapValues { it.value.get() }
		val fallbackTotal = byReason.values.sum()
		val total = successCount + fallbackTotal
		val successRate = if (total > 0L) successCount.toDouble() / total.toDouble() else 0.0
		return PlannerMetricsSnapshot(
			ollamaSuccessCount = successCount,
			fallbackCount = fallbackTotal,
			fallbacksByReason = byReason,
			totalCycles = total,
			ollamaSuccessRate = successRate
		)
	}

	/**
	 * Logs an unconditional final summary of the current [PlannerMetricsSnapshot].
	 *
	 * Unlike [logPeriodicSummary] (which only fires every [REPORT_EVERY_N_CYCLES] cycles),
	 * this always logs exactly once per call — intended for callers that detect the
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
		// Issue #834 review finding #6: successRate here is not comparable to a pre-#834 run's —
		// #834 reclassified idle ticks (former RULE_FALLBACK) to LLM_NO_OP, and REVISED's cap-full
		// no_op converts former fallback ticks into LLM successes. Read it as a within-#834 figure.
		logger.info {
			"[MeasuringPlanAdapter] note: successRate is reclassified in #834 and not comparable " +
				"to pre-#834 runs"
		}
	}

	// ── Internal helpers ──────────────────────────────────────────────────────

	private fun logPeriodicSummary(simTime: Double) {
		val snapshot = getMetricsSnapshot()
		if (snapshot.totalCycles > 0L && snapshot.totalCycles % REPORT_EVERY_N_CYCLES == 0L) {
			logger.info { formatSummaryLine("summary at simTime=${simTime}s", snapshot) }
		}
	}

	/** Builds the shared `[MeasuringPlanAdapter] <label> — totalCycles=... successRate=...` log line. */
	private fun formatSummaryLine(
		label: String,
		snapshot: PlannerMetricsSnapshot
	): String {
		val byReasonStr =
			FallbackReason.entries.joinToString(", ") { reason ->
				"${reason.name}=${snapshot.fallbacksByReason[reason] ?: 0}"
			}
		return "[MeasuringPlanAdapter] $label — " +
			"totalCycles=${snapshot.totalCycles} " +
			"ollamaSuccess=${snapshot.ollamaSuccessCount} " +
			"fallback=${snapshot.fallbackCount} ($byReasonStr) " +
			"successRate=${formatRate(snapshot.ollamaSuccessRate)}"
	}

	private fun formatRate(rate: Double): String {
		val pct = (rate * 100.0).toLong()
		return "$pct%"
	}
}
