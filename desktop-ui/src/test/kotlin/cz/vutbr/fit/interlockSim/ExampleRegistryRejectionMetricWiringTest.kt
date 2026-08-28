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
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory
import cz.vutbr.fit.interlockSim.dispatcher.agents.RejectionRecordingTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.StationTopologySerializer
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createExampleContext
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Wiring regression test for the per-run rejection metric (Issue #847 round 4).
 *
 * ## Two separate wiring hazards
 *
 * 1. The tools have to be **decorated** in production, not just decoratable. A
 *    [RejectionRecordingTool] that exists and is unit-tested but is never installed is the exact
 *    "built, fully tested, never wired" failure this code path has produced four times already.
 *
 * 2. The recorder the decorator reports to has to be the **same instance the run persists**.
 *    `ExampleRegistry` resolves [KoogAgentFactory] to build the planner and only afterwards declares
 *    the correctly-armed [DispatcherRunRecorder] over the module's rule-based default. A factory
 *    that captured its recorder at construction would count every rejection into an object that is
 *    never written — a metric that reads as a clean run no matter how many calls the model got
 *    wrong. That is why the factory resolves the recorder lazily, and this test is what would catch
 *    a regression back to eager capture.
 *
 * The assertion is on the recorder's own snapshot rather than on a spy, so it fails if any link in
 * the chain breaks: the decorator, the code on the rejection, the lazy lookup, or the recorder's
 * counting.
 *
 * @since Issue #847 (round 4)
 */
@DisplayName("Tool rejections are counted into the recorder the run actually persists")
class ExampleRegistryRejectionMetricWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	private fun createAiContext(): DefaultSimulationContext {
		val registry = get<ExampleRegistry>()
		return createExampleContext(
			registry,
			get<SimulationContextFactory>(),
			"createShuntingLoopAIExample",
			"shuntingLoopAI",
			"60"
		)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("a rejected request_route lands in the persisted recorder's rejectionsByCode")
	fun rejectionReachesThePersistedRecorder() {
		val context = createAiContext()
		// The recorder ExampleRegistry declared — the one finishAndPersist will write.
		val recorder = checkNotNull(context.scope.getOrNull<DispatcherRunRecorder>())
		// Build the surface exactly as createAgent does, through the same factory instance, so the
		// decoration and the lazy recorder lookup are the production ones.
		val factory = context.scope.get<KoogAgentFactory>()
		val tools = instrumentedToolsOf(factory, context)
		val requestRoute = tools.first { it.name == "request_route" }

		val result =
			runBlocking {
				requestRoute.execute(
					mapOf("trainName" to "Train #1", "fromEndpointName" to "kA", "toEndpointName" to "B")
				)
			}

		// Precondition: the call really was rejected, and for the reason the metric claims.
		assertThat((result as ToolResult.Error).rejection, "rejection code")
			.isEqualTo(RejectionCode.ENDPOINT_IS_BLOCK_ID)
		assertThat(
			recorder.snapshot().rejectionsByCode[RejectionCode.ENDPOINT_IS_BLOCK_ID.name],
			"ENDPOINT_IS_BLOCK_ID count in the persisted recorder"
		).isEqualTo(1L)
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("an accepted call adds no rejection")
	fun acceptedCallAddsNoRejection() {
		val context = createAiContext()
		val recorder = checkNotNull(context.scope.getOrNull<DispatcherRunRecorder>())
		val tools = instrumentedToolsOf(context.scope.get<KoogAgentFactory>(), context)

		runBlocking { tools.first { it.name == "no_op" }.execute(emptyMap()) }

		assertThat(
			recorder
				.snapshot()
				.rejectionsByCode.values
				.sum(),
			"total rejections after a clean call"
		).isEqualTo(0L)
	}

	/**
	 * Rebuilds the decorated surface the way `KoogAgentFactory.createAgent` does, without needing a
	 * live Ollama: `createAgent` performs a model warm-up, which is network I/O this test must not
	 * depend on. The recorder lookup under test is the factory's own lazy provider, exercised via
	 * the same [RejectionRecordingTool] decoration.
	 */
	private fun instrumentedToolsOf(
		factory: KoogAgentFactory,
		context: DefaultSimulationContext
	): List<cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool> {
		val recordMethod =
			KoogAgentFactory::class.java.getDeclaredMethod(
				"recordRejection",
				String::class.java,
				RejectionCode::class.java
			)
		recordMethod.isAccessible = true
		val topology = StationTopologySerializer.describe(context)
		val endpoints = (topology.inOuts + topology.signals.map { it.name }).toSet()
		return get<ToolGroupRegistry>()
			.assembleAllTools(
				endpoints,
				context.scope.get(),
				context.scope.get(),
				context.scope.get(),
				topology.blocks.map { it.name }.toSet()
			).map { tool ->
				RejectionRecordingTool(tool) { toolName, code -> recordMethod.invoke(factory, toolName, code) }
			}
	}
}
