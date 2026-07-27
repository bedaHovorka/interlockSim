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
 * Unit tests for the three dispatch-loop tools introduced by SP4.1 (Issue #563):
 * - [QueuedTrainsTool] — sensor, reads [DispatchLoopSensorPort.getQueuedTrains]
 * - [BlockInputsTool] — sensor, reads inner + outer block inputs
 * - [ApproveTrainTool] — actuator, posts [DispatchDecision.ApproveTrain] to queue
 *
 * @since Issue #563 (SP4.1 — Goal 10 reactive-train agent)
 */
class DispatchLoopToolsTest {
	private val commandQueue = ActuatorCommandQueue()

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
	fun `approve_train posts ApproveTrain decision and returns queued-success`() {
		val result = runBlocking { approveTool().execute(mapOf("trainId" to "Train #1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val msg = (result as ToolResult.Success).data as String
		assertThat(msg).contains("Train #1")

		val decisions = commandQueue.drain()
		assertThat(decisions).hasSize(1)
		assertThat(decisions.single()).isInstanceOf<DispatchDecision.ApproveTrain>()
		assertThat((decisions.single() as DispatchDecision.ApproveTrain).trainId).isEqualTo("Train #1")
	}

	@Test
	fun `approve_train missing trainId returns ToolResult Error without posting`() {
		val result = runBlocking { approveTool().execute(emptyMap()) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(commandQueue.drain()).hasSize(0)
	}

	@Test
	fun `approve_train blank trainId returns ToolResult Error without posting`() {
		val result = runBlocking { approveTool().execute(mapOf("trainId" to "")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(commandQueue.drain()).hasSize(0)
	}

	@Test
	fun `approve_train reports backpressure when queue is full`() {
		val fullQueue = ActuatorCommandQueue(capacity = 1)
		fullQueue.postAll(listOf(DispatchDecision.ReleaseRoute("occupier")))

		val result = runBlocking { approveTool(fullQueue).execute(mapOf("trainId" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		// The occupier decision is still the only one in the queue.
		assertThat(fullQueue.drain()).hasSize(1)
	}

	@Test
	fun `approve_train tool has correct name and one trainId parameter`() {
		val tool = approveTool()

		assertThat(tool.name).isEqualTo("approve_train")
		assertThat(tool.parameters).hasSize(1)
		assertThat(tool.parameters[0].name).isEqualTo("trainId")
	}

	@Test
	fun `approve_train rejects when active count meets the cap`() {
		val result = runBlocking { approveTool(activeCount = 2).execute(mapOf("trainId" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(commandQueue.drain()).hasSize(0)
	}

	@Test
	fun `approve_train allows when active count is below the cap`() {
		val result = runBlocking { approveTool(activeCount = 1).execute(mapOf("trainId" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(commandQueue.drain()).hasSize(1)
	}

	@Test
	fun `approve_train description states the concrete concurrency cap`() {
		assertThat(approveTool(maxConcurrentTrains = 2).description).contains("Rejected if 2 trains are already active")
		assertThat(approveTool(maxConcurrentTrains = 3).description).contains("Rejected if 3 trains are already active")
	}

	@Test
	fun `approve_train respects a custom maxConcurrentTrains override`() {
		val result =
			runBlocking {
				approveTool(activeCount = 1, maxConcurrentTrains = 1).execute(mapOf("trainId" to "T1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(commandQueue.drain()).hasSize(0)
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

	private fun approveTool(
		queue: ActuatorCommandQueue = commandQueue,
		activeCount: Int = 0,
		maxConcurrentTrains: Int = 2
	): ApproveTrainTool = ApproveTrainTool(DefaultDispatchLoopActuatorPort(queue), { activeCount }, maxConcurrentTrains)

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
