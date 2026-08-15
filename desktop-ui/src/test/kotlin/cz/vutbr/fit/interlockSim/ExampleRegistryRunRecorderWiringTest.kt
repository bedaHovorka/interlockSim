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
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherArm
import cz.vutbr.fit.interlockSim.dispatcher.planner.DispatcherRunRecorder
import cz.vutbr.fit.interlockSim.dispatcher.planner.RunSnapshotStore
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Wiring regression test for the SP2c.22/23 run-recording pipeline
 * (Issue #847 round 4, finding R4-5).
 *
 * ## What was broken
 *
 * `RunSnapshotStore.write()` had **zero** production callers and no Koin binding at all, so
 * `build/reports/dispatcher-runs/` was never created by any run and SP2c.23's cross-run aggregator
 * (#846) had no producer whatsoever — `dispatcherReliabilityReport` always rendered an all-zero
 * report from an empty directory.
 *
 * `DispatcherRunRecorder` fared little better: the GUI called `finish()`, but nothing ever called
 * `onTick` or `onActionOutcome`, so what it froze was an all-zero snapshot that was then discarded.
 *
 * And the binding hard-coded `arm = RULE_BASED, model = ""` for **every** context, so even a working
 * pipeline would have filed every LLM run under `dispatcher-runs/rule_based/` — silently merging the
 * two arms whose comparison is the entire point of A4.
 *
 * @since Issue #847 (round 4)
 */
@DisplayName("ExampleRegistry wires the per-run recorder and snapshot store")
class ExampleRegistryRunRecorderWiringTest : KoinTestBase() {
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
	@DisplayName("a snapshot store is resolvable, so a finished run has somewhere to be written")
	fun snapshotStoreIsBound() {
		val context = createExample("createShuntingLoopAIExample", "shuntingLoopAI")

		assertThat(context.scope.getOrNull<RunSnapshotStore>()).isNotNull()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("the AI example records under the LLM arm, not the hard-coded rule-based default")
	fun aiExampleRecordsUnderTheLlmArm() {
		val context = createExample("createShuntingLoopAIExample", "shuntingLoopAI")
		val recorder = checkNotNull(context.scope.getOrNull<DispatcherRunRecorder>())

		val snapshot = recorder.snapshot()

		assertThat(snapshot.arm, "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(snapshot.params.model, "recorded model").isNotEmpty()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("the GUI AI example records under the LLM arm, not the hard-coded rule-based default")
	fun guiAiExampleRecordsUnderTheLlmArm() {
		// Issue #928: createShuntingLoopAIGuiExample never declared the LLM_TOOL_CALLING recorder
		// override that createShuntingLoopAIExample (the console variant) has had since #847 round 4,
		// so every GUI shuntingLoopAI run fell through to the Koin-default RULE_BASED binding and was
		// written to disk mislabeled, polluting measurement data.
		val context = createExample("createShuntingLoopAIGuiExample", "shuntingLoopAI")
		val recorder = checkNotNull(context.scope.getOrNull<DispatcherRunRecorder>())

		val snapshot = recorder.snapshot()

		assertThat(snapshot.arm, "recorded arm").isEqualTo(DispatcherArm.LLM_TOOL_CALLING)
		assertThat(snapshot.params.model, "recorded model").isNotEmpty()
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	@DisplayName("the rule-based example still records under the rule-based arm")
	fun ruleBasedExampleRecordsUnderTheRuleBasedArm() {
		val context = createExample("createShuntingLoopExample", "shuntingLoop")
		val recorder = checkNotNull(context.scope.getOrNull<DispatcherRunRecorder>())

		assertThat(recorder.snapshot().arm, "recorded arm").isEqualTo(DispatcherArm.RULE_BASED)
	}

	/**
	 * Two contexts must never share a recorder — a run's identity is one context lifetime
	 * (SP2c.22), and a shared instance would merge two runs' counters into one JSON.
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	@DisplayName("each context gets its own recorder with its own run id")
	fun recordersAreIndependentPerContext() {
		val first =
			checkNotNull(createExample("createShuntingLoopAIExample", "shuntingLoopAI").scope.getOrNull<DispatcherRunRecorder>())
		val second =
			checkNotNull(createExample("createShuntingLoopAIExample", "shuntingLoopAI").scope.getOrNull<DispatcherRunRecorder>())

		assertThat(first.runId == second.runId, "two contexts share a runId").isEqualTo(false)
	}
}
