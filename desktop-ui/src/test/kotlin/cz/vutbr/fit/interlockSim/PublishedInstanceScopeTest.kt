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
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.context.SimulationController
import cz.vutbr.fit.interlockSim.dispatcher.AgentLoopDriver
import cz.vutbr.fit.interlockSim.dispatcher.DefaultSnapshotSignal
import cz.vutbr.fit.interlockSim.dispatcher.OrphanReservationSweeper
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationCapacity
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.publishedOrNull
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContextFromMap
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Per-context visibility of the instances `ExampleRegistry` publishes into a context (Issue #1348).
 *
 * Every test builds the console LLM example `shuntingLoopAI` — which publishes every type below —
 * and then the dispatcher-free `multiTrainLoop` in the same Koin application, the way a GUI session
 * or a test JVM does. Published with Koin's `Scope.declare`, each instance was served to every later
 * context that published none, even after its own context was closed (the #1317 leak).
 */
@DisplayName("A context sees only the instances published into it (#1348)")
class PublishedInstanceScopeTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	/** Each type `ExampleRegistry` publishes, with the lookup its readers use. */
	enum class PublishedType(
		val lookup: (Scope) -> Any?
	) {
		MEASURING_PLAN_ADAPTER({ it.publishedOrNull<MeasuringPlanAdapter>() }),
		SIMULATION_CONTROLLER({ it.publishedOrNull<SimulationController>() }),
		SNAPSHOT_SIGNAL({ it.publishedOrNull<DefaultSnapshotSignal>() }),
		ORPHAN_SWEEPER({ it.publishedOrNull<OrphanReservationSweeper>() }),
		AGENT_LOOP_DRIVER({ it.publishedOrNull<AgentLoopDriver>() }),
		STATION_CAPACITY({ it.publishedOrNull<StationCapacity>() })
	}

	private fun createConsoleExample(exampleName: String): DefaultSimulationContext =
		createExampleContextFromMap(
			get<ExampleRegistry>().examples,
			get<SimulationContextFactory>(),
			exampleName
		).tracked()

	@ParameterizedTest
	@EnumSource(PublishedType::class)
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a context that publishes nothing resolves nothing, while the publisher is open and after it is closed")
	fun publishedInstanceDoesNotLeakToAnotherContext(type: PublishedType) {
		val publisher = createConsoleExample(ExampleNames.SHUNTING_LOOP_AI)
		val whileOpen = createConsoleExample(ExampleNames.MULTI_TRAIN_LOOP)

		assertThat(type.lookup(publisher.scope), "publisher's own instance").isNotNull()
		assertThat(type.lookup(whileOpen.scope), "second context, publisher open").isNull()

		publisher.close()
		val afterClose = createConsoleExample(ExampleNames.MULTI_TRAIN_LOOP)

		assertThat(type.lookup(afterClose.scope), "third context, publisher closed").isNull()
	}

	/** The effect the issue reports: stale planner, sweeper and decision-rate summaries. */
	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName("a dispatcher-free run after a closed LLM run logs no dispatcher summary")
	fun dispatcherFreeRunAfterAnLlmRunLogsNoSummary() {
		createConsoleExample(ExampleNames.SHUNTING_LOOP_AI).close()
		val dispatcherFree = createConsoleExample(ExampleNames.MULTI_TRAIN_LOOP)

		assertThat(DispatcherRunSummaries.log(dispatcherFree.scope))
			.isEqualTo(DispatcherRunSummaries.Reported(false, false, false))
	}
}
