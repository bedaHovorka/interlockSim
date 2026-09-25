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

import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaExecutorConfig
import cz.vutbr.fit.interlockSim.dispatcher.executor.OllamaSimpleExecutor
import cz.vutbr.fit.interlockSim.dispatcher.planner.KoogAgentPlanAdapter
import cz.vutbr.fit.interlockSim.dispatcher.planner.MeasuringPlanAdapter
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.createMockShuntingContext
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.koin.dsl.module
import org.koin.mp.KoinPlatform.getKoin
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [DispatcherRunLifecycle] (Issue #1072).
 *
 * Proves the two end-of-run lifetimes stay separated:
 * - [DispatcherRunLifecycle.releaseKoogAgent] releases a scoped measuring/Koog adapter
 * - [DispatcherRunLifecycle.closeSharedOllamaExecutor] closes the shared singleton executor
 *   when one is bound, and is safe when Koin has no such binding
 */
@DisplayName("DispatcherRunLifecycle (Issue #1072)")
class DispatcherRunLifecycleTest : KoinTestBase() {
	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("releaseKoogAgent calls MeasuringPlanAdapter.releaseAgent when present")
	fun releaseKoogAgentCallsMeasuringAdapter() {
		val context = createMockShuntingContext()
		val measuring = mockk<MeasuringPlanAdapter>(relaxed = true)
		context.scope.declare(measuring)

		DispatcherRunLifecycle.releaseKoogAgent(context.scope)

		verify(exactly = 1) { measuring.releaseAgent() }
		context.close()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("releaseKoogAgent falls back to bare KoogAgentPlanAdapter when MeasuringPlanAdapter is absent")
	fun releaseKoogAgentFallsBackToBareAdapter() {
		val context = createMockShuntingContext()
		val bare = mockk<KoogAgentPlanAdapter>(relaxed = true)
		context.scope.declare(bare)

		DispatcherRunLifecycle.releaseKoogAgent(context.scope)

		verify(exactly = 1) { bare.releaseAgent() }
		context.close()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("releaseKoogAgent is a no-op for null scope and for scopes without a planner")
	fun releaseKoogAgentNoOpWhenAbsent() {
		DispatcherRunLifecycle.releaseKoogAgent(null)

		val context = createMockShuntingContext()
		DispatcherRunLifecycle.releaseKoogAgent(context.scope)
		context.close()
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("closeSharedOllamaExecutor closes the bound singleton and is idempotent")
	fun closeSharedOllamaExecutorClosesBoundSingleton() {
		val executor = OllamaSimpleExecutor(OllamaExecutorConfig.forLocalTesting())
		// allowOverride: testModule may already bind OllamaSimpleExecutor from the dispatcher module.
		getKoin().loadModules(
			listOf(
				module {
					single { executor }
				}
			),
			allowOverride = true
		)

		DispatcherRunLifecycle.closeSharedOllamaExecutor()
		DispatcherRunLifecycle.closeSharedOllamaExecutor()

		assertThrows<IllegalStateException> { executor.getExecutor() }
	}

	@Test
	@Timeout(value = 10, unit = TimeUnit.SECONDS)
	@DisplayName("closeSharedOllamaExecutor is safe when no OllamaSimpleExecutor is bound")
	fun closeSharedOllamaExecutorSafeWhenUnbound() {
		DispatcherRunLifecycle.closeSharedOllamaExecutor()
	}
}
