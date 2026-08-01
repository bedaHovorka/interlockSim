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
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.CancelRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the actuator tools satisfy the SP2c.6 SinkHolder contract (Issue #829):
 * `execute()` runs on the driver thread and emits a [DispatchAction] to the [SinkHolder]
 * (fire-and-forget). Replaces the SP1.7 queue-based contract verified by this class before
 * the SP2c.6 rewrite.
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); SP1.7 (#774) rewired to queue;
 *   SP2c.6 (#829) rewired to SinkHolder
 */
class ActuatorToolExecuteTest {
	private val emitted = mutableListOf<DispatchAction>()
	private val sinkHolder = SinkHolder(EmittedActionSink { emitted.add(it) })

	@Test
	fun `request_route emits a RequestRoute action and returns an emitted-success descriptor`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "doA1")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val data = (result as ToolResult.Success).data as String
		assertThat(data).contains("T1")
		assertThat(data).contains("zA")
		assertThat(data).contains("doA1")

		assertThat(emitted).hasSize(1)
		val action = emitted.single()
		assertThat(action).isInstanceOf<DispatchAction.RequestRoute>()
		action as DispatchAction.RequestRoute
		assertThat(action.trainId).isEqualTo("T1")
		assertThat(action.fromEndpointName).isEqualTo("zA")
		assertThat(action.toEndpointName).isEqualTo("doA1")
	}

	@Test
	fun `request_route rejects an unknown fromEndpointName without emitting`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "kA", "toEndpointName" to "doA1")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		val message = (result as ToolResult.Error).message
		assertThat(message).contains("kA")
		assertThat(message).contains("zA")
		assertThat(message).contains("doA1")
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `request_route rejects an unknown toEndpointName without emitting`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "kB")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat((result as ToolResult.Error).message).contains("kB")
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `cancel_route emits a CancelRoute action and returns an emitted-success descriptor`() {
		val result = runBlocking { CancelRouteTool(sinkHolder).execute(mapOf("trainId" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val data = (result as ToolResult.Success).data as String
		assertThat(data).contains("T1")

		assertThat(emitted).hasSize(1)
		assertThat(emitted.single()).isInstanceOf<DispatchAction.CancelRoute>()
		assertThat((emitted.single() as DispatchAction.CancelRoute).trainId).isEqualTo("T1")
	}

	@Test
	fun `invalid arguments return ToolResult Error without emitting`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf(
						"trainName" to "",
						"fromEndpointName" to "zA",
						"toEndpointName" to "doA1"
					)
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(emitted).hasSize(0)
	}
}
