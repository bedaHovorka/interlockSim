/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Lookup and wiring of the two Cerveny Ujezd examples that the Goal 1B SP10.1 measurement
 * (Issue #1276) sweeps with `aiSweep`: the LLM arm `cervenyUjezdAI` and its rule-based control
 * arm `cervenyUjezdRuleBased`, both on the dispatcher-agent stack like the vyhybna pair
 * `shuntingLoopAI` / `shuntingLoop`.
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
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.context.ThrottlingSimulationController
import cz.vutbr.fit.interlockSim.dispatcher.AgentDriverLoop
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
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

	private fun build(
		map: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext>,
		name: String
	): DefaultSimulationContext {
		val factory = checkNotNull(map[name]) { "$name is not registered" }
		return (factory(get(), arrayOf("example", name, "60")) as DefaultSimulationContext).tracked()
	}

	private fun assertCervenyUjezdLoop(ctx: DefaultSimulationContext) {
		val loop = ctx.mainProcess
		assertThat(loop).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat((loop as ShuntingLoop).stationCapacity).isEqualTo(CERVENY_UJEZD_CAPACITY)
	}

	private fun recordedArm(ctx: DefaultSimulationContext): DispatcherArm =
		checkNotNull(ctx.scope.getOrNull<DispatcherRunRecorder>()).snapshot().arm

	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `CLI map resolves cervenyUjezdAI to the LLM arm on the four-track station`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.examples.keys).contains(ExampleNames.CERVENY_UJEZD_AI)
		val ctx = build(registry.examples, ExampleNames.CERVENY_UJEZD_AI)

		assertCervenyUjezdLoop(ctx)
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(ctx.scope.getOrNull<MeasuringPlanAdapter>(), "measuring planner").isNotNull()
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
		val ctx = build(registry.guiExamples, ExampleNames.CERVENY_UJEZD_AI)

		assertCervenyUjezdLoop(ctx)
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(ctx.scope.getOrNull<MeasuringPlanAdapter>(), "measuring planner").isNotNull()
	}

	/**
	 * The control arm must write a run JSON, or `aiSweep` records every control run as CRASH.
	 * `cervenyUjezd` cannot be that arm: it is wired synchronously (native parity, SP3) and has no
	 * [AgentDriverLoop], so `DispatcherRunSummaries.finishAndPersist` writes nothing for it.
	 */
	@Test
	@Timeout(value = 20, unit = TimeUnit.SECONDS)
	fun `CLI map resolves cervenyUjezdRuleBased to the rule-based arm on the dispatcher-agent stack`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.examples.keys).contains(ExampleNames.CERVENY_UJEZD_RULE_BASED)
		val ctx = build(registry.examples, ExampleNames.CERVENY_UJEZD_RULE_BASED)

		assertCervenyUjezdLoop(ctx)
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.RULE_BASED)
		assertThat(ctx.scope.getOrNull<AgentDriverLoop>(), "agent driver loop").isNotNull()
		assertThat(ctx.scope.getOrNull<MeasuringPlanAdapter>(), "measuring planner").isNull()
		assertThat(registry.usesLlmDispatcher(ExampleNames.CERVENY_UJEZD_RULE_BASED)).isFalse()
	}

	private companion object {
		private const val CERVENY_UJEZD_CAPACITY = 4
	}
}
