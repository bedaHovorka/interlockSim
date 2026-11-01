/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Lookup and wiring of the Cerveny Ujezd arms that the Goal 1B SP10.1 measurement (Issue #1276)
 * sweeps with `aiSweep`: the rule-based control arm `cervenyUjezd` and the LLM arm `cervenyUjezdAI`,
 * both on the dispatcher-agent stack like the vyhybna pair `shuntingLoop` / `shuntingLoopAI`
 * (owner decision, PR #1282 review: no separate `cervenyUjezdRuleBased` name). A third test pins
 * the synchronous parity twin `cervenyUjezdSync`, the JVM side of the `CrossPlatformParityTest`
 * station comparison.
 *
 * Contexts are built, never run, so no Ollama call, GUI window or driver thread starts.
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.context.ThrottlingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.CERVENY_UJEZD_STATION_CAPACITY
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContextFromMap
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

@DisplayName("ExampleRegistry - Cerveny Ujezd LLM and rule-based sweep arms (#1276)")
class ExampleRegistryCervenyUjezdAiTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun assertCervenyUjezdLoop(ctx: DefaultSimulationContext): ShuntingLoop {
		val loop = ctx.mainProcess
		assertThat(loop).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat((loop as ShuntingLoop).stationCapacity).isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		return loop
	}

	private fun shownCapacity(ctx: DefaultSimulationContext): Int? =
		ctx.getMetricsServices().getMetricsCollectionService().capacityShownToDispatcher()

	private fun recordedArm(ctx: DefaultSimulationContext): DispatcherArm =
		checkNotNull(ctx.scope.getOrNull<DispatcherRunRecorder>()).snapshot().arm

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `CLI map resolves cervenyUjezdAI to the LLM arm on the four-track station`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.examples.keys).contains(ExampleNames.CERVENY_UJEZD_AI)
		val ctx = createExampleContextFromMap(registry.examples, get(), ExampleNames.CERVENY_UJEZD_AI).tracked()

		assertCervenyUjezdLoop(ctx)
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(ctx.scope.getOrNull<MeasuringPlanAdapter>(), "measuring planner").isNotNull()
		// The prompt shows the station's own cap, so the run record must too (review of #1311).
		assertThat(shownCapacity(ctx), "capacity shown to the LLM").isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		// The headless pacing controller must be the SAME instance Main hands to context.run().
		assertThat(ctx.scope.getOrNull<SimulationController>())
			.isNotNull()
			.isInstanceOf<ThrottlingSimulationController>()
		// DispatcherRunSummaries writes a run JSON only when an AgentDriverLoop exists.
		assertThat(ctx.scope.getOrNull<AgentDriverLoop>(), "agent driver loop").isNotNull()
	}

	@Test
	fun `cervenyUjezdAI is driven by the LLM dispatcher`() {
		assertThat(get<ExampleRegistry>().usesLlmDispatcher(ExampleNames.CERVENY_UJEZD_AI)).isTrue()
	}

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `GUI map resolves cervenyUjezdAI to the LLM arm on the four-track station`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.guiExamples.keys).contains(ExampleNames.CERVENY_UJEZD_AI)
		val ctx = createExampleContextFromMap(registry.guiExamples, get(), ExampleNames.CERVENY_UJEZD_AI).tracked()

		assertCervenyUjezdLoop(ctx)
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(ctx.scope.getOrNull<MeasuringPlanAdapter>(), "measuring planner").isNotNull()
		// The prompt shows the station's own cap, so the run record must too (review of #1311).
		assertThat(shownCapacity(ctx), "capacity shown to the LLM").isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
	}

	/**
	 * The control arm must write a run JSON, or `aiSweep` records every control run as CRASH.
	 * Since the PR #1282 review the control arm IS `cervenyUjezd` (owner decision): the console
	 * `shuntingLoop` wiring, while the plain `cervenyUjezdSync` twin keeps the synchronous SP3
	 * build the native binary runs.
	 */
	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `CLI map resolves cervenyUjezd to the rule-based control arm on the dispatcher-agent stack`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.examples.keys).contains(ExampleNames.CERVENY_UJEZD)
		val ctx = createExampleContextFromMap(registry.examples, get(), ExampleNames.CERVENY_UJEZD).tracked()

		assertCervenyUjezdLoop(ctx)
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.RULE_BASED)
		assertThat(shownCapacity(ctx), "capacity shown to the rule-based planner")
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
		assertThat(ctx.scope.getOrNull<AgentDriverLoop>(), "agent driver loop").isNotNull()
		assertThat(ctx.scope.getOrNull<MeasuringPlanAdapter>(), "measuring planner").isNull()
		assertThat(registry.usesLlmDispatcher(ExampleNames.CERVENY_UJEZD)).isFalse()
	}

	/**
	 * The parity twin keeps the synchronous, native-parity build: no [AgentDriverLoop] (so
	 * `DispatcherRunSummaries.finishAndPersist` writes no run JSON) and no real-time sync —
	 * the wiring `CrossPlatformParityTest` compares against the native `cervenyUjezd`.
	 */
	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `cervenyUjezdSync is the sync native-parity twin driven by the synchronous dispatcher`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.examples.keys).contains(ExampleNames.CERVENY_UJEZD_SYNC)
		val ctx = createExampleContextFromMap(registry.examples, get(), ExampleNames.CERVENY_UJEZD_SYNC).tracked()

		val loop = assertCervenyUjezdLoop(ctx)
		assertThat(loop.enableRealTimeSync).isFalse()
		assertThat(ctx.scope.getOrNull<AgentDriverLoop>(), "agent driver loop").isNull()
		assertThat(registry.usesLlmDispatcher(ExampleNames.CERVENY_UJEZD_SYNC)).isFalse()
	}
}
