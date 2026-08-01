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
import assertk.assertions.hasSize
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.BlockOccupancyTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.CancelRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.SignalAspectTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.TrainPositionTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.TrainTimetableTool
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies that arg-taking tools reject invalid arguments (missing / null / blank / unknown enum)
 * with [ToolResult.Error] **without reaching the port or emitting to the sink** (#551 review
 * #3/#4/#6; actuator tools emit via the [SinkHolder]/[EmittedActionSink] seam since SP2c.6,
 * Issue #829).
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); SP2c.6 (#829) rewires actuator tools to
 *   SinkHolder
 */
class ToolParameterValidationTest {
	private val perceptionPort = mockk<NetworkPerceptionPort>(relaxed = true)
	private val emitted = mutableListOf<DispatchAction>()
	private val sinkHolder = SinkHolder(EmittedActionSink { emitted.add(it) })

	@Test
	fun `signal_aspect rejects missing null and blank semaphoreName without calling the port`() {
		val tool = SignalAspectTool(perceptionPort)
		runBlocking {
			assertThat(tool.execute(emptyMap())).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("semaphoreName" to null))).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("semaphoreName" to "  "))).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("semaphoreName" to 42))).isInstanceOf<ToolResult.Error>()
		}
		verify(exactly = 0) { perceptionPort.signalAspect(any()) }
	}

	@Test
	fun `block_occupancy rejects missing null and blank blockId without calling the port`() {
		val tool = BlockOccupancyTool(perceptionPort)
		runBlocking {
			assertThat(tool.execute(emptyMap())).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("blockId" to null))).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("blockId" to ""))).isInstanceOf<ToolResult.Error>()
		}
		verify(exactly = 0) { perceptionPort.blockOccupancy(any()) }
	}

	@Test
	fun `train_position rejects missing null and blank trainId without calling the port`() {
		val tool = TrainPositionTool(perceptionPort)
		runBlocking {
			assertThat(tool.execute(emptyMap())).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainId" to null))).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainId" to "\t"))).isInstanceOf<ToolResult.Error>()
		}
		verify(exactly = 0) { perceptionPort.trainPosition(any()) }
	}

	@Test
	fun `train_timetable rejects missing null and blank trainId without calling the port`() {
		val tool = TrainTimetableTool(perceptionPort)
		runBlocking {
			assertThat(tool.execute(emptyMap())).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainId" to null))).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainId" to " "))).isInstanceOf<ToolResult.Error>()
		}
		verify(exactly = 0) { perceptionPort.trainTimetable(any()) }
	}

	@Test
	fun `request_route rejects any blank argument without emitting to the sink`() {
		val tool = RequestRouteTool(sinkHolder, setOf("zA", "doA1"))
		runBlocking {
			assertThat(tool.execute(mapOf("trainName" to "", "fromEndpointName" to "zA", "toEndpointName" to "doA1")))
				.isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainName" to "T1", "fromEndpointName" to null, "toEndpointName" to "doA1")))
				.isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "   ")))
				.isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainName" to "T1")))
				.isInstanceOf<ToolResult.Error>()
		}
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `cancel_route rejects missing null and blank trainId without emitting to the sink`() {
		val tool = CancelRouteTool(sinkHolder)
		runBlocking {
			assertThat(tool.execute(emptyMap())).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainId" to null))).isInstanceOf<ToolResult.Error>()
			assertThat(tool.execute(mapOf("trainId" to ""))).isInstanceOf<ToolResult.Error>()
		}
		assertThat(emitted).hasSize(0)
	}
}
