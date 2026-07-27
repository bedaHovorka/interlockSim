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
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.DefaultSimulationContext
import cz.vutbr.fit.interlockSim.context.EditingContext
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.DefaultNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SnapshotProjectionNetworkPerceptionPort
import cz.vutbr.fit.interlockSim.sim.DefaultSimulationProcessFactory
import cz.vutbr.fit.interlockSim.testutil.TestFixtures
import cz.vutbr.fit.interlockSim.xml.XMLContextFactory
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
 * SP1.7 end-to-end threading-contract gate (Issue #774).
 *
 * Proves the two halves of the SP1.7 contract with the **real production components** (no mocks):
 *
 * 1. **Actuator marshalling** — an actuator [DomainTool][cz.vutbr.fit.interlockSim.dispatcher.agents.DomainTool]
 *    invoked on a **background (non-sim) thread** posts a [DispatchDecision] to the
 *    [ActuatorCommandQueue] (fire-and-forget) and returns a queued-success [ToolResult.Success]
 *    *without* mutating live state. The live state only changes once
 *    [DispatchDecisionApplier.onControlStep] drains the queue on the sim thread.
 *
 * 2. **Perception off-thread** — a perception tool assembled with the
 *    [SnapshotProjectionNetworkPerceptionPort] projection returns a reading from the published
 *    snapshot when invoked from a background thread, never touching the sim thread.
 *
 * This is the test the SP1.7 review said "would prove the PR's title claim" — that
 * `DomainTool.execute()` is safe to call off the kDisco thread.
 *
 * @since Issue #774 (SP1.7 — Goal 10 threading contract)
 */
@DisplayName("SP1.7 threading contract: actuator tools marshal via queue, perception reads off-thread")
@Tag("integration-test")
@Timeout(30, unit = TimeUnit.SECONDS)
class ActuatorToolMarshallingIntegrationTest {
	private val xmlContextFactory = XMLContextFactory()
	private val processFactory = DefaultSimulationProcessFactory()

	@BeforeEach
	fun startKoinForContext() {
		startKoin { modules(dispatcherAgentTestModule) }
	}

	@AfterEach
	fun stopKoinAfterContext() {
		stopKoin()
	}

	private fun loadShuntingLoopContext(): DefaultSimulationContext =
		TestFixtures.loadShuntingXml().use { xmlStream ->
			val editingContext = xmlContextFactory.createContext(xmlStream) as EditingContext
			DefaultSimulationContext.fromEditingContext(editingContext, processFactory)
		}

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
		val projection = SnapshotProjectionNetworkPerceptionPort { perceptionPort.snapshot() }
		val tools = ToolGroupRegistry().assembleAllTools(projection, queue, setOf("zA", "doA1"))
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

	@Test
	@DisplayName("perception tool execute() off-thread reads the published snapshot via the projection")
	fun perceptionToolReadsOffThreadViaProjection() {
		val context = loadShuntingLoopContext()
		context.getInOuts()

		val perceptionPort =
			DefaultNetworkPerceptionPort(
				env = context,
				activeTrains = { emptyList() }
			)
		// Publish a snapshot on the sim (test) thread.
		perceptionPort.captureSnapshot()

		val projection = SnapshotProjectionNetworkPerceptionPort { perceptionPort.snapshot() }
		val queue = ActuatorCommandQueue()
		val tools = ToolGroupRegistry().assembleAllTools(projection, queue, emptySet())
		val signalAspectTool = tools.first { it.name == "signal_aspect" }

		// Invoke the perception tool from a BACKGROUND thread — it must read the projected
		// snapshot, not touch the live (sim-thread-only) port methods.
		val backgroundThread = Executors.newSingleThreadExecutor()
		try {
			val result =
				backgroundThread
					.submit<cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult> {
						runBlocking {
							signalAspectTool.execute(mapOf("semaphoreName" to "zA"))
						}
					}.get(5, TimeUnit.SECONDS)

			assertThat(result).isInstanceOf<cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult.Success>()
			val data = (result as cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult.Success).data
			assertThat(data).isNotNull()
			val reading = data as cz.vutbr.fit.interlockSim.ports.SemaphoreReading
			assertThat(reading.name).isEqualTo("zA")
		} finally {
			backgroundThread.shutdown()
		}

		// No actuator decision was posted — the queue stays empty.
		assertThat(queue.drain()).hasSize(0)
	}
}
