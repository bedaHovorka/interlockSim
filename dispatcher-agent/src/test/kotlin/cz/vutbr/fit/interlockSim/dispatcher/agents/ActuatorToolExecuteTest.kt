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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameAs
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ReleaseRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.SetSignalAspectTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.SetSwitchPositionTool
import cz.vutbr.fit.interlockSim.objects.cells.RailSwitch
import cz.vutbr.fit.interlockSim.objects.cells.Signal
import cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort
import cz.vutbr.fit.interlockSim.ports.RouteRequestResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the 4 actuator tools forward arguments to [NetworkActuatorPort] correctly (incl.
 * argument order and case-insensitive enum parsing) and wrap the result in [ToolResult.Success].
 * Also guards the enum parameter descriptors (#551 review #2: `set_signal_aspect.signal` must
 * be an `Enum` carrying [Signal] entries, not a plain `String`).
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class ActuatorToolExecuteTest {
	private val actuatorPort = mockk<NetworkActuatorPort>(relaxed = true)

	@Test
	fun `request_route forwards trainName from to in order and wraps the result`() {
		val reserved = RouteRequestResult.Reserved("T1", 3)
		every { actuatorPort.requestRoute("T1", "zA", "doA1") } returns reserved

		val result =
			runBlocking {
				RequestRouteTool(actuatorPort).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "doA1")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isSameAs(reserved)
		verify(exactly = 1) { actuatorPort.requestRoute("T1", "zA", "doA1") }
	}

	@Test
	fun `release_route forwards trainName and wraps the boolean`() {
		every { actuatorPort.releaseRoute("T1") } returns true

		val result = runBlocking { ReleaseRouteTool(actuatorPort).execute(mapOf("trainName" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isEqualTo(true)
		verify(exactly = 1) { actuatorPort.releaseRoute("T1") }
	}

	@Test
	fun `set_switch_position parses lowercase input and forwards Conf enum`() {
		every { actuatorPort.setSwitchPosition("v1", RailSwitch.Conf.MAIN) } returns true

		val result =
			runBlocking {
				SetSwitchPositionTool(actuatorPort).execute(mapOf("switchName" to "v1", "position" to "main"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isEqualTo(true)
		verify(exactly = 1) { actuatorPort.setSwitchPosition("v1", RailSwitch.Conf.MAIN) }
	}

	@Test
	fun `set_switch_position parses branch case-insensitively`() {
		every { actuatorPort.setSwitchPosition("v2", RailSwitch.Conf.BRANCH) } returns true

		runBlocking {
			SetSwitchPositionTool(actuatorPort).execute(mapOf("switchName" to "v2", "position" to "Branch"))
		}

		verify(exactly = 1) { actuatorPort.setSwitchPosition("v2", RailSwitch.Conf.BRANCH) }
	}

	@Test
	fun `set_signal_aspect parses lowercase input and forwards Signal enum`() {
		every { actuatorPort.setSignalAspect("zA", Signal.S40) } returns true

		val result =
			runBlocking {
				SetSignalAspectTool(actuatorPort).execute(mapOf("semaphoreName" to "zA", "signal" to "s40"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((result as ToolResult.Success).data).isEqualTo(true)
		verify(exactly = 1) { actuatorPort.setSignalAspect("zA", Signal.S40) }
	}

	@Test
	fun `set_signal_aspect signal parameter is an Enum descriptor carrying Signal entries`() {
		val tool = SetSignalAspectTool(actuatorPort)
		val signalParam = tool.parameters.first { it.name == "signal" }

		assertThat(signalParam.type).isInstanceOf<DomainToolParameterType.Enum>()
		val enumType = signalParam.type as DomainToolParameterType.Enum
		assertThat(enumType.entries).isEqualTo(Signal.entries.map { it.name })
	}

	@Test
	fun `set_switch_position position parameter is an Enum descriptor carrying Conf entries`() {
		val tool = SetSwitchPositionTool(actuatorPort)
		val positionParam = tool.parameters.first { it.name == "position" }

		assertThat(positionParam.type).isInstanceOf<DomainToolParameterType.Enum>()
		val enumType = positionParam.type as DomainToolParameterType.Enum
		assertThat(enumType.entries).isEqualTo(RailSwitch.Conf.entries.map { it.name })
	}

	@Test
	fun `actuator tool translates a port exception into ToolResult Error`() {
		every { actuatorPort.releaseRoute("boom") } throws IllegalStateException("locked")

		val result = runBlocking { ReleaseRouteTool(actuatorPort).execute(mapOf("trainName" to "boom")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat((result as ToolResult.Error).cause).isNotNull()
	}
}
