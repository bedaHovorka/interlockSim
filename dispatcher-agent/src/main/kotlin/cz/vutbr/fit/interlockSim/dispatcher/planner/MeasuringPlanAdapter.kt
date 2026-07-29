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
 *
 * ## Usage
 *
 * ```kotlin
 * val koogAdapter = KoogAgentPlanAdapter(agentFactory, context, fallback, commandQueue = queue)
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

	// ── Internal helpers ──────────────────────────────────────────────────────

	private fun logPeriodicSummary(simTime: Double) {
		val snapshot = getMetricsSnapshot()
		if (snapshot.totalCycles > 0L && snapshot.totalCycles % REPORT_EVERY_N_CYCLES == 0L) {
			val byReasonStr =
				FallbackReason.entries.joinToString(", ") { reason ->
					"${reason.name}=${snapshot.fallbacksByReason[reason] ?: 0}"
				}
			logger.info {
				"[MeasuringPlanAdapter] summary at simTime=${simTime}s — " +
					"totalCycles=${snapshot.totalCycles} " +
					"ollamaSuccess=${snapshot.ollamaSuccessCount} " +
					"fallback=${snapshot.fallbackCount} ($byReasonStr) " +
					"successRate=${formatRate(snapshot.ollamaSuccessRate)}"
			}
		}
	}

	private fun formatRate(rate: Double): String {
		val pct = (rate * 100.0).toLong()
		return "$pct%"
	}
}
