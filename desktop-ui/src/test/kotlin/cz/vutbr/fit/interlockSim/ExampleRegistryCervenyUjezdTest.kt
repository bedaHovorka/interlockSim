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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.sim.Dispatcher
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
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
	): DefaultSimulationContext {
		// Names come from `ExampleNames` (`:core`) — one home for both registries and the tests.
		val factory = checkNotNull(map[ExampleNames.CERVENY_UJEZD]) { "cervenyUjezd is not registered" }
		return (factory(get(), arrayOf("example", ExampleNames.CERVENY_UJEZD, "60")) as DefaultSimulationContext).tracked()
	}

	@Test
	fun `CLI map resolves cervenyUjezd to a ShuntingLoop on the four-track station`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.examples.keys).contains(ExampleNames.CERVENY_UJEZD)
		val ctx = build(registry.examples)
		val loop = ctx.mainProcess
		assertThat(loop).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat((loop as ShuntingLoop).stationCapacity).isEqualTo(CERVENY_UJEZD_CAPACITY)
	}

	@Test
	fun `GUI map resolves cervenyUjezd to a ShuntingLoop on the four-track station`() {
		val registry = get<ExampleRegistry>()

		assertThat(registry.guiExamples.keys).contains(ExampleNames.CERVENY_UJEZD)
		val ctx = build(registry.guiExamples)
		val loop = ctx.mainProcess
		assertThat(loop).isNotNull().isInstanceOf<ShuntingLoop>()
		assertThat((loop as ShuntingLoop).stationCapacity).isEqualTo(CERVENY_UJEZD_CAPACITY)
	}

	/**
	 * Pins the GUI example's dispatcher cap at the vyhybna-derived constant of
	 * [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS] (2), while this station's loop capacity
	 * is 4 — the difference the synchronous CLI/native wiring ([wireSynchronousDispatcher], which
	 * sizes the dispatcher to the loop capacity) does not have. SP3 documents this as demand D5 in
	 * `docs/goal9b-demands/SP3-cerveny-ujezd-scenario.md`; SP8 owns raising the cap
	 * (Issue #1155). Until then the GUI variant may approve at most 2 trains in this scenario.
	 */
	@Test
	fun `GUI wiring keeps the dispatcher cap at the vyhybna constant while the station could take four`() {
		val registry = get<ExampleRegistry>()

		val ctx = build(registry.guiExamples)

		val dispatcher = checkNotNull(ctx.scope.getOrNull<Dispatcher>())
		assertThat(dispatcher).isInstanceOf<RuleBasedDispatcher>()
		assertThat((dispatcher as RuleBasedDispatcher).maxConcurrentTrains)
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

	private companion object {
		private const val CERVENY_UJEZD_CAPACITY = 4
	}
}
