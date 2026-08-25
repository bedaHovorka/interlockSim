/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator — Dispatcher Agent Tests
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.EmittedActionSink
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.dispatcher.testutil.newShuntingLoopContext
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SP1.7 end-to-end threading-contract gate (Issue #774; sink wiring updated for SP2c.6, Issue #829).
 *
 * Proves the two halves of the SP1.7 contract with the **real production components** (no mocks):
 *
 * 1. **Actuator marshalling** — an actuator [DomainTool][cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool]
 *    invoked on a **background (non-sim) thread** emits a [DispatchAction] to an [EmittedActionSink]
 *    (SP2c.6) which converts it to a [DispatchDecision] and posts it to the [ActuatorCommandQueue]
 *    (fire-and-forget), mirroring the conversion [KoogAgentFactory][cz.vutbr.fit.interlockSim.dispatcher.agents.KoogAgentFactory]
 *    performs in production. The tool returns a queued-success [ToolResult.Success] *without*
 *    mutating live state. The live state only changes once [DispatchDecisionApplier.onControlStep]
 *    drains the queue on the sim thread.
 *
 * 2. ~~**Perception off-thread**~~ — removed in SP2c.6 (#829): the perception tools were deleted
 *    from the LLM tool surface (perception now flows through the single sim-thread-captured
 *    [DispatcherObservationProjector] value, not through LLM-queried tools). The SP1.7
 *    perception off-thread read contract they exercised is intentionally dropped here; the
 *    actuator off-thread contract above remains covered and is the half that still ships.
 *
 * This is the test the SP1.7 review said "would prove the PR's title claim" — that
 * `DomainTool.execute()` is safe to call off the kDisco thread.
 *
 * @since Issue #774 (SP1.7 — Goal 10 threading contract); SP2c.6 (#829) rewires actuator wiring to
 *   the [SinkHolder]/[EmittedActionSink] seam
 */
@DisplayName("SP1.7 threading contract: actuator tools marshal via the SinkHolder queue off-thread")
@Tag("integration-test")
@Timeout(30, unit = TimeUnit.SECONDS)
class ActuatorToolMarshallingIntegrationTest {
	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext = newShuntingLoopContext()

	@Test
	@DisplayName("actuator tool execute() off-thread queues a decision; onControlStep() applies it on the sim thread")
	fun actuatorToolMarshalsThroughQueueAndAppliesOnControlStep() {
		val context = loadShuntingLoopContext()
		context.getInOuts() // initialize dynamic wrapper map

		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = { emptyList() }
			)
		val actuatorPort = DefaultNetworkActuatorPort(env = context)
		val queue = ActuatorCommandQueue()
		val applier =
			DispatchDecisionApplier(
				queue = queue,
				networkActuator = actuatorPort,
				onApproveTrain = {},
				onBlockTransition = {},
				onFailedReservation = {}
			)

		// Publish a snapshot on the sim (test) thread so the projection has data.
		perceptionPort.captureSnapshot()
		val sinkHolder =
			SinkHolder(
				EmittedActionSink { action ->
					val decisions =
						when (action) {
							is DispatchAction.RequestRoute ->
								listOf(
									DispatchDecision.RequestRoute(
										trainName = action.trainId,
										fromEndpointName = action.fromEndpointName,
										toEndpointName = action.toEndpointName
									)
								)
							else -> error("Unexpected action in this test: $action")
						}
					queue.postAll(decisions)
				}
			)
		val tools = ToolGroupRegistry().assembleAllTools(setOf("zA", "doA1"), sinkHolder)
		val requestRouteTool = tools.first { it.name == "request_route" }

		val trainId = "T1"
		assertThat(perceptionPort.allBlockOccupancies().none { it.trainId == trainId }).isTrue()

		// Invoke the actuator tool from a BACKGROUND thread (the agent driver thread in production).
		val backgroundThread = Executors.newSingleThreadExecutor()
		try {
			val result =
				backgroundThread
					.submit<cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult> {
						runBlocking {
							requestRouteTool.execute(
								mapOf("trainName" to trainId, "fromEndpointName" to "zA", "toEndpointName" to "doA1")
							)
						}
					}.get(5, TimeUnit.SECONDS)

			// Fire-and-forget: the tool returns Success describing the queued request, not the effect.
			assertThat(result).isInstanceOf<cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult.Success>()
		} finally {
			backgroundThread.shutdown()
		}

		// The decision is queued but NOT yet applied — live state is unchanged.
		assertThat(queue.approximateSize()).isEqualTo(1)
		assertThat(perceptionPort.allBlockOccupancies().none { it.trainId == trainId }).isTrue()

		// Sim thread drains + applies the decision.
		applier.onControlStep()

		// Queue is now empty and the reservation is now visible in live state.
		assertThat(queue.approximateSize()).isEqualTo(0)
		assertThat(perceptionPort.allBlockOccupancies().any { it.trainId == trainId }).isTrue()
	}
}
