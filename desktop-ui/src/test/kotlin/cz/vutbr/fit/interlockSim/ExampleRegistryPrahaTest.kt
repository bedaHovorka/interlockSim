/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Lookup and wiring of the Praha hlavni nadrazi Station arms (Goal 1B SP10.2, Issue #1366): the
 * rule-based control arm `prahaHlavniNadrazi` and the LLM arm `prahaHlavniNadraziAI`, in the CLI
 * and the GUI map, both with the low traffic of the traffic-simulation-expert ruling.
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
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.publishedOrNull
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContextFromMap
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

@DisplayName("ExampleRegistry - Praha hlavni nadrazi Station arms (#1366, #1376)")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ExampleRegistryPrahaTest : KoinTestBase() {
	private companion object {
		/** The low-traffic admission cap of the ruling: 4 of the variant's 9 station tracks. */
		const val LOW_TRAFFIC_CAP = 4
	}

	override fun getTestModule(): Module = testModuleFull

	private fun build(
		map: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext>,
		name: String
	): DefaultSimulationContext {
		assertThat(map.keys).contains(name)
		return createExampleContextFromMap(map, get(), name).tracked()
	}

	/**
	 * The loop of [ctx]: a ShuntingLoop admitting at most the low-traffic cap, not the 9 tracks, and
	 * generating only switch-legal endpoint pairs (Issue #1376).
	 */
	private fun lowTrafficLoop(ctx: DefaultSimulationContext): ShuntingLoop {
		val loop = ctx.mainProcess
		assertThat(loop).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat((loop as ShuntingLoop).stationCapacity, "effective admission cap").isEqualTo(LOW_TRAFFIC_CAP)
		assertThat(loop.switchLegalPairsOnly, "switch-legal pairs only").isTrue()
		return loop
	}

	private fun recordedArm(ctx: DefaultSimulationContext): DispatcherArm =
		checkNotNull(ctx.scope.getOrNull<DispatcherRunRecorder>()).snapshot().arm

	@Test
	fun `CLI map resolves prahaHlavniNadrazi to the low-traffic rule-based control arm`() {
		val registry = get<ExampleRegistry>()
		val ctx = build(registry.examples, ExampleNames.PRAHA_HLAVNI_NADRAZI)

		assertThat(lowTrafficLoop(ctx).enableRealTimeSync).isFalse()
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.RULE_BASED)
		val planner = checkNotNull(ctx.scope.publishedOrNull<AgentLoopDriver>()).planner
		assertThat(planner).isInstanceOf<RuleBasedPlanAdapter>()
		val dispatcher = (planner as RuleBasedPlanAdapter).dispatcher
		assertThat((dispatcher as RuleBasedDispatcher).maxConcurrentTrains).isEqualTo(LOW_TRAFFIC_CAP)
		assertThat(registry.usesLlmDispatcher(ExampleNames.PRAHA_HLAVNI_NADRAZI)).isFalse()
	}

	@Test
	fun `CLI map resolves prahaHlavniNadraziAI to the low-traffic LLM arm`() {
		val registry = get<ExampleRegistry>()
		val ctx = build(registry.examples, ExampleNames.PRAHA_HLAVNI_NADRAZI_AI)

		assertThat(lowTrafficLoop(ctx).enableRealTimeSync).isFalse()
		assertThat(recordedArm(ctx), "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		val planner = checkNotNull(ctx.scope.publishedOrNull<AgentLoopDriver>()).planner
		assertThat(planner).isInstanceOf<MeasuringPlanAdapter>()
		val fallback = (planner as MeasuringPlanAdapter).inner.fallbackDispatcher
		assertThat((fallback as RuleBasedDispatcher).maxConcurrentTrains).isEqualTo(LOW_TRAFFIC_CAP)
		assertThat(registry.usesLlmDispatcher(ExampleNames.PRAHA_HLAVNI_NADRAZI_AI)).isTrue()
	}

	@Test
	fun `GUI map resolves both Praha arms to real-time low-traffic loops`() {
		val registry = get<ExampleRegistry>()

		val rule = build(registry.guiExamples, ExampleNames.PRAHA_HLAVNI_NADRAZI)
		assertThat(lowTrafficLoop(rule).enableRealTimeSync).isTrue()
		assertThat(recordedArm(rule), "recorded arm").isEqualTo(DispatcherArm.RULE_BASED)

		val llm = build(registry.guiExamples, ExampleNames.PRAHA_HLAVNI_NADRAZI_AI)
		assertThat(lowTrafficLoop(llm).enableRealTimeSync).isTrue()
		assertThat(recordedArm(llm), "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
	}

	/**
	 * Paired arrival streams (Issue #1366 ruling): both arms take their traffic from
	 * [ExampleRegistry.prahaTraffic], which reads only the optional seed `args[3]`, so run i of each
	 * arm with the same seed gets the same seed and train count. (ShuntingLoop does not expose its
	 * seed or train cap, so the arms are compared through the one function they both call; the
	 * effective cap of every arm is pinned above.)
	 */
	@Test
	fun `both Praha arms get the same seed and train count for the same run seed`() {
		val registry = get<ExampleRegistry>()

		val rule = registry.prahaTraffic(arrayOf("example", ExampleNames.PRAHA_HLAVNI_NADRAZI, "600", "7"))
		val llm = registry.prahaTraffic(arrayOf("example", ExampleNames.PRAHA_HLAVNI_NADRAZI_AI, "600", "7"))

		assertThat(llm).isEqualTo(rule)
		assertThat(rule).isEqualTo(
			ExampleRegistry.LoopTraffic(
				maxConcurrentTrains = LOW_TRAFFIC_CAP,
				seed = 7L,
				maxTrains = 8,
				switchLegalPairsOnly = true
			)
		)
		assertThat(registry.prahaTraffic(arrayOf("example", ExampleNames.PRAHA_HLAVNI_NADRAZI, "600")).seed)
			.isEqualTo(0L)
	}

	/**
	 * The switch-legal pair restriction is Praha's only (Issue #1376): the vyhybna and Červený Újezd
	 * loops keep the generator's accept-all pairing, so their runs and the #895 bracket stay unchanged.
	 */
	@Test
	fun `vyhybna and Cerveny Ujezd loops keep the unrestricted pairing`() {
		val registry = get<ExampleRegistry>()

		for (name in listOf(
			ExampleNames.SHUNTING_LOOP,
			ExampleNames.SHUNTING_LOOP_AI,
			ExampleNames.SHUNTING_LOOP_SYNC,
			ExampleNames.CERVENY_UJEZD,
			ExampleNames.CERVENY_UJEZD_AI,
			ExampleNames.CERVENY_UJEZD_SYNC
		)) {
			val loop = build(registry.examples, name).mainProcess
			assertThat(loop, name).isNotNull().isInstanceOf<ShuntingLoop>()
			assertThat((loop as ShuntingLoop).switchLegalPairsOnly, "switch-legal pairs only in $name").isFalse()
		}
		assertThat(ExampleRegistry.LoopTraffic.DEFAULT.switchLegalPairsOnly, "default traffic").isFalse()
	}
}
