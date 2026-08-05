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
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import cz.vutbr.fit.interlockSim.dispatcher.DispatchAction
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.CancelRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.NoOpTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Verifies the actuator tools satisfy the SP2c.6 SinkHolder contract (Issue #829):
 * `execute()` runs on the driver thread and emits a [DispatchAction] to the [SinkHolder]
 * (fire-and-forget). Replaces the SP1.7 queue-based contract verified by this class before
 * the SP2c.6 rewrite.
 *
 * @since Issue #551 (SP1.6 — Goal 10 tool-calling loop); SP1.7 (#774) rewired to queue;
 *   SP2c.6 (#829) rewired to SinkHolder
 */
class ActuatorToolExecuteTest {
	private val emitted = mutableListOf<DispatchAction>()
	private val sinkHolder = SinkHolder(EmittedActionSink { emitted.add(it) })

	@Test
	fun `request_route emits a RequestRoute action and returns an emitted-success descriptor`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "doA1")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val data = (result as ToolResult.Success).data as String
		assertThat(data).contains("T1")
		assertThat(data).contains("zA")
		assertThat(data).contains("doA1")
		// SP2c.6 receipt-string contract (#829 M2): actuator tools return "emitted …", never "queued".
		assertThat(data).contains("emitted")
		assertThat(data).doesNotContain("queued")

		assertThat(emitted).hasSize(1)
		val action = emitted.single()
		assertThat(action).isInstanceOf<DispatchAction.RequestRoute>()
		action as DispatchAction.RequestRoute
		assertThat(action.trainId).isEqualTo("T1")
		assertThat(action.fromEndpointName).isEqualTo("zA")
		assertThat(action.toEndpointName).isEqualTo("doA1")
	}

	@Test
	fun `request_route rejects an unknown fromEndpointName without emitting`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "kA", "toEndpointName" to "doA1")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		val message = (result as ToolResult.Error).message
		assertThat(message).contains("kA")
		assertThat(message).contains("zA")
		assertThat(message).contains("doA1")
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `request_route rejects an unknown toEndpointName without emitting`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "kB")
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat((result as ToolResult.Error).message).contains("kB")
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `cancel_route emits a CancelRoute action and returns an emitted-success descriptor`() {
		val result = runBlocking { CancelRouteTool(sinkHolder).execute(mapOf("trainId" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val data = (result as ToolResult.Success).data as String
		assertThat(data).contains("T1")
		// SP2c.6 receipt-string contract (#829 M2): actuator tools return "emitted …", never "queued".
		assertThat(data).contains("emitted")
		assertThat(data).doesNotContain("queued")

		assertThat(emitted).hasSize(1)
		assertThat(emitted.single()).isInstanceOf<DispatchAction.CancelRoute>()
		assertThat((emitted.single() as DispatchAction.CancelRoute).trainId).isEqualTo("T1")
	}

	/**
	 * Issue #847 cleanup pass: [CancelRouteTool] pre-validates `trainId` against
	 * [NetworkPerceptionPort.snapshot] when a port is supplied, mirroring
	 * [RequestRouteTool]'s in-turn endpoint-name validation.
	 */
	@Test
	fun `cancel_route with a perception port emits when trainId matches an active train`() {
		val port = stubPerceptionPort(activeTrainIds = listOf("T1"))
		val result =
			runBlocking { CancelRouteTool(sinkHolder, port).execute(mapOf("trainId" to "T1")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(emitted).hasSize(1)
	}

	@Test
	fun `cancel_route with a perception port rejects an unknown trainId without emitting`() {
		val port = stubPerceptionPort(activeTrainIds = listOf("T1"))
		val result =
			runBlocking { CancelRouteTool(sinkHolder, port).execute(mapOf("trainId" to "GhostTrain")) }

		assertThat(result).isInstanceOf<ToolResult.Error>()
		val message = (result as ToolResult.Error).message
		assertThat(message).contains("GhostTrain")
		assertThat(message).contains("T1")
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `cancel_route without a perception port skips the trainId pre-check`() {
		// No port supplied (default null) -- preserves pre-#847 behavior: any non-blank
		// trainId is accepted at the tool layer; ActionValidator remains the systemic gate.
		val result =
			runBlocking { CancelRouteTool(sinkHolder).execute(mapOf("trainId" to "GhostTrain")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(emitted).hasSize(1)
	}

	/** Minimal [NetworkPerceptionPort] stub exposing only [snapshot], as [CancelRouteTool] needs. */
	private class StubPerceptionPort(activeTrainIds: List<String>) : NetworkPerceptionPort {
		private val fixedSnapshot =
			SimulationSnapshot.EMPTY.copy(
				trainPositions =
					activeTrainIds.map {
						TrainPositionReading(
							trainId = it,
							velocity = 0.0,
							acceleration = 0.0,
							totalDistance = 0.0,
							frontSectionName = null
						)
					}
			)

		override fun signalAspect(semaphoreName: String) = null

		override fun allSignalAspects() = emptyList<Nothing>()

		override fun blockOccupancy(blockId: String) = null

		override fun allBlockOccupancies() = emptyList<Nothing>()

		override fun trainPosition(trainId: String) = fixedSnapshot.trainPositions.find { it.trainId == trainId }

		override fun allTrainPositions() = fixedSnapshot.trainPositions

		override fun trainTimetable(trainId: String) = null

		override fun allTrainTimetables() = emptyList<Nothing>()

		override fun trainPerception(trainId: String) = null

		override fun allTrainPerceptions() = emptyList<Nothing>()

		override fun snapshot() = fixedSnapshot

		override fun captureSnapshot() = fixedSnapshot
	}

	private fun stubPerceptionPort(activeTrainIds: List<String>): NetworkPerceptionPort =
		StubPerceptionPort(activeTrainIds)

	/**
	 * SP2c.6 receipt-string contract (#829 M2): `no_op` emits a [DispatchAction.NoOp] and returns
	 * an "emitted no_op" descriptor (never "queued"). The optional `reason` argument is accepted but
	 * ignored — it must not affect the emitted action or the receipt string.
	 */
	@Test
	fun `no_op emits a NoOp action and returns an emitted-success descriptor`() {
		val result = runBlocking { NoOpTool(sinkHolder).execute(mapOf("reason" to "nothing to do")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		val data = (result as ToolResult.Success).data as String
		assertThat(data).contains("emitted")
		assertThat(data).doesNotContain("queued")

		assertThat(emitted).hasSize(1)
		assertThat(emitted.single()).isInstanceOf<DispatchAction.NoOp>()
	}

	@Test
	fun `invalid arguments return ToolResult Error without emitting`() {
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1")).execute(
					mapOf(
						"trainName" to "",
						"fromEndpointName" to "zA",
						"toEndpointName" to "doA1"
					)
				)
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(emitted).hasSize(0)
	}
}
