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
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.SimulationContextFactory
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.testutil.KoinTestBase
import cz.vutbr.fit.interlockSim.testutil.testModuleFull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.module.Module
import org.koin.test.get
import java.util.concurrent.TimeUnit

/**
 * Regression test for the Goal 10 "dispatcher cannot approve any train" root cause: two separate
 * [cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort] instances existed per simulation
 * — one directly constructed in [ExampleRegistry.wireDispatcherAgent] and kept fresh via
 * `loop.snapshotCaptureHook`, and a second, never-refreshed one resolved from the Koin scope
 * (the instance every LLM-callable perception tool actually reads from via
 * [cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory]). Before the fix, the
 * Koin-scoped instance's [NetworkPerceptionPort.snapshot] permanently returned
 * [cz.vutbr.fit.interlockSim.ports.SimulationSnapshot.EMPTY] regardless of simulation state.
 *
 * Invokes the private `createShuntingLoopExample` factory method reflectively (same technique as
 * [ExampleRegistryCollisionWiringTest]), then proves the Koin-resolved [NetworkPerceptionPort] —
 * not a hand-rolled equivalent — reports live train positions during a real run.
 *
 * Polls [NetworkPerceptionPort.snapshot] rather than `allTrainPositions()`: the live port's
 * single-query methods are kDisco-thread-only, while `snapshot()` is `@Volatile`-backed and safe
 * to poll from the test thread while the simulation runs concurrently on its own thread.
 *
 * @since Goal 10 dispatcher-cannot-approve-trains fix
 */
@DisplayName("ExampleRegistry perception-port DI wiring — Goal 10 dispatcher-cannot-approve-trains fix")
class ExampleRegistryPerceptionPortWiringTest : KoinTestBase() {
	override fun getTestModule(): Module = testModuleFull

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	@DisplayName(
		"createShuntingLoopExample's Koin-scoped NetworkPerceptionPort reports live train " +
			"positions during a real run, not a permanently-empty snapshot"
	)
	fun shuntingLoopExampleWiresSharedPerceptionPort() {
		val registry = get<ExampleRegistry>()
		val createMethod =
			ExampleRegistry::class.java.getDeclaredMethod(
				"createShuntingLoopExample",
				SimulationContextFactory::class.java,
				Array<String>::class.java
			)
		createMethod.isAccessible = true
		val factory = get<SimulationContextFactory>()
		val args = arrayOf("example", "shuntingLoop", "300")

		val context = createMethod.invoke(registry, factory, args) as DefaultSimulationContext
		// Resolve the SAME NetworkPerceptionPort instance the dispatcher agent's tools read
		// from, via Koin — not the port ExampleRegistry passes to AgentLoopDriver. Before the
		// fix these were two different instances; only this call proves they're unified now.
		val perceptionPort = context.scope.get<NetworkPerceptionPort>()

		val simThread = Thread({ context.run() }, "perception-port-wiring-test-sim")
		simThread.isDaemon = true
		simThread.start()
		try {
			val deadlineMillis = System.currentTimeMillis() + 10_000
			var positions = perceptionPort.snapshot().trainPositions
			while (positions.isEmpty() && System.currentTimeMillis() < deadlineMillis) {
				Thread.sleep(20)
				positions = perceptionPort.snapshot().trainPositions
			}
			assertThat(positions).isNotEmpty()
		} finally {
			context.stop()
			simThread.join(TimeUnit.SECONDS.toMillis(10))
		}
	}
}
