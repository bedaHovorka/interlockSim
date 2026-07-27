/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ReleaseRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the 2 actuator tools satisfy the SP1.7 threading contract (Issue #774): `execute()`
 * runs on the driver thread and posts a [DispatchDecision] to the [ActuatorCommandQueue]
 * (fire-and-forget) rather than touching a [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort]
 * directly. The applier drains the queue on the kDisco thread and applies the decision.
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to the queue in Issue #774 (SP1.7)
 */
class ActuatorToolExecuteTest {
	private val queue = ActuatorCommandQueue()

	@Test
	fun `request_route posts a RequestRoute decision and returns a queued-success descriptor`() {
		val result =
			runBlocking {
				RequestRouteTool(queue, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "doA1")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val data = (result as ToolResult.Success).data as String
		assertThat(data).contains("T1")
		assertThat(data).contains("zA")
		assertThat(data).contains("doA1")

		val decisions = queue.drain()
		assertThat(decisions).hasSize(1)
		val decision = decisions.single()
		assertThat(decision).isInstanceOf<DispatchDecision.RequestRoute>()
		decision as DispatchDecision.RequestRoute
		assertThat(decision.trainName).isEqualTo("T1")
		assertThat(decision.fromEndpointName).isEqualTo("zA")
		assertThat(decision.toEndpointName).isEqualTo("doA1")
	}

	@Test
	fun `request_route rejects an unknown fromEndpointName without posting to the queue`() {
		val result =
			runBlocking {
				RequestRouteTool(queue, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "kA", "toEndpointName" to "doA1")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		val message = (result as ToolResult.Error).message
		assertThat(message).contains("kA")
		assertThat(message).contains("zA")
		assertThat(message).contains("doA1")
		assertThat(queue.drain()).hasSize(0)
	}

	@Test
	fun `request_route rejects an unknown toEndpointName without posting to the queue`() {
		val result =
			runBlocking {
				RequestRouteTool(queue, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "kB")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat((result as ToolResult.Error).message).contains("kB")
		assertThat(queue.drain()).hasSize(0)
	}

	@Test
	fun `release_route posts a ReleaseRoute decision`() {
		val result = runBlocking { ReleaseRouteTool(queue).execute(mapOf("trainName" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val decisions = queue.drain()
		assertThat(decisions).hasSize(1)
		assertThat(decisions.single()).isInstanceOf<DispatchDecision.ReleaseRoute>()
		assertThat((decisions.single() as DispatchDecision.ReleaseRoute).trainName).isEqualTo("T1")
	}

	@Test
	fun `invalid arguments return ToolResult Error without posting to the queue`() {
		val result =
			runBlocking {
				RequestRouteTool(queue, setOf("zA", "doA1")).execute(
					mapOf(
						"trainName" to "",
						"fromEndpointName" to "zA",
						"toEndpointName" to "doA1"
					)
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(queue.drain()).hasSize(0)
	}

	@Test
	fun `actuator tool reports backpressure as ToolResult Error when the queue is full`() {
		val fullQueue = ActuatorCommandQueue(capacity = 1)
		// Occupy the single slot.
		fullQueue.postAll(listOf(DispatchDecision.ReleaseRoute("occupier")))

		val result = runBlocking { ReleaseRouteTool(fullQueue).execute(mapOf("trainName" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat((result as ToolResult.Error).message).isNotNull()
		// The occupier decision is still the only one in the queue.
		assertThat(fullQueue.drain()).hasSize(1)
	}
}
