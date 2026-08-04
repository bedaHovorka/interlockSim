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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Aggregates a collection of [DispatcherRunSnapshot] values (one per file from [RunSnapshotStore])
 * into [ArmReport] instances and renders them as a Markdown document.
 *
 * ## Gate logic (A4)
 *
 * ```
 * runPassed  = completedNaturally && !terminalFallbackEngaged && c7Clean
 * gatePassed = runCount >= 10 && passingRuns >= 8 && snapshots.all { it.c7Clean }
 * ```
 *
 * A **single** non-`c7Clean` run fails the whole arm even at 10/10 completions.
 * C7 is a deterministic-component correctness gate, not a majority vote.
 *
 * ## Tables rendered
 *
 * | Table | Content |
 * |---|---|
 * | T1 | Arm comparison: one row per arm, headline metrics |
 * | T2 | Per-run detail: one row per run, outcome histogram |
 * | T3 | Failure modes: [RejectionCode] × arm — most actionable for prompt iteration |
 * | T4 | Apply failures: [ApplyFailureCode] × arm |
 * | T5 | Author attribution: [ActionAuthor] × arm |
 * | T6 | Latency: arm × tick period, p50/p95/max and deadline-miss count |
 * | T7 | Parameter sweep: one row per [RunParameters] cell |
 *
 * @param store Used to load per-run JSON snapshots for the Gradle task entry point.
 *
 * @since Issue #846 (SP2c.23 — cross-run aggregator + Markdown report + Gradle task)
 */
class RunReportAggregator(
	private val store: RunSnapshotStore
) {
	companion object {
		private val logger = KotlinLogging.logger {}

		/** Minimum run count for the arm gate. */
		private const val MIN_RUN_COUNT = 10

		/** Minimum passing-run count for the arm gate. */
		private const val MIN_PASSING_RUNS = 8

		private val REPORT_TS_FMT: DateTimeFormatter =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
	}

	// ── Public API ────────────────────────────────────────────────────────────

	/**
	 * Aggregates [snapshots] for a single arm into an [ArmReport].
	 *
	 * All snapshots are expected to share the same [DispatcherRunSnapshot.arm].
	 * If [snapshots] is empty the result has `runCount = 0` and `gatePassed = false`.
	 */
	fun aggregate(snapshots: List<DispatcherRunSnapshot>): ArmReport {
		if (snapshots.isEmpty()) {
			logger.warn { "[RunReportAggregator] aggregate called with empty snapshot list" }
			val emptyParams = RunParameters(0L, 0, 0.0, 0, "", null)
			return ArmReport(
				arm = DispatcherArm.RULE_BASED,
				params = emptyParams,
				runCount = 0,
				passingRuns = 0,
				gatePassed = false,
				medianLlmSuccessRate = 0.0,
				iqrLlmSuccessRate = 0.0,
				medianNoOpRate = 0.0,
				iqrNoOpRate = 0.0,
				medianValidAt1 = 0.0,
				iqrValidAt1 = 0.0,
				medianCorrectAt1 = null,
				iqrCorrectAt1 = null,
				p95LatencyMs = 0L,
				allC7Clean = true,
				rejectionCounts = emptyMap(),
				applyFailureCounts = emptyMap(),
				authorCounts = emptyMap(),
				snapshots = emptyList()
			)
		}

		val arm = snapshots.first().arm
		val params = snapshots.first().params

		val passingRuns = snapshots.count { runPassed(it) }
		val allC7Clean = snapshots.all { it.c7Clean }
		val runCount = snapshots.size
		val gatePassed = runCount >= MIN_RUN_COUNT && passingRuns >= MIN_PASSING_RUNS && allC7Clean

		val llmSuccessRates = snapshots.map { it.llmSuccessRate }.sorted()
		val noOpRates = snapshots.map { it.noOpRate }.sorted()
		val validAt1s = snapshots.map { it.validAt1 }.sorted()

		val hasOracle = snapshots.any { it.correctAt1 != null }
		val correctAt1s = if (hasOracle) snapshots.mapNotNull { it.correctAt1 }.sorted() else null

		val latencies = snapshots.map { it.latencyP95Ms }.sorted()

		val rejectionCounts =
			aggregateEnumCounts(
				snapshots,
				{ it.rejectionsByCodeTyped() },
				RejectionCode.entries.toList()
			)
		val applyFailureCounts =
			aggregateEnumCounts(
				snapshots,
				{ it.applyFailuresByCodeTyped() },
				ApplyFailureCode.entries.toList()
			)
		val authorCounts =
			aggregateEnumCounts(
				snapshots,
				{ it.actionsByAuthorTyped() },
				ActionAuthor.entries.toList()
			)

		return ArmReport(
			arm = arm,
			params = params,
			runCount = runCount,
			passingRuns = passingRuns,
			gatePassed = gatePassed,
			medianLlmSuccessRate = median(llmSuccessRates),
			iqrLlmSuccessRate = iqr(llmSuccessRates),
			medianNoOpRate = median(noOpRates),
			iqrNoOpRate = iqr(noOpRates),
			medianValidAt1 = median(validAt1s),
			iqrValidAt1 = iqr(validAt1s),
			medianCorrectAt1 = correctAt1s?.let { median(it) },
			iqrCorrectAt1 = correctAt1s?.let { iqr(it) },
			p95LatencyMs = percentile95(latencies),
			allC7Clean = allC7Clean,
			rejectionCounts = rejectionCounts,
			applyFailureCounts = applyFailureCounts,
			authorCounts = authorCounts,
			snapshots = snapshots
		)
	}

	/**
	 * Renders a Markdown report from [reports] — one [ArmReport] per arm.
	 *
	 * Contains tables T1–T7 as specified in SP2c.23 (Issue #846).
	 */
	fun renderMarkdown(reports: List<ArmReport>): String {
		val sb = StringBuilder()
		val ts = REPORT_TS_FMT.format(Instant.now())

		sb.appendLine("# Dispatcher Reliability Report")
		sb.appendLine()
		sb.appendLine("Generated: $ts UTC")
		sb.appendLine()

		appendT1(sb, reports)
		appendT2(sb, reports)
		appendT3(sb, reports)
		appendT4(sb, reports)
		appendT5(sb, reports)
		appendT6(sb, reports)
		appendT7(sb, reports)

		return sb.toString()
	}

	// ── Gate predicate ────────────────────────────────────────────────────────

	/**
	 * Per-run pass predicate: the run completed naturally, the terminal fallback never engaged,
	 * and no C7 violation was observed.
	 */
	fun runPassed(snapshot: DispatcherRunSnapshot): Boolean =
		snapshot.completedNaturally && !snapshot.terminalFallbackEngaged && snapshot.c7Clean

	// ── Table renderers ───────────────────────────────────────────────────────

	private fun appendT1(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## T1 Arm Comparison")
		sb.appendLine()
		sb.appendLine(
			"| Arm | Runs | Passing | Gate | LLM Success (median) | NoOp (median) | " +
				"validAt1 (median) | correctAt1 (median) | p95 latency ms | C7 clean |"
		)
		sb.appendLine(
			"|---|---|---|---|---|---|---|---|---|---|"
		)
		for (r in reports) {
			sb.appendLine(
				"| ${r.arm} " +
					"| ${r.runCount} " +
					"| ${r.passingRuns} " +
					"| ${gateSymbol(r.gatePassed)} " +
					"| ${fmtRate(r.medianLlmSuccessRate)} " +
					"| ${fmtRate(r.medianNoOpRate)} " +
					"| ${fmtRate(r.medianValidAt1)} " +
					"| ${r.medianCorrectAt1?.let { fmtRate(it) } ?: "n/a"} " +
					"| ${r.p95LatencyMs} " +
					"| ${boolSymbol(r.allC7Clean)} |"
			)
		}
		sb.appendLine()
	}

	private fun appendT2(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## T2 Per-Run Detail")
		sb.appendLine()
		sb.appendLine(
			"| Arm | RunId | Ticks | LLM_ACTIONS | LLM_NO_OP | LLM_REPAIRED | TIMEOUT_NOOP | " +
				"RULE_FALLBACK | End cause | C7 clean | Fallback tick |"
		)
		sb.appendLine(
			"|---|---|---|---|---|---|---|---|---|---|---|"
		)
		for (r in reports) {
			for (snap in r.snapshots) {
				val byOutcome = snap.ticksByOutcome
				sb.appendLine(
					"| ${snap.arm} " +
						"| ${snap.runId} " +
						"| ${snap.totalTicks} " +
						"| ${byOutcome[TickOutcome.LLM_ACTIONS.name] ?: 0L} " +
						"| ${byOutcome[TickOutcome.LLM_NO_OP.name] ?: 0L} " +
						"| ${byOutcome[TickOutcome.LLM_REPAIRED.name] ?: 0L} " +
						"| ${byOutcome[TickOutcome.TIMEOUT_NOOP.name] ?: 0L} " +
						"| ${byOutcome[TickOutcome.RULE_FALLBACK.name] ?: 0L} " +
						"| ${snap.endCause ?: "in-progress"} " +
						"| ${boolSymbol(snap.c7Clean)} " +
						"| ${snap.terminalFallbackTickIndex ?: "-"} |"
				)
			}
		}
		sb.appendLine()
	}

	private fun appendT3(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## T3 Failure Modes (Rejection Codes)")
		sb.appendLine()
		sb.appendLine(
			"> Read T3 together with T4: a **high noOpRate with low ALL_PATHS_BLOCKED** is correct " +
				"restraint; a **low noOpRate with high ALL_PATHS_BLOCKED** is thrashing."
		)
		sb.appendLine()

		val codes = RejectionCode.entries
		val header = "| Rejection Code | " + reports.joinToString(" | ") { it.arm.name } + " |"
		val separator = "|---|" + reports.joinToString("|") { "---" } + "|"
		sb.appendLine(header)
		sb.appendLine(separator)

		for (code in codes) {
			val row =
				"| $code | " +
					reports.joinToString(" | ") { r ->
						"${r.rejectionCounts[code] ?: 0L}"
					} + " |"
			sb.appendLine(row)
		}
		sb.appendLine()
	}

	private fun appendT4(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## T4 Apply Failures")
		sb.appendLine()

		val codes = ApplyFailureCode.entries
		val header = "| Apply Failure Code | " + reports.joinToString(" | ") { it.arm.name } + " |"
		val separator = "|---|" + reports.joinToString("|") { "---" } + "|"
		sb.appendLine(header)
		sb.appendLine(separator)

		for (code in codes) {
			val row =
				"| $code | " +
					reports.joinToString(" | ") { r ->
						"${r.applyFailureCounts[code] ?: 0L}"
					} + " |"
			sb.appendLine(row)
		}
		sb.appendLine()
	}

	private fun appendT5(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## T5 Author Attribution")
		sb.appendLine()
		sb.appendLine(
			"> Must be `{LLM: n, everything else: 0}` for a passing LLM arm."
		)
		sb.appendLine()

		val authors = ActionAuthor.entries
		val header = "| Action Author | " + reports.joinToString(" | ") { it.arm.name } + " |"
		val separator = "|---|" + reports.joinToString("|") { "---" } + "|"
		sb.appendLine(header)
		sb.appendLine(separator)

		for (author in authors) {
			val row =
				"| $author | " +
					reports.joinToString(" | ") { r ->
						"${r.authorCounts[author] ?: 0L}"
					} + " |"
			sb.appendLine(row)
		}
		sb.appendLine()
	}

	private fun appendT6(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## T6 Latency")
		sb.appendLine()
		sb.appendLine("| Arm | Tick period ms | p50 latency ms | p95 latency ms | Max latency ms | Deadline misses |")
		sb.appendLine("|---|---|---|---|---|---|")

		for (r in reports) {
			// Compute cross-run aggregates from raw snapshots
			val p50s = r.snapshots.map { it.latencyP50Ms }.sorted()
			val p95s = r.snapshots.map { it.latencyP95Ms }.sorted()
			val maxes = r.snapshots.map { it.latencyMaxMs }.sorted()

			val deadlineMisses =
				r.snapshots.sumOf { snap ->
					snap.ticksByOutcome[TickOutcome.TIMEOUT_NOOP.name] ?: 0L
				}

			sb.appendLine(
				"| ${r.arm} " +
					"| ${r.params.tickPeriodMs} " +
					"| ${percentile50(p50s)} " +
					"| ${percentile95(p95s)} " +
					"| ${maxes.lastOrNull() ?: 0L} " +
					"| $deadlineMisses |"
			)
		}
		sb.appendLine()
	}

	private fun appendT7(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## T7 Parameter Sweep")
		sb.appendLine()
		sb.appendLine(
			"| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Gate | " +
				"LLM Success | validAt1 | correctAt1 | p95 latency ms |"
		)
		sb.appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")

		for (r in reports) {
			val p = r.params
			sb.appendLine(
				"| ${r.arm} " +
					"| ${p.model.ifEmpty { "rule-based" }} " +
					"| ${p.temperature} " +
					"| ${p.tickPeriodMs} " +
					"| ${p.historyN} " +
					"| ${p.maxActionsPerTick} " +
					"| ${p.seed ?: "unset"} " +
					"| ${gateSymbol(r.gatePassed)} " +
					"| ${fmtRate(r.medianLlmSuccessRate)} " +
					"| ${fmtRate(r.medianValidAt1)} " +
					"| ${r.medianCorrectAt1?.let { fmtRate(it) } ?: "n/a"} " +
					"| ${r.p95LatencyMs} |"
			)
		}
		sb.appendLine()
	}

	// ── Statistics helpers ────────────────────────────────────────────────────

	private fun median(sorted: List<Double>): Double {
		if (sorted.isEmpty()) return 0.0
		val mid = sorted.size / 2
		return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
	}

	private fun iqr(sorted: List<Double>): Double {
		if (sorted.size < 2) return 0.0
		val q1 = sorted[(sorted.size - 1) / 4]
		val q3 = sorted[((sorted.size - 1) * 3) / 4]
		return q3 - q1
	}

	private fun percentile95(sorted: List<Long>): Long {
		if (sorted.isEmpty()) return 0L
		val idx = ((sorted.size - 1) * 95 / 100).coerceIn(0, sorted.size - 1)
		return sorted[idx]
	}

	private fun percentile50(sorted: List<Long>): Long {
		if (sorted.isEmpty()) return 0L
		val mid = sorted.size / 2
		return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
	}

	private fun <E : Enum<E>> aggregateEnumCounts(
		snapshots: List<DispatcherRunSnapshot>,
		extract: (DispatcherRunSnapshot) -> Map<E, Long>,
		allValues: List<E>
	): Map<E, Long> {
		val result = allValues.associateWith { 0L }.toMutableMap()
		for (snap in snapshots) {
			for ((code, count) in extract(snap)) {
				result[code] = (result[code] ?: 0L) + count
			}
		}
		return result
	}

	// ── Formatting helpers ────────────────────────────────────────────────────

	private fun fmtRate(rate: Double): String = "%.3f".format(rate)

	private fun gateSymbol(passed: Boolean): String = if (passed) "✅ PASS" else "❌ FAIL"

	private fun boolSymbol(v: Boolean): String = if (v) "yes" else "no"
}
