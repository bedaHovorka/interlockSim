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
import cz.vutbr.fit.interlockSim.dispatcher.RouteScope
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.ApproveTrainTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.CancelRouteTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.NoOpTool
import cz.vutbr.fit.interlockSim.dispatcher.agents.tools.RequestRouteTool
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSnapshot
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.BlockInputObservation
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
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
	fun `request_route sets scope EndToEnd for an InOut target and Section for a Signal target`() {
		// No ports => the declared-destination direction check is skipped, so both targets emit and
		// only the scope differs. inOutNames = {A, B} distinguishes the end-to-end target from the
		// Signal section hop; validEndpointNames must include every name used.
		val inOutNames = setOf("A", "B")
		val valid = setOf("A", "B", "zA", "doA1")

		val endToEndResult =
			runBlocking {
				RequestRouteTool(sinkHolder, valid, inOutNames = inOutNames).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "A")
				)
			}
		assertThat(endToEndResult).isInstanceOf<ToolResult.Success>()
		assertThat((emitted.last() as DispatchAction.RequestRoute).scope).isEqualTo(RouteScope.EndToEnd)

		val sectionResult =
			runBlocking {
				RequestRouteTool(sinkHolder, valid, inOutNames = inOutNames).execute(
					mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "doA1")
				)
			}
		assertThat(sectionResult).isInstanceOf<ToolResult.Success>()
		assertThat((emitted.last() as DispatchAction.RequestRoute).scope).isEqualTo(RouteScope.Section)
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

	/**
	 * Issue #847 round 2: `request_route` pre-validates `trainName` against the union of queued and
	 * active trains. The literal placeholder train name that the topology prompt used to carry was
	 * copied verbatim by the model, and — with no validation on the live path — reserved real blocks
	 * for a train that does not exist, which nothing can release. This is the in-turn guard.
	 */
	@Test
	fun `request_route emits when trainName matches an active train`() {
		val result =
			runBlocking {
				RequestRouteTool(
					sinkHolder,
					setOf("zA", "doA1"),
					stubPerceptionPort(activeTrainIds = listOf("Train #1")),
					stubSensorPort(queuedTrainIds = emptyList())
				).execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "zA", "toEndpointName" to "doA1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(emitted).hasSize(1)
	}

	@Test
	fun `request_route emits when trainName matches a queued train not yet active`() {
		// Reserving a route before approve_train is the intended order, so a queued train must be
		// accepted even though it is not in the active list.
		val result =
			runBlocking {
				RequestRouteTool(
					sinkHolder,
					setOf("zA", "doA1"),
					stubPerceptionPort(activeTrainIds = emptyList()),
					stubSensorPort(queuedTrainIds = listOf("Train #2"))
				).execute(mapOf("trainName" to "Train #2", "fromEndpointName" to "zA", "toEndpointName" to "doA1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(emitted).hasSize(1)
	}

	@Test
	fun `request_route rejects a trainName that is neither queued nor active without emitting`() {
		val result =
			runBlocking {
				RequestRouteTool(
					sinkHolder,
					setOf("zA", "doA1"),
					stubPerceptionPort(activeTrainIds = listOf("Train #1")),
					stubSensorPort(queuedTrainIds = listOf("Train #2"))
				).execute(mapOf("trainName" to "T1", "fromEndpointName" to "zA", "toEndpointName" to "doA1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		val message = (result as ToolResult.Error).message
		assertThat(message).contains("T1")
		assertThat(message).contains("Train #1")
		assertThat(message).contains("Train #2")
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `request_route without both ports skips the trainName pre-check`() {
		// Preserves pre-#847 behavior for callers that wire neither port. Validating against only
		// one port would give a partial union and reject legitimate trains.
		val result =
			runBlocking {
				RequestRouteTool(sinkHolder, setOf("zA", "doA1"))
					.execute(mapOf("trainName" to "GhostTrain", "fromEndpointName" to "zA", "toEndpointName" to "doA1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(emitted).hasSize(1)
	}

	@Test
	fun `approve_train emits when trainId matches a queued train`() {
		val result =
			runBlocking {
				ApproveTrainTool(sinkHolder, stubSensorPort(queuedTrainIds = listOf("Train #1")))
					.execute(mapOf("trainId" to "Train #1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(emitted).hasSize(1)
	}

	@Test
	fun `approve_train rejects a trainId that is not queued without emitting`() {
		// ShuntingLoop.approveQueuedTrain returns silently on an unknown name, so without this
		// check a hallucinated id was simply a lost turn with no feedback to the model.
		val result =
			runBlocking {
				ApproveTrainTool(sinkHolder, stubSensorPort(queuedTrainIds = listOf("Train #1")))
					.execute(mapOf("trainId" to "T1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		val message = (result as ToolResult.Error).message
		assertThat(message).contains("T1")
		assertThat(message).contains("Train #1")
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `approve_train without a sensor port skips the trainId pre-check`() {
		val result = runBlocking { ApproveTrainTool(sinkHolder).execute(mapOf("trainId" to "GhostTrain")) }

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat(emitted).hasSize(1)
	}

	/**
	 * Issue #847 round 2: the model writes the bare ordinal `"1"` for `"Train #1"` — in one 600 s
	 * run *every* rejected train argument took that form (90 of them), and zero trains completed
	 * against 28 for the rule-based dispatcher. Koog drops tool results before they reach Ollama,
	 * so the error can never teach the model; the abbreviation is resolved instead.
	 */
	@Test
	fun `request_route resolves a bare ordinal to the canonical train id and emits that`() {
		val result =
			runBlocking {
				RequestRouteTool(
					sinkHolder,
					setOf("zA", "doA1"),
					stubPerceptionPort(activeTrainIds = listOf("Train #1")),
					stubSensorPort(queuedTrainIds = emptyList())
				).execute(mapOf("trainName" to "1", "fromEndpointName" to "zA", "toEndpointName" to "doA1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		// The canonical id must reach the sink — nothing downstream should ever see "1".
		assertThat((emitted.single() as DispatchAction.RequestRoute).trainId).isEqualTo("Train #1")
	}

	@Test
	fun `approve_train resolves a bare ordinal to the canonical train id and emits that`() {
		val result =
			runBlocking {
				ApproveTrainTool(sinkHolder, stubSensorPort(queuedTrainIds = listOf("Train #7")))
					.execute(mapOf("trainId" to "7"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((emitted.single() as DispatchAction.ApproveTrain).trainId).isEqualTo("Train #7")
	}

	@Test
	fun `cancel_route resolves a bare ordinal to the canonical train id and emits that`() {
		val result =
			runBlocking {
				CancelRouteTool(sinkHolder, stubPerceptionPort(activeTrainIds = listOf("Train #3")))
					.execute(mapOf("trainId" to "3"))
			}

		assertThat(result).isInstanceOf<ToolResult.Success>()
		assertThat((emitted.single() as DispatchAction.CancelRoute).trainId).isEqualTo("Train #3")
	}

	@Test
	fun `an ambiguous ordinal is rejected rather than retargeted onto the wrong train`() {
		// Retargeting an action onto a different train is exactly the failure this round exists to
		// prevent, so resolution must stay unambiguous-only. Two ids ending "#1" means no match.
		val result =
			runBlocking {
				ApproveTrainTool(sinkHolder, stubSensorPort(queuedTrainIds = listOf("Train #1", "Shunt #1")))
					.execute(mapOf("trainId" to "1"))
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(emitted).hasSize(0)
	}

	@Test
	fun `a partial-prefix train id is still rejected`() {
		// Only the "#<ordinal>" suffix form is tolerated — never loose prefix or substring matching.
		val result =
			runBlocking {
				ApproveTrainTool(sinkHolder, stubSensorPort(queuedTrainIds = listOf("Train #12")))
					.execute(mapOf("trainId" to "Train"))
			}

		assertThat(result).isInstanceOf<ToolResult.Error>()
		assertThat(emitted).hasSize(0)
	}

	/** Minimal [DispatchLoopSensorPort] stub exposing only [getQueuedTrains]. */
	private fun stubSensorPort(queuedTrainIds: List<String>): DispatchLoopSensorPort =
		object : DispatchLoopSensorPort {
			private val queued = queuedTrainIds.map { QueuedTrainObservation(it, "B") }

			override fun snapshot() = DispatchLoopSnapshot(queued, emptyList(), emptyList())

			override fun getQueuedTrains() = queued

			override fun getInnerBlockInputs() = emptyList<BlockInputObservation>()

			override fun getOuterBlockInputs() = emptyList<BlockInputObservation>()
		}

	/** Minimal [NetworkPerceptionPort] stub exposing only [snapshot], as [CancelRouteTool] needs. */
	private class StubPerceptionPort(
		activeTrainIds: List<String>
	) : NetworkPerceptionPort {
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
