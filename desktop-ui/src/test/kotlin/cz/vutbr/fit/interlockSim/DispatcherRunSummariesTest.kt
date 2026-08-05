/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Wiring regression test for the headless end-of-run dispatcher summaries
 * (Issue #847 round 4, findings R4-1 and R4-2).
 *
 * ## The gap this closes
 *
 * `MeasuringPlanAdapter.logFinalSummary()` had exactly one caller in the whole codebase — the GUI's
 * `Frame`. A headless run therefore emitted only the modulo-10 periodic lines, which with 20-29
 * cycles per run means **two** summary lines and no final one. That is why round 3's run 1 could go
 * quiet after `simTime=216.0s, totalCycles=10` without anything in the log marking the end state: the
 * final summary that would have shown `totalCycles` frozen at 10 was never emitted, because only the
 * GUI knew how to ask for it.
 *
 * #847's sweep is headless by definition, so every per-run number it needs has to be produced on
 * this path.
 *
 * Both halves are asserted, because reporting on a run that has no dispatcher is as wrong as failing
 * to report on one that does: a plain `shuntingLoop` must produce no dispatcher summaries at all,
 * not a set of zeroes that would read as "the dispatcher did nothing".
 *
 * Builds examples reflectively, as [ExampleRegistryOrphanSweeperWiringTest] does; no Ollama needed
 * and no simulation is run.
 *
 * @since Issue #847 (round 4)
 */
@DisplayName("Headless runs report the dispatcher summaries the GUI already reported")
class DispatcherRunSummariesTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun createExample(
		factoryMethod: String,
		exampleName: String
	): DefaultSimulationContext {
		val registry = get<ExampleRegistry>()
		val createMethod =
			ExampleRegistry::class.java.getDeclaredMethod(
				factoryMethod,
				SimulationContextFactory::class.java,
				Array<String>::class.java
			)
		createMethod.isAccessible = true
		return createMethod.invoke(
			registry,
			get<SimulationContextFactory>(),
			arrayOf("example", exampleName, "60")
		) as DefaultSimulationContext
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("an AI example reports planner, sweeper and decision-rate summaries")
	fun aiExampleReportsEverySummary() {
		val context = createExample("createShuntingLoopAIExample", "shuntingLoopAI")

		val reported = DispatcherRunSummaries.log(context.scope)

		assertThat(reported.plannerSummary, "planner final summary reported").isTrue()
		assertThat(reported.sweeperSummary, "orphan sweeper summary reported").isTrue()
		assertThat(reported.decisionRateSummary, "decision-rate summary reported").isTrue()
	}

	/**
	 * The rule-based console example runs the same `wireDispatcherAgent` stack, so it has a driver
	 * loop and a snapshot signal — and therefore a decision rate worth reporting — but no
	 * `MeasuringPlanAdapter`, which exists only to measure the LLM arm. The report must degrade to
	 * exactly the parts that are present rather than being all-or-nothing: #847 compares the LLM arm
	 * against this baseline, so the baseline's tick-to-cycle chain has to be readable too.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("the rule-based example reports a decision rate but has no LLM planner summary")
	fun ruleBasedExampleReportsDecisionRateOnly() {
		val context = createExample("createShuntingLoopExample", "shuntingLoop")

		val reported = DispatcherRunSummaries.log(context.scope)

		assertThat(reported.plannerSummary, "planner final summary reported").isFalse()
		assertThat(reported.decisionRateSummary, "decision-rate summary reported").isTrue()
	}

	/**
	 * `shuntingLoopSync` uses `wireSynchronousDispatcher` and has no dispatcher-agent stack at all.
	 * It must print nothing: a row of zeroes here would read as "the dispatcher did nothing" instead
	 * of "there was no dispatcher", and it is the example the cross-platform determinism comparison
	 * runs, so stray output would be actively harmful.
	 */
	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a dispatcher-free example reports nothing rather than a row of zeroes")
	fun syncExampleReportsNothing() {
		val context = createExample("createShuntingLoopSyncExample", "shuntingLoopSync")

		val reported = DispatcherRunSummaries.log(context.scope)

		assertThat(reported.plannerSummary, "planner final summary reported").isFalse()
		assertThat(reported.sweeperSummary, "orphan sweeper summary reported").isFalse()
		assertThat(reported.decisionRateSummary, "decision-rate summary reported").isFalse()
	}
}
