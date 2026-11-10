/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Admission cap of the rule-based dispatchers the agent stack builds (Issue #1303).
 *
 * Contexts are built, never run, so no Ollama call, GUI window or driver thread starts.
 */
package cz.vutbr.fit.interlockSim

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.RuleBasedPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.publishedOrNull
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.DispatchObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.RuleBasedDispatcher
import cz.vutbr.fit.interlockSim.testutil.CERVENY_UJEZD_STATION_CAPACITY
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContextFromMap
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Pins the admission cap of every [RuleBasedDispatcher] the dispatcher-agent stack builds: the
 * rule-based planner of the control arms (`shuntingLoop`, `cervenyUjezd`) and the fallback of the
 * LLM arms (`shuntingLoopAI`, `cervenyUjezdAI`). Each takes its own station's capacity — 4 on
 * Červený Újezd, 2 on vyhybna — instead of the vyhybna default
 * [RuleBasedDispatcher.DEFAULT_MAX_CONCURRENT_TRAINS].
 *
 * The cap is read from the planner the context's [AgentLoopDriver] actually runs, and the
 * two-context tests build both stations in one Koin application (the way a GUI session starts
 * one example after another), in both orders, so a capacity leaking from one context to the next
 * would show.
 */
@DisplayName("Agent-stack rule-based dispatchers take the station capacity (#1303)")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class StationCapacityDispatcherWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun build(
		map: Map<String, (SimulationContextFactory, Array<String>) -> SimulationContext>,
		exampleName: String
	): DefaultSimulationContext = createExampleContextFromMap(map, get<SimulationContextFactory>(), exampleName).tracked()

	private fun console(exampleName: String) = build(get<ExampleRegistry>().examples, exampleName)

	private fun gui(exampleName: String) = build(get<ExampleRegistry>().guiExamples, exampleName)

	/** The rule-based planner the driver of a control-arm context runs. */
	private fun ruleBasedPlanner(ctx: DefaultSimulationContext): RuleBasedPlanAdapter {
		val planner = checkNotNull(ctx.scope.publishedOrNull<AgentLoopDriver>()).planner
		assertThat(planner).isInstanceOf<RuleBasedPlanAdapter>()
		return planner as RuleBasedPlanAdapter
	}

	/** The dispatcher behind the rule-based planner of a control-arm context. */
	private fun ruleBasedPlannerDispatcher(ctx: DefaultSimulationContext): RuleBasedDispatcher {
		val dispatcher = ruleBasedPlanner(ctx).dispatcher
		assertThat(dispatcher).isInstanceOf<RuleBasedDispatcher>()
		return dispatcher as RuleBasedDispatcher
	}

	/** The rule-based fallback of an LLM-arm context. */
	private fun llmFallbackDispatcher(ctx: DefaultSimulationContext): RuleBasedDispatcher {
		val planner = checkNotNull(ctx.scope.publishedOrNull<AgentLoopDriver>()).planner
		assertThat(planner).isInstanceOf<MeasuringPlanAdapter>()
		val fallback = (planner as MeasuringPlanAdapter).inner.fallbackDispatcher
		assertThat(fallback).isInstanceOf<RuleBasedDispatcher>()
		return fallback as RuleBasedDispatcher
	}

	/** Five queued trains and an empty station: a dispatcher approves exactly as many as its cap. */
	private fun fiveQueuedTrainsOnAnEmptyStation(): DispatchObservation =
		DispatchObservation.from(
			simTime = 0.0,
			trainPositions = emptyList(),
			unapprovedTrains = (1..FIVE_TRAINS).map { QueuedTrainObservation("T$it", "out") },
			innerBlockInputs = emptyList(),
			outerBlockInputs = emptyList()
		)

	private fun approvals(count: Int): List<DispatchDecision> = (1..count).map { DispatchDecision.ApproveTrain("T$it") }

	@Test
	fun `cervenyUjezd control arm admits up to the four-track station capacity`() {
		val ctx = console(ExampleNames.CERVENY_UJEZD)

		assertThat(ruleBasedPlannerDispatcher(ctx).maxConcurrentTrains).isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		assertThat(runBlocking { ruleBasedPlanner(ctx).plan(fiveQueuedTrainsOnAnEmptyStation()) })
			.containsExactly(*approvals(CERVENY_UJEZD_STATION_CAPACITY).toTypedArray())
	}

	@Test
	fun `cervenyUjezd GUI arm plans with the four-track station capacity`() {
		assertThat(ruleBasedPlannerDispatcher(gui(ExampleNames.CERVENY_UJEZD)).maxConcurrentTrains)
			.isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
	}

	@Test
	fun `cervenyUjezdAI rule fallback admits up to the four-track station capacity`() {
		val fallback = llmFallbackDispatcher(console(ExampleNames.CERVENY_UJEZD_AI))

		assertThat(fallback.maxConcurrentTrains).isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		assertThat(fallback.decide(fiveQueuedTrainsOnAnEmptyStation()))
			.containsExactly(*approvals(CERVENY_UJEZD_STATION_CAPACITY).toTypedArray())
	}

	@Test
	fun `cervenyUjezdAI GUI rule fallback takes the four-track station capacity`() {
		assertThat(llmFallbackDispatcher(gui(ExampleNames.CERVENY_UJEZD_AI)).maxConcurrentTrains)
			.isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
	}

	@Test
	fun `vyhybna arms keep the two-track cap`() {
		assertThat(ruleBasedPlannerDispatcher(console(ExampleNames.SHUNTING_LOOP)).maxConcurrentTrains)
			.isEqualTo(VYHYBNA_STATION_CAPACITY)
		assertThat(llmFallbackDispatcher(console(ExampleNames.SHUNTING_LOOP_AI)).maxConcurrentTrains)
			.isEqualTo(VYHYBNA_STATION_CAPACITY)
	}

	@Test
	fun `Cerveny Ujezd then vyhybna in one Koin application each plan with their own capacity`() {
		val cervenyUjezd = console(ExampleNames.CERVENY_UJEZD)
		val vyhybna = console(ExampleNames.SHUNTING_LOOP)

		assertThat(ruleBasedPlannerDispatcher(cervenyUjezd).maxConcurrentTrains, "Cerveny Ujezd")
			.isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		assertThat(ruleBasedPlannerDispatcher(vyhybna).maxConcurrentTrains, "vyhybna")
			.isEqualTo(VYHYBNA_STATION_CAPACITY)
	}

	@Test
	fun `vyhybna then Cerveny Ujezd in one Koin application each plan with their own capacity`() {
		val vyhybna = console(ExampleNames.SHUNTING_LOOP)
		val cervenyUjezd = console(ExampleNames.CERVENY_UJEZD)

		assertThat(ruleBasedPlannerDispatcher(vyhybna).maxConcurrentTrains, "vyhybna")
			.isEqualTo(VYHYBNA_STATION_CAPACITY)
		assertThat(ruleBasedPlannerDispatcher(cervenyUjezd).maxConcurrentTrains, "Cerveny Ujezd")
			.isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
	}

	@Test
	fun `LLM fallbacks of Cerveny Ujezd and vyhybna in one Koin application each take their own capacity`() {
		val cervenyUjezd = console(ExampleNames.CERVENY_UJEZD_AI)
		val vyhybna = console(ExampleNames.SHUNTING_LOOP_AI)
		val cervenyUjezdAgain = console(ExampleNames.CERVENY_UJEZD_AI)

		assertThat(llmFallbackDispatcher(cervenyUjezd).maxConcurrentTrains, "Cerveny Ujezd")
			.isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
		assertThat(llmFallbackDispatcher(vyhybna).maxConcurrentTrains, "vyhybna")
			.isEqualTo(VYHYBNA_STATION_CAPACITY)
		assertThat(llmFallbackDispatcher(cervenyUjezdAgain).maxConcurrentTrains, "Cerveny Ujezd after vyhybna")
			.isEqualTo(CERVENY_UJEZD_STATION_CAPACITY)
	}

	private companion object {
		/** vyhybna.xml has two parallel station tracks (k1, k2). */
		const val VYHYBNA_STATION_CAPACITY = 2

		const val FIVE_TRAINS = 5
	}
}
