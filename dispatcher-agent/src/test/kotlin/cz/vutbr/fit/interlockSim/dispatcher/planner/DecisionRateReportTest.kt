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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DecisionRateReport] (Issue #847 round 4, findings R4-1 and R4-2).
 *
 * ## What this reports and why it did not exist
 *
 * Round 3 could not reconcile three numbers for the same run — 301 control ticks, 20-29
 * `MeasuringPlanAdapter` cycles, 57-62 Koog subgraph log lines — and said so honestly rather than
 * picking one. Reconciling them was itself listed as round-4 work.
 *
 * The numbers are reconcilable, but only two of the three were ever recorded anywhere:
 *
 * - 301 control ticks is structural. `ShuntingLoop.iteration()` ends in `hold(1.0)` and
 *   `interLoopSleep()` holds another, so a control step is 2.0 simulated seconds: 600/2 + 1 = 301.
 * - The drop from 301 to ~25 is `DefaultSnapshotSignal`'s at-most-one-pending-permit rule. Every
 *   tick that fires while the driver is inside a 10-25 s inference overwrites the previous permit
 *   and is gone. Correct behaviour, but it was invisible, so the decision rate looked like a defect
 *   rather than a consequence of inference latency.
 * - 57-62 was a count of Koog *log lines*, and `AIAgentSubgraph` logs both "Executing subgraph" and
 *   "Completed subgraph" per run — so ~29-31 agent runs, consistent with the counted cycles rather
 *   than in conflict with them.
 *
 * This report prints all of it on one line at the end of a headless run, so #847's sweep can judge
 * decision rate from the log instead of re-deriving it. It also surfaces
 * [AgentDriverLoop.stoppedByFailures], which is how a run whose dispatcher died mid-way announces
 * itself rather than passing as healthy.
 */
@DisplayName("DecisionRateReport — the tick-to-cycle gap on one line")
class DecisionRateReportTest {
	@Test
	@DisplayName("reports ticks signalled, ticks that reached the driver, and cycles actually run")
	fun reportsTheFullChain() {
		val signal = DefaultSnapshotSignal()
		// Six ticks fire; the driver only manages to consume two of them.
		repeat(4) { signal.signal() }
		signal.await()
		signal.signal()
		signal.await()
		signal.signal()

		var cycles = 0
		val driverLoop =
			AgentDriverLoop(
				isActive = { cycles < 2 },
				runCycle = {
					cycles++
					true
				}
			)
		runBlocking { driverLoop.run() }

		val line = DecisionRateReport.line(signal, driverLoop, planner = null)

		assertThat(line).isNotNull()
		assertThat(line!!, "report line").contains("ticksSignalled=6")
		assertThat(line, "report line").contains("ticksCoalesced=3")
		assertThat(line, "report line").contains("ticksReachingDriver=3")
		assertThat(line, "report line").contains("driverCycles=2")
		assertThat(line, "report line").contains("driverFailures=0")
		assertThat(line, "report line").contains("stoppedByFailures=false")
	}

	@Test
	@DisplayName("includes the planner's own cycle count when a measuring planner is wired")
	fun includesPlannerCycles() {
		val signal = DefaultSnapshotSignal()
		signal.signal()
		val driverLoop = AgentDriverLoop(isActive = { false }, runCycle = { true })
		val planner = MeasuringPlanAdapter(mockk<KoogAgentPlanAdapter>(relaxed = true))

		val line = DecisionRateReport.line(signal, driverLoop, planner)

		assertThat(line).isNotNull()
		assertThat(line!!, "report line").contains("plannerCycles=0")
	}

	@Test
	@DisplayName("a dispatcher-free example produces no line rather than a line of zeroes")
	fun noDispatcherNoLine() {
		assertThat(DecisionRateReport.line(null, null, null)).isNull()
	}

	@Test
	@DisplayName("a driver that gave up says so, so the run cannot pass as healthy")
	fun stoppedLoopIsVisible() {
		val driverLoop =
			AgentDriverLoop(
				isActive = { true },
				runCycle = { error("always fails") },
				maxConsecutiveFailures = 2
			)
		runBlocking { driverLoop.run() }

		val line = checkNotNull(DecisionRateReport.line(null, driverLoop, null))

		assertThat(line, "report line").contains("stoppedByFailures=true")
		assertThat(line, "report line").contains("driverFailures=2")
	}
}
