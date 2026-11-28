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
import cz.vutbr.fit.interlockSim.sim.metrics.RunEndClassification
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Aggregates a collection of [DispatcherRunSnapshot] values (one per file from [RunSnapshotStore])
 * into [ArmReport] instances and renders them as a Markdown document.
 *
 * ## Gate logic (A4)
 *
 * ```
 * runPassed  = completedNaturally && !terminalFallbackEngaged && c7Clean &&
 *              (arm == RULE_BASED || actionableTickRate >= MIN_ACTIONABLE_RATE) &&
 *              exitTermAcceptable
 * exitTermAcceptable = bar == null || trainsExited == null ||
 *              trainsExited - (wrongExits ?: 0) >= bar      // bar = ExitPassBars.barFor(example)
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
 * half is now the exit bar. Issue #1315 made that bar per example ([ExitPassBars]): it counts exits
 * by the timetabled InOut, and [MIN_TRAINS_EXITED] is the #895 vyhybna entry of that table. Both
 * thresholds are measured — see their own KDoc, and `docs/GOAL_10_SP2C14_RELIABILITY_REPORT.md`
 * §12.2 for how the #895 values were derived.
 *
 * ## Sections rendered
 *
 * | Section | Content |
 * |---|---|
 * | Arm Comparison | one row per arm, headline metrics |
 * | Logged FATAL Simulation Exceptions | FATAL runs per arm, then each run that logged one |
 * | Per-Run Detail | one row per run, outcome histogram |
 * | Failure Modes | [RejectionCode] × arm — most actionable for prompt iteration |
 * | Apply Failures | [ApplyFailureCode] × arm, then stale-origin refusals after a silent cycle per arm |
 * | Author Attribution | [ActionAuthor] × arm |
 * | Skipped NEXT SECTION Offers | per arm, offered hops the LLM left unrequested on fallback-skipping cycles |
 * | Latency | arm × tick period, p50/p95/max and deadline-miss count |
 * | Parameter Sweep | one row per cell: [RunParameters] and example ([ParameterSweepCellKey], Issue #1333) |
 *
 * @param store Used to load per-run JSON snapshots for the Gradle task entry point.
 *
 * @since Issue #846 (SP2c.23 — cross-run aggregator + Markdown report + Gradle task)
 */
class RunReportAggregator(
	private val store: RunSnapshotStore
) {
	companion object {
		/** The arms whose runs an LLM issues; a run of one counts only with an LLM command source (#1479). */
		private val LLM_ARMS: Set<DispatcherArm> = setOf(DispatcherArm.LLM_TOOL_CALLING, DispatcherArm.LLM_CONSTRAINED_JSON)

		private val logger = KotlinLogging.logger {}

		/** Minimum run count for the arm gate. */
		private const val MIN_RUN_COUNT = 10

		/** Figure columns of the skipped NEXT SECTION offers table, between "Runs" and "Runs measured". */
		private const val SKIPPED_OFFER_FIGURE_COLUMNS = 6

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
		 * The #895 exit bar of the vyhybna examples (`shuntingLoop`, `shuntingLoopAI`, and a run file
		 * without an example) in [ExitPassBars]: the minimum count of trains that left by their
		 * **timetabled InOut** a run must reach to pass [runPassed]. Since Issue #1315 every other
		 * example has a bar of its own, measured the same way, or none.
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
		 * `RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS = 2`. Issue #1315 measured the same arm
		 * at 15 exits (all by the own InOut); the bar stays `6` by ruling, so the #895 verdicts do
		 * not move. It stays a tripwire.
		 *
		 * An absent exit count is "not measured", never "zero" — see [runPassed].
		 */
		const val MIN_TRAINS_EXITED: Long = 6

		/**
		 * The text a verdict carries when the run's example has no measured exit bar in [ExitPassBars]
		 * (Issue #1315): such a run gets no exit check, and the report must not let that read as a
		 * pass against a bar.
		 */
		const val EXIT_BAR_NOT_MEASURED = "exit bar not measured"

		/** Column count of the Parameter Sweep "Decision Hygiene" table (for its separator row). */
		private const val HYGIENE_TABLE_COLUMNS = 25

		/** Column count of the Parameter Sweep "Railway Outcomes" table (for its separator row). */
		private const val OUTCOMES_TABLE_COLUMNS = 25

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
				"LLM_SILENT_ALL_MOVING",
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
	 *
	 * Issue #1479: a run of an LLM arm counts only when its command source is an LLM
	 * ([countsInArmReport]); a scripted CI twin carries an LLM arm and author `LLM`, so without
	 * this filter it would count in an LLM campaign (ADR part 1 §3.4 of #1474). When every snapshot
	 * is left out, the report is empty and keeps the snapshots' arm.
	 */
	fun aggregate(snapshots: List<DispatcherRunSnapshot>): ArmReport {
		val counted = snapshots.filter(::countsInArmReport)
		if (counted.isEmpty()) {
			if (snapshots.isEmpty()) {
				logger.warn { "[RunReportAggregator] aggregate called with empty snapshot list" }
			}
			return emptyReport(snapshots.firstOrNull()?.arm ?: DispatcherArm.RULE_BASED)
		}
		return aggregateCounted(counted)
	}

	/**
	 * Whether [snapshot] counts in its arm's report (Issue #1479): every run of an arm without an
	 * LLM, and a run of an LLM arm only when [commandSourceOrDerived] is [CommandSourceKind.LLM].
	 * A file before the `commandSource` field derives LLM from its arm and still counts.
	 */
	fun countsInArmReport(snapshot: DispatcherRunSnapshot): Boolean =
		snapshot.arm !in LLM_ARMS || snapshot.commandSourceOrDerived().kind == CommandSourceKind.LLM

	private fun emptyReport(arm: DispatcherArm): ArmReport {
		val emptyParams = RunParameters(0L, 0, 0.0, 0, "", null)
		return ArmReport(
			arm = arm,
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

	private fun aggregateCounted(snapshots: List<DispatcherRunSnapshot>): ArmReport {
		val arm = snapshots.first().arm
		val params = snapshots.first().params

		val passingRuns = snapshots.count { runPassed(it) }
		val allC7Clean = snapshots.all { it.c7Clean }
		val runCount = snapshots.size
		// Issue #676: a run without a dispatcher measures no dispatcher, so its arm never passes.
		val gatePassed =
			arm.hasDispatcher && runCount >= MIN_RUN_COUNT && passingRuns >= MIN_PASSING_RUNS && allC7Clean

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
	 * One [ArmReport] per arm, in [DispatcherArm] order: every arm with a dispatcher, even one
	 * without runs (shown with zero runs), and [DispatcherArm.NO_DISPATCHER] only when it has runs
	 * (Issue #676), so a report over dispatcher runs keeps its layout.
	 *
	 * @since Issue #676 (Goal 6 SP5)
	 */
	fun aggregateByArm(snapshots: List<DispatcherRunSnapshot>): List<ArmReport> {
		// Issue #1479: a scripted run of an LLM arm is left out here as well as in aggregate(), so it
		// neither counts nor makes the arm appear.
		val byArm = snapshots.filter(::countsInArmReport).groupBy { it.arm }
		return DispatcherArm.entries
			.filter { it.hasDispatcher || it in byArm }
			.map { arm ->
				// aggregate() reports RULE_BASED for an empty list, having no snapshot to read the
				// arm from; put the right arm back so an empty arm does not misreport itself.
				aggregate(byArm[arm] ?: emptyList()).let { if (it.runCount == 0) it.copy(arm = arm) else it }
			}
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
		appendSkippedNextSectionOffers(sb, reports)
		appendLatency(sb, reports)
		appendParameterSweep(sb, reports)

		return sb.toString()
	}

	// ── Gate predicate ────────────────────────────────────────────────────────

	/**
	 * Per-run pass predicate: the run completed naturally, the terminal fallback never engaged, no
	 * C7 violation was observed, its decision rate cleared [MIN_ACTIONABLE_RATE] (Issue #927) and
	 * its railway moved at least its example's exit bar of trains out by their timetabled InOut
	 * (Issue #895, per example since Issue #1315).
	 *
	 * **The exit term (Issue #1315 ruling).** The bar is [ExitPassBars.barFor] of
	 * [DispatcherRunSnapshot.example]. Wrong exits count against a pass by **subtraction**, not as a
	 * veto: `trainsExited - (wrongExits ?: 0) >= bar`. A veto is wrong because the own-exit fallback
	 * (#1299) is designed degraded operation, and the bar was derived from the control arm's correctly
	 * delivered exits. An example without a bar gets no exit check; the report then shows
	 * [EXIT_BAR_NOT_MEASURED] on its verdict, never a silent pass against the vyhybna bar.
	 *
	 * Two scoping rules, both measured by #895:
	 *
	 * - **The actionable-rate clause does not apply to [DispatcherArm.RULE_BASED].** That arm
	 *   consults no LLM, so it records `totalTicks = 0` and an `actionableTickRate` of `0.0`.
	 *   Applying an LLM decision-quality clause to it failed a control arm that had just delivered
	 *   eleven exits on every one of ten runs — #895 printed `| RULE_BASED | 10 | 0 | FAIL |` where
	 *   #847 and #834 had both published the same arm as 10/10 PASS. [DispatcherArm.NO_DISPATCHER]
	 *   is exempt for the same reason (Issue #676): with no dispatcher at all its runs always record
	 *   `0.0`. Its arm gate never passes anyway; the exemption keeps its per-run verdict about the
	 *   railway only.
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

	/** See [runPassed] for why the rule-based arm and the no-dispatcher arm are exempt. */
	private fun actionableRateAcceptable(snapshot: DispatcherRunSnapshot): Boolean =
		snapshot.arm == DispatcherArm.RULE_BASED ||
			!snapshot.arm.hasDispatcher ||
			snapshot.actionableTickRate >= MIN_ACTIONABLE_RATE

	/**
	 * The exit term: see [runPassed] for why an example without a bar and an absent exit count
	 * cannot fail a run, and why wrong exits are subtracted.
	 */
	private fun railwayOutcomeAcceptable(snapshot: DispatcherRunSnapshot): Boolean {
		val bar = ExitPassBars.barFor(snapshot.example) ?: return true
		val exited = snapshot.railwayOutcome.trainsExited ?: return true
		return exited - (snapshot.railwayOutcome.wrongExits ?: 0L) >= bar
	}

	/** Exits by the timetabled InOut: `null` when the exit count was not measured. */
	private fun ownInOutExits(outcome: RailwayOutcome): Long? =
		outcome.trainsExited?.let { it - (outcome.wrongExits ?: 0L) }

	/**
	 * Exits by the timetabled InOut, summed over the runs that measured an exit count: `null` when none
	 * did. Per run, not over the composite outcome -- a run whose exits were absent must contribute
	 * neither exits nor a subtraction to the measured runs' total (Issue #1315 thread of 2026-11-23).
	 */
	private fun ownInOutExits(snapshots: List<DispatcherRunSnapshot>): Long? =
		snapshots.mapNotNull { ownInOutExits(it.railwayOutcome) }.reduceOrNull(Long::plus)

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
					"| ${verdictText(r)} " +
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
						"| ${byOutcome[TickOutcome.LLM_SILENT_ALL_MOVING.name] ?: 0L} " +
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
	 *
	 * Opens with one row per arm (Issue #1296) — runs with at least one FATAL, runs whose log was
	 * scanned, and runs whose log was not — so an arm's FATAL count is read next to its A4 verdict
	 * without adding up the per-run rows. "Not scanned" is kept apart from "scanned clean": absent
	 * is not zero (see [DispatcherRunSnapshot.loggedFatalSimExceptionCount]).
	 */
	private fun appendFatalExceptions(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		val flagged = reports.flatMap { it.snapshots }.filter { (it.loggedFatalSimExceptionCount ?: 0L) > 0L }

		sb.appendLine("## Logged FATAL Simulation Exceptions")
		sb.appendLine()
		sb.appendLine("| Arm | Runs | Runs with FATAL | Runs scanned | Runs not scanned |")
		sb.appendLine("|---|---|---|---|---|")
		for (r in reports) {
			val scanned = r.snapshots.mapNotNull { it.loggedFatalSimExceptionCount }
			sb.appendLine(
				"| ${r.arm} | ${r.runCount} | ${scanned.count { it > 0L }} | ${scanned.size} | " +
					"${r.runCount - scanned.size} |"
			)
		}
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
		appendStaleOriginRefusals(sb, reports)
	}

	/**
	 * One row per arm counting the [ApplyFailureCode.ORIGIN_NOT_CONTIGUOUS] refusals of decisions a
	 * silent non-idle LLM cycle emitted (Issue #1365, part 1 of #1355) — see
	 * [DispatcherRunSnapshot.staleOriginRefusalsAfterSilentCycle] for what is counted. Refusals and
	 * runs with a refusal are summed over the runs that measured the count; "Runs not measured" (a
	 * run JSON written before the field existed, or an aborted run) is kept apart, and an arm
	 * without any measured run shows `n/a`, not `0`: absent is not zero.
	 */
	private fun appendStaleOriginRefusals(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("### Stale-Origin Refusals After a Silent Non-Idle LLM Cycle")
		sb.appendLine()
		sb.appendLine("| Arm | Runs | Refusals | Runs with a refusal | Runs measured | Runs not measured |")
		sb.appendLine("|---|---|---|---|---|---|")
		for (r in reports) {
			val measured = r.snapshots.mapNotNull { it.staleOriginRefusalsAfterSilentCycle }
			val refusals = if (measured.isEmpty()) "n/a" else measured.sum().toString()
			val runsWithRefusal = if (measured.isEmpty()) "n/a" else measured.count { it > 0L }.toString()
			sb.appendLine(
				"| ${r.arm} | ${r.runCount} | $refusals | $runsWithRefusal | ${measured.size} | " +
					"${r.runCount - measured.size} |"
			)
		}
		sb.appendLine()
	}

	/**
	 * One row per arm counting the NEXT SECTION hops the LLM was offered and left without an
	 * accepted `request_route` on the cycles that skipped the rule-based fallback (Issue #1302) —
	 * see [DispatcherRunSnapshot.skippedNextSectionOffers]. Offered, skipped and standing-at-a-signal
	 * figures are summed and the two longest-streak figures are the maximum, over the runs that
	 * measured them; "Runs not measured" (a rule-based run, a run JSON written before schema 12, or
	 * an aborted run) is kept apart, and an arm without any measured run shows `n/a`, not `0`: absent
	 * is not zero. The streaks tell one train skipped for many cycles from many trains skipped once.
	 */
	private fun appendSkippedNextSectionOffers(
		sb: StringBuilder,
		reports: List<ArmReport>
	) {
		sb.appendLine("## Skipped NEXT SECTION Offers")
		sb.appendLine()
		sb.appendLine(
			"Counted on the LLM cycles that skip the rule-based fallback (an acting cycle, one `no_op` " +
				"is enough; a failure after a partial emission; `LLM_SILENT_ALL_MOVING`). A NEXT SECTION " +
				"hop the cycle message offered is skipped when its train has no accepted `request_route` " +
				"that cycle. A request counts as accepted when the `request_route` tool emitted it for that " +
				"train (any target, whatever the apply outcome); a request the tool refused counts as skipped."
		)
		sb.appendLine()
		sb.appendLine(
			"| Arm | Runs | Offered hops | Skipped offers | Skipped standing at a signal | Runs with a skip | " +
				"Longest streak (cycles) | Longest streak (sim s) | Runs measured | Runs not measured |"
		)
		sb.appendLine("|---|---|---|---|---|---|---|---|---|---|")
		for (r in reports) {
			val measured = r.snapshots.filter { it.skippedNextSectionOffers != null }
			val cells =
				if (measured.isEmpty()) {
					List(SKIPPED_OFFER_FIGURE_COLUMNS) { "n/a" }
				} else {
					listOf(
						measured.sumOf { it.offeredNextSectionHops ?: 0L }.toString(),
						measured.sumOf { it.skippedNextSectionOffers ?: 0L }.toString(),
						measured.sumOf { it.skippedNextSectionOffersStandingAtSignal ?: 0L }.toString(),
						measured.count { (it.skippedNextSectionOffers ?: 0L) > 0L }.toString(),
						measured.mapNotNull { it.longestSkippedOfferStreakCycles }.maxOrNull()?.toString() ?: "n/a",
						measured
							.mapNotNull { it.longestSkippedOfferStreakSimSeconds }
							.maxOrNull()
							?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "n/a"
					)
				}
			sb.appendLine(
				"| ${r.arm} | ${r.runCount} | ${cells.joinToString(" | ")} | ${measured.size} | " +
					"${r.runCount - measured.size} |"
			)
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
	 * One row per distinct `(arm, params, example)` combination — the grid cells, not the arms —
	 * split across tables that share the same parameter-identity columns, Example included, as their
	 * join key.
	 *
	 * ## Why the example is part of the cell (Issue #1333)
	 *
	 * [RunParameters] has no example field, so a cell keyed by it alone pooled two networks run with
	 * equal parameters (`shuntingLoop` and `cervenyUjezd` on the rule-based arm; `multiTrainLoop` and
	 * `threeTrainLoop` without a dispatcher) into one row. The cell is now a [ParameterSweepCellKey]:
	 * a cell's `Runs`, `Passing` and `Gate` are read per example, so a split row can fall under the
	 * gate's run-count minimum where the pooled row did not. Arm Comparison still pools the examples
	 * of an arm.
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
	 * a row in one to its counterpart in the other without guessing. A third, **Performance**
	 * ([PerformanceReportSection], Issue #1306), renders the same cells and starts with the same
	 * columns.
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
		sb.appendLine(
			"> One row per distinct parameter cell and example. `Runs`/`Passing`/`Gate` are that cell's own."
		)
		sb.appendLine()

		val ranked = parameterSweepCells(reports)

		appendParameterSweepLegend(sb)
		appendHygieneTable(sb, ranked)
		appendOutcomesTable(sb, ranked)
		// Issue #1306, #1333: the same cells, so wall time is never pooled across networks.
		PerformanceReportSection.append(sb, ranked, ::paramsRowPrefix)
	}

	/**
	 * The Parameter Sweep cells of [reports], ranked: every arm's runs grouped by
	 * [ParameterSweepCellKey] (Issue #1333) and each group aggregated alone, ordered by summed
	 * [RailwayOutcome.trainsExited] descending (unmeasured last), then by parameters and example.
	 *
	 * An arm with no snapshots still yields a placeholder cell: [aggregate] fills its `params` with
	 * the default RunParameters (rule-based arm, unset seed), its example is unnamed and its railway
	 * figures render as `n/a`. This is the intentional fallback pinned by `renderMarkdown falls back
	 * to placeholders …` and `… zero runs renders both tables without throwing` — an empty arm must
	 * show it exists with defaults, not vanish from the sweep.
	 */
	private fun parameterSweepCells(reports: List<ArmReport>): List<ParameterSweepCell> =
		reports
			.flatMap { report ->
				if (report.snapshots.isEmpty()) {
					listOf(ParameterSweepCell(ParameterSweepCellKey(report.params, null), report, RailwayOutcome.UNMEASURED))
				} else {
					report.snapshots
						.groupBy(ParameterSweepCellKey::of)
						.map { (key, cellSnapshots) ->
							ParameterSweepCell(key, aggregate(cellSnapshots), aggregateRailwayOutcome(cellSnapshots))
						}
				}
			}.sortedWith(
				compareByDescending<ParameterSweepCell> { it.outcome.trainsExited ?: Long.MIN_VALUE }
					.thenBy { it.key.params.toString() }
					.thenBy(nullsLast()) { it.key.example }
			)

	private fun appendHygieneTable(
		sb: StringBuilder,
		ranked: List<ParameterSweepCell>
	) {
		sb.appendLine("### Decision Hygiene")
		sb.appendLine()
		sb.appendLine(
			"| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | " +
				"Breaker Threshold | Breaker Cooldown s | Example | " +
				"Runs | Passing | Gate | LLM Success | Invalid-action rate | No-op rate | " +
				"Repair-success rate † | validAt1 | correctAt1 | p50 latency ms | p95 latency ms | " +
				"C7 clean | RULE_FALLBACK ticks |"
		)
		sb.appendLine("|" + "---|".repeat(HYGIENE_TABLE_COLUMNS))

		for (cell in ranked) {
			val r = cell.report
			val invalidActionRate = medianInvalidActionRate(r.snapshots)
			val repairRate = median(r.snapshots.map { it.repairSuccessRate }.sorted())
			val p50s = r.snapshots.mapNotNull { it.latencyP50Ms }.sorted()
			val p50 = if (p50s.isEmpty()) null else percentile50(p50s)
			val ruleFallbackTicks = r.snapshots.sumOf { it.ticksByOutcome[TickOutcome.RULE_FALLBACK.name] ?: 0L }
			sb.appendLine(
				paramsRowPrefix(cell) +
					"| ${r.runCount} " +
					"| ${r.passingRuns} " +
					"| ${verdictText(r)} " +
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
		ranked: List<ParameterSweepCell>
	) {
		sb.appendLine("### Railway Outcomes")
		sb.appendLine()
		sb.appendLine(
			"| Arm | Model | Temperature | Tick ms | historyN | maxActions | Seed | Timeout s | Prompt Variant | " +
				"Breaker Threshold | Breaker Cooldown s | Example | " +
				"Runs | Journeys completed | Trains entered | Trains exited (authoritative) | Max concurrent | " +
				"Block transitions | Conflicts | Failed reservations | Unresolved at run end | Wrong exits | Cutoff runs | " +
				"Exits by own InOut | Exit bar (per run) |"
		)
		sb.appendLine("|" + "---|".repeat(OUTCOMES_TABLE_COLUMNS))

		for (cell in ranked) {
			val r = cell.report
			val outcome = cell.outcome
			sb.appendLine(
				paramsRowPrefix(cell) +
					"| ${r.runCount} " +
					"| ${fmtNullableLong(outcome.journeysCompleted)} " +
					"| ${fmtNullableLong(outcome.trainsEntered)} " +
					"| ${fmtNullableLong(outcome.trainsExited)} " +
					"| ${fmtNullableLong(outcome.maxConcurrentTrains)} " +
					"| ${fmtNullableLong(outcome.blockTransitions)} " +
					"| ${fmtNullableLong(outcome.conflicts)} " +
					"| ${fmtNullableLong(outcome.failedReservations)} " +
					"| ${fmtNullableLong(outcome.unresolvedAtRunEnd)} " +
					"| ${fmtNullableLong(outcome.wrongExits)} " +
					"| ${fmtNullableLong(cutoffRuns(r.snapshots))} " +
					"| ${fmtNullableLong(ownInOutExits(r.snapshots))} " +
					"| ${exitBarText(cell.key.example)} |"
			)
		}
		sb.appendLine()
	}

	/**
	 * Runs of the cell classified [RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS]; `null` when
	 * no run carries an end classification (files before schema version 11), Issue #676.
	 */
	private fun cutoffRuns(snapshots: List<DispatcherRunSnapshot>): Long? {
		val classified = snapshots.mapNotNull { it.railwayOutcome.endClassification }
		if (classified.isEmpty()) return null
		return classified.count { it == RunEndClassification.CUTOFF_WITH_UNFINISHED_JOURNEYS }.toLong()
	}

	/**
	 * Legend for the Parameter Sweep tables. Several of these metrics have names that sound
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
				"run's even at identical railway behaviour — read it as a within-#834 comparison only. " +
				"Likewise, from #988 a silent tick with every train moving and a path ahead counts as a " +
				"success (`LLM_SILENT_ALL_MOVING`), so BOTH LLM Success and Actionable Rate rise for the same " +
				"railway behaviour (relabelled former `LLM_SILENT_NONACTIONABLE` ticks count in numerator " +
				"and denominator); only runs from the same side of the change are comparable."
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
				"Conflicts / Failed reservations / Unresolved at run end** — RailwayOutcome fields, summed " +
				"across the cell's runs " +
				"(max, for \"max concurrent\"), skipping any run where the figure was not measured. `n/a` " +
				"when no run in the cell measured it — never rendered as `0`, which would misrepresent " +
				"\"not measured\" as \"measured as none\"."
		)
		sb.appendLine(
			"- **Conflicts / Unresolved at run end (issue #1280)** — **Conflicts** counts only double-bookings " +
				"the reservation registry caught while the run was live. **Unresolved at run end** counts trains " +
				"still waiting for a block another train held when the run stopped: a deadlock or starvation " +
				"sign, or queueing an end-time cutoff interrupted, never a double-booking. Runs written before " +
				"schema version 10 counted both in Conflicts and show `n/a` for Unresolved at run end."
		)
		sb.appendLine(
			"- **Wrong exits / Cutoff runs (issue #676)** — **Wrong exits** sums the trains that left " +
				"through another InOut than the one they were queued for. **Cutoff runs** counts the runs " +
				"that reached their end time with admitted trains still on the network " +
				"(`CUTOFF_WITH_UNFINISHED_JOURNEYS`). A cutoff never fails a run or the gate; wrong exits " +
				"count only through Exits by own InOut (issue #1315). Runs written before schema version 11 " +
				"show `n/a`."
		)
		appendExitBarLegend(sb)
		sb.appendLine(
			"- **Example (issue #1333)** — the example the cell's runs ran; `(unnamed)` is a run that named " +
				"none or a blank name (a GUI run, or a file before schema version 13). A cell is its parameters **and** its " +
				"example, so two networks run with equal parameters get a row each, and each row's `Runs`, " +
				"`Passing` and `Gate` are that example's own: a row can show `FAIL` on fewer than 10 runs where " +
				"the arm's pooled row in Arm Comparison, which still pools the examples, shows enough runs."
		)
		sb.appendLine(
			"- **NO_DISPATCHER arm (issue #676)** — runs with no dispatcher at all (for example " +
				"`multiTrainLoop`), persisted because a sweep configured a store for them. Their railway " +
				"figures are shown; the gate is `n/a`, because they measure no dispatcher."
		)
		sb.appendLine(
			"- **Ranking (issue #906)** — the Parameter Sweep tables are ranked by **Trains exited**, descending, cells " +
				"with no measured `trainsExited` sorted last. Not by `journeysCompleted`: that counter " +
				"increments whenever a train's reservation count reaches zero, with no termination or " +
				"movement predicate, so a stale swept route can credit a journey to a train that never " +
				"moved, and the counter can fire more than once per train. `trainsExited` is " +
				"termination-gated and is the authoritative per-cell outcome figure; `journeysCompleted` is " +
				"still reported alongside it, for comparison only."
		)
		sb.appendLine(PerformanceReportSection.legend())
		sb.appendLine()
	}

	/** The legend bullet of the Exits by own InOut and Exit bar columns (Issue #1315). */
	private fun appendExitBarLegend(sb: StringBuilder) {
		sb.appendLine(
			"- **Exits by own InOut / Exit bar (issue #1315)** — **Exits by own InOut** is Trains exited " +
				"minus Wrong exits, summed: the trains that left by their timetabled InOut. **Exit bar** is " +
				"the minimum of those a single run of the example must reach to pass, measured per example " +
				"from its deterministic arm at 600 s (half of its fewest own-InOut exits, rounded up; " +
				"`shuntingLoop`, its LLM twin and an unnamed run keep the #895 bar of 6; an LLM example uses " +
				"its twin's bar). It is a tripwire. Wrong exits subtract from a pass, they never veto it. " +
				"`$EXIT_BAR_NOT_MEASURED` — on the bar and on the Gate — marks an example with no measured " +
				"bar (an XML file run from the GUI, say): its runs had no exit check. A run without an example " +
				"(a file before schema version 13) is judged against the bar of 6 whatever network it ran. " +
				"Runs written before schema version 11 counted no wrong exits, so there Exits by own InOut " +
				"equals Trains exited. Arm Comparison pools runs judged against different bars, one per " +
				"example; each run's verdict reads its own bar."
		)
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
			failedReservations = reduceOrNull(outcomes.map { it.failedReservations }) { it.sum() },
			unresolvedAtRunEnd = reduceOrNull(outcomes.map { it.unresolvedAtRunEnd }) { it.sum() },
			wrongExits = reduceOrNull(outcomes.map { it.wrongExits }) { it.sum() }
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
	 * Shared cell-identity columns rendered identically, in the same order, in every Parameter
	 * Sweep table — the join key a reader uses to match a cell's hygiene row to its outcomes and
	 * performance rows without guessing: the arm, the [RunParameters] and, last, the example
	 * (Issue #1333), escaped because it is free text (a GUI run can be named after its XML file).
	 */
	private fun paramsRowPrefix(cell: ParameterSweepCell): String {
		val r = cell.report
		val p = cell.key.params
		return "| ${r.arm} " +
			"| ${p.model.ifEmpty { if (r.arm.hasDispatcher) "rule-based" else "none" }} " +
			"| ${p.temperature} " +
			"| ${p.tickPeriodMs} " +
			"| ${p.historyN} " +
			"| ${p.maxActionsPerTick} " +
			"| ${p.seed ?: "unset"} " +
			"| ${p.inferenceTimeoutSeconds} " +
			"| ${p.promptVariant} " +
			"| ${p.circuitBreakerFailureThreshold} " +
			"| ${p.circuitBreakerCooldownSeconds} " +
			"| ${mdEscapeCell(cell.key.exampleLabel)} "
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

	/** The gate cell: `n/a` for an arm without a dispatcher, which the gate does not judge (Issue #676). */
	private fun gateText(r: ArmReport): String =
		if (r.arm.hasDispatcher) gateSymbol(r.gatePassed) else "n/a (no dispatcher)"

	/**
	 * The gate cell plus, when some of [r]'s runs have no measured exit bar, [EXIT_BAR_NOT_MEASURED]
	 * (Issue #1315): their `Passing` count did not read the railway's exits.
	 */
	private fun verdictText(r: ArmReport): String {
		val unmeasured = r.snapshots.count { ExitPassBars.barFor(it.example) == null }
		return when (unmeasured) {
			0 -> gateText(r)
			r.snapshots.size -> "${gateText(r)} · $EXIT_BAR_NOT_MEASURED"
			else -> "${gateText(r)} · $EXIT_BAR_NOT_MEASURED for $unmeasured of ${r.snapshots.size} runs"
		}
	}

	/** The Exit bar cell of a sweep row: the bar of its [example], or [EXIT_BAR_NOT_MEASURED]. */
	private fun exitBarText(example: String?): String = ExitPassBars.barFor(example)?.toString() ?: EXIT_BAR_NOT_MEASURED

	private fun boolSymbol(v: Boolean): String = if (v) "yes" else "no"

	/** Escapes [value] for a single Markdown table cell: no bare `|` or newline can break the row. */
	private fun mdEscapeCell(value: String?): String = (value ?: "").replace("|", "\\|").replace("\n", " ")
}
