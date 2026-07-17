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
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for tool registry and tool assembly (SP1.6, Issue #551).
 *
 * Tests that:
 * - ToolGroupRegistry correctly assembles perception tools (8 tools)
 * - ToolGroupRegistry correctly assembles actuator tools (4 tools)
 * - ToolGroupRegistry correctly assembles all tools together (12 tools)
 * - Tools have proper names, descriptions, and parameters
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop)
 */
class ToolGroupRegistryTest {
	private val mockPerceptionPort = mockk<cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort>()
	private val mockActuatorPort = mockk<cz.vutbr.fit.interlockSim.ports.NetworkActuatorPort>()
	private val registry = ToolGroupRegistry()

	@Test
	fun `assemblePerceptionTools returns 8 perception tools`() {
		val tools = registry.assemblePerceptionTools(mockPerceptionPort)

		assertThat(tools).hasSize(8)
	}

	@Test
	fun `assembleActuatorTools returns 4 actuator tools`() {
		val tools = registry.assembleActuatorTools(mockActuatorPort)

		assertThat(tools).hasSize(4)
	}

	@Test
	fun `assembleAllTools returns 12 total tools`() {
		val tools = registry.assembleAllTools(mockPerceptionPort, mockActuatorPort)

		assertThat(tools).hasSize(12)
	}

	@Test
	fun `perception tools have correct names`() {
		val tools = registry.assemblePerceptionTools(mockPerceptionPort)
		val toolNames = tools.map { it.name }

		assertThat(toolNames).isEqualTo(
			listOf(
				"signal_aspect",
				"all_signal_aspects",
				"block_occupancy",
				"all_block_occupancies",
				"train_position",
				"all_train_positions",
				"train_timetable",
				"all_train_timetables"
			)
		)
	}

	@Test
	fun `actuator tools have correct names`() {
		val tools = registry.assembleActuatorTools(mockActuatorPort)
		val toolNames = tools.map { it.name }

		assertThat(toolNames).isEqualTo(
			listOf(
				"request_route",
				"release_route",
				"set_switch_position",
				"set_signal_aspect"
			)
		)
	}

	@Test
	fun `perception tools have descriptions`() {
		val tools = registry.assemblePerceptionTools(mockPerceptionPort)

		tools.forEach { tool ->
			assertThat(tool.description).isNotEmpty()
		}
	}

	@Test
	fun `actuator tools have descriptions`() {
		val tools = registry.assembleActuatorTools(mockActuatorPort)

		tools.forEach { tool ->
			assertThat(tool.description).isNotEmpty()
		}
	}

	@Test
	fun `signal_aspect tool has parameter`() {
		val tools = registry.assemblePerceptionTools(mockPerceptionPort)
		val signalAspectTool = tools.first { it.name == "signal_aspect" }

		assertThat(signalAspectTool.parameters).hasSize(1)
		assertThat(signalAspectTool.parameters[0].name).isEqualTo("semaphoreName")
	}

	@Test
	fun `request_route tool has three parameters`() {
		val tools = registry.assembleActuatorTools(mockActuatorPort)
		val requestRouteTool = tools.first { it.name == "request_route" }

		assertThat(requestRouteTool.parameters).hasSize(3)
		assertThat(requestRouteTool.parameters.map { it.name }).isEqualTo(
			listOf("trainName", "fromEndpointName", "toEndpointName")
		)
	}

	@Test
	fun `all_block_occupancies tool has no required parameters`() {
		val tools = registry.assemblePerceptionTools(mockPerceptionPort)
		val allBlockOccupiesTool = tools.first { it.name == "all_block_occupancies" }

		assertThat(allBlockOccupiesTool.parameters).hasSize(0)
	}
}
