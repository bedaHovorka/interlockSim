/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Lookup of the Cerveny Ujezd scenario by name in the CLI and GUI maps of [ExampleRegistry]
 * (Goal 1B SP3.2, Issue #1164), and a check that the five older examples still resolve.
 *
 * Contexts are built, never run, so no GUI window or driver thread starts.
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsAtLeast
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.CERVENY_UJEZD_STATION_CAPACITY
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContextFromMap
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.test.get

@DisplayName("ExampleRegistry - Cerveny Ujezd scenario")
class ExampleRegistryCervenyUjezdTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun build(
		map: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext>
	): DefaultSimulationContext = createExampleContextFromMap(map, get(), ExampleNames.CERVENY_UJEZD).tracked()

	@Test
	fun `CLI map resolves cervenyUjezd to a ShuntingLoop on the four-track station without real-time sync`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.examples.keys).contains(ExampleNames.CERVENY_UJEZD)
		val ctx = build(registry.examples)
		val loop = ctx.mainProcess
		assertThat(loop).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat((loop as ShuntingLoop).stationCapacity).isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		assertThat(loop.enableRealTimeSync).isFalse()
	}

	@Test
	fun `GUI map resolves cervenyUjezd to a real-time ShuntingLoop on the four-track station`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.guiExamples.keys).contains(ExampleNames.CERVENY_UJEZD)
		val ctx = build(registry.guiExamples)
		val loop = ctx.mainProcess
		assertThat(loop).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat((loop as ShuntingLoop).stationCapacity).isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		assertThat(loop.enableRealTimeSync).isTrue()
	}

	/**
	 * Pins the dispatcher cap at the vyhybna-derived constant of
	 * [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS] (2), while this station's loop capacity
	 * is 4 — on both agent-stack arm wirings of this station: this GUI example and, since
	 * Issue #1276 (PR #1282 review), the console control arm too. SP3 documents the synchronous
	 * native run at capacity 4 as demand D5 in `docs/goal9b-demands/SP3-cerveny-ujezd-scenario.md`;
	 * SP8 owns raising the cap (Issue #1155). Until then the agent-stack examples may approve at
	 * most 2 trains in this scenario.
	 */
	@Test
	fun `agent-stack arm wirings keep the dispatcher cap at the vyhybna constant while the station could take four`() {
		val registry = get<ExampleRegistry>()

		val dispatcher = checkNotNull(build(registry.guiExamples).scope.getOrNull<Dispatcher>())
		assertThat(dispatcher).isInstanceOf<RuleBasedDispatcher>()
		assertThat((dispatcher as RuleBasedDispatcher).maxConcurrentTrains)
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)

		// Issue #1276 (PR #1282 review): the console cervenyUjezd runs the same agent stack, so
		// the SP10.1 doc's "both arms ran at most 2 trains at a time" is pinned here too, not
		// only measured.
		val cliDispatcher = checkNotNull(build(registry.examples).scope.getOrNull<Dispatcher>())
		assertThat(cliDispatcher).isInstanceOf<RuleBasedDispatcher>()
		assertThat((cliDispatcher as RuleBasedDispatcher).maxConcurrentTrains)
			.isEqualTo(RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS)
	}

	@Test
	fun `the five older examples still resolve unchanged`() {
		val registry = get<ExampleRegistry>()

		// Names come from `ExampleNames` (`:core`).
		assertThat(registry.getAvailableExamples()).containsAtLeast(
			ExampleNames.MULTI_TRAIN_LOOP,
			ExampleNames.SHUNTING_LOOP,
			ExampleNames.SHUNTING_LOOP_AI,
			ExampleNames.SHUNTING_LOOP_SYNC,
			ExampleNames.THREE_TRAIN_LOOP
		)
		assertThat(registry.getAvailableGuiExamples()).containsAtLeast(
			ExampleNames.MULTI_TRAIN_LOOP,
			ExampleNames.SHUNTING_LOOP,
			ExampleNames.SHUNTING_LOOP_AI,
			ExampleNames.THREE_TRAIN_LOOP
		)
	}
}
