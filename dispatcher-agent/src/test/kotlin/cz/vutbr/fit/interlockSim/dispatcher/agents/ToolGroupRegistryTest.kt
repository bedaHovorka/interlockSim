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
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ToolGroupRegistry] (SP1.6, Issue #551; updated in SP1.7, Issue #774; reduced to
 * the four-tool [SinkHolder]-based actuator surface in SP2c.6, Issue #829).
 *
 * The perception-tool and dispatch-loop-sensor-tool assembly methods were removed in SP2c.6
 * (perception now flows through the sim-thread-captured
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector], not LLM
 * tools), so this test now covers only the four-tool actuator surface that remains.
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); SP2c.6 (#829) reduces the surface to 4 tools
 */
class ToolGroupRegistryTest {
	private val registry = ToolGroupRegistry()

	@Test
	fun `assembleAllTools returns 4 actuator tools (SP2c6)`() {
		val tools = registry.assembleAllTools(emptySet())

		assertThat(tools).hasSize(4)
	}

	@Test
	fun `assembleAllTools returns the 4 actuator tool names (SP2c6)`() {
		val tools = registry.assembleAllTools(emptySet())
		val toolNames = tools.map { it.name }.toSet()

		assertThat(toolNames).isEqualTo(setOf("approve_train", "request_route", "cancel_route", "no_op"))
	}

	@Test
	fun `actuator tools have descriptions (SP2c6)`() {
		val tools = registry.assembleAllTools(emptySet())

		tools.forEach { tool ->
			assertThat(tool.description).isNotEmpty()
		}
	}

	@Test
	fun `request_route tool has three parameters`() {
		val tools = registry.assembleAllTools(emptySet())
		val requestRouteTool = tools.first { it.name == "request_route" }

		assertThat(requestRouteTool.parameters).hasSize(3)
		assertThat(requestRouteTool.parameters.map { it.name }).isEqualTo(
			listOf("trainName", "fromEndpointName", "toEndpointName")
		)
	}

	@Test
	fun `assembleAllTools returns 4 tools with distinct names (SP2c6)`() {
		val tools = registry.assembleAllTools(emptySet())

		assertThat(tools).hasSize(4)
		assertThat(tools.map { it.name }.toSet()).hasSize(4)
	}
}
