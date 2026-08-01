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
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.CancelRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies that the actuator tools reject invalid arguments (missing / null / blank) with
 * [ToolResult.Error] **without emitting to the sink** (#551 review #3/#4/#6; actuator tools emit
 * via the [SinkHolder]/[EmittedActionSink] seam since SP2c.6, Issue #829).
 *
 * The perception-tool validation cases were removed in SP2c.6 along with the perception tools
 * themselves (perception now flows through the sim-thread-captured
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector], not LLM
 * tools). `approve_train` argument validation is covered by [DispatchLoopToolsTest].
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); SP2c.6 (#829) rewires actuator tools to
 *   SinkHolder
 */
class ToolParameterValidationTest {
	private val emitted = mutableListOf<DispatchAction>()
	private val sinkHolder = SinkHolder(EmittedActionSink { emitted.add(it) })

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
