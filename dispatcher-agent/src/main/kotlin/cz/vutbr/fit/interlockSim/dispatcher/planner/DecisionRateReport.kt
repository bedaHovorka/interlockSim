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

import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * End-of-run report of how many control ticks became dispatcher decisions, and why the rest did not.
 *
 * ## Why this exists (Issue #847 round 4, findings R4-1 and R4-2)
 *
 * Round 3 measured three numbers for the same run and could not reconcile them: **301** control
 * ticks, **20-29** `MeasuringPlanAdapter` cycles, and **57-62** Koog subgraph executions. It reported
 * the range honestly and left reconciliation as round-4 work. The numbers are reconcilable, but only
 * one of the three was ever recorded anywhere — the rest had to be reconstructed by grepping logs
 * after the fact, which is not something #847's unattended sweep can do.
 *
 * The chain, end to end:
 *
 * | Stage | Where it is decided |
 * |---|---|
 * | 301 control ticks over 600 s | Structural: `ShuntingLoop.iteration()` ends in `hold(1.0)` and `interLoopSleep()` holds another, so one control step is 2.0 simulated seconds — 600/2 + 1. |
 * | ticks → ticks reaching the driver | [DefaultSnapshotSignal]'s at-most-one-pending-permit rule. A tick that fires while the driver is inside a 10-25 s inference overwrites the previous permit and is gone. |
 * | ticks reaching the driver → cycles | [AgentDriverLoop]: one cycle per tick consumed, minus any that threw. |
 * | cycles → planner cycles | [MeasuringPlanAdapter]: LLM successes plus counted fallbacks. |
 *
 * The 57-62 figure was never a fourth stage: it counted Koog **log lines**, and `AIAgentSubgraph`
 * emits both `Executing subgraph` and `Completed subgraph` per run — so ~29-31 agent runs,
 * consistent with the counted cycles rather than in conflict with them.
 *
 * The coalescing is correct behaviour, not a defect: a dispatcher that recomputes from scratch each
 * cycle gains nothing from a backlog of stale wake-ups. What was wrong was that it was invisible, so
 * a decision rate set by inference latency read as an unexplained counter discrepancy.
 *
 * This report also carries [AgentDriverLoop.stoppedByFailures], which is how a run whose dispatcher
 * died part-way announces itself instead of passing as healthy — round 3's run 1 went quiet after
 * `simTime=216.0s` and still reported a full 600.9 s horizon and exit code 0.
 *
 * @since Issue #847 round 4 (PR #891)
 */
object DecisionRateReport {
	private val logger = KotlinLogging.logger {}

	/**
	 * Builds the one-line decision-rate report, or `null` if no dispatcher was wired at all.
	 *
	 * Returning `null` rather than a line of zeroes matters: the console `example` mode also runs
	 * dispatcher-free examples, and a `ticksSignalled=0 driverCycles=0` line against those would
	 * read as a dispatcher that did nothing rather than one that was never there.
	 *
	 * All three arguments are independently optional so the report degrades gracefully — a run
	 * wired with a rule-based planner has no [MeasuringPlanAdapter] but still has a driver loop.
	 */
	fun line(
		signal: DefaultSnapshotSignal?,
		driverLoop: AgentDriverLoop?,
		planner: MeasuringPlanAdapter?
	): String? {
		if (signal == null && driverLoop == null && planner == null) return null

		val parts = mutableListOf<String>()
		if (signal != null) {
			parts += "ticksSignalled=${signal.signalCount}"
			parts += "ticksCoalesced=${signal.coalescedCount}"
			parts += "ticksReachingDriver=${signal.signalCount - signal.coalescedCount}"
		}
		if (driverLoop != null) {
			parts += "driverCycles=${driverLoop.cycleCount}"
			parts += "driverFailures=${driverLoop.failureCount}"
			parts += "stoppedByFailures=${driverLoop.stoppedByFailures}"
		}
		if (planner != null) {
			parts += "plannerCycles=${planner.getMetricsSnapshot().totalCycles}"
		}
		return "[DecisionRateReport] ${parts.joinToString(" ")}"
	}

	/**
	 * Logs [line] at INFO, or nothing when no dispatcher was wired.
	 *
	 * Logged at ERROR instead when the driver gave up mid-run: such a run's remaining simulated time
	 * had no dispatcher at all, so #847's sweep must not count it as a valid data point.
	 */
	fun log(
		signal: DefaultSnapshotSignal?,
		driverLoop: AgentDriverLoop?,
		planner: MeasuringPlanAdapter?
	) {
		val text = line(signal, driverLoop, planner) ?: return
		if (driverLoop?.stoppedByFailures == true) {
			logger.error { "$text — dispatcher driver gave up mid-run; this run is NOT valid measurement data" }
		} else {
			logger.info { text }
		}
	}
}
