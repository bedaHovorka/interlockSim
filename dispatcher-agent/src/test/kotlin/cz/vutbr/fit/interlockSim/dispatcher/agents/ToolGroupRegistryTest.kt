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
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ToolGroupRegistry
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for tool registry and tool assembly (SP1.6, Issue #551; updated in SP1.7, Issue #774
 * for actuator tools taking an [ActuatorCommandQueue] instead of an actuator port).
 *
 * Tests that:
 * - ToolGroupRegistry correctly assembles perception tools (9 tools)
 * - ToolGroupRegistry correctly assembles actuator tools (4 tools)
 * - ToolGroupRegistry correctly assembles all tools together (13 tools)
 * - Tools have proper names, descriptions, and parameters
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); SP2a.1 (#552) adds train_perception
 */
class ToolGroupRegistryTest {
	private val mockPerceptionPort = mockk<cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort>()
	private val mockSensorPort = mockk<DispatchLoopSensorPort>()
	private val commandQueue = ActuatorCommandQueue()
	private val registry = ToolGroupRegistry()

	@Test
	fun `assemblePerceptionTools returns 9 perception tools`() {
		val tools = registry.assemblePerceptionTools(mockPerceptionPort)

		assertThat(tools).hasSize(9)
	}

	@Test
	fun `assembleActuatorTools returns 4 actuator tools`() {
		val tools = registry.assembleActuatorTools(commandQueue)

		assertThat(tools).hasSize(4)
	}

	@Test
	fun `assembleAllTools returns 13 total tools`() {
		val tools = registry.assembleAllTools(mockPerceptionPort, commandQueue)

		assertThat(tools).hasSize(13)
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
				"all_train_timetables",
				"train_perception"
			)
		)
	}

	@Test
	fun `actuator tools have correct names`() {
		val tools = registry.assembleActuatorTools(commandQueue)
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
		val tools = registry.assembleActuatorTools(commandQueue)

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
		val tools = registry.assembleActuatorTools(commandQueue)
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

	@Test
	fun `assembleAllTools returns 13 tools with distinct names`() {
		val tools = registry.assembleAllTools(mockPerceptionPort, commandQueue)

		assertThat(tools).hasSize(13)
		// A duplicate-name / drop-one regression must not pass the size check alone (#551 review #9).
		assertThat(tools.map { it.name }.toSet()).hasSize(13)
	}

	// ── SP4.1 dispatch-loop tool groups (Issue #563) ──────────────────────────

	@Test
	fun `assembleDispatchLoopSensorTools returns 2 sensor tools`() {
		val tools = registry.assembleDispatchLoopSensorTools(mockSensorPort)

		assertThat(tools).hasSize(2)
	}

	@Test
	fun `assembleDispatchLoopActuatorTools returns 1 actuator tool`() {
		val tools = registry.assembleDispatchLoopActuatorTools(commandQueue)

		assertThat(tools).hasSize(1)
	}

	@Test
	fun `assembleDispatchLoopTools returns 3 total dispatch-loop tools`() {
		val tools = registry.assembleDispatchLoopTools(mockSensorPort, commandQueue)

		assertThat(tools).hasSize(3)
	}

	@Test
	fun `dispatch-loop sensor tools have correct names`() {
		val tools = registry.assembleDispatchLoopSensorTools(mockSensorPort)
		val toolNames = tools.map { it.name }

		assertThat(toolNames).isEqualTo(listOf("queued_trains", "block_inputs"))
	}

	@Test
	fun `dispatch-loop actuator tools have correct names`() {
		val tools = registry.assembleDispatchLoopActuatorTools(commandQueue)
		val toolNames = tools.map { it.name }

		assertThat(toolNames).isEqualTo(listOf("approve_train"))
	}

	@Test
	fun `assembleDispatchLoopTools returns 3 tools with distinct names`() {
		val tools = registry.assembleDispatchLoopTools(mockSensorPort, commandQueue)

		assertThat(tools).hasSize(3)
		assertThat(tools.map { it.name }.toSet()).hasSize(3)
	}

	@Test
	fun `dispatch-loop tools do not overlap with assembleAllTools tool names`() {
		val allToolNames = registry.assembleAllTools(mockPerceptionPort, commandQueue).map { it.name }.toSet()
		val dispatchLoopNames = registry.assembleDispatchLoopTools(mockSensorPort, commandQueue).map { it.name }.toSet()

		assertThat(allToolNames.intersect(dispatchLoopNames)).hasSize(0)
	}

	@Test
	fun `assembleAllTools still returns exactly 12 tools after SP4 additions`() {
		// Regression guard: SP4.1 additions must NOT affect the SP1 tool count.
		val tools = registry.assembleAllTools(mockPerceptionPort, commandQueue)

		assertThat(tools).hasSize(12)
	}
}
