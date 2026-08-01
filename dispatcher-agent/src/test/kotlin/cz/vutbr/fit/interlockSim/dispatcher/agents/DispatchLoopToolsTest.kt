/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for the [ApproveTrainTool] actuator tool and [DefaultDispatchLoopActuatorPort]
 * (SP4.1, Issue #563; rewired to the [SinkHolder] seam in SP2c.6, Issue #829).
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DefaultDispatchLoopActuatorPort
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ApproveTrainTool
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Unit tests for the [ApproveTrainTool] actuator tool (rewired to the [SinkHolder] seam in
 * SP2c.6, Issue #829) and the [DefaultDispatchLoopActuatorPort] it parallels.
 *
 * SP2c.6 removed the dispatch-loop sensor tools (`queued_trains`, `block_inputs`) from the LLM
 * tool surface — perception now flows through the sim-thread-captured
 * [cz.vutbr.fit.interlockSim.dispatcher.observation.DispatcherObservationProjector]. Their tool
 * classes and tests were deleted here. The [cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort]
 * they backed remains and is covered by `core`'s `DispatchLoopPortsTest` (the sensor port is still
 * used in production by the observation projector and by [KoogAgentFactory] for topology reads).
 *
 * The concurrent-train cap check that used to live in [ApproveTrainTool] was removed in SP2c.6;
 * it is now enforced at apply time on the sim thread (SP2c.18) — see
 * `CapEnforcementAtApplyTimeSp2c18Test`.
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent); SP2c.6 (#829) rewires ApproveTrainTool
 *   to [SinkHolder] and drops the cap check
 */
class DispatchLoopToolsTest {
	private val commandQueue = ActuatorCommandQueue()
	private val emitted = mutableListOf<DispatchAction>()
	private val sinkHolder = SinkHolder(EmittedActionSink { emitted.add(it) })

	// ── ApproveTrainTool ──────────────────────────────────────────────────────

	@Test
	fun `approve_train emits ApproveTrain action and returns emitted-success`() {
		val result = runBlocking { ApproveTrainTool(sinkHolder).execute(mapOf("trainId" to "Train #1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val msg = (result as ToolResult.Success).data as String
		assertThat(msg).contains("Train #1")
		// SP2c.6 receipt-string contract (#829 M2): actuator tools return "emitted …", never "queued".
		assertThat(msg).contains("emitted")
		assertThat(msg).doesNotContain("queued")

		assertThat(emitted).hasSize(1)
		assertThat(emitted.single()).isInstanceOf<DispatchAction.ApproveTrain>()
		assertThat((emitted.single() as DispatchAction.ApproveTrain).trainId).isEqualTo("Train #1")
	}

	@Test
	fun `approve_train missing trainId returns ToolResult Error without emitting`() {
		val result = runBlocking { ApproveTrainTool(sinkHolder).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `approve_train blank trainId returns ToolResult Error without emitting`() {
		val result = runBlocking { ApproveTrainTool(sinkHolder).execute(mapOf("trainId" to "")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `approve_train tool has correct name and one trainId parameter`() {
		val tool = ApproveTrainTool(sinkHolder)

		assertThat(tool.name).isEqualTo("approve_train")
		assertThat(tool.parameters).hasSize(1)
		assertThat(tool.parameters[0].name).isEqualTo("trainId")
	}

	// ── DefaultDispatchLoopActuatorPort direct tests ─────────────────────────

	@Test
	fun `DefaultDispatchLoopActuatorPort approveTrain posts ApproveTrain to queue`() {
		val port = DefaultDispatchLoopActuatorPort(commandQueue)

		val result = port.approveTrain("Train #2")

		assertThat(result).isEqualTo(true)
		val decisions = commandQueue.drain()
		assertThat(decisions).hasSize(1)
		assertThat((decisions.single() as DispatchDecision.ApproveTrain).trainId).isEqualTo("Train #2")
	}

	@Test
	fun `DefaultDispatchLoopActuatorPort approveTrain rejects blank trainId`() {
		val port = DefaultDispatchLoopActuatorPort(commandQueue)

		org.junit.jupiter.api
			.assertThrows<IllegalArgumentException> { port.approveTrain("") }
		assertThat(commandQueue.drain()).hasSize(0)
	}
}
