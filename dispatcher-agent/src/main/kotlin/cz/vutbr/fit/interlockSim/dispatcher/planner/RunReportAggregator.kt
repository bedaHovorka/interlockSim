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
 * runPassed  = completedNaturally && !terminalFallbackEngaged && c7Clean &&
 *              (arm == RULE_BASED || actionableTickRate >= MIN_ACTIONABLE_RATE) &&
 *              (trainsExited == null || trainsExited >= MIN_TRAINS_EXITED)
 * gatePassed = runCount >= 10 && passingRuns >= 8 && snapshots.all { it.c7Clean }
 * ```
 *
 * A **single** non-`c7Clean` run fails the whole arm even at 10/10 completions.
 * C7 is a deterministic-component correctness gate, not a majority vote.
 *
 * The `actionableTickRate` threshold (Issue #927) was introduced to make the gate
 * *actionable-rate AND railway outcome*, after a measured run at `tickPeriodMs=20000` scored
 * `llmSuccessRate=0.846` on a railway that moved nothing (journeys 0/7). Issue #895 measured that
 * both of its terms were decision-hygiene terms and that neither read the railway, so the outcome
 * half is now [MIN_TRAINS_EXITED]. Both constants are measured — see their own KDoc, and
 * `docs/GOAL_10_I895_REBASELINE_REPORT.md` for the campaign that set them.
 *
 * ## Sections rendered
 *
 * | Section | Content |
 * |---|---|
 * | Arm Comparison | one row per arm, headline metrics |
 * | Per-Run Detail | one row per run, outcome histogram |
 * | Failure Modes | [RejectionCode] × arm — most actionable for prompt iteration |
 * | Apply Failures | [ApplyFailureCode] × arm |
 * | Author Attribution | [ActionAuthor] × arm |
 * | Latency | arm × tick period, p50/p95/max and deadline-miss count |
 * | Parameter Sweep | one row per [RunParameters] cell |
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

		/**
		 * Minimum [DispatcherRunSnapshot.actionableTickRate] an **LLM** run must clear to pass
		 * [runPassed] (Issue #927, measured by Issue #895).
		 *
		 * Measured, no longer a placeholder. Across #895's 40 LLM runs the rate ranged 0.647 to
		 * 1.000, and among the 23 runs that both completed naturally and cleared [MIN_TRAINS_EXITED]
		 * the lowest was **0.810**. `0.8` sits just under that, so it fails no run that campaign
		 * considers healthy while still catching a collapse in decision quality.
		 *
		 * **It is a tripwire, not a discriminator, and #895 says so plainly.** Every threshold from
		 * 0.3 to 0.6 passed 40 of 40 runs, and this value changes no verdict in that campaign either.
		 * The clause does not separate a run that moved eleven trains from one that moved none — #895
		 * measured six runs with a flawless decision record completing well and six deadlocking. That
		 * separation is the job of [MIN_TRAINS_EXITED] and of `completedNaturally`, not of this value.
		 *
		 * Not applied to [DispatcherArm.RULE_BASED] — see [runPassed].
		 */
		const val MIN_ACTIONABLE_RATE: Double = 0.8

		/**
		 * Minimum [RailwayOutcome.trainsExited] a run must reach to pass [runPassed] (Issue #895).
		 *
		 * This is the gate's **railway-outcome** term. #927 described the gate as "actionable rate
		 * AND railway outcome", but both of its terms measured decision hygiene; nothing in the
		 * predicate read what the railway actually achieved.
		 *
		 * Derived from the **deterministic control arm**, deliberately not from the LLM arm it
		 * judges: across #895's twenty rule-based runs the arm delivered exactly 11 exits every time,
		 * with zero variance, on `vyhybna.xml` at a 600 s horizon. Half of that, rounded up, is `6` —
		 * an LLM run that moves less than half of what the deterministic dispatcher moves is not
		 * doing the job.
		 *
		 * **Also a tripwire on today's data**: each of the eight runs that passed #895's gate had
		 * already exited 8 to 11 trains, and every naturally completed run below that failed on
		 * `c7Clean` first. It exists so a future throughput regression cannot pass a gate that only
		 * reads decision records.
		 *
		 * **Network- and horizon-specific.** `11` is a property of `vyhybna.xml` at 600 s with
		 * `RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS = 2`. Re-derive this constant from the
		 * control arm before reading the gate on a different network or horizon.
		 *
		 * An absent exit count is "not measured", never "zero" — see [runPassed].
		 */
		const val MIN_TRAINS_EXITED: Long = 6

		/** Column count of the Parameter Sweep "Decision Hygiene" table (for its separator row). */
		private const val HYGIENE_TABLE_COLUMNS = 22

		/** Column count of the Parameter Sweep "Railway Outcomes" table (for its separator row). */
		private const val OUTCOMES_TABLE_COLUMNS = 17

		/**
		 * Column headers of the "Arm Comparison" table — the single source for both its header row
		 * and its separator row, so adding/removing a column cannot desync the two (Issue #927
		 * review: the separator was previously hand-counted).
		 */
		private val ARM_COMPARISON_COLUMNS =
			listOf(
				"Arm",
				"Runs",
				"Passing",
				"Gate",
				"LLM Success (median)",
				"Actionable Rate (median)",
				"NoOp (median)",
				"validAt1 (median)",
				"correctAt1 (median)",
				"p95 latency ms",
				"C7 clean"
			)

		/**
		 * Column headers of the "Per-Run Detail" table — single source for its header and
		 * separator rows (Issue #927 review: the separator was previously hand-counted).
		 */
		private val PER_RUN_DETAIL_COLUMNS =
			listOf(
				"Arm",
				"RunId",
				"Ticks",
				"LLM_ACTIONS",
				"LLM_NO_OP",
				"LLM_REPAIRED",
				"LLM_SILENT_NONACTIONABLE",
				"TIMEOUT_NOOP",
				"RULE_FALLBACK",
				"Actionable Rate",
				"End cause",
				"C7 clean",
				"Fallback tick",
				"logged FATAL sim exceptions"
			)

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
				medianActionableTickRate = 0.0,
				medianNoOpRate = 0.0,
				iqrNoOpRate = 0.0,
				medianValidAt1 = 0.0,
				iqrValidAt1 = 0.0,
				medianCorrectAt1 = null,
				iqrCorrectAt1 = null,
				p95LatencyMs = null,
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
		val actionableTickRates = snapshots.map { it.actionableTickRate }.sorted()
		val noOpRates = snapshots.map { it.noOpRate }.sorted()
		val validAt1s = snapshots.map { it.validAt1 }.sorted()

		val hasOracle = snapshots.any { it.correctAt1 != null }
		val correctAt1s = if (hasOracle) snapshots.mapNotNull { it.correctAt1 }.sorted() else null

		// Skip runs whose latencyP95Ms is null (absent, not zero — see nearestRankPercentile's
		// "absent is not zero" convention) so a rule-based arm with no inference latency does not
		// pull the cross-run p95 down to 0.
		val latencies = snapshots.mapNotNull { it.latencyP95Ms }.sorted()

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
			medianActionableTickRate = median(actionableTickRates),
			medianNoOpRate = median(noOpRates),
			iqrNoOpRate = iqr(noOpRates),
			medianValidAt1 = median(validAt1s),
			iqrValidAt1 = iqr(validAt1s),
			medianCorrectAt1 = correctAt1s?.let { median(it) },
			iqrCorrectAt1 = correctAt1s?.let { iqr(it) },
			p95LatencyMs = if (latencies.isEmpty()) null else percentile95(latencies),
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
	 * Contains the seven sections specified in SP2c.23 (Issue #846).
	 */
	fun renderMarkdown(reports: List<ArmReport>): String {
		val sb = StringBuilder()
		val ts = REPORT_TS_FMT.format(Instant.now())

		sb.appendLine("# Dispatcher Reliability Report")
		sb.appendLine()
		sb.appendLine("Generated: $ts UTC")
		sb.appendLine()

		appendArmComparison(sb, reports)
		appendFatalExceptions(sb, reports)
		appendPerRunDetail(sb, reports)
		appendFailureModes(sb, reports)
		appendApplyFailures(sb, reports)
		appendAuthorAttribution(sb, reports)
		appendLatency(sb, reports)
		appendParameterSweep(sb, reports)

		return sb.toString()
	}

	// ── Gate predicate ────────────────────────────────────────────────────────

	/**
	 * Per-run pass predicate: the run completed naturally, the terminal fallback never engaged, no
	 * C7 violation was observed, its decision rate cleared [MIN_ACTIONABLE_RATE] (Issue #927) and
	 * its railway moved at least [MIN_TRAINS_EXITED] trains out (Issue #895).
	 *
	 * Two scoping rules, both measured by #895:
	 *
	 * - **The actionable-rate clause does not apply to [DispatcherArm.RULE_BASED].** That arm
	 *   consults no LLM, so it records `totalTicks = 0` and an `actionableTickRate` of `0.0`.
	 *   Applying an LLM decision-quality clause to it failed a control arm that had just delivered
	 *   eleven exits on every one of ten runs — #895 printed `| RULE_BASED | 10 | 0 | FAIL |` where
	 *   #847 and #834 had both published the same arm as 10/10 PASS.
	 * - **An absent [RailwayOutcome.trainsExited] does not fail the outcome term.** Absent is "not
	 *   measured", never "zero", the same rule [RailwayProgress] follows. That counter is absent for
	 *   any example whose main process is not a `ShuntingLoop`.
	 */
	fun runPassed(snapshot: DispatcherRunSnapshot): Boolean =
		snapshot.completedNaturally &&
			!snapshot.terminalFallbackEngaged &&
			snapshot.c7Clean &&
			actionableRateAcceptable(snapshot) &&
			railwayOutcomeAcceptable(snapshot)

	/** See [runPassed] for why the rule-based arm is exempt. */
	private fun actionableRateAcceptable(snapshot: DispatcherRunSnapshot): Boolean =
		snapshot.arm == DispatcherArm.RULE_BASED || snapshot.actionableTickRate >= MIN_ACTIONABLE_RATE

	/** See [runPassed] for why an absent exit count cannot fail a run. */
	private fun railwayOutcomeAcceptable(snapshot: DispatcherRunSnapshot): Boolean =
		snapshot.railwayOutcome.trainsExited?.let { it >= MIN_TRAINS_EXITED } ?: true

	// ── Table renderers ───────────────────────────────────────────────────────

	private fun appendArmComparison(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Arm Comparison")
		sb.appendLine()
		sb.appendLine("| " + ARM_COMPARISON_COLUMNS.joinToString(" | ") + " |")
		sb.appendLine("|" + "---|".repeat(ARM_COMPARISON_COLUMNS.size))
		for (r in reports) {
			sb.appendLine(
				"| ${r.arm} " +
					"| ${r.runCount} " +
					"| ${r.passingRuns} " +
					"| ${gateSymbol(r.gatePassed)} " +
					"| ${fmtRate(r.medianLlmSuccessRate)} " +
					"| ${fmtRate(r.medianActionableTickRate)} " +
					"| ${fmtRate(r.medianNoOpRate)} " +
					"| ${fmtRate(r.medianValidAt1)} " +
					"| ${r.medianCorrectAt1?.let { fmtRate(it) } ?: "n/a"} " +
					"| ${fmtLatency(r.p95LatencyMs)} " +
					"| ${boolSymbol(r.allC7Clean)} |"
			)
		}
		sb.appendLine()
	}

	private fun appendPerRunDetail(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Per-Run Detail")
		sb.appendLine()
		sb.appendLine("| " + PER_RUN_DETAIL_COLUMNS.joinToString(" | ") + " |")
		sb.appendLine("|" + "---|".repeat(PER_RUN_DETAIL_COLUMNS.size))
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
						"| ${byOutcome[TickOutcome.LLM_SILENT_NONACTIONABLE.name] ?: 0L} " +
						"| ${byOutcome[TickOutcome.TIMEOUT_NOOP.name] ?: 0L} " +
						"| ${byOutcome[TickOutcome.RULE_FALLBACK.name] ?: 0L} " +
						"| ${fmtRate(snap.actionableTickRate)} " +
						"| ${snap.endCause ?: "in-progress"} " +
						"| ${boolSymbol(snap.c7Clean)} " +
						"| ${snap.terminalFallbackTickIndex ?: "-"} " +
						"| ${fmtNullableLong(snap.loggedFatalSimExceptionCount)} |"
				)
			}
		}
		sb.appendLine()
	}

	/**
	 * Flags every run that recorded at least one FATAL `SimulationException` occurrence in its log
	 * (including subclasses such as `PathSeparatorChangeException`/`TrackOperationException`, not
	 * only the base class — see `FatalExceptionScanner`'s KDoc). The count covers both
	 * caught-and-logged and uncaught occurrences; the scanner cannot distinguish them from the log
	 * text alone.
	 *
	 * Rendered near the top of the report, ahead of every other section, because a flagged run
	 * had at least one FATAL `SimulationException` appear somewhere in its output. The gate
	 * predicate ([runPassed] and [ArmReport.gatePassed]) deliberately does not read
	 * [DispatcherRunSnapshot.loggedFatalSimExceptionCount] — this section exists so a human
	 * deciding whether to keep or discard a run's data has the fact in front of them, not buried
	 * in a per-run log file nobody re-opens.
	 */
	private fun appendFatalExceptions(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		val flagged = reports.flatMap { it.snapshots }.filter { (it.loggedFatalSimExceptionCount ?: 0L) > 0L }

		sb.appendLine("## Logged FATAL Simulation Exceptions")
		sb.appendLine()
		if (flagged.isEmpty()) {
			sb.appendLine(
				"No run recorded a FATAL `SimulationException` occurrence in its log. (A run whose log " +
					"could not be scanned shows `n/a`, not a count, for `logged FATAL sim exceptions` in " +
					"Per-Run Detail below — absence of a finding, not a clean bill.)"
			)
			sb.appendLine()
			return
		}

		sb.appendLine(
			"> A `FATAL` `SimulationException` was logged during these runs. This covers both caught " +
				"exceptions whose handler called `logger.warn(e)` and genuinely uncaught ones absorbed " +
				"by kDisco's `SupervisorJob`; the scanner cannot distinguish the two from the log text " +
				"alone. Inspect the per-run log for the first message below to determine the kind."
		)
		sb.appendLine()
		sb.appendLine("| Arm | RunId | Count | First message |")
		sb.appendLine("|---|---|---|---|")
		for (snap in flagged) {
			sb.appendLine(
				"| ${snap.arm} | ${snap.runId} | ${snap.loggedFatalSimExceptionCount} | " +
					"${mdEscapeCell(snap.loggedFatalSimExceptionFirstMessage)} |"
			)
		}
		sb.appendLine()
	}

	private fun appendFailureModes(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Failure Modes (Rejection Codes)")
		sb.appendLine()
		sb.appendLine(
			"> Read Failure Modes together with Apply Failures: a **high noOpRate with low ALL_PATHS_BLOCKED** is correct " +
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

	private fun appendApplyFailures(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Apply Failures")
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

	private fun appendAuthorAttribution(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Author Attribution")
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

	private fun appendLatency(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Latency")
		sb.appendLine()
		sb.appendLine("| Arm | Tick period ms | p50 latency ms | p95 latency ms | Max latency ms | Deadline misses |")
		sb.appendLine("|---|---|---|---|---|---|")

		for (r in reports) {
			// Compute cross-run aggregates from raw snapshots. Runs with a null latency figure
			// (absent, not zero) are skipped so a rule-based arm does not pull the percentiles to 0.
			val p50s = r.snapshots.mapNotNull { it.latencyP50Ms }.sorted()
			val p95s = r.snapshots.mapNotNull { it.latencyP95Ms }.sorted()
			val maxes = r.snapshots.mapNotNull { it.latencyMaxMs }.sorted()

			val deadlineMisses =
				r.snapshots.sumOf { snap ->
					snap.ticksByOutcome[TickOutcome.TIMEOUT_NOOP.name] ?: 0L
				}

			sb.appendLine(
				"| ${r.arm} " +
					"| ${r.params.tickPeriodMs} " +
					"| ${fmtLatency(if (p50s.isEmpty()) null else percentile50(p50s))} " +
					"| ${fmtLatency(if (p95s.isEmpty()) null else percentile95(p95s))} " +
					"| ${fmtLatency(maxes.lastOrNull())} " +
					"| $deadlineMisses |"
			)
		}
		sb.appendLine()
	}

	/**
	 * One row per distinct `(arm, params)` combination — the grid cells, not the arms — split
	 * across two tables that share the same parameter-identity columns as their join key.
	 *
	 * ## Why two tables (Issue #834, SP2c.11, Task 5)
	 *
	 * #834's acceptance criterion requires success/invalid-action/no-op/repair-success rate,
	 * p50/p95 latency and `c7Clean` per cell, plus the two [RunParameters] axes that previously
	 * had no column ([RunParameters.inferenceTimeoutSeconds], [RunParameters.promptVariant]) and
	 * the railway-outcome figures from [RailwayOutcome] (#895). One table with every one of those
	 * columns stopped being legible, so this renders **Decision Hygiene** (tick/action
	 * bookkeeping, the existing gate columns) and **Railway Outcomes** (what the railway actually
	 * did) as two tables built from the identical [paramsRowPrefix] columns, so a reader can join
	 * a row in one to its counterpart in the other without guessing.
	 *
	 * ## Why this is not one row per arm (Issue #847, SP2c.24)
	 *
	 * [aggregate] takes `params` from `snapshots.first()`, and [DispatcherReliabilityReport]
	 * groups runs by arm alone. A sweep over, say, two temperatures therefore rendered a **single**
	 * `LLM_TOOL_CALLING` row, labelled with whichever cell's parameters happened to be read first
	 * and with medians pooled across configurations that were never meant to be pooled. A section
	 * called "Parameter Sweep" that cannot show two parameter values is worse than absent: it
	 * reports a number for a configuration that was never run.
	 *
	 * Re-grouping happens here rather than in the caller so every producer of a report — the
	 * Gradle task, the sweep driver, a test — gets it, and so [ArmReport] keeps its "one arm,
	 * one configuration" meaning everywhere else.
	 *
	 * ## Ranking (Issue #906)
	 *
	 * Both tables are ordered by [RailwayOutcome.trainsExited] (summed per cell, descending; cells
	 * with nothing measured sort last) rather than [RailwayOutcome.journeysCompleted] — see
	 * [appendParameterSweepLegend] for why.
	 */
	private fun appendParameterSweep(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Parameter Sweep")
		sb.appendLine()
		sb.appendLine("> One row per distinct parameter cell. `Runs`/`Passing` are that cell's own.")
		sb.appendLine()

		val cellReports =
			reports.flatMap { report ->
				if (report.snapshots.isEmpty()) {
					// An arm with no snapshots still renders a placeholder Parameter Sweep row:
					// [aggregate] fills its `params` with the default RunParameters (rule-based arm,
					// unset seed), and the railway figures render as `n/a`. This is the intentional
					// fallback pinned by `renderMarkdown falls back to placeholders …` and `… zero
					// runs renders both tables without throwing` — an empty arm must show it exists
					// with defaults, not vanish from the sweep.
					listOf(report)
				} else {
					report.snapshots
						.groupBy { it.params }
						.entries
						.sortedBy { it.key.toString() }
						.map { (_, cellSnapshots) -> aggregate(cellSnapshots) }
				}
			}

		val ranked =
			cellReports
				.map { it to aggregateRailwayOutcome(it.snapshots) }
				.sortedWith(
					compareByDescending<Pair<ArmReport, RailwayOutcome>> { it.second.trainsExited ?: Long.MIN_VALUE }
						.thenBy { it.first.params.toString() }
				)

		appendParameterSweepLegend(sb)
		appendHygieneTable(sb, ranked)
		appendOutcomesTable(sb, ranked)
	}

	private fun appendHygieneTable(
		sb: StringBuilder,
		ranked: List<Pair<ArmReport, RailwayOutcome>>
	) {
		sb.appendLine("### Decision Hygiene")
		sb.appendLine()
		sb.appendLine(
			"| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | " +
				"Runs | Passing | Gate | LLM Success | Invalid-action rate | No-op rate | " +
				"Repair-success rate † | validAt1 | correctAt1 | p50 latency ms | p95 latency ms | " +
				"C7 clean | RULE_FALLBACK ticks |"
		)
		sb.appendLine("|" + "---|".repeat(HYGIENE_TABLE_COLUMNS))

		for ((r, _) in ranked) {
			val invalidActionRate = medianInvalidActionRate(r.snapshots)
			val repairRate = median(r.snapshots.map { it.repairSuccessRate }.sorted())
			val p50s = r.snapshots.mapNotNull { it.latencyP50Ms }.sorted()
			val p50 = if (p50s.isEmpty()) null else percentile50(p50s)
			val ruleFallbackTicks = r.snapshots.sumOf { it.ticksByOutcome[TickOutcome.RULE_FALLBACK.name] ?: 0L }
			sb.appendLine(
				paramsRowPrefix(r) +
					"| ${r.runCount} " +
					"| ${r.passingRuns} " +
					"| ${gateSymbol(r.gatePassed)} " +
					"| ${fmtRate(r.medianLlmSuccessRate)} " +
					"| ${fmtRateNullable(invalidActionRate)} " +
					"| ${fmtRate(r.medianNoOpRate)} " +
					"| ${fmtRate(repairRate)} " +
					"| ${fmtRate(r.medianValidAt1)} " +
					"| ${r.medianCorrectAt1?.let { fmtRate(it) } ?: "n/a"} " +
					"| ${fmtLatency(p50)} " +
					"| ${fmtLatency(r.p95LatencyMs)} " +
					"| ${boolSymbol(r.allC7Clean)} " +
					"| $ruleFallbackTicks |"
			)
		}
		sb.appendLine()
	}

	private fun appendOutcomesTable(
		sb: StringBuilder,
		ranked: List<Pair<ArmReport, RailwayOutcome>>
	) {
		sb.appendLine("### Railway Outcomes")
		sb.appendLine()
		sb.appendLine(
			"| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | " +
				"Runs | Journeys completed | Trains entered | Trains exited (authoritative) | Max concurrent | " +
				"Block transitions | Conflicts | Failed reservations |"
		)
		sb.appendLine("|" + "---|".repeat(OUTCOMES_TABLE_COLUMNS))

		for ((r, outcome) in ranked) {
			sb.appendLine(
				paramsRowPrefix(r) +
					"| ${r.runCount} " +
					"| ${fmtNullableLong(outcome.journeysCompleted)} " +
					"| ${fmtNullableLong(outcome.trainsEntered)} " +
					"| ${fmtNullableLong(outcome.trainsExited)} " +
					"| ${fmtNullableLong(outcome.maxConcurrentTrains)} " +
					"| ${fmtNullableLong(outcome.blockTransitions)} " +
					"| ${fmtNullableLong(outcome.conflicts)} " +
					"| ${fmtNullableLong(outcome.failedReservations)} |"
			)
		}
		sb.appendLine()
	}

	/**
	 * Legend for the two Parameter Sweep tables. Several of these metrics have names that sound
	 * interchangeable and are not — see each bullet for the exact numerator and denominator.
	 */
	private fun appendParameterSweepLegend(sb: StringBuilder) {
		sb.appendLine("### Legend")
		sb.appendLine()
		sb.appendLine(
			"- **LLM Success** — median `llmSuccessRate` (ticks counted as an LLM success ÷ total ticks) " +
				"across the cell's runs. **Not comparable to pre-#834 runs:** #834 reclassified idle ticks " +
				"(former `RULE_FALLBACK`) to `LLM_NO_OP` and REVISED's cap-full `no_op` converts former " +
				"fallback ticks into LLM successes, so this rate is structurally higher than a pre-#834 " +
				"run's even at identical railway behaviour — read it as a within-#834 comparison only."
		)
		sb.appendLine(
			"- **Invalid-action rate** — rejected actions ÷ emitted actions (`rejectionsByCode` sum " +
				"÷ `emittedByActionType` sum), median across the cell's runs. This is **not** the same " +
				"figure as `invalidOutputRate` (`TIMEOUT_NOOP` ticks ÷ total ticks): one counts actions, " +
				"the other counts ticks, and they can disagree sharply on the same run. `n/a` when no run in " +
				"the cell emitted any action."
		)
		sb.appendLine("- **No-op rate** — `noOpRate`: `LLM_NO_OP` ticks ÷ total ticks, median across the cell's runs.")
		sb.appendLine(
			"- **Repair-success rate †** — `repairSuccessRate`: `LLM_REPAIRED` ticks ÷ total ticks. " +
				"† As of SP2c.11, `LLM_REPAIRED` has no live producer on the async path, so this column is " +
				"structurally `0` for every cell — read it as \"not yet measurable\", not as a finding."
		)
		sb.appendLine(
			"- **p50 / p95 latency ms** — median / 95th percentile of the cell's per-run tick-latency " +
				"percentiles. `—` means no run in the cell measured inference latency (rule-based arm, or " +
				"every cycle failed before inference started) — *not measured*, never *measured as none*."
		)
		sb.appendLine(
			"- **C7 clean** — whether every run in the cell was C7-clean (no RULE_FALLBACK/SAFETY_NET-authored " +
				"action)."
		)
		sb.appendLine(
			"- **RULE_FALLBACK ticks** — total ticks across the cell's runs where the rule dispatcher ran as " +
				"fallback, from `ticksByOutcome`. Per #847, a cell can show `C7 clean = yes` while this is " +
				"nonzero: `c7Clean` counts attributed *actions*, not whether the rule dispatcher ran at all."
		)
		sb.appendLine(
			"- **Journeys completed / Trains entered / Trains exited / Max concurrent / Block transitions / " +
				"Conflicts / Failed reservations** — RailwayOutcome fields, summed across the cell's runs " +
				"(max, for \"max concurrent\"), skipping any run where the figure was not measured. `n/a` " +
				"when no run in the cell measured it — never rendered as `0`, which would misrepresent " +
				"\"not measured\" as \"measured as none\"."
		)
		sb.appendLine(
			"- **Ranking (issue #906)** — both tables are ranked by **Trains exited**, descending, cells " +
				"with no measured `trainsExited` sorted last. Not by `journeysCompleted`: that counter " +
				"increments whenever a train's reservation count reaches zero, with no termination or " +
				"movement predicate, so a stale swept route can credit a journey to a train that never " +
				"moved, and the counter can fire more than once per train. `trainsExited` is " +
				"termination-gated and is the authoritative per-cell outcome figure; `journeysCompleted` is " +
				"still reported alongside it, for comparison only."
		)
		sb.appendLine()
	}

	/**
	 * The [snapshots]'s aggregate [RailwayOutcome] for one Parameter Sweep cell: each figure
	 * summed across the cell's runs (max, for [RailwayOutcome.maxConcurrentTrains], since that
	 * figure is itself already a peak), skipping any run where the figure is `null`. A field is
	 * `null` on the result only when **every** run in [snapshots] left it unmeasured — see
	 * [RailwayOutcome]'s "Absent is not zero" KDoc.
	 */
	private fun aggregateRailwayOutcome(snapshots: List<DispatcherRunSnapshot>): RailwayOutcome {
		val outcomes = snapshots.map { it.railwayOutcome }
		return RailwayOutcome(
			journeysCompleted = reduceOrNull(outcomes.map { it.journeysCompleted }) { it.sum() },
			trainsEntered = reduceOrNull(outcomes.map { it.trainsEntered }) { it.sum() },
			trainsExited = reduceOrNull(outcomes.map { it.trainsExited }) { it.sum() },
			maxConcurrentTrains = reduceOrNull(outcomes.map { it.maxConcurrentTrains }) { it.max() },
			blockTransitions = reduceOrNull(outcomes.map { it.blockTransitions }) { it.sum() },
			conflicts = reduceOrNull(outcomes.map { it.conflicts }) { it.sum() },
			failedReservations = reduceOrNull(outcomes.map { it.failedReservations }) { it.sum() }
		)
	}

	/** Applies [reduce] to the non-null entries of [values]; `null` when none of them are present. */
	private fun reduceOrNull(
		values: List<Long?>,
		reduce: (List<Long>) -> Long
	): Long? {
		val present = values.filterNotNull()
		return if (present.isEmpty()) null else reduce(present)
	}

	/**
	 * Median, across [snapshots], of each run's *action-level* invalid rate: rejected actions
	 * (sum of [DispatcherRunSnapshot.rejectionsByCode]) divided by emitted actions (sum of
	 * [DispatcherRunSnapshot.emittedByActionType]).
	 *
	 * This is deliberately **not** [DispatcherRunSnapshot.invalidOutputRate], which is tick-scoped
	 * (`TIMEOUT_NOOP` ticks ÷ total ticks). A run can emit zero actions across every tick it ran
	 * and still have `invalidOutputRate = 0.0`, or emit many actions with a nonzero rejection
	 * share while never timing out a whole tick — the two denominators measure different things.
	 * A run where no action was ever emitted contributes no rate to the median (skipped, not
	 * counted as `0.0`); the overall result is `null` only when *no* run in [snapshots] emitted
	 * any action.
	 */
	private fun medianInvalidActionRate(snapshots: List<DispatcherRunSnapshot>): Double? {
		val rates =
			snapshots.mapNotNull { snap ->
				val emitted = snap.emittedByActionType.values.sum()
				val rejected = snap.rejectionsByCode.values.sum()
				if (emitted > 0) rejected.toDouble() / emitted else null
			}
		return if (rates.isEmpty()) null else median(rates.sorted())
	}

	/**
	 * Shared parameter-identity columns rendered identically, in the same order, in both
	 * Parameter Sweep tables — the join key a reader uses to match a [ArmReport]'s hygiene row to
	 * its outcomes row without guessing.
	 */
	private fun paramsRowPrefix(r: ArmReport): String {
		val p = r.params
		return "| ${r.arm} " +
			"| ${p.model.ifEmpty { "rule-based" }} " +
			"| ${p.temperature} " +
			"| ${p.tickPeriodMs} " +
			"| ${p.historyN} " +
			"| ${p.maxActionsPerTick} " +
			"| ${p.seed ?: "unset"} " +
			"| ${p.inferenceTimeoutSeconds} " +
			"| ${p.promptVariant} "
	}

	// ── Statistics helpers ────────────────────────────────────────────────────

	private fun median(sorted: List<Double>): Double {
		if (sorted.isEmpty()) return 0.0
		val mid = sorted.size / 2
		return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
	}

	/**
	 * Coarse interquartile range for the cross-run latency spread. Uses crude hinge indices
	 * (`(n-1)/4` and `3(n-1)/4`) rather than a proper interpolated hinge — acceptable as a
	 * noise-floor indicator at the sweep's small n (10 per arm), where the difference is a
	 * fraction of a sample and the value is read as "is there spread at all", not as a precise
	 * quartile.
	 */
	private fun iqr(sorted: List<Double>): Double {
		if (sorted.size < 2) return 0.0
		val q1 = sorted[(sorted.size - 1) / 4]
		val q3 = sorted[((sorted.size - 1) * 3) / 4]
		return q3 - q1
	}

	/**
	 * Cross-run 95th percentile of per-run latencies. Uses a **linear-index** formula
	 * `idx = (n-1)*95/100`, deliberately different from the **per-run** [nearestRankPercentile]
	 * (`ceil(p/100*n)`). The two answer different questions: per-run percentiles describe a
	 * single run's tick-latency distribution (nearest-rank, the conventional single-sample
	 * estimator), while this one aggregates *one latency figure per run* across the arm's N runs
	 * (linear interpolation across runs). Both are only ever called on a non-empty list — the
	 * `0L` empty guard is unreachable from [appendLatency], which skips null/empty latency arms
	 * before calling this.
	 */
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

	/** As [fmtRate], but `n/a` for a [rate] that was never measured rather than a bare `0.000`. */
	private fun fmtRateNullable(rate: Double?): String = rate?.let { fmtRate(it) } ?: "n/a"

	/** `n/a` for a [value] that was never measured, never a bare `0` that would misread as a finding. */
	private fun fmtNullableLong(value: Long?): String = value?.toString() ?: "n/a"

	/**
	 * `—` for a latency that was never measured (rule-based / pre-inference-failure runs), never a
	 * bare `0` that would misread as "the model answered in 0 ms" — the "absent is not zero"
	 * convention [nearestRankPercentile] documents.
	 */
	private fun fmtLatency(value: Long?): String = value?.toString() ?: "—"

	private fun gateSymbol(passed: Boolean): String = if (passed) "✅ PASS" else "❌ FAIL"

	private fun boolSymbol(v: Boolean): String = if (v) "yes" else "no"

	/** Escapes [value] for a single Markdown table cell: no bare `|` or newline can break the row. */
	private fun mdEscapeCell(value: String?): String = (value ?: "").replace("|", "\\|").replace("\n", " ")
}
