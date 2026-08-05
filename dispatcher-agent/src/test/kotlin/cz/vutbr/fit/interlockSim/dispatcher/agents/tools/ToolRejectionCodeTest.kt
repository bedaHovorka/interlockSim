/* Brno University of Technology
 * Faculty of Information Technology
 *
 * BSc Thesis  2006/2007
 *
 * Railway Interlocking Simulator
 *
 * Bedrich Hovorka
 */
package cz.vutbr.fit.interlockSim.dispatcher.agents.tools

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import cz.vutbr.fit.interlockSim.dispatcher.RejectionCode
import cz.vutbr.fit.interlockSim.dispatcher.agents.SinkHolder
import cz.vutbr.fit.interlockSim.dispatcher.agents.ToolResult
import cz.vutbr.fit.interlockSim.objects.core.TrackFacility
import cz.vutbr.fit.interlockSim.ports.BlockOccupancyReading
import cz.vutbr.fit.interlockSim.ports.DispatchLoopSensorPort
import cz.vutbr.fit.interlockSim.ports.NetworkPerceptionPort
import cz.vutbr.fit.interlockSim.ports.SimulationSnapshot
import cz.vutbr.fit.interlockSim.ports.TrainPositionReading
import cz.vutbr.fit.interlockSim.sim.QueuedTrainObservation
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Typed-rejection tests for the four actuator tools (Issue #847 round 4).
 *
 * ## Why a code and not just a message
 *
 * Rounds 2 and 3 headline "86 rejected endpoint calls", "90 rejected train-id calls", "0 rejected"
 * — every one of those numbers was produced by **grepping the log after the fact**. #847's sweep is
 * unattended and writes one JSON per run; it cannot grep, and `DispatcherRunSnapshot.rejectionsByCode`
 * — the field #846's aggregator renders its "Failure Modes" table from — was structurally guaranteed
 * to be empty, because the only `ActionOutcome` ever constructed in production passes
 * `rejection = null` and `ActionValidator` (the component that produces [RejectionCode]s) is only
 * ever invoked from the test-only `DispatchTickLoop`.
 *
 * The external review of this PR put the same point the other way round: prompt instructions the
 * model can ignore are worth less than a validation layer it cannot talk around — and a validation
 * layer whose effect is never measured is an assertion, not evidence.
 *
 * The tools already reject these calls and already explain why in prose. Attaching the existing
 * [RejectionCode] to the same rejection makes the rate countable without parsing English.
 *
 * ## `ENDPOINT_IS_BLOCK_ID` specifically
 *
 * This is the `kA` vs `A` confusion that motivated the whole anti-hallucination work: `vyhybna.xml`
 * names blocks `k1`/`kA`/`kB` and InOuts `A`/`B`, and round 2 measured 48 rejected calls naming
 * block `k1` in one run. Lumping it under `UNKNOWN_ENDPOINT` would hide the one failure mode the
 * prompt changes are aimed at, so it gets its own code — which is exactly what the existing enum
 * already provides.
 */
@DisplayName("Actuator tools attach a typed RejectionCode to every rejection")
class ToolRejectionCodeTest {
	private val endpoints = setOf("A", "B", "doA1", "doB1")

	private fun perceptionPort(
		activeTrains: List<String> = emptyList(),
		blockIds: List<String> = listOf("k1", "kA", "kB")
	): NetworkPerceptionPort =
		mockk<NetworkPerceptionPort>(relaxed = true).also { port ->
			every { port.snapshot() } returns
				SimulationSnapshot.EMPTY.copy(
					trainPositions =
						activeTrains.map {
							TrainPositionReading(
								trainId = it,
								velocity = 0.0,
								acceleration = 0.0,
								totalDistance = 0.0,
								frontSectionName = null
							)
						},
					blocks =
						blockIds.map {
							BlockOccupancyReading(blockId = it, state = TrackFacility.State.FREE, trainId = null)
						}
				)
		}

	private fun sensorPort(queued: List<String>): DispatchLoopSensorPort =
		mockk<DispatchLoopSensorPort>(relaxed = true).also { port ->
			every { port.getQueuedTrains() } returns
				queued.map { QueuedTrainObservation(trainId = it, destinationInOutName = "B") }
		}

	private fun requestRouteTool(
		active: List<String> = emptyList(),
		queued: List<String> = emptyList()
	) = RequestRouteTool(SinkHolder(), endpoints, perceptionPort(active), sensorPort(queued))

	private fun errorOf(result: ToolResult): ToolResult.Error {
		assertThat(result).isInstanceOf(ToolResult.Error::class)
		return result as ToolResult.Error
	}

	@Test
	@DisplayName("a blank argument is BLANK_ARGUMENT")
	fun blankArgumentIsCoded() {
		val result =
			runBlocking {
				requestRouteTool().execute(mapOf("trainName" to "", "fromEndpointName" to "A", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.BLANK_ARGUMENT)
	}

	@Test
	@DisplayName("an invented endpoint name is UNKNOWN_ENDPOINT")
	fun unknownEndpointIsCoded() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "Z9", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_ENDPOINT)
	}

	/**
	 * The `kA` vs `A` trap, measured 48 times in one round-2 run. It must be countable separately
	 * from a wholly invented name, because the two say different things about the prompt.
	 */
	@Test
	@DisplayName("a Block ID passed as an endpoint is ENDPOINT_IS_BLOCK_ID, not UNKNOWN_ENDPOINT")
	fun blockIdAsEndpointIsCodedDistinctly() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "kA", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.ENDPOINT_IS_BLOCK_ID)
	}

	@Test
	@DisplayName("an unresolvable train name on request_route is UNKNOWN_TRAIN")
	fun unknownTrainOnRequestRouteIsCoded() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Ghost", "fromEndpointName" to "A", "toEndpointName" to "B"))
			}

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_TRAIN)
	}

	@Test
	@DisplayName("an unknown trainId on approve_train is UNKNOWN_TRAIN")
	fun unknownTrainOnApproveIsCoded() {
		val tool = ApproveTrainTool(SinkHolder(), sensorPort(listOf("Train #1")))

		val result = runBlocking { tool.execute(mapOf("trainId" to "Ghost")) }

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_TRAIN)
	}

	@Test
	@DisplayName("an unknown trainId on cancel_route is UNKNOWN_TRAIN")
	fun unknownTrainOnCancelIsCoded() {
		val tool = CancelRouteTool(SinkHolder(), perceptionPort(activeTrains = listOf("Train #1")))

		val result = runBlocking { tool.execute(mapOf("trainId" to "Ghost")) }

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.UNKNOWN_TRAIN)
	}

	@Test
	@DisplayName("a blank trainId on approve_train is BLANK_ARGUMENT")
	fun blankTrainIdOnApproveIsCoded() {
		val tool = ApproveTrainTool(SinkHolder(), sensorPort(listOf("Train #1")))

		val result = runBlocking { tool.execute(mapOf("trainId" to " ")) }

		assertThat(errorOf(result).rejection, "rejection code").isEqualTo(RejectionCode.BLANK_ARGUMENT)
	}

	/**
	 * A successful call must carry no code — otherwise the rate this exists to measure would count
	 * every call rather than every rejection.
	 */
	@Test
	@DisplayName("a successful call carries no rejection code")
	fun successCarriesNoCode() {
		val result =
			runBlocking {
				requestRouteTool(queued = listOf("Train #1"))
					.execute(mapOf("trainName" to "Train #1", "fromEndpointName" to "A", "toEndpointName" to "B"))
			}

		assertThat(result).isInstanceOf(ToolResult.Success::class)
		assertThat((result as? ToolResult.Error)?.rejection, "rejection code on success").isNull()
	}
}
