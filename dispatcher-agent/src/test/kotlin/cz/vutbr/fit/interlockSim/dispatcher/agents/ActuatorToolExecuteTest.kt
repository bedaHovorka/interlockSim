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
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.SetSignalAspectTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.SetSwitchPositionTool
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the 4 actuator tools satisfy the SP1.7 threading contract (Issue #774): `execute()`
 * runs on the driver thread and posts a [DispatchDecision] to the [ActuatorCommandQueue]
 * (fire-and-forget) rather than touching a [cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort]
 * directly. The applier drains the queue on the kDisco thread and applies the decision.
 *
 * Also guards the enum parameter descriptors (#551 review #2: `set_signal_aspect.signal` must
 * be an `Enum` carrying [Signal] entries, not a plain `String`).
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); rewired to the queue in Issue #774 (SP1.7)
 */
class ActuatorToolExecuteTest {
	private val queue = ActuatorCommandQueue()

	@Test
	fun `request_route posts a RequestRoute decision and returns a queued-success descriptor`() {
		val result =
			runBlocking {
				RequestRouteTool(queue).execute(
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
	fun `release_route posts a ReleaseRoute decision`() {
		val result = runBlocking { ReleaseRouteTool(queue).execute(mapOf("trainName" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val decisions = queue.drain()
		assertThat(decisions).hasSize(1)
		assertThat(decisions.single()).isInstanceOf<DispatchDecision.ReleaseRoute>()
		assertThat((decisions.single() as DispatchDecision.ReleaseRoute).trainName).isEqualTo("T1")
	}

	@Test
	fun `set_switch_position parses lowercase input and posts a SetSwitchPosition decision`() {
		val result =
			runBlocking {
				SetSwitchPositionTool(queue).execute(mapOf("switchName" to "v1", "position" to "main"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val decision = queue.drain().single() as DispatchDecision.SetSwitchPosition
		assertThat(decision.switchName).isEqualTo("v1")
		assertThat(decision.position).isEqualTo(RailSwitch.Conf.MAIN)
	}

	@Test
	fun `set_switch_position parses branch case-insensitively`() {
		runBlocking {
			SetSwitchPositionTool(queue).execute(mapOf("switchName" to "v2", "position" to "Branch"))
		}

		val decision = queue.drain().single() as DispatchDecision.SetSwitchPosition
		assertThat(decision.position).isEqualTo(RailSwitch.Conf.BRANCH)
	}

	@Test
	fun `set_signal_aspect parses lowercase input and posts a SetSignalAspect decision`() {
		val result =
			runBlocking {
				SetSignalAspectTool(queue).execute(mapOf("semaphoreName" to "zA", "signal" to "s40"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val decision = queue.drain().single() as DispatchDecision.SetSignalAspect
		assertThat(decision.semaphoreName).isEqualTo("zA")
		assertThat(decision.signal).isEqualTo(Signal.S40)
	}

	@Test
	fun `set_signal_aspect signal parameter is an Enum descriptor carrying Signal entries`() {
		val tool = SetSignalAspectTool(queue)
		val signalParam = tool.parameters.first { it.name == "signal" }

		assertThat(signalParam.type).isInstanceOf<DomainToolParameterType.Enum>()
		val enumType = signalParam.type as DomainToolParameterType.Enum
		assertThat(enumType.entries).isEqualTo(Signal.entries.map { it.name })
	}

	@Test
	fun `set_switch_position position parameter is an Enum descriptor carrying Conf entries`() {
		val tool = SetSwitchPositionTool(queue)
		val positionParam = tool.parameters.first { it.name == "position" }

		assertThat(positionParam.type).isInstanceOf<DomainToolParameterType.Enum>()
		val enumType = positionParam.type as DomainToolParameterType.Enum
		assertThat(enumType.entries).isEqualTo(RailSwitch.Conf.entries.map { it.name })
	}

	@Test
	fun `invalid arguments return ToolResult Error without posting to the queue`() {
		val result =
			runBlocking {
				RequestRouteTool(queue).execute(mapOf("trainName" to "", "fromEndpointName" to "zA", "toEndpointName" to "doA1"))
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
