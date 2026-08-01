/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator - Test Suite
 *
 * Tests for dispatch-loop sensor and actuator tools (SP4.1, Issue #563).
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameAs
import cz.vutbr.fit.interlockSim.dispatcher.ActuatorCommandQueue
import cz.vutbr.fit.interlockSim.dispatcher.DefaultDispatchLoopActuatorPort
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ApproveTrainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.BlockInputsTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.QueuedTrainsTool
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.DefaultDispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.DispatchDecision
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import cz.vutbr.fit.interlockSim.sim.ShuntingLoop
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Unit tests for the dispatch-loop sensor tools introduced by SP4.1 (Issue #563) and the
 * [ApproveTrainTool] actuator tool (rewired to the [SinkHolder] seam in SP2c.6, Issue #829):
 * - [QueuedTrainsTool] — sensor, reads [DispatchLoopSensorPort.getQueuedTrains]
 * - [BlockInputsTool] — sensor, reads inner + outer block inputs
 * - [ApproveTrainTool] — actuator, emits [DispatchAction.ApproveTrain] to the active sink
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

	// ── QueuedTrainsTool ──────────────────────────────────────────────────────

	@Test
	fun `queued_trains returns Success wrapping the queued-train list`() {
		val expected = listOf(QueuedTrainObservation("Train #1", "inout_A"))
		val port = mockk<DispatchLoopSensorPort>()
		every { port.getQueuedTrains() } returns expected

		val result = runBlocking { QueuedTrainsTool(port).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		@Suppress("UNCHECKED_CAST")
		assertThat((result as ToolResult.Success).data as List<*>).isSameAs(expected)
		verify(exactly = 1) { port.getQueuedTrains() }
	}

	@Test
	fun `queued_trains returns empty list when no trains queued`() {
		val port = mockk<DispatchLoopSensorPort>()
		every { port.getQueuedTrains() } returns emptyList()

		val result = runBlocking { QueuedTrainsTool(port).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		@Suppress("UNCHECKED_CAST")
		assertThat((result as ToolResult.Success).data as List<*>).isEmpty()
	}

	@Test
	fun `queued_trains translates port exception to ToolResult Error`() {
		val port = mockk<DispatchLoopSensorPort>()
		every { port.getQueuedTrains() } throws RuntimeException("sensor boom")

		val result = runBlocking { QueuedTrainsTool(port).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat((result as ToolResult.Error).cause).isNotNull()
	}

	@Test
	fun `queued_trains tool has correct name and no required parameters`() {
		val tool = QueuedTrainsTool(mockk(relaxed = true))

		assertThat(tool.name).isEqualTo("queued_trains")
		assertThat(tool.parameters).hasSize(0)
	}

	// ── BlockInputsTool ───────────────────────────────────────────────────────

	@Test
	fun `block_inputs returns combined inner and outer inputs from a single snapshot`() {
		val inner = listOf(makeBlockInput("k1", "zA"))
		val outer = listOf(makeBlockInput("kA", "doA1"))
		val port = mockk<DispatchLoopSensorPort>()
		every { port.snapshot() } returns
			DispatchLoopSnapshot(
				queuedTrains = emptyList(),
				innerBlockInputs = inner,
				outerBlockInputs = outer
			)

		val result = runBlocking { BlockInputsTool(port).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		@Suppress("UNCHECKED_CAST")
		val data = (result as ToolResult.Success).data as List<*>
		assertThat(data).hasSize(2)
		// Critical #2: BlockInputsTool must read inner + outer from ONE snapshot call, not two
		// independent accessor calls (which could observe different ticks if the sim thread
		// republishes between them — see DispatchLoopSensorPort.snapshot).
		verify(exactly = 1) { port.snapshot() }
	}

	@Test
	fun `block_inputs returns empty list when snapshot is empty`() {
		val port = mockk<DispatchLoopSensorPort>()
		every { port.snapshot() } returns DispatchLoopSnapshot.EMPTY

		val result = runBlocking { BlockInputsTool(port).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		@Suppress("UNCHECKED_CAST")
		assertThat((result as ToolResult.Success).data as List<*>).isEmpty()
	}

	@Test
	fun `block_inputs translates port exception to ToolResult Error`() {
		val port = mockk<DispatchLoopSensorPort>()
		every { port.snapshot() } throws IllegalStateException("snapshot boom")

		val result = runBlocking { BlockInputsTool(port).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
	}

	@Test
	fun `block_inputs tool has correct name and no required parameters`() {
		val tool = BlockInputsTool(mockk(relaxed = true))

		assertThat(tool.name).isEqualTo("block_inputs")
		assertThat(tool.parameters).hasSize(0)
	}

	// ── ApproveTrainTool ──────────────────────────────────────────────────────

	@Test
	fun `approve_train emits ApproveTrain action and returns emitted-success`() {
		val result = runBlocking { ApproveTrainTool(sinkHolder).execute(mapOf("trainId" to "Train #1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val msg = (result as ToolResult.Success).data as String
		assertThat(msg).contains("Train #1")

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

	// ── DefaultDispatchLoopSensorPort via the tools ───────────────────────────

	@Test
	fun `QueuedTrainsTool with DefaultDispatchLoopSensorPort reads from the observation provider`() {
		val obs =
			ShuntingLoop.TickObservation(
				queuedTrains = listOf(QueuedTrainObservation("T1", "A")),
				innerBlockInputs = emptyList(),
				outerBlockInputs = emptyList()
			)
		val port = DefaultDispatchLoopSensorPort { obs }

		val result = runBlocking { QueuedTrainsTool(port).execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		@Suppress("UNCHECKED_CAST")
		val trains = (result as ToolResult.Success).data as List<QueuedTrainObservation>
		assertThat(trains).hasSize(1)
		assertThat(trains[0].trainId).isEqualTo("T1")
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private fun makeBlockInput(
		blockId: String,
		semaphoreName: String
	): BlockInputObservation =
		BlockInputObservation(
			blockId = blockId,
			towardSemaphoreName = semaphoreName,
			toSeparatorName = null,
			state = TrackFacility.State.FREE,
			ownerTrainId = null,
			isApproachingThisInput = false,
			pathSetUpTowardThisInput = false,
			pathAlreadyExtendedBeyond = false
		)
}
